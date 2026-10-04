"""Graceful island stop (world is saved) and the destroy barrier (crash-agent
collects evidence before a container is deleted)."""
import uuid
from unittest.mock import AsyncMock, patch

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.crud.crud_island import crud_island
from app.models.island import Island as IslandModel, IslandEvent as IslandEventModel
from app.schemas.island import IslandStatusEnum
from app.services import crash_agent, spare_pool
from app.services.island_service import island_service
from app.services.lxd_service import LXDService, LXDServiceError
from sqlalchemy import select


# ── shutdown_container ─────────────────────────────────────────────────

def _svc(status):
    svc = LXDService()
    svc.get_container_state = AsyncMock(return_value=None if status is None else {"status": status})
    svc.stop_container = AsyncMock()
    svc.unfreeze_container = AsyncMock()
    return svc


@pytest.mark.asyncio
async def test_shutdown_is_graceful_with_long_timeout():
    svc = _svc("Running")
    assert await svc.shutdown_container("c") == "graceful"
    svc.stop_container.assert_awaited_once_with("c", force=False,
                                                timeout=spare_pool.settings.LXD_GRACEFUL_STOP_TIMEOUT)


@pytest.mark.asyncio
async def test_shutdown_resumes_frozen_container_first():
    svc = _svc("Frozen")
    assert await svc.shutdown_container("c") == "graceful"
    svc.unfreeze_container.assert_awaited_once_with("c")


@pytest.mark.asyncio
async def test_shutdown_forces_only_after_graceful_timeout():
    svc = _svc("Running")
    svc.stop_container.side_effect = [LXDServiceError("timeout"), None]
    assert await svc.shutdown_container("c") == "forced"
    assert svc.stop_container.await_args_list[1].kwargs == {"force": True}


@pytest.mark.asyncio
async def test_shutdown_of_stopped_container_does_nothing():
    svc = _svc("Stopped")
    assert await svc.shutdown_container("c") == "already_stopped"
    svc.stop_container.assert_not_awaited()


def test_graceful_timeout_covers_unit_stop_timeout():
    # minecraft.service: TimeoutStopSec=150 > wrapper --grace 120
    assert spare_pool.settings.LXD_GRACEFUL_STOP_TIMEOUT > 150


# ── crash_agent barrier ────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_barrier_disabled_without_agent_url():
    with patch.object(crash_agent.settings, "CRASH_AGENT_URL", ""):
        assert (await crash_agent.collect_before_destroy("c"))[0] is True


@pytest.mark.asyncio
async def test_barrier_gives_up_after_timeout():
    with patch.object(crash_agent.settings, "CRASH_AGENT_URL", "http://agent"), \
         patch.object(crash_agent.settings, "DESTROY_BARRIER_TIMEOUT_SECONDS", 0), \
         patch("app.services.crash_agent.collect", AsyncMock(return_value=(False, "агент недоступний"))):
        ok, reason = await crash_agent.collect_before_destroy("c")
    assert ok is False and "недоступний" in reason


# ── island deletion ────────────────────────────────────────────────────

class _session_cm:
    def __init__(self, session):
        self._session = session

    async def __aenter__(self):
        return self._session

    async def __aexit__(self, *exc):
        return False


async def _island(db: AsyncSession) -> IslandModel:
    island = IslandModel(container_name=f"skyblock-solo-x-{uuid.uuid4().hex[:8]}",
                         status=IslandStatusEnum.STOPPED, player_uuid=str(uuid.uuid4()))
    db.add(island)
    await db.commit()
    await db.refresh(island)
    return island


@pytest.mark.asyncio
async def test_island_not_deleted_until_evidence_collected(db_session: AsyncSession):
    island = await _island(db_session)
    island_id, name = island.id, island.container_name
    lxd = AsyncMock()
    with patch("app.services.island_service.lxd_service", lxd), \
         patch("app.services.island_service.crash_agent.collect_before_destroy",
               AsyncMock(return_value=(False, "crash-report ще дописується"))), \
         patch("app.db.session.AsyncSessionLocal", return_value=_session_cm(db_session)):
        await island_service._perform_lxd_delete_and_cleanup(island_id)

    lxd.shutdown_container.assert_awaited_once_with(name)
    lxd.delete_container.assert_not_awaited()
    db_session.expire_all()
    kept = await crud_island.get(db_session, island_id=island_id)
    assert kept.status == IslandStatusEnum.DELETING
    events = (await db_session.execute(select(IslandEventModel.event_type)
                                       .where(IslandEventModel.island_id == island_id))).scalars().all()
    assert "destroy_blocked" in events


@pytest.mark.asyncio
async def test_island_deleted_after_stop_and_collection(db_session: AsyncSession):
    island = await _island(db_session)
    island_id, name = island.id, island.container_name
    lxd = AsyncMock()
    order = []
    lxd.shutdown_container.side_effect = lambda n: order.append("stop")
    lxd.delete_container.side_effect = lambda n: order.append("delete")

    async def collected(n):
        order.append("collect")
        return True, "зібрано"

    with patch("app.services.island_service.lxd_service", lxd), \
         patch("app.services.island_service.crash_agent.collect_before_destroy", collected), \
         patch("app.db.session.AsyncSessionLocal", return_value=_session_cm(db_session)):
        await island_service._perform_lxd_delete_and_cleanup(island_id)

    assert order == ["stop", "collect", "delete"]
    db_session.expire_all()
    assert await crud_island.get(db_session, island_id=island_id) is None


@pytest.mark.asyncio
async def test_spare_kept_while_agent_has_not_collected(db_session: AsyncSession):
    spare = IslandModel(container_name="skyblock-spare-deadbeef", is_spare=True,
                        status=IslandStatusEnum.ERROR, minecraft_ready=False)
    db_session.add(spare)
    await db_session.commit()
    lxd = AsyncMock()
    lxd.get_image_fingerprint.return_value = None
    with patch.object(spare_pool.settings, "WARM_POOL_SIZE", 0), \
         patch.object(spare_pool.settings, "CRASH_AGENT_URL", "http://agent"), \
         patch("app.services.spare_pool.lxd_service", lxd), \
         patch("app.services.spare_pool.crash_agent.collect", AsyncMock(return_value=(False, "Loki недоступний"))):
        await spare_pool._prune_spares(db_session, None)
    lxd.delete_container.assert_not_awaited()
