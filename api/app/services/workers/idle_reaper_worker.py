"""API Idle Reaper — the server-side "plan B" for island lifecycle state.

Root problem this closes (2026-08-22/23 incident): every existing idle
timer lived in exactly one place that could lose it silently — the Forge
mod's own 5-minute freeze schedule (only fires if a real "player left"
Minecraft event happens on that boot), or the Velocity proxy's in-memory
15-minute stop schedule (wiped by any proxy restart). If neither event ever
fires — an aborted CRP transfer, a manually-triggered start nobody actually
joins, a proxy restart mid-countdown — the island simply runs forever with
nothing watching it. Conversely, DB/LXD status can silently diverge (health
races) with nothing but `health_worker`'s heartbeat-gated check noticing,
and that check only ever looks at DB=RUNNING islands.

This worker is a second, independent, server-side authority that needs no
event from anywhere else to eventually notice and correct both problems.
Three reconciliation passes per tick, each on its own DB session so one
pass's failure can't roll back another's work:

  1. _reap_state_mismatches — DB status vs real LXD status, for every
     "stable" (non-transitional) status. Independent of heartbeat.
  2. _reap_idle_islands — FROZEN for longer than STOP_TIMER_SECONDS -> stop.
     Freezing is NOT this worker's job (removed 2026-08-25, see the
     function docstring) — only the mod/plugin ever freeze an island.
  3. _reap_stuck_transitions — PENDING_START/STOP/FREEZE held far longer
     than a normal LXD op should take, inspected via the LXD-level Java
     process check (never the container's own network) and reconciled to
     an accurate terminal status. Deliberately does NOT auto-restart
     anything (that's A6, explicitly scoped out of P0) — it only makes
     sure the state itself is never silently wrong forever.

Every corrective action is written to `island_events` (crud_island_event)
so it's visible without opening the database.
"""
import asyncio
import logging
import uuid
from datetime import datetime, timedelta
from typing import Optional

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.leader_election import LeaderElection
from app.db.session import AsyncSessionLocal
from app.crud.crud_island import crud_island
from app.crud.crud_island_event import crud_island_event
from app.models.island import Island as IslandModel
from app.models.team import Team as TeamModel, TeamMember as TeamMemberModel
from app.schemas.island import IslandStatusEnum
from app.services import admin_hold
from app.services.lxd_service import lxd_service
from app.services.island_service import island_service
from app.services.websocket_manager import manager as websocket_manager

logger = logging.getLogger(__name__)

_worker_running = False
_worker_task = None

# Statuses this worker will reconcile against real LXD state in pass 1.
_STABLE_STATUS_TO_LXD = {
    IslandStatusEnum.RUNNING: "running",
    IslandStatusEnum.FROZEN: "frozen",
    IslandStatusEnum.STOPPED: "stopped",
}
_LXD_TO_STABLE_STATUS = {v: k for k, v in _STABLE_STATUS_TO_LXD.items()}

# Transitional statuses pass 3 is allowed to eventually inspect once they've
# been held too long. Anything NOT in this set and not in _STABLE_STATUS_TO_LXD
# (CREATING, PENDING_CREATION, UPDATING, PENDING_UPDATE, ERROR*, ...) is left
# alone entirely — those belong to a different flow (creation, the update
# system) that this worker must never interfere with.
_WATCHED_TRANSITIONAL_STATUSES = {
    IslandStatusEnum.PENDING_START,
    IslandStatusEnum.PENDING_STOP,
    IslandStatusEnum.PENDING_FREEZE,
}


async def idle_reaper_loop():
    """Runs on every gunicorn process; persistent Redis leader election (see
    app.core.leader_election) decides which one actually reaps each tick."""
    global _worker_running
    _worker_running = True
    logger.info("Idle reaper loop started.")

    # TTL must outlive one full tick (work + sleep), not just the sleep —
    # renewal only happens at the top of the next tick() call.
    leader = LeaderElection("idle_reaper_leader_lock",
                             max(settings.WORKER_LEADER_LOCK_TTL_SECONDS, settings.IDLE_REAPER_INTERVAL_SECONDS * 4))

    while _worker_running:
        try:
            if await leader.tick():
                async with AsyncSessionLocal() as db_session:
                    await _reap_state_mismatches(db_session)
                async with AsyncSessionLocal() as db_session:
                    await _reap_idle_islands(db_session)
                async with AsyncSessionLocal() as db_session:
                    await _reap_stuck_transitions(db_session)
        except Exception as e:
            logger.error(f"Idle reaper: unhandled exception: {e}", exc_info=True)

        await asyncio.sleep(settings.IDLE_REAPER_INTERVAL_SECONDS)

    logger.info("Idle reaper loop stopped.")


