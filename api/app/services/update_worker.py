"""Background worker that rolls out update campaigns island by island.

One campaign is active at a time. Every tick the worker takes the oldest
PENDING island of the active campaign and applies the update according to
the island's state:

    soft update (no restart)  -> apply immediately, even with players online
    hard update, island off   -> stop-safe apply (container stays stopped)
    hard update, player online:
        critical    -> chat message, short grace, container stop, apply
        both/other  -> chat message, entry goes WAITING until the player
                       logs out (POST /islands/{uuid}/player_left)

When every entry is terminal, the template container is refreshed and the
image republished so new islands start with the fresh files.
"""
import asyncio
import logging

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.db.session import AsyncSessionLocal
from app.crud.crud_island import crud_island
from app.crud.crud_update import crud_update_campaign
from app.crud.crud_update_queue import crud_update_queue
from app.models.update import CampaignStatusEnum, UpdateQueueStatusEnum
from app.schemas.island import IslandStatusEnum
from app.services import git_sync
from app.services.update_service import update_service, UpdateServiceError

logger = logging.getLogger(__name__)

_worker_running = False
_worker_task = None

# Island states the worker can safely act on.
_UPDATABLE_OFFLINE = {IslandStatusEnum.STOPPED, IslandStatusEnum.FROZEN,
                      IslandStatusEnum.ERROR_START, IslandStatusEnum.UPDATE_FAILED}
_TRANSIENT = {IslandStatusEnum.PENDING_START, IslandStatusEnum.PENDING_STOP,
              IslandStatusEnum.PENDING_FREEZE, IslandStatusEnum.PENDING_CREATION,
              IslandStatusEnum.CREATING, IslandStatusEnum.UPDATING}


async def update_worker_loop():
    """The main loop for the update worker."""
    global _worker_running
    _worker_running = True
    logger.info("Update worker loop started.")

    # Recovery: entries stuck in PROCESSING mean the previous process died
    # mid-update — return them to PENDING so the campaign resumes.
    try:
        async with AsyncSessionLocal() as db_session:
            reset = await crud_update_queue.reset_stale_processing(db_session)
            if reset:
                logger.warning(f"Update worker: Reset {reset} stale PROCESSING entries back to PENDING.")
    except Exception as e:
        logger.error(f"Update worker: Recovery pass failed: {e}", exc_info=True)

    while _worker_running:
        try:
            async with AsyncSessionLocal() as db_session:
                await process_active_campaign(db_session)
        except Exception as e:
            logger.error(f"Update worker loop encountered an unhandled exception: {e}", exc_info=True)

        await asyncio.sleep(settings.UPDATE_WORKER_INTERVAL)

    logger.info("Update worker loop stopped.")


async def process_active_campaign(db_session: AsyncSession):
    """Advances the active campaign by one island (if any work is pending)."""
    campaign = await crud_update_campaign.get_active(db_session)
    if not campaign:
        return

    if campaign.status == CampaignStatusEnum.PENDING:
        # Make sure the repo working tree matches the campaign before any push.
        try:
            await git_sync.clone_or_pull(settings.UPDATES_REPO_URL, settings.UPDATES_REPO_LOCAL_PATH)
            await git_sync.checkout_tag(settings.UPDATES_REPO_LOCAL_PATH, campaign.version)
        except git_sync.GitSyncError as e:
            logger.error(f"Update worker: Cannot prepare repo for campaign {campaign.version}: {e}")
            await crud_update_campaign.set_status(db_session, campaign_id=campaign.id,
                                                  status=CampaignStatusEnum.FAILED, error_message=str(e))
            return
        await crud_update_campaign.set_status(db_session, campaign_id=campaign.id,
                                              status=CampaignStatusEnum.IN_PROGRESS)
        logger.info(f"Update worker: Campaign {campaign.version} is now IN_PROGRESS.")

    entry = await crud_update_queue.get_next_pending(db_session, campaign_id=campaign.id)
    if entry:
        await process_queue_entry(db_session, campaign, entry)
        return

    # No PENDING entries left — the campaign is done once nothing is WAITING/PROCESSING.
    remaining = await crud_update_queue.get_by_statuses(
        db_session, campaign_id=campaign.id,
        statuses=[UpdateQueueStatusEnum.WAITING, UpdateQueueStatusEnum.PROCESSING],
    )
    if remaining:
        # Safety net: player_left can be missed (proxy restart), and an empty
        # island freezes/stops on its own timers — release WAITING entries
        # whose island is no longer RUNNING.
        for waiting_entry in remaining:
            if waiting_entry.status != UpdateQueueStatusEnum.WAITING:
                continue
            island = await crud_island.get(db_session, island_id=waiting_entry.island_id)
            if island and island.status in _UPDATABLE_OFFLINE:
                await crud_update_queue.set_status(db_session, entry_id=waiting_entry.id,
                                                   status=UpdateQueueStatusEnum.PENDING)
                logger.info(f"Update worker: WAITING island {island.id} went offline — re-queued.")
        return

    await finish_campaign(db_session, campaign)


