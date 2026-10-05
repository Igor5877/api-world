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
import time

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.leader_election import LeaderElection
from app.db.session import AsyncSessionLocal
from app.crud.crud_island import crud_island
from app.crud.crud_update import crud_update_campaign
from app.crud.crud_update_queue import crud_update_queue
from app.models.island import Island as IslandModel
from app.models.update import CampaignStatusEnum, UpdateQueue as UpdateQueueModel, UpdateQueueStatusEnum
from app.schemas.island import IslandStatusEnum
from app.services import admin_hold, git_sync, spare_pool, update_notifier
from app.services.lxd_service import lxd_service
from app.services.update_service import update_service, IslandBusyError, UpdateServiceError

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
    """The main loop for the update worker.

    Runs on EVERY gunicorn worker process — persistent Redis leader election
    (`LeaderElection.tick()`) decides which one actually does the work each
    iteration, and keeps retrying/re-electing forever rather than the old
    "won the SETNX once at process startup or never runs again" gate in
    `main.py`. A double restart landing within the old lock's 60s TTL used
    to leave the whole cluster with zero active leaders (2026-08-22
    incident, stalled the v0.0.20 campaign) — this loop self-heals instead.
    """
    global _worker_running
    _worker_running = True
    logger.info("Update worker loop started.")

    # TTL must outlive one full tick — and a tick here is one island's LXD
    # backup+push, which can take well over UPDATE_WORKER_INTERVAL (10s).
    # Renewal only happens at the top of the next tick(), so a short TTL
    # made the lock expire mid-push, handing leadership to the other
    # gunicorn worker, which then saw the island still UPDATING and treated
    # it as a crash — restarting the same island's update from scratch.
    leader = LeaderElection("update_worker_leader_lock", max(settings.WORKER_LEADER_LOCK_TTL_SECONDS, 300))
    ran_startup_recovery = False

    while _worker_running:
        try:
            is_leader = await leader.tick()
            if is_leader:
                if not ran_startup_recovery:
                    await _run_startup_recovery()
                    ran_startup_recovery = True
                async with AsyncSessionLocal() as db_session:
                    await process_active_campaign(db_session)
            else:
                # Not leader (yet, or anymore) — if we WERE the one who ran
                # recovery and later lost leadership, a future re-election
                # should re-run it too (the new leader might be a fresh
                # process that never did), so only skip once per instance.
                pass
        except Exception as e:
            logger.error(f"Update worker loop encountered an unhandled exception: {e}", exc_info=True)

        await asyncio.sleep(settings.UPDATE_WORKER_INTERVAL)

    logger.info("Update worker loop stopped.")


async def _run_startup_recovery():
    """One-time recovery pass, run by whichever process first becomes leader.

    Entries stuck in PROCESSING or islands stuck in UPDATING mean a previous
    leader process died mid-update; both need resetting before the campaign
    can resume. Also prunes stale file backups left over from crashes.
    """
    try:
        async with AsyncSessionLocal() as db_session:
            reset = await crud_update_queue.reset_stale_processing(db_session)
            if reset:
                logger.warning(f"Update worker: Reset {reset} stale PROCESSING entries back to PENDING.")
    except Exception as e:
        logger.error(f"Update worker: Recovery pass failed: {e}", exc_info=True)

    try:
        async with AsyncSessionLocal() as db_session:
            await reset_stale_updating_islands(db_session)
    except Exception as e:
        logger.error(f"Update worker: UPDATING-recovery pass failed: {e}", exc_info=True)

    try:
        async with AsyncSessionLocal() as db_session:
            pruned = await update_service.prune_file_backups(db_session)
            if pruned:
                logger.info(f"Update worker: Startup pruning removed {pruned} old backup version dirs.")
    except Exception as e:
        logger.error(f"Update worker: Startup backup pruning failed: {e}", exc_info=True)


