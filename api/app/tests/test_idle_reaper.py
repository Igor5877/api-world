"""Tests for the idle reaper's proxy-backed stop check (pass 2)."""
import uuid
from datetime import datetime, timedelta
from unittest.mock import AsyncMock, patch

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.crud.crud_island import crud_island
from app.crud import crud_team
from app.schemas.island import IslandStatusEnum
from app.schemas.team import TeamCreate
from app.services.workers.idle_reaper_worker import _reap_idle_islands


async def _make_frozen_team_island(db_session: AsyncSession):
    """Team (owner + one member) with an island FROZEN long past STOP_TIMER_SECONDS."""
    owner_uuid = str(uuid.uuid4())
    member_uuid = str(uuid.uuid4())
    team = await crud_team.create_team(
        db=db_session, team_in=TeamCreate(name=f"team-{owner_uuid[:8]}", owner_uuid=owner_uuid))
    await db_session.commit()
    await crud_team.add_member(db=db_session, team=team, player_uuid=member_uuid)
    await db_session.commit()
    island = await crud_island.create(
        db_session=db_session, team_id=team.id,
        container_name=f"c-{owner_uuid[:8]}", initial_status=IslandStatusEnum.FROZEN,
    )
    await db_session.commit()
    island = await crud_island.update_by_id(
        db_session, island_id=island.id,
        obj_in={"updated_at": datetime.utcnow() - timedelta(days=1)})
    return team, island, owner_uuid, member_uuid


def _ws_mock(allow):
    ws = AsyncMock()
    ws.await_response.return_value = {"allow": allow, "online_count": 0 if allow else 1}
    return ws


@pytest.mark.asyncio
async def test_stop_check_asks_proxy_about_every_team_member(db_session: AsyncSession):
    _, _, owner_uuid, member_uuid = await _make_frozen_team_island(db_session)
    ws = _ws_mock(allow=False)
    with patch("app.services.workers.idle_reaper_worker.websocket_manager", ws):
        await _reap_idle_islands(db_session)

    request = ws.send_personal_message.await_args.args[0]
    assert request["type"] == "island_stop_check_request"
    assert request["owner_uuid"] == owner_uuid
    assert set(request["member_uuids"]) == {owner_uuid, member_uuid}


@pytest.mark.asyncio
async def test_island_stays_frozen_while_a_member_is_online_on_proxy(db_session: AsyncSession):
    _, island, _, _ = await _make_frozen_team_island(db_session)
    service = AsyncMock()
    with patch("app.services.workers.idle_reaper_worker.websocket_manager", _ws_mock(allow=False)), \
         patch("app.services.workers.idle_reaper_worker.island_service", service):
        await _reap_idle_islands(db_session)

    service._perform_lxd_stop_and_update_status.assert_not_awaited()
    refetched = await crud_island.get(db_session, island_id=island.id)
    assert refetched.status == IslandStatusEnum.FROZEN


@pytest.mark.asyncio
async def test_island_stops_when_nobody_from_team_is_online(db_session: AsyncSession):
    team, _, _, _ = await _make_frozen_team_island(db_session)
    service = AsyncMock()
    with patch("app.services.workers.idle_reaper_worker.websocket_manager", _ws_mock(allow=True)), \
         patch("app.services.workers.idle_reaper_worker.island_service", service):
        await _reap_idle_islands(db_session)

    service._perform_lxd_stop_and_update_status.assert_awaited_once_with(team_id=team.id)
