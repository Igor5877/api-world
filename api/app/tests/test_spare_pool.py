"""Tests for the warm spare pool (services/spare_pool.py) and its hooks."""
import uuid
from datetime import datetime, timedelta
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.crud import crud_team
from app.crud.crud_island import crud_island
from app.models.island import Island as IslandModel
from app.schemas.island import IslandStatusEnum
from app.schemas.team import TeamCreate
from app.services import spare_pool
from app.services.island_service import island_service
from app.services.workers.idle_reaper_worker import _reap_idle_islands


async def _make_spare(db: AsyncSession, *, status=IslandStatusEnum.FROZEN, ready=True,
                      age_seconds=0) -> IslandModel:
    island = IslandModel(container_name=f"skyblock-spare-{uuid.uuid4().hex[:8]}", is_spare=True,
                         status=status, minecraft_ready=ready,
                         created_at=datetime.utcnow() - timedelta(seconds=age_seconds))
    db.add(island)
    await db.commit()
    await db.refresh(island)
    return island


async def _make_team(db: AsyncSession) -> tuple:
    owner_uuid = str(uuid.uuid4())
    team = await crud_team.create_team(
        db=db, team_in=TeamCreate(name=f"t-{owner_uuid[:8]}", owner_uuid=owner_uuid))
    await db.commit()
    return team, owner_uuid


def _pool_size(n):
    return patch.object(spare_pool.settings, "WARM_POOL_SIZE", n)


# ── claim ──────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_new_player_gets_ready_spare_instead_of_clone(db_session: AsyncSession):
    spare = await _make_spare(db_session)
    player_uuid = str(uuid.uuid4())
    bg = MagicMock()
    with _pool_size(1), patch("app.services.island_service.lxd_service") as lxd:
        result = await island_service.start_island_instance(
            db_session, player_uuid=player_uuid, player_name="Newbie", background_tasks=bg)

    assert result.id == spare.id
    assert result.status == IslandStatusEnum.PENDING_START
    lxd.clone_container.assert_not_called()
    bg.add_task.assert_called_once_with(spare_pool.activate_claimed_spare, island_id=spare.id)

    claimed = await crud_island.get(db_session, island_id=spare.id)
    team = await crud_team.get_team_by_player(db=db_session, player_uuid=player_uuid)
    assert claimed.is_spare is False
    assert claimed.team_id == team.id
    assert claimed.minecraft_ready is False
    assert claimed.spare_claimed_at is not None


@pytest.mark.asyncio
async def test_no_spare_falls_back_to_clone(db_session: AsyncSession):
    bg = MagicMock()
    with _pool_size(1):
        result = await island_service.start_island_instance(
            db_session, player_uuid=str(uuid.uuid4()), player_name="Newbie", background_tasks=bg)
    assert result.status == IslandStatusEnum.PENDING_CREATION
    assert bg.add_task.call_args.args[0] == island_service._perform_lxd_clone_and_update_status


@pytest.mark.asyncio
async def test_pool_disabled_never_claims(db_session: AsyncSession):
    await _make_spare(db_session)
    team, _ = await _make_team(db_session)
    with _pool_size(0):
        assert await spare_pool.claim_spare(db_session, team=team) is None


@pytest.mark.asyncio
async def test_spare_still_booting_is_not_claimable(db_session: AsyncSession):
    await _make_spare(db_session, status=IslandStatusEnum.RUNNING, ready=False)
    team, _ = await _make_team(db_session)
    with _pool_size(1):
        assert await spare_pool.claim_spare(db_session, team=team) is None


@pytest.mark.asyncio
async def test_one_spare_is_claimed_only_once(db_session: AsyncSession):
    await _make_spare(db_session)
    team_a, _ = await _make_team(db_session)
    team_b, _ = await _make_team(db_session)
    with _pool_size(1):
        first = await spare_pool.claim_spare(db_session, team=team_a)
        await db_session.commit()
        second = await spare_pool.claim_spare(db_session, team=team_b)
    assert first is not None
    assert second is None


@pytest.mark.asyncio
async def test_claim_message_carries_owner_and_members(db_session: AsyncSession):
    spare = await _make_spare(db_session)
    team, owner_uuid = await _make_team(db_session)
    member_uuid = str(uuid.uuid4())
    await crud_team.add_member(db=db_session, team=team, player_uuid=member_uuid)
    with _pool_size(1):
        await spare_pool.claim_spare(db_session, team=team)
    await db_session.commit()

    island = await crud_island.get(db_session, island_id=spare.id)
    message = await spare_pool.build_claim_message(db_session, island)
    assert message["type"] == "claim"
    assert message["owner_uuid"] == owner_uuid
    assert message["team_id"] == team.id
    assert member_uuid in message["member_uuids"]


@pytest.mark.asyncio
async def test_no_claim_message_for_unclaimed_spare(db_session: AsyncSession):
    spare = await _make_spare(db_session)
    assert await spare_pool.build_claim_message(db_session, spare) is None


# ── pool maintenance ───────────────────────────────────────────────────

def _lxd(base_image="img-new", current_image="img-new"):
    lxd = AsyncMock()
    lxd.get_image_fingerprint.return_value = current_image
    lxd.get_container_base_image.return_value = base_image
    lxd.get_container_ip.return_value = "10.0.0.5"
    return lxd


