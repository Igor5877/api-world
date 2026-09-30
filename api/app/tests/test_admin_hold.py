"""Tests for admin mode (services/admin_hold.py): the API leaves a held island alone."""
import uuid
from datetime import datetime, timedelta
from unittest.mock import AsyncMock, patch

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.crud import crud_team
from app.crud.crud_island import crud_island
from app.schemas.island import IslandStatusEnum
from app.schemas.team import TeamCreate
from app.services.health_worker import check_running_islands

API = "/api/v1/islands"


async def _make_island(db_session: AsyncSession, *, hold_minutes=None, status=IslandStatusEnum.RUNNING):
    owner_uuid = str(uuid.uuid4())
    team = await crud_team.create_team(
        db=db_session, team_in=TeamCreate(name=f"team-{owner_uuid[:8]}", owner_uuid=owner_uuid))
    await db_session.commit()
    island = await crud_island.create(
        db_session=db_session, team_id=team.id,
        container_name=f"c-{owner_uuid[:8]}", player_uuid=owner_uuid, initial_status=status)
    await db_session.commit()
    fields = {"minecraft_ready": True,
              "last_heartbeat_at": datetime.utcnow() - timedelta(seconds=600)}
    if hold_minutes is not None:
        fields.update(admin_hold_by="Igor", admin_hold_reason="test",
                      admin_hold_until=datetime.utcnow() + timedelta(minutes=hold_minutes))
    island = await crud_island.update_by_id(db_session, island_id=island.id, obj_in=fields)
    return owner_uuid, island


@pytest.mark.asyncio
async def test_watchdog_leaves_held_island_alone(db_session: AsyncSession):
    """Silent heartbeat on a held island is the admin's restart, not a hang."""
    _, island = await _make_island(db_session, hold_minutes=30)
    lxd = AsyncMock()
    lxd.get_container_state.return_value = {"status": "Running"}
    with patch("app.services.health_worker.lxd_service", lxd):
        await check_running_islands(db_session)
    lxd.get_container_state.assert_not_awaited()
    lxd.stop_container.assert_not_awaited()
    assert (await crud_island.get(db_session, island_id=island.id)).status == IslandStatusEnum.RUNNING


@pytest.mark.asyncio
async def test_watchdog_judges_island_after_hold_expired(db_session: AsyncSession):
    _, island = await _make_island(db_session, hold_minutes=-1)
    lxd = AsyncMock()
    lxd.get_container_state.return_value = {"status": "Stopped"}
    with patch("app.services.health_worker.lxd_service", lxd):
        await check_running_islands(db_session)
    assert (await crud_island.get(db_session, island_id=island.id)).status == IslandStatusEnum.STOPPED


@pytest.mark.asyncio
async def test_player_start_and_stop_refused_but_admin_passes(client, db_session: AsyncSession):
    owner_uuid, _ = await _make_island(db_session, hold_minutes=30, status=IslandStatusEnum.STOPPED)

    r = await client.post(f"{API}/start/{owner_uuid}?player_name=Steve")
    assert r.status_code == 423
    assert "Igor" in r.json()["detail"]
    r = await client.post(f"{API}/stop/{owner_uuid}")
    assert r.status_code == 423
    r = await client.post(f"{API}/{owner_uuid}/freeze")
    assert r.status_code == 423

    with patch("app.api.v1.endpoints.islands.island_service") as svc:
        svc.start_island_instance = AsyncMock(side_effect=ValueError("not found"))
        r = await client.post(f"{API}/start/{owner_uuid}?player_name=Steve&admin_name=Igor")
    assert r.status_code != 423  # guard passed; the mocked service decided the rest


@pytest.mark.asyncio
async def test_hold_release_and_list_endpoints(client, db_session: AsyncSession):
    owner_uuid, island = await _make_island(db_session, status=IslandStatusEnum.RUNNING)

    r = await client.post(f"{API}/{owner_uuid}/admin/hold",
                          json={"admin_name": "Igor", "reason": "консоль", "minutes": 15})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["held"] is True and body["admin_hold_by"] == "Igor"

    r = await client.get(f"{API}/admin/holds")
    assert [h["owner_uuid"] for h in r.json()] == [owner_uuid]
    r = await client.get(f"{API}/admin/list")
    row = next(i for i in r.json() if i["owner_uuid"] == owner_uuid)
    assert row["admin_hold_by"] == "Igor"

    r = await client.delete(f"{API}/{owner_uuid}/admin/hold?admin_name=Igor")
    assert r.status_code == 200 and r.json()["held"] is False
    refetched = await crud_island.get(db_session, island_id=island.id)
    assert refetched.admin_hold_until is None
    assert refetched.last_heartbeat_at is None  # watchdog waits for a fresh heartbeat
    assert (await client.get(f"{API}/admin/holds")).json() == []


@pytest.mark.asyncio
async def test_hold_rejects_too_long(client, db_session: AsyncSession):
    owner_uuid, _ = await _make_island(db_session)
    r = await client.post(f"{API}/{owner_uuid}/admin/hold", json={"admin_name": "Igor", "minutes": 100000})
    assert r.status_code == 422