async def reset_stale_updating_islands(db_session: AsyncSession):
    """Resets islands stuck in UPDATING after a mid-update crash.

    The worker is single-leader, so at startup no island can legitimately be
    mid-update: any UPDATING status is stale. The island becomes RUNNING or
    STOPPED depending on the real LXD container state.
    """
    from app.services.lxd_service import lxd_service

    stuck = await crud_island.get_islands_by_statuses(
        db_session, statuses=[IslandStatusEnum.UPDATING], limit=1000)
    for island in stuck:
        try:
            state = await lxd_service.get_container_state(island.container_name)
            lxd_status = ((state or {}).get("status") or "").lower()
            new_status = IslandStatusEnum.RUNNING if lxd_status == "running" else IslandStatusEnum.STOPPED
            fields = {"status": new_status}
            if new_status == IslandStatusEnum.STOPPED:
                fields.update({"internal_ip_address": None, "minecraft_ready": False,
                               "last_heartbeat_at": None, "online_players": 0, "idle_since": None})
            await crud_island.update_by_id(db_session, island_id=island.id, obj_in=fields)
            logger.warning(f"Update worker: Island {island.id} ('{island.container_name}') was stuck "
                           f"in UPDATING after a crash — reset to {new_status.value} "
                           f"(container: {lxd_status or 'missing'}).")
        except Exception as e:
            logger.error(f"Update worker: Failed to reset stuck island {island.id}: {e}", exc_info=True)


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
            # Текст помилки git може містити токен у URL — у Discord не шлемо.
            await update_notifier.campaign_failed_to_start(campaign)
            return
        await crud_update_campaign.set_status(db_session, campaign_id=campaign.id,
                                              status=CampaignStatusEnum.IN_PROGRESS)
        logger.info(f"Update worker: Campaign {campaign.version} is now IN_PROGRESS.")
        await update_notifier.campaign_started(db_session, campaign)
        # Template first: new islands and the warm spare are on the new version
        # minutes after the tag, not hours later when the last online player
        # logs out and frees the WAITING islands.
        await refresh_template(db_session, campaign)

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
                await update_notifier.island_resumed(campaign, island, "острів уже не працює — оновлення продовжиться")
        return

    if await enqueue_islands_cloned_from_old_template(db_session, campaign):
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
        await update_notifier.island_skipped(campaign, island, "для острова вимкнено автооновлення (skip_auto_updates)")
        return
    if island.current_version == campaign.version:
        await crud_update_queue.set_status(db_session, entry_id=entry.id,
                                           status=UpdateQueueStatusEnum.COMPLETED)
        return
    if admin_hold.is_held(island):
        # Адмін працює з островом — оновимо після зняття hold.
        await crud_update_queue.defer_entry(db_session, entry_id=entry.id)
        await update_notifier.island_paused(campaign, island,
                                            "адмін працює з островом (режим адміна) — оновиться після зняття")
        return
    if island.status in _TRANSIENT:
        # Island is mid-transition (starting/stopping/...) — try again later.
        await crud_update_queue.defer_entry(db_session, entry_id=entry.id)
        await update_notifier.island_paused(campaign, island,
                                            f"острів зараз у перехідному стані ({island.status.value}) — повтор, щойно завершиться")
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
            await update_notifier.island_paused(campaign, island,
                                                "на острові грають — оновиться, щойно всі вийдуть (оновлення потребує рестарту)")
            await update_notifier.progress(db_session, campaign)
            return

    if campaign.requires_restart and island.status not in _UPDATABLE_OFFLINE \
            and island.status != IslandStatusEnum.RUNNING:
        await crud_update_queue.set_status(db_session, entry_id=entry.id,
                                           status=UpdateQueueStatusEnum.SKIPPED,
                                           error_message=f"Island in non-updatable state {island.status.value}.")
        await update_notifier.island_skipped(campaign, island,
                                             f"острів у стані {island.status.value}, з якого оновити не можна")
        return

    await crud_update_queue.set_status(db_session, entry_id=entry.id,
                                       status=UpdateQueueStatusEnum.PROCESSING)
    who = f" (гравець {island.player_name})" if island.player_name else ""
    await update_notifier.island_started(campaign, island, restart=campaign.requires_restart)
    await update_notifier.progress(db_session, campaign, current=f"оновлюється **{island.container_name}**{who}")
    started_at = time.monotonic()
    try:
        await update_service.perform_island_update(db_session, island, campaign)
        await crud_update_queue.set_status(db_session, entry_id=entry.id,
                                           status=UpdateQueueStatusEnum.COMPLETED)
        logger.info(f"Update worker: Island {island.id} updated to {campaign.version}.")
        await update_notifier.island_done(campaign, island, time.monotonic() - started_at)
        await update_notifier.progress(db_session, campaign)
        from app.services.island_service import island_service
        await island_service.start_if_requested_after_update(island.id)
    except IslandBusyError:
        # Гравець запустив острів між перевіркою й оновленням — нічого не чіпали;
        # наступний прохід побачить його RUNNING (чекати виходу / м'яке оновлення).
        await crud_update_queue.set_status(db_session, entry_id=entry.id, status=UpdateQueueStatusEnum.PENDING)
        await crud_update_queue.defer_entry(db_session, entry_id=entry.id)
        logger.info(f"Update worker: Island {island.id} is being started by a player — update deferred.")
        await update_notifier.island_paused(campaign, island, "гравець саме запускає острів — оновлення після старту")
    except UpdateServiceError as e:
        retry = entry.retry_count + 1
        if retry < settings.UPDATE_MAX_RETRIES:
            logger.warning(f"Update worker: Island {island.id} failed (attempt {retry}), will retry: {e}")
            await crud_update_queue.set_status(db_session, entry_id=entry.id,
                                               status=UpdateQueueStatusEnum.PENDING,
                                               error_message=str(e), increment_retry=True)
            await crud_update_queue.defer_entry(db_session, entry_id=entry.id)
            await update_notifier.island_retry(campaign, island, retry, str(e))
        else:
            logger.error(f"Update worker: Island {island.id} failed permanently: {e}")
            await crud_update_queue.set_status(db_session, entry_id=entry.id,
                                               status=UpdateQueueStatusEnum.FAILED,
                                               error_message=str(e), increment_retry=True)
            await update_notifier.island_failed(campaign, island, str(e))
            await update_notifier.progress(db_session, campaign)


