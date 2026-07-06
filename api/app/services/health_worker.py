"""Background watchdog that reconciles island DB status with reality.

The core blind spot it closes: the LXD container can be up while the
Minecraft server inside it is dead or hung — the API used to never find out.

Signal: the Forge mod sends a heartbeat over the island WebSocket every
~30 seconds from the server tick loop (so a hung server thread means no
heartbeat). The watchdog checks every RUNNING island whose heartbeat went
silent for longer than HEARTBEAT_TIMEOUT_SECONDS and reacts by the real
container state:

    container gone      -> event "crashed",  status ERROR
    container stopped   -> event "crashed",  status STOPPED
    container frozen    -> event "state_mismatch", status FROZEN
    container running   -> Minecraft itself is dead/hung inside:
                           event "stopped_externally" (clean "stopping" signal
                           was seen) or "hung"; the container is stopped and
                           the island becomes STOPPED — the player simply
                           rejoins and the island starts fresh. No automatic
                           restart loops.

Islands that never sent a heartbeat this boot (old mod version still inside
the container) are skipped — the watchdog only judges servers that already
proved they can heartbeat.
"""
import asyncio
import logging
from datetime import datetime

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.db.session import AsyncSessionLocal
from app.crud.crud_island import crud_island
from app.crud.crud_island_event import crud_island_event
from app.schemas.island import IslandStatusEnum
from app.services.lxd_service import lxd_service

logger = logging.getLogger(__name__)

_worker_running = False
_worker_task = None

# How far back a "stopping" signal still explains the silence.
_CLEAN_STOP_WINDOW_SECONDS = 300


async def health_worker_loop():
    """The main loop for the health watchdog."""
    global _worker_running
    _worker_running = True
    logger.info("Health watchdog loop started.")

    while _worker_running:
        try:
            async with AsyncSessionLocal() as db_session:
                await check_running_islands(db_session)
        except Exception as e:
            logger.error(f"Health watchdog: unhandled exception: {e}", exc_info=True)

        await asyncio.sleep(settings.HEALTH_WORKER_INTERVAL)

    logger.info("Health watchdog loop stopped.")


async def check_running_islands(db_session: AsyncSession):
    """One watchdog pass over all islands the DB believes are RUNNING."""
    islands = await crud_island.get_islands_by_statuses(
        db_session, statuses=[IslandStatusEnum.RUNNING], limit=1000)
    now = datetime.utcnow()

    for island in islands:
        try:
            if not island.minecraft_ready:
                continue  # still booting; the start flow owns this phase
            if island.last_heartbeat_at is None:
                continue  # no heartbeat this boot (old mod) — cannot judge
            silence = (now - island.last_heartbeat_at).total_seconds()
            if silence < settings.HEARTBEAT_TIMEOUT_SECONDS:
                continue
            await _handle_silent_island(db_session, island, silence)
        except Exception as e:
            logger.error(f"Health watchdog: check of island {island.id} failed: {e}", exc_info=True)


async def _handle_silent_island(db_session: AsyncSession, island, silence: float):
    """Reacts to a RUNNING island whose heartbeat went silent."""
    container_name = island.container_name
    state = await lxd_service.get_container_state(container_name)
    lxd_status = ((state or {}).get("status") or "").lower()

    offline_fields = {"internal_ip_address": None, "minecraft_ready": False,
                      "last_heartbeat_at": None}

    if state is None:
        logger.error(f"Health watchdog: island {island.id}: container '{container_name}' "
                     f"is missing in LXD — marking ERROR.")
        await crud_island_event.add(db_session, island_id=island.id, event_type="crashed",
                                    details=f"Container '{container_name}' not found in LXD "
                                            f"(silent {int(silence)}s).")
        await crud_island.update_by_id(db_session, island_id=island.id,
                                       obj_in={"status": IslandStatusEnum.ERROR, **offline_fields})
        return

    if lxd_status == "stopped":
        logger.warning(f"Health watchdog: island {island.id}: container '{container_name}' "
                       f"went down while RUNNING — crash. Marking STOPPED.")
        await crud_island_event.add(db_session, island_id=island.id, event_type="crashed",
                                    details=f"Container stopped outside the API "
                                            f"(silent {int(silence)}s).")
        await crud_island.update_by_id(db_session, island_id=island.id,
                                       obj_in={"status": IslandStatusEnum.STOPPED, **offline_fields})
        return

    if lxd_status == "frozen":
        logger.warning(f"Health watchdog: island {island.id}: container '{container_name}' "
                       f"is frozen but DB says RUNNING — fixing status to FROZEN.")
        await crud_island_event.add(db_session, island_id=island.id, event_type="state_mismatch",
                                    details="Container frozen while DB status was RUNNING.")
        await crud_island.update_by_id(db_session, island_id=island.id,
                                       obj_in={"status": IslandStatusEnum.FROZEN,
                                               "last_heartbeat_at": None})
        return

    # Container is running but Minecraft is silent: dead or hung inside.
    clean_stop = await crud_island_event.has_recent_event(
        db_session, island_id=island.id, event_type="stopping",
        within_seconds=_CLEAN_STOP_WINDOW_SECONDS)
    event_type = "stopped_externally" if clean_stop else "hung"
    logger.warning(f"Health watchdog: island {island.id}: container '{container_name}' is up "
                   f"but Minecraft is silent for {int(silence)}s ({event_type}) — stopping container.")

    try:
        try:
            await lxd_service.stop_container(container_name, force=False)
        except Exception:
            logger.warning(f"Health watchdog: graceful stop of '{container_name}' failed, forcing.")
            await lxd_service.stop_container(container_name, force=True)
    except Exception as e:
        await crud_island_event.add(db_session, island_id=island.id, event_type=event_type,
                                    details=f"Minecraft silent {int(silence)}s; container stop FAILED: {e}")
        logger.error(f"Health watchdog: could not stop container '{container_name}': {e}", exc_info=True)
        return

    await crud_island_event.add(db_session, island_id=island.id, event_type=event_type,
                                details=f"Minecraft silent {int(silence)}s inside a running "
                                        f"container; container stopped by watchdog.")
    await crud_island.update_by_id(db_session, island_id=island.id,
                                   obj_in={"status": IslandStatusEnum.STOPPED, **offline_fields})


def start_health_worker():
    """Starts the health watchdog in a background asyncio task."""
    global _worker_task, _worker_running
    if _worker_task is None or _worker_task.done():
        logger.info("Starting background health watchdog.")
        _worker_running = True
        _worker_task = asyncio.create_task(health_worker_loop())
    else:
        logger.warning("Health watchdog is already running.")


def stop_health_worker():
    """Stops the background health watchdog."""
    global _worker_running
    if _worker_running:
        logger.info("Stopping background health watchdog.")
        _worker_running = False
    else:
        logger.warning("Health watchdog is not running.")