# ── Pass 1: state reconciliation ────────────────────────────────────────

async def _reap_state_mismatches(db_session: AsyncSession):
    """DB status vs real LXD status, for stable (non-transitional) statuses.

    Independent of heartbeat — catches e.g. DB=STOPPED/LXD=FROZEN (a health
    race from an unclean restart), which health_worker's heartbeat-gated
    check never looks at (it only inspects DB=RUNNING islands).
    """
    islands = await crud_island.get_islands_by_statuses(
        db_session, statuses=list(_STABLE_STATUS_TO_LXD.keys()), limit=1000)

    for island in islands:
        if admin_hold.is_held(island):
            continue  # адмін міг навмисно зупинити/запустити контейнер — не "виправляємо"
        try:
            state = await lxd_service.get_container_state(island.container_name)
        except Exception as e:
            logger.warning(f"Idle reaper: could not read LXD state for island {island.id} "
                           f"('{island.container_name}'): {e} — skipping this tick.")
            continue

        if state is None:
            # Container genuinely missing from LXD while DB=RUNNING is
            # health_worker's job (it already handles that exact case).
            # Missing while DB=FROZEN/STOPPED is not obviously wrong (could
            # be mid-deletion by another flow) — don't guess, leave it.
            continue

        lxd_status = (state.get("status") or "").lower()
        expected_status = _LXD_TO_STABLE_STATUS.get(lxd_status)
        if expected_status is None or expected_status == island.status:
            continue

        # Re-read fresh immediately before writing — another flow may have
        # already moved this island between the LXD query above and now.
        fresh = await crud_island.get(db_session, island_id=island.id)
        if not fresh or fresh.status != island.status:
            continue

        logger.warning(f"Idle reaper: island {island.id} ('{island.container_name}') state "
                       f"mismatch — DB={island.status.value} LXD={lxd_status}. Reconciling.")
        await crud_island_event.add(
            db_session, island_id=island.id, event_type="state_mismatch",
            details=f"Idle reaper reconciled: DB={island.status.value} -> "
                    f"{expected_status.value} (real LXD state).")
        update_fields = {"status": expected_status}
        if expected_status != IslandStatusEnum.RUNNING:
            update_fields["internal_ip_address"] = None
            update_fields["minecraft_ready"] = False
            update_fields["online_players"] = 0
            update_fields["idle_since"] = None
        elif not island.internal_ip_address:
            # DB thought the island was offline (no IP recorded) but LXD says
            # it's actually running — fetch the real IP so RUNNING doesn't
            # get stamped with a stale/missing address (the mod's /ready call
            # never touches internal_ip_address, so nothing else would).
            real_ip = await lxd_service.get_container_ip(island.container_name)
            if real_ip:
                update_fields["internal_ip_address"] = real_ip
        await crud_island.update_by_id(db_session, island_id=island.id, obj_in=update_fields)


# ── Pass 2: idle timeout ────────────────────────────────────────────────

async def _reap_idle_islands(db_session: AsyncSession):
    """FROZEN for long enough with no proxy-reported activity -> stop.

    Freezing is deliberately NOT this worker's job any more (removed
    2026-08-25): it was pure duplication of the mod's own
    PlayerEventHandler.scheduleFreezeTask (same 5-minute number), and unlike
    the mod it had no way to know a player was still actively connected —
    online_players/idle_since only update via the heartbeat WS, a completely
    separate connection from the player's actual game connection through the
    proxy. A stalled/dead heartbeat WS with a still-playing player used to
    read as "idle" here and get silently frozen out from under them. See
    lifecycle_stability_plan.md §3.

    Stop is kept as a backstop for when the proxy's own stop_delay_seconds
    never fires (proxy restart, crash) — `updated_at` on a FROZEN island is
    reliable on its own (freezing suspends the process, so nothing keeps
    bumping it while frozen).
    """
    now = datetime.utcnow()

    frozen = await crud_island.get_islands_by_statuses(
        db_session, statuses=[IslandStatusEnum.FROZEN], limit=1000)
    for island in frozen:
        if island.is_spare:
            continue  # a warm spare is frozen on purpose — stopping it defeats the pool
        if admin_hold.is_held(island, now):
            continue
        since = island.updated_at
        if since is None:
            continue
        idle_seconds = (now - since).total_seconds()
        if idle_seconds < settings.STOP_TIMER_SECONDS:
            continue
        await _reap_stop(db_session, island, idle_seconds)