async def refresh_template(db_session: AsyncSession, campaign):
    """Updates and republishes the template unless its image is already at
    campaign.version, then takes the old-version warm spares out of rotation."""
    try:
        if await lxd_service.get_image_version(settings.LXD_BASE_IMAGE) == campaign.version:
            return
        await update_service.update_template_container(campaign.version, campaign=campaign)
    except Exception as e:
        # A template failure shouldn't fail the islands' update — log loudly;
        # finish_campaign retries, or the admin republishes manually.
        logger.error(f"Update worker: Template update for {campaign.version} failed: {e}", exc_info=True)
        return
    await spare_pool.retire_spares(db_session)


async def enqueue_islands_cloned_from_old_template(db_session: AsyncSession, campaign) -> int:
    """Queues islands created after the campaign started from an image older
    than campaign.version — the queue was built at campaign creation and the
    template is republished a few minutes later, so a clone in between would
    otherwise stay on the old version for good. Returns how many were queued."""
    queued_ids = select(UpdateQueueModel.island_id).where(UpdateQueueModel.campaign_id == campaign.id)
    stragglers = (await db_session.execute(
        select(IslandModel).where(
            IslandModel.created_at >= campaign.created_at,
            IslandModel.is_spare.is_(False),
            IslandModel.skip_auto_updates.is_(False),
            IslandModel.status.notin_([IslandStatusEnum.DELETING, IslandStatusEnum.ARCHIVED]),
            (IslandModel.current_version.is_(None)) | (IslandModel.current_version != campaign.version),
            IslandModel.id.notin_(queued_ids),
        )
    )).scalars().all()
    for island in stragglers:
        await crud_update_queue.add_island(db_session, campaign_id=campaign.id, island=island)
        logger.info(f"Update worker: Island {island.id} was cloned from an older template "
                    f"({island.current_version or 'unknown'}) during {campaign.version} — queued.")
    return len(stragglers)


async def finish_campaign(db_session: AsyncSession, campaign):
    """Makes sure the template is at the campaign version and closes the campaign."""
    # Normally done at campaign start; this retries a failure there and covers
    # campaigns that were already running when this code was deployed.
    await refresh_template(db_session, campaign)

    await update_notifier.progress(db_session, campaign, current="оновлення Spawn")
    try:
        await update_service.update_spawn_container(campaign.version, campaign=campaign)
    except Exception as e:
        logger.error(f"Update worker: Spawn update for {campaign.version} failed: {e}", exc_info=True)

    counts = await crud_update_queue.count_by_status(db_session, campaign_id=campaign.id)
    await crud_update_campaign.set_status(db_session, campaign_id=campaign.id,
                                          status=CampaignStatusEnum.COMPLETED)
    logger.info(f"Update worker: Campaign {campaign.version} COMPLETED. Breakdown: {counts}")
    await update_notifier.campaign_finished(db_session, campaign)

    # Disk hygiene: old file backups are useless once a newer campaign landed
    # (rollback only goes one version back) — prune to the configured depth.
    try:
        pruned = await update_service.prune_file_backups(db_session)
        if pruned:
            logger.info(f"Update worker: Pruned {pruned} old backup version dirs.")
    except Exception as e:
        logger.error(f"Update worker: Backup pruning failed: {e}", exc_info=True)


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
    campaign = await crud_update_campaign.get(db_session, campaign_id=entry.campaign_id)
    island = await crud_island.get(db_session, island_id=island_id)
    if campaign and island:
        await update_notifier.island_resumed(campaign, island, "гравці вийшли — острів оновиться найближчим часом")
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
