"""Warm spare pool: islands cloned, booted and frozen before anyone needs them.

A brand-new player's first /myisland normally waits for an LXD clone of the
template plus a full modded cold boot (a minute or more). With
WARM_POOL_SIZE > 0 this module keeps that many owner-less islands ready:

  1. create   — clone LXD_BASE_IMAGE into skyblock-spare-<hex>, push a
                skyblock_island_data.toml with `spare = true`, start it.
  2. boot     — the mod, seeing `spare`, stays out of every owner-bound path
                (FTB team, quests, freeze), connects its WS as
                core_spare-<island id>, heartbeats, and sends `spare_ready`.
  3. freeze   — on spare_ready the API freezes the container (FROZEN, CPU 0)
                until someone claims it.
  4. claim    — a new player's team takes the spare (row lock, SKIP LOCKED so
                two newcomers never get the same one), the container is
                resumed and the mod receives a `claim` over WS: it rewrites
                the toml, loads the team, reconnects as core_<owner> and
                sends the usual /ready. The claim is resent whenever
                core_spare-<id> (re)connects, because a resumed JVM usually
                finds its old WS dead.
  5. fallback — no /ready within SPARE_CLAIM_TIMEOUT_SECONDS: the API writes
                the toml itself and restarts the container (a normal boot,
                still without the clone).

The pool loop also deletes spares that are broken (stopped, errored, never
became ready) or were cloned from an older template than the one
LXD_BASE_IMAGE points to now, so a claimed island is always current.

Spares are left out of update campaigns and of the idle reaper's stop pass.
"""
import asyncio
import logging
import uuid
from datetime import datetime, timedelta, timezone
from typing import Optional

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core.config import settings
from app.core.leader_election import LeaderElection
from app.crud.crud_island import crud_island
from app.crud.crud_island_event import crud_island_event
from app.db.session import AsyncSessionLocal
from app.models.island import Island as IslandModel, IslandEvent as IslandEventModel
from app.models.team import Team as TeamModel
from app.schemas.island import IslandStatusEnum
from app.services.lxd_service import ISLAND_LABELS, lxd_service
from app.services.websocket_manager import manager as websocket_manager

logger = logging.getLogger(__name__)

ISLAND_DATA_PATH = "/opt/minecraft/world/serverconfig/skyblock_island_data.toml"
SPARE_CLIENT_PREFIX = "spare-"

# Spare statuses that count towards WARM_POOL_SIZE (alive or on its way).
_LIVE_SPARE_STATUSES = (
    IslandStatusEnum.PENDING_CREATION,
    IslandStatusEnum.RUNNING,
    IslandStatusEnum.FROZEN,
)

_worker_running = False
_worker_task = None


def spare_client_id(island_id: int) -> str:
    """WS client_id of a spare's mod (without the core_ prefix)."""
    return f"{SPARE_CLIENT_PREFIX}{island_id}"


def parse_spare_client_id(identity: str) -> Optional[int]:
    """Island id from a `spare-<id>` WS identity, or None if it isn't one."""
    if not identity.startswith(SPARE_CLIENT_PREFIX):
        return None
    try:
        return int(identity[len(SPARE_CLIENT_PREFIX):])
    except ValueError:
        return None


def island_data_toml(team: TeamModel) -> str:
    """skyblock_island_data.toml for an owned island (same format as a fresh clone)."""
    member_uuids = [member.player_uuid for member in team.members]
    return (f"is_island_server = true\n"
            f"team_id = {team.id}\n"
            f"owner_uuid = \"{team.owner_uuid}\"\n"
            f"member_uuids = {member_uuids}\n")


def _spare_toml(island_id: int) -> str:
    return (f"is_island_server = false\n"
            f"spare = true\n"
            f"spare_island_id = {island_id}\n")


def _utcnow() -> datetime:
    return datetime.utcnow()


# ── claim ────────────────────────────────────────────────────────────────