async def _reap_stop(db_session: AsyncSession, island: IslandModel, idle_seconds: float):
    """Stops one FROZEN island after re-verifying it's still safe to.

    Before acting, asks the proxy_control connection (if any) whether any
    member of the island's team is online anywhere on the proxy (spawn,
    someone else's island, mid-/myisland) — such a player can come back any
    second, so the island stays FROZEN (instant resume) instead of being
    stopped (full cold boot). Asking about the island's own backend server
    only was wrong twice over: a member on the spawn is "not on the island"
    yet about to return, and Velocity names the backend after whichever
    player connected (`island-<player uuid>`), so a non-owner member's
    session was invisible to an owner-keyed check. If the proxy doesn't
    answer in time (no connection at all, or it's down) this worker proceeds
    anyway — it must stay a backstop that works independently of the proxy
    being alive.
    """
    fresh = await crud_island.get(db_session, island_id=island.id)
    if not fresh or fresh.status != IslandStatusEnum.FROZEN:
        return

    owner_uuid = str(fresh.player_uuid) if fresh.player_uuid else None
    member_uuids: list[str] = [owner_uuid] if owner_uuid else []
    if fresh.team_id:
        if not owner_uuid:
            owner_uuid = await db_session.scalar(select(TeamModel.owner_uuid).where(TeamModel.id == fresh.team_id))
        member_uuids += (await db_session.scalars(
            select(TeamMemberModel.player_uuid).where(TeamMemberModel.team_id == fresh.team_id))).all()
    if owner_uuid:
        member_uuids.append(owner_uuid)
    member_uuids = sorted({str(u) for u in member_uuids if u})

    if member_uuids:
        request_id = str(uuid.uuid4())
        # owner_uuid kept for proxies still running the old owner-server check.
        await websocket_manager.send_personal_message(
            {"type": "island_stop_check_request", "request_id": request_id,
             "owner_uuid": owner_uuid, "member_uuids": member_uuids},
            "proxy_control")
        response = await websocket_manager.await_response(request_id, timeout=5.0)
        if response is not None and response.get("allow") is False:
            logger.info(f"Idle reaper: island {island.id} stop deferred — proxy reports "
                       f"{response.get('online_count', '?')} team member(s) online on the proxy.")
            await crud_island_event.add(
                db_session, island_id=island.id, event_type="stop_deferred_by_proxy",
                details=f"Idle reaper: proxy reports {response.get('online_count', '?')} "
                        f"team member(s) online on the proxy — stop skipped this tick, will retry.")
            return

    logger.info(f"Idle reaper: island {island.id} ('{island.container_name}') frozen-idle "
               f"{int(idle_seconds)}s — auto-stopping.")
    await crud_island_event.add(
        db_session, island_id=island.id, event_type="auto_stopped_idle",
        details=f"Idle reaper: FROZEN for {int(idle_seconds)}s "
                f"(>= STOP_TIMER_SECONDS={settings.STOP_TIMER_SECONDS}) — stopped automatically.")
    await crud_island.update_by_id(db_session, island_id=island.id,
                                   obj_in={"status": IslandStatusEnum.PENDING_STOP})
    try:
        if fresh.team_id:
            await island_service._perform_lxd_stop_and_update_status(team_id=fresh.team_id)
        elif fresh.player_uuid:
            await island_service._perform_solo_lxd_stop_and_update_status(
                player_uuid_str=str(fresh.player_uuid))
        else:
            logger.error(f"Idle reaper: island {island.id} has neither team_id nor player_uuid — "
                         f"cannot determine which stop path to use.")
    except Exception as e:
        logger.error(f"Idle reaper: stop of island {island.id} failed: {e}", exc_info=True)
        await crud_island_event.add(
            db_session, island_id=island.id, event_type="auto_stopped_idle",
            details=f"Idle reaper: auto-stop attempt FAILED: {e}")


# ── Pass 3: stuck transitions ────────────────────────────────────────────