@pytest.mark.asyncio
async def test_pool_creates_spare_when_empty(db_session: AsyncSession):
    lxd = _lxd()
    with _pool_size(1), patch("app.services.spare_pool.lxd_service", lxd):
        await spare_pool.maintain_pool(db_session)

    lxd.clone_container.assert_awaited_once()
    toml = lxd.push_file_to_container.await_args.args[2].decode()
    assert "spare = true" in toml
    assert "is_island_server = false" in toml
    spares = await crud_island.get_multi(db_session, skip=0, limit=10)
    assert len(spares) == 1 and spares[0].is_spare and spares[0].status == IslandStatusEnum.RUNNING


@pytest.mark.asyncio
async def test_pool_full_creates_nothing(db_session: AsyncSession):
    await _make_spare(db_session)
    lxd = _lxd()
    with _pool_size(1), patch("app.services.spare_pool.lxd_service", lxd):
        await spare_pool.maintain_pool(db_session)
    lxd.clone_container.assert_not_awaited()
    lxd.delete_container.assert_not_awaited()


@pytest.mark.asyncio
async def test_pool_replaces_spare_from_old_template(db_session: AsyncSession):
    old = await _make_spare(db_session)
    lxd = _lxd(base_image="img-old", current_image="img-new")
    with _pool_size(1), patch("app.services.spare_pool.lxd_service", lxd):
        await spare_pool.maintain_pool(db_session)
    lxd.delete_container.assert_awaited_once_with(old.container_name)
    lxd.clone_container.assert_awaited_once()


@pytest.mark.asyncio
async def test_pool_deletes_spare_that_never_became_ready(db_session: AsyncSession):
    stuck = await _make_spare(db_session, status=IslandStatusEnum.RUNNING, ready=False,
                              age_seconds=spare_pool.settings.SPARE_BOOT_TIMEOUT_SECONDS + 60)
    lxd = _lxd()
    with _pool_size(1), patch("app.services.spare_pool.lxd_service", lxd):
        await spare_pool.maintain_pool(db_session)
    lxd.delete_container.assert_awaited_once_with(stuck.container_name)


@pytest.mark.asyncio
async def test_spare_ready_freezes_it(db_session: AsyncSession):
    spare = await _make_spare(db_session, status=IslandStatusEnum.RUNNING, ready=False)
    lxd = _lxd()
    with patch("app.services.spare_pool.lxd_service", lxd), \
         patch("app.services.spare_pool.AsyncSessionLocal", return_value=_session_cm(db_session)):
        await spare_pool.handle_spare_ready(spare.id)
    lxd.freeze_container.assert_awaited_once_with(spare.container_name)
    refreshed = await crud_island.get(db_session, island_id=spare.id)
    assert refreshed.status == IslandStatusEnum.FROZEN
    assert refreshed.minecraft_ready is True


@pytest.mark.asyncio
async def test_stale_claim_falls_back_to_restart(db_session: AsyncSession):
    spare = await _make_spare(db_session)
    team, _ = await _make_team(db_session)
    with _pool_size(1):
        await spare_pool.claim_spare(db_session, team=team)
    await db_session.commit()
    await crud_island.update_by_id(db_session, island_id=spare.id, obj_in={
        "spare_claimed_at": datetime.utcnow() - timedelta(minutes=5)})

    lxd = _lxd()
    service = AsyncMock()
    with _pool_size(1), patch("app.services.spare_pool.lxd_service", lxd), \
         patch("app.services.island_service.island_service", service):
        await spare_pool.maintain_pool(db_session)

    toml = next(c.args[2].decode() for c in lxd.push_file_to_container.await_args_list
                if c.args[0] == spare.container_name)
    assert f'owner_uuid = "{team.owner_uuid}"' in toml
    service._perform_lxd_start_and_update_status.assert_awaited_once_with(
        team_id=team.id, container_name=spare.container_name)


# ── other components leave spares alone ────────────────────────────────

@pytest.mark.asyncio
async def test_idle_reaper_never_stops_a_frozen_spare(db_session: AsyncSession):
    spare = await _make_spare(db_session)
    await crud_island.update_by_id(db_session, island_id=spare.id,
                                   obj_in={"updated_at": datetime.utcnow() - timedelta(days=1)})
    service = AsyncMock()
    with patch("app.services.workers.idle_reaper_worker.island_service", service), \
         patch("app.services.workers.idle_reaper_worker.websocket_manager", AsyncMock()):
        await _reap_idle_islands(db_session)
    refreshed = await crud_island.get(db_session, island_id=spare.id)
    assert refreshed.status == IslandStatusEnum.FROZEN


def test_spare_client_id_roundtrip():
    assert spare_pool.parse_spare_client_id(spare_pool.spare_client_id(42)) == 42
    assert spare_pool.parse_spare_client_id(str(uuid.uuid4())) is None
    assert spare_pool.parse_spare_client_id("spare-abc") is None


class _session_cm:
    """AsyncSessionLocal() stand-in that hands out the test's session."""

    def __init__(self, session):
        self._session = session

    async def __aenter__(self):
        return self._session

    async def __aexit__(self, *exc):
        return False