async def claim_spare(db_session: AsyncSession, *, team: TeamModel,
                      player_uuid: Optional[str] = None,
                      player_name: Optional[str] = None) -> Optional[IslandModel]:
    """Hands a ready spare to `team`. Does not commit; returns None if there is none.

    The row lock (FOR UPDATE SKIP LOCKED on MySQL) makes two concurrent
    newcomers pick different spares, or one of them fall back to a clone.
    The caller commits, then schedules activate_claimed_spare().
    """
    if settings.WARM_POOL_SIZE <= 0:
        return None
    spare = (await db_session.execute(
        select(IslandModel)
        .where(IslandModel.is_spare.is_(True),
               IslandModel.minecraft_ready.is_(True),
               IslandModel.status.in_([IslandStatusEnum.FROZEN, IslandStatusEnum.RUNNING]))
        .order_by(IslandModel.id)
        .limit(1)
        .with_for_update(skip_locked=True)
    )).scalars().first()
    if not spare:
        return None

    spare.is_spare = False
    spare.team_id = team.id
    spare.player_uuid = player_uuid
    spare.player_name = player_name
    spare.status = IslandStatusEnum.PENDING_START
    spare.minecraft_ready = False
    spare.spare_claimed_at = _utcnow()
    spare.idle_since = None
    db_session.add(spare)
    await db_session.flush()
    db_session.add(IslandEventModel(
        island_id=spare.id, event_type="spare_claimed",
        details=f"Warm spare '{spare.container_name}' claimed by team {team.id}."))
    logger.info(f"Spare pool: '{spare.container_name}' (island {spare.id}) claimed by team {team.id}.")
    return spare


async def build_claim_message(db_session: AsyncSession, island: IslandModel) -> Optional[dict]:
    """The `claim` WS payload for a claimed-but-not-ready island, else None."""
    if island.is_spare or island.spare_claimed_at is None or not island.team_id:
        return None
    # populate_existing: a Team already in this session keeps its old members
    # collection otherwise, and the mod would get a stale roster.
    team = (await db_session.execute(
        select(TeamModel).where(TeamModel.id == island.team_id)
        .options(selectinload(TeamModel.members))
        .execution_options(populate_existing=True)
    )).scalars().first()
    if not team:
        return None
    return {
        "type": "claim",
        "island_id": island.id,
        "team_id": team.id,
        "owner_uuid": team.owner_uuid,
        "member_uuids": [member.player_uuid for member in team.members],
    }


async def activate_claimed_spare(island_id: int):
    """Background task after claim_spare(): resume, mark RUNNING, send the claim."""
    from app.services.island_service import island_service

    async with AsyncSessionLocal() as db:
        island = await crud_island.get(db, island_id=island_id)
        if not island or island.spare_claimed_at is None:
            return
        try:
            await lxd_service.start_container(island.container_name)  # resumes if frozen
            ip = await lxd_service.get_container_ip(island.container_name)
            if not ip:
                raise RuntimeError("no IP after resume")
            island = await crud_island.update_by_id(db, island_id=island.id, obj_in={
                "status": IslandStatusEnum.RUNNING,
                "internal_ip_address": ip,
                "internal_port": settings.DEFAULT_MC_PORT_INTERNAL,
                "last_seen_at": datetime.now(timezone.utc),
                # The resumed JVM's last heartbeat is from before the freeze —
                # let the watchdog judge only heartbeats from here on.
                "last_heartbeat_at": None,
            })
        except Exception as e:
            logger.error(f"Spare pool: resuming claimed island {island_id} failed: {e} — "
                         f"falling back to a restart.", exc_info=True)
            await _restart_claimed(db, island)
            return

        await _enqueue_if_campaign_missed(db, island)

        message = await build_claim_message(db, island)
        if message:
            await websocket_manager.send_personal_message(message, f"core_{spare_client_id(island.id)}")

        team = (await db.execute(
            select(TeamModel).where(TeamModel.id == island.team_id)
            .options(selectinload(TeamModel.members), selectinload(TeamModel.island))
        )).scalars().first()
        await island_service._send_update_notification(team, island)


async def _enqueue_if_campaign_missed(db: AsyncSession, island: IslandModel):
    """A spare booted before the running campaign's template refresh isn't in
    its queue (spares never are) — queue it now that it's a real island."""
    from app.crud.crud_update import crud_update_campaign
    from app.crud.crud_update_queue import crud_update_queue

    try:
        campaign = await crud_update_campaign.get_active(db)
        if campaign and island.current_version != campaign.version:
            await crud_update_queue.add_island(db, campaign_id=campaign.id, island=island)
            logger.info(f"Spare pool: claimed island {island.id} ({island.current_version or 'unknown'}) "
                        f"queued for running campaign {campaign.version}.")
    except Exception as e:
        logger.error(f"Spare pool: queueing claimed island {island.id} for the campaign failed: {e}",
                     exc_info=True)


