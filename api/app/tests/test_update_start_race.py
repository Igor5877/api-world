"""A player's start and an update campaign must never work on the same island
at once (2026-10-05: mods/ deleted and re-pushed while the server booted)."""
import uuid
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.crud.crud_island import crud_island
from app.models.island import Island as IslandModel
from app.schemas.island import IslandStatusEnum
from app.services.island_service import island_service
from app.services.update_service import IslandBusyError, update_service


class _FakeRedis:
    def __init__(self):
        self.data = {}

    async def get(self, k):
        return self.data.get(k)

    async def set(self, k, v, ex=None):
        self.data[k] = v

    async def delete(self, k):
        self.data.pop(k, None)


async def _island(db: AsyncSession, status=IslandStatusEnum.STOPPED) -> IslandModel:
    island = IslandModel(container_name=f"skyblock-solo-r-{uuid.uuid4().hex[:8]}", status=status,
                         player_uuid=str(uuid.uuid4()))
    db.add(island)
    await db.commit()
    await db.refresh(island)
    return island


@pytest.mark.asyncio
async def test_claim_status_only_one_winner(db_session: AsyncSession):
    island = await _island(db_session)
    first = await crud_island.claim_status(db_session, island_id=island.id,
                                           expected=IslandStatusEnum.STOPPED, new=IslandStatusEnum.PENDING_START)
    second = await crud_island.claim_status(db_session, island_id=island.id,
                                            expected=IslandStatusEnum.STOPPED, new=IslandStatusEnum.UPDATING)
    assert (first, second) == (True, False)


@pytest.mark.asyncio
async def test_update_does_not_touch_island_a_player_just_started(db_session: AsyncSession):
    island = await _island(db_session)
    island_id = island.id
    seen_by_worker = SimpleNamespace(id=island.id, status=IslandStatusEnum.STOPPED,
                                     container_name=island.container_name, team_id=None, player_uuid=None)
    # гравець встиг запустити острів після перевірки воркера
    await crud_island.update_by_id(db_session, island_id=island.id, obj_in={"status": IslandStatusEnum.PENDING_START})
    lxd = AsyncMock()
    lxd.get_container_state.return_value = {"status": "Running"}
    campaign = SimpleNamespace(id=1, version="v9", requires_restart=True, changed_paths=[], reload_commands=[])
    with patch("app.services.update_service.lxd_service", lxd):
        with pytest.raises(IslandBusyError):
            await update_service.perform_island_update(db_session, seen_by_worker, campaign)
    lxd.shutdown_container.assert_not_awaited()
    lxd.backup_files.assert_not_awaited()
    db_session.expire_all()
    assert (await crud_island.get(db_session, island_id=island_id)).status == IslandStatusEnum.PENDING_START


@pytest.mark.asyncio
async def test_start_during_update_waits_and_starts_after(db_session: AsyncSession):
    island = await _island(db_session, status=IslandStatusEnum.UPDATING)
    island_id = island.id
    redis = _FakeRedis()
    with patch("app.services.island_service.get_redis_client", return_value=redis), \
         patch.object(island_service, "_send_update_notification", AsyncMock()):
        resp = await island_service._start_existing_island(db_session, island, None, MagicMock())
    assert resp.status == IslandStatusEnum.UPDATING
    assert redis.data == {f"start_after_update:{island_id}": "1"}

    # оновлення завершилось — острів STOPPED; воркер просить старт
    await crud_island.update_by_id(db_session, island_id=island_id, obj_in={"status": IslandStatusEnum.STOPPED})

    class _cm:
        async def __aenter__(self):
            return db_session

        async def __aexit__(self, *a):
            return False

    solo_start = AsyncMock()
    with patch("app.services.island_service.get_redis_client", return_value=redis), \
         patch("app.db.session.AsyncSessionLocal", return_value=_cm()), \
         patch.object(island_service, "_perform_solo_lxd_start_and_update_status", solo_start):
        await island_service.start_if_requested_after_update(island_id)
    solo_start.assert_awaited_once()
    assert redis.data == {}
    db_session.expire_all()
    assert (await crud_island.get(db_session, island_id=island_id)).status == IslandStatusEnum.PENDING_START