async def _reap_stuck_transitions(db_session: AsyncSession):
    """PENDING_START/STOP/FREEZE held far longer than a normal LXD op takes.

    Inspects the REAL state via LXD (container status + a `pgrep` for java
    through the local socket, never the container's own network — see
    lxd_service.get_java_process_info) and reconciles the DB status to
    something accurate. Deliberately does NOT retry/restart anything here
    (A6, explicitly P2) — P0's job is only to stop the state from being
    silently wrong forever, per the invariant "an island must never stay in
    STARTING without active monitoring."
    """
    stuck = await crud_island.get_islands_by_statuses(
        db_session, statuses=list(_WATCHED_TRANSITIONAL_STATUSES), limit=1000)
    now = datetime.utcnow()

    for island in stuck:
        if admin_hold.is_held(island, now):
            continue
        age = (now - (island.updated_at or now)).total_seconds()
        if age < settings.STARTUP_STUCK_TIMEOUT_SECONDS:
            continue

        fresh = await crud_island.get(db_session, island_id=island.id)
        if not fresh or fresh.status != island.status:
            continue  # already resolved between the scan and now

        try:
            state = await lxd_service.get_container_state(fresh.container_name)
        except Exception as e:
            logger.warning(f"Idle reaper: could not inspect stuck island {island.id}: {e}")
            continue

        lxd_status = ((state or {}).get("status") or "missing").lower()

        if lxd_status == "running":
            java_info = await lxd_service.get_java_process_info(fresh.container_name)
            if java_info is None:
                # UNKNOWN — exec itself failed. Do not conclude anything; the
                # spec's invariant #1 applies here just as much as it does to
                # a heartbeat timeout.
                logger.warning(f"Idle reaper: island {island.id} stuck {int(age)}s in "
                               f"{island.status.value}, container RUNNING, but the LXD-level java "
                               f"check itself failed — leaving status as-is (UNKNOWN, not dead).")
                continue
            if java_info["alive"]:
                # Container up, java up — this might just be a genuinely slow
                # boot (huge modpack) or a hung-but-alive JVM. Record it once
                # per stuck-detection so an admin can see it, but do not
                # change status — minecraft_ready/heartbeat still own that
                # decision, per invariant #1 (missing heartbeat != dead).
                logger.info(f"Idle reaper: island {island.id} stuck {int(age)}s in "
                           f"{island.status.value}, but java (pid(s) {java_info['pids']}) is alive "
                           f"inside a RUNNING container — leaving it to continue booting.")
                continue
            # Container RUNNING but no java process at all: the JVM died
            # without the container itself stopping. This is a confirmed,
            # not guessed, dead state.
            logger.warning(f"Idle reaper: island {island.id} stuck {int(age)}s in "
                           f"{island.status.value}, container RUNNING but NO java process found — "
                           f"confirmed dead. Reconciling to ERROR_START.")
            await crud_island_event.add(
                db_session, island_id=island.id, event_type="hung",
                details=f"Idle reaper: stuck {int(age)}s in {island.status.value}; container RUNNING "
                        f"but no java process (confirmed via LXD exec, not heartbeat) — ERROR_START.")
            await crud_island.update_by_id(
                db_session, island_id=island.id,
                obj_in={"status": IslandStatusEnum.ERROR_START, "internal_ip_address": None,
                        "minecraft_ready": False, "online_players": 0, "idle_since": None})
            continue

        if lxd_status == "frozen":
            await crud_island_event.add(
                db_session, island_id=island.id, event_type="state_mismatch",
                details=f"Idle reaper: stuck {int(age)}s in {island.status.value}; container is "
                        f"actually FROZEN — reconciling.")
            await crud_island.update_by_id(
                db_session, island_id=island.id,
                obj_in={"status": IslandStatusEnum.FROZEN, "last_heartbeat_at": None})
            continue

        if lxd_status in ("stopped", "missing"):
            target = IslandStatusEnum.ERROR_START if island.status == IslandStatusEnum.PENDING_START else IslandStatusEnum.STOPPED
            await crud_island_event.add(
                db_session, island_id=island.id, event_type="state_mismatch",
                details=f"Idle reaper: stuck {int(age)}s in {island.status.value}; container is "
                        f"{lxd_status} — reconciling to {target.value}.")
            await crud_island.update_by_id(
                db_session, island_id=island.id,
                obj_in={"status": target, "internal_ip_address": None, "minecraft_ready": False,
                        "online_players": 0, "idle_since": None})
            continue


def start_idle_reaper_worker():
    """Starts the idle reaper in a background asyncio task."""
    global _worker_task, _worker_running
    if _worker_task is None or _worker_task.done():
        logger.info("Starting background idle reaper.")
        _worker_running = True
        _worker_task = asyncio.create_task(idle_reaper_loop())
    else:
        logger.warning("Idle reaper is already running.")


def stop_idle_reaper_worker():
    """Stops the background idle reaper."""
    global _worker_running
    if _worker_running:
        logger.info("Stopping background idle reaper.")
        _worker_running = False
    else:
        logger.warning("Idle reaper is not running.")