async def _restart_claimed(db: AsyncSession, island: IslandModel):
    """Fallback: the mod never took the claim — write its toml and cold-restart it."""
    from app.services.island_service import island_service

    team = (await db.execute(
        select(TeamModel).where(TeamModel.id == island.team_id)
        .options(selectinload(TeamModel.members))
        .execution_options(populate_existing=True)
    )).scalars().first()
    if not team:
        logger.error(f"Spare pool: claimed island {island.id} has no team — cannot fall back.")
        return
    await crud_island_event.add(
        db, island_id=island.id, event_type="spare_claim_fallback",
        details="Mod did not confirm the claim in time — writing island data and restarting.")
    await crud_island.update_by_id(db, island_id=island.id, obj_in={
        "spare_claimed_at": None, "minecraft_ready": False,
        "status": IslandStatusEnum.PENDING_START})
    try:
        await lxd_service.stop_container(island.container_name, force=True)
        await lxd_service.push_file_to_container(
            island.container_name, ISLAND_DATA_PATH, island_data_toml(team).encode("utf-8"))
    except Exception as e:
        logger.error(f"Spare pool: fallback for island {island.id} failed: {e}", exc_info=True)
    await island_service._perform_lxd_start_and_update_status(
        team_id=team.id, container_name=island.container_name)


# ── spare_ready (from the WS endpoint) ───────────────────────────────────

async def handle_spare_ready(island_id: int):
    """The spare's mod finished booting — mark it claimable and freeze it."""
    async with AsyncSessionLocal() as db:
        island = await crud_island.get(db, island_id=island_id)
        if not island or not island.is_spare:
            return
        if island.status == IslandStatusEnum.FROZEN and island.minecraft_ready:
            return
        if await _is_outdated(island.container_name):
            # Booted from a template that was republished meanwhile — never
            # claimable; the pool tick deletes and rebuilds it.
            logger.info(f"Spare pool: spare {island.id} finished booting from an old template — not using it.")
            return
        await crud_island.update_by_id(db, island_id=island.id,
                                       obj_in={"minecraft_ready": True,
                                               "status": IslandStatusEnum.PENDING_FREEZE})
        try:
            await lxd_service.freeze_container(island.container_name)
        except Exception as e:
            logger.error(f"Spare pool: freezing spare {island.id} failed: {e}", exc_info=True)
            # Still claimable while RUNNING; the pool tick retries the freeze.
            await crud_island.update_by_id(db, island_id=island.id,
                                           obj_in={"status": IslandStatusEnum.RUNNING})
            return
        await crud_island.update_by_id(db, island_id=island.id,
                                       obj_in={"status": IslandStatusEnum.FROZEN,
                                               "last_heartbeat_at": None})
        logger.info(f"Spare pool: spare '{island.container_name}' (island {island.id}) ready and frozen.")


async def _is_outdated(container_name: str) -> bool:
    current = await lxd_service.get_image_fingerprint(settings.LXD_BASE_IMAGE)
    if not current:
        return False
    base = await lxd_service.get_container_base_image(container_name)
    return base is not None and base != current


# ── pool maintenance ─────────────────────────────────────────────────────

async def _delete_spare(db: AsyncSession, island: IslandModel, reason: str):
    logger.warning(f"Spare pool: deleting spare '{island.container_name}' (island {island.id}): {reason}")
    await crud_island.update_by_id(db, island_id=island.id,
                                   obj_in={"status": IslandStatusEnum.DELETING})
    try:
        await lxd_service.delete_container(island.container_name)
    except Exception as e:
        logger.error(f"Spare pool: deleting container '{island.container_name}' failed: {e} — "
                     f"leaving the row DELETING for the next tick.", exc_info=True)
        return
    await db.delete(island)
    await db.commit()