async def process_queue_entry(db_session: AsyncSession, campaign, entry):
    """Processes a single island entry of the campaign."""
    island = await crud_island.get(db_session, island_id=entry.island_id)
    if not island:
        await crud_update_queue.set_status(db_session, entry_id=entry.id,
                                           status=UpdateQueueStatusEnum.SKIPPED,
                                           error_message="Island no longer exists.")
        return
    if island.skip_auto_updates:
        await crud_update_queue.set_status(db_session, entry_id=entry.id,
                                           status=UpdateQueueStatusEnum.SKIPPED,
                                           error_message="skip_auto_updates is set.")
        return
    if island.current_version == campaign.version:
        await crud_update_queue.set_status(db_session, entry_id=entry.id,
                                           status=UpdateQueueStatusEnum.COMPLETED)
        return
    if island.status in _TRANSIENT:
        # Island is mid-transition (starting/stopping/...) — try again later.
        await crud_update_queue.defer_entry(db_session, entry_id=entry.id)
        return

    owner_uuid = await update_service.get_island_owner_uuid(db_session, island)

    if campaign.requires_restart and island.status == IslandStatusEnum.RUNNING:
        if campaign.update_type.value == "critical":
            if owner_uuid:
                await update_service.notify_island(
                    owner_uuid,
                    f"Критичне оновлення {campaign.version}! Сервер перезапускається.",
                    kick=True,
                )
                await asyncio.sleep(5)  # let the player see the message before the stop
            # fall through: perform_island_update stops the container itself
        else:
            if owner_uuid:
                message = (
                    f"Оновлення {campaign.version}: після виходу оновіть клієнт."
                    if campaign.update_type.value == "both"
                    else f"Оновлення {campaign.version} буде застосовано після вашого виходу."
                )
                await update_service.notify_island(owner_uuid, message, kick=False)
            await crud_update_queue.set_status(db_session, entry_id=entry.id,
                                               status=UpdateQueueStatusEnum.WAITING)
            logger.info(f"Update worker: Island {island.id} is online — WAITING for logout.")
            return

    if campaign.requires_restart and island.status not in _UPDATABLE_OFFLINE \
            and island.status != IslandStatusEnum.RUNNING:
        await crud_update_queue.set_status(db_session, entry_id=entry.id,
                                           status=UpdateQueueStatusEnum.SKIPPED,
                                           error_message=f"Island in non-updatable state {island.status.value}.")
        return

    await crud_update_queue.set_status(db_session, entry_id=entry.id,
                                       status=UpdateQueueStatusEnum.PROCESSING)
    try:
        await update_service.perform_island_update(db_session, island, campaign)
        await crud_update_queue.set_status(db_session, entry_id=entry.id,
                                           status=UpdateQueueStatusEnum.COMPLETED)
        logger.info(f"Update worker: Island {island.id} updated to {campaign.version}.")
    except UpdateServiceError as e:
        retry = entry.retry_count + 1
        if retry < settings.UPDATE_MAX_RETRIES:
            logger.warning(f"Update worker: Island {island.id} failed (attempt {retry}), will retry: {e}")
            await crud_update_queue.set_status(db_session, entry_id=entry.id,
                                               status=UpdateQueueStatusEnum.PENDING,
                                               error_message=str(e), increment_retry=True)
            await crud_update_queue.defer_entry(db_session, entry_id=entry.id)
        else:
            logger.error(f"Update worker: Island {island.id} failed permanently: {e}")
            await crud_update_queue.set_status(db_session, entry_id=entry.id,
                                               status=UpdateQueueStatusEnum.FAILED,
                                               error_message=str(e), increment_retry=True)


async def finish_campaign(db_session: AsyncSession, campaign):
    """Refreshes the template container and closes the campaign."""
    try:
        await update_service.update_template_container(campaign.version)
    except Exception as e:
        # The islands are updated; a template failure shouldn't mark the whole
        # campaign failed — log loudly and let the admin republish manually.
        logger.error(f"Update worker: Template update for {campaign.version} failed: {e}", exc_info=True)

    counts = await crud_update_queue.count_by_status(db_session, campaign_id=campaign.id)
    await crud_update_campaign.set_status(db_session, campaign_id=campaign.id,
                                          status=CampaignStatusEnum.COMPLETED)
    logger.info(f"Update worker: Campaign {campaign.version} COMPLETED. Breakdown: {counts}")


async def on_player_left_island(db_session: AsyncSession, island_id: int) -> bool:
    """Called by POST /islands/{uuid}/player_left: releases a WAITING entry.

    Returns:
        True if a WAITING entry was moved back to PENDING.
    """
    entry = await crud_update_queue.get_waiting_for_island(db_session, island_id=island_id)
    if not entry:
        return False
    await crud_update_queue.set_status(db_session, entry_id=entry.id,
                                       status=UpdateQueueStatusEnum.PENDING)
    logger.info(f"Update worker: Island {island_id} logged out — WAITING entry re-queued.")
    return True


def start_update_worker():
    """Starts the update worker in a background asyncio task."""
    global _worker_task, _worker_running
    if _worker_task is None or _worker_task.done():
        logger.info("Starting background update worker.")
        _worker_running = True
        _worker_task = asyncio.create_task(update_worker_loop())
    else:
        logger.warning("Update worker is already running.")


def stop_update_worker():
    """Stops the background update worker."""
    global _worker_running
    if _worker_running:
        logger.info("Stopping background update worker.")
        _worker_running = False
    else:
        logger.warning("Update worker is not running.")