async def _prune_spares(db: AsyncSession, current_image: Optional[str]):
    """Deletes broken and outdated spares; retries a freeze that failed earlier."""
    spares = (await db.execute(
        select(IslandModel).where(IslandModel.is_spare.is_(True))
    )).scalars().all()
    # created_at is the DB's server_default NOW() — in the DB server's local
    # time zone, not UTC — so the age is measured against the DB's own clock.
    # Comparing it with utcnow() made a spare look 3 h younger (Kyiv), and a
    # spare that crashed while booting blocked the pool for 3 h 15 min.
    now = await db.scalar(select(func.now()))
    for island in spares:
        age = (now - island.created_at).total_seconds() if island.created_at else 0
        if island.status in (IslandStatusEnum.STOPPED, IslandStatusEnum.DELETING,
                             IslandStatusEnum.ERROR, IslandStatusEnum.ERROR_CREATE,
                             IslandStatusEnum.ERROR_START):
            await _delete_spare(db, island, f"status {island.status.value}")
            continue
        if not island.minecraft_ready and age > settings.SPARE_BOOT_TIMEOUT_SECONDS:
            await _delete_spare(db, island, f"no spare_ready after {int(age)}s")
            continue
        if current_image and island.status in (IslandStatusEnum.RUNNING, IslandStatusEnum.FROZEN):
            base_image = await lxd_service.get_container_base_image(island.container_name)
            if base_image is None:
                await _delete_spare(db, island, "container missing in LXD")
                continue
            if base_image != current_image:
                await _delete_spare(db, island, f"template changed ({base_image[:12]} → {current_image[:12]})")
                continue
        if island.minecraft_ready and island.status == IslandStatusEnum.RUNNING:
            await handle_spare_ready(island.id)


async def retire_spares(db: AsyncSession) -> int:
    """Takes every unclaimed spare out of rotation right away — called after
    the template image is republished, so nobody claims an old-version spare
    in the ≤ SPARE_POOL_INTERVAL_SECONDS before the pool tick deletes it
    (the pool sees the base image changed and rebuilds it)."""
    spares = (await db.execute(
        select(IslandModel).where(IslandModel.is_spare.is_(True),
                                  IslandModel.minecraft_ready.is_(True))
    )).scalars().all()
    for island in spares:
        island.minecraft_ready = False
        db.add(island)
    await db.commit()
    if spares:
        logger.info(f"Spare pool: template republished — {len(spares)} spare(s) taken out of rotation.")
    return len(spares)


def island_container_name(island: IslandModel, team: TeamModel) -> str:
    """The name a non-spare island would have got from a normal clone."""
    if island.player_uuid and island.player_name:
        safe = "".join(c if c.isalnum() else "-" for c in island.player_name)
        return f"skyblock-solo-{safe}-{uuid.UUID(str(island.player_uuid)).hex[:8]}"
    safe = "".join(c if c.isalnum() else "-" for c in team.name)
    return f"skyblock-team-{safe}-{team.id}"


async def _rename_claimed_spares(db: AsyncSession):
    """A claimed spare keeps its skyblock-spare-* name while it runs (LXD can't
    rename a running container) — once it is STOPPED, give it the normal
    island name so admins and the crash dashboards see whose island it is."""
    rows = (await db.execute(
        select(IslandModel).where(IslandModel.is_spare.is_(False),
                                  IslandModel.container_name.like("skyblock-spare-%"),
                                  IslandModel.status == IslandStatusEnum.STOPPED,
                                  IslandModel.team_id.isnot(None))
        .with_for_update(skip_locked=True)
    )).scalars().all()
    for island in rows:
        team = await db.get(TeamModel, island.team_id)
        new_name = island_container_name(island, team)
        state = await lxd_service.get_container_state(island.container_name)
        if ((state or {}).get("status") or "").lower() != "stopped":
            continue  # LXD каже інакше, ніж БД — не чіпаємо
        if await lxd_service.get_container_state(new_name) is not None:
            logger.warning(f"Spare pool: cannot rename '{island.container_name}' — '{new_name}' already exists.")
            continue
        old_name = island.container_name
        try:
            await lxd_service.rename_container(old_name, new_name)
        except Exception as e:
            logger.error(f"Spare pool: renaming '{old_name}' → '{new_name}' failed: {e}", exc_info=True)
            continue
        island.container_name = new_name
        db.add(island)
        db.add(IslandEventModel(island_id=island.id, event_type="container_renamed",
                                details=f"Claimed warm spare renamed '{old_name}' → '{new_name}'."))
        logger.info(f"Spare pool: claimed island {island.id} renamed '{old_name}' → '{new_name}'.")
    await db.commit()


async def _fallback_stale_claims(db: AsyncSession):
    """Claimed islands whose mod never sent /ready — restart them the normal way."""
    deadline = _utcnow() - timedelta(seconds=settings.SPARE_CLAIM_TIMEOUT_SECONDS)
    stale = (await db.execute(
        select(IslandModel).where(IslandModel.is_spare.is_(False),
                                  IslandModel.spare_claimed_at.isnot(None),
                                  IslandModel.spare_claimed_at < deadline,
                                  IslandModel.minecraft_ready.is_(False))
    )).scalars().all()
    for island in stale:
        await _restart_claimed(db, island)


async def _create_spare(db: AsyncSession):
    container_name = f"skyblock-spare-{uuid.uuid4().hex[:8]}"
    island = IslandModel(container_name=container_name, is_spare=True,
                         status=IslandStatusEnum.PENDING_CREATION)
    db.add(island)
    await db.commit()
    await db.refresh(island)
    logger.info(f"Spare pool: creating spare '{container_name}' (island {island.id}).")
    try:
        await lxd_service.clone_container(source_image_alias=settings.LXD_BASE_IMAGE,
                                          new_container_name=container_name,
                                          config=dict(ISLAND_LABELS),
                                          profiles=settings.LXD_DEFAULT_PROFILES)
        await lxd_service.push_file_to_container(container_name, ISLAND_DATA_PATH,
                                                 _spare_toml(island.id).encode("utf-8"))
        current_version = await lxd_service.get_container_image_version(container_name)
        await lxd_service.start_container(container_name)
        ip = await lxd_service.get_container_ip(container_name)
        await crud_island.update_by_id(db, island_id=island.id, obj_in={
            "current_version": current_version,
            "status": IslandStatusEnum.RUNNING, "internal_ip_address": ip,
            "internal_port": settings.DEFAULT_MC_PORT_INTERNAL, "last_heartbeat_at": None})
    except Exception as e:
        logger.error(f"Spare pool: creating spare '{container_name}' failed: {e}", exc_info=True)
        await crud_island.update_by_id(db, island_id=island.id,
                                       obj_in={"status": IslandStatusEnum.ERROR_CREATE})


async def maintain_pool(db: AsyncSession):
    """One pool tick: prune, fall back stuck claims, top up to WARM_POOL_SIZE."""
    await _fallback_stale_claims(db)
    await _rename_claimed_spares(db)
    if settings.WARM_POOL_SIZE <= 0:
        return
    current_image = await lxd_service.get_image_fingerprint(settings.LXD_BASE_IMAGE)
    await _prune_spares(db, current_image)

    live = await db.scalar(select(func.count(IslandModel.id)).where(
        IslandModel.is_spare.is_(True), IslandModel.status.in_(_LIVE_SPARE_STATUSES)))
    if live >= settings.WARM_POOL_SIZE:
        return
    running = await crud_island.get_running_islands_count(db)
    if running >= settings.MAX_RUNNING_SERVERS:
        logger.info(f"Spare pool: {running}/{settings.MAX_RUNNING_SERVERS} islands running — "
                    f"not booting a spare now.")
        return
    await _create_spare(db)


async def spare_pool_loop():
    """Runs on every gunicorn process; one leader maintains the pool per tick."""
    global _worker_running
    _worker_running = True
    logger.info("Spare pool loop started.")
    # A tick can include a full LXD clone, so the lock must outlive that,
    # not just the sleep — renewal only happens at the next tick().
    leader = LeaderElection("spare_pool_leader_lock",
                            max(settings.WORKER_LEADER_LOCK_TTL_SECONDS, 900))
    while _worker_running:
        try:
            if await leader.tick():
                async with AsyncSessionLocal() as db:
                    await maintain_pool(db)
        except Exception as e:
            logger.error(f"Spare pool: unhandled exception: {e}", exc_info=True)
        await asyncio.sleep(settings.SPARE_POOL_INTERVAL_SECONDS)
    logger.info("Spare pool loop stopped.")


def start_spare_pool_worker():
    """Starts the spare pool in a background asyncio task."""
    global _worker_task, _worker_running
    if _worker_task is None or _worker_task.done():
        logger.info("Starting background spare pool.")
        _worker_running = True
        _worker_task = asyncio.create_task(spare_pool_loop())
    else:
        logger.warning("Spare pool is already running.")
