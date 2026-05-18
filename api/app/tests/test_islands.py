"""
HTTP endpoint tests for /api/v1/islands/*.

Each test mocks island_service at the endpoint level so we only
verify HTTP routing, status codes, and response shapes — not business logic.
"""
import uuid
from unittest.mock import AsyncMock, patch, MagicMock

import pytest

from app.schemas.island import IslandResponse, IslandStatusEnum, MessageResponse

API = "/api/v1/islands"
PLAYER_UUID = str(uuid.uuid4())


def island_resp(**kwargs) -> IslandResponse:
    defaults = dict(
        id=1,
        player_uuid=uuid.UUID(PLAYER_UUID),
        team_id=1,
        container_name="skyblock-test",
        status=IslandStatusEnum.STOPPED,
        minecraft_ready=False,
    )
    defaults.update(kwargs)
    return IslandResponse(**defaults)


# ── GET /{player_uuid} ────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_get_island_found(client):
    resp_data = island_resp(status=IslandStatusEnum.RUNNING)
    with patch("app.api.v1.endpoints.islands.island_service") as svc:
        svc.get_island_by_player_uuid = AsyncMock(return_value=resp_data)
        r = await client.get(f"{API}/{PLAYER_UUID}")

    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "RUNNING"
    assert body["id"] == 1
    assert body["container_name"] == "skyblock-test"


@pytest.mark.asyncio
async def test_get_island_not_found(client):
    with patch("app.api.v1.endpoints.islands.island_service") as svc:
        svc.get_island_by_player_uuid = AsyncMock(return_value=None)
        r = await client.get(f"{API}/{PLAYER_UUID}")

    assert r.status_code == 404
    assert "not found" in r.json()["detail"].lower()


# ── POST /start/{player_uuid} ─────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_start_island_from_stopped(client):
    resp_data = island_resp(status=IslandStatusEnum.PENDING_START)
    with patch("app.api.v1.endpoints.islands.island_service") as svc:
        svc.start_island_instance = AsyncMock(return_value=resp_data)
        r = await client.post(f"{API}/start/{PLAYER_UUID}?player_name=Steve")

    assert r.status_code == 202
    assert r.json()["status"] == "PENDING_START"


@pytest.mark.asyncio
async def test_start_island_new_player_creates_island(client):
    resp_data = island_resp(status=IslandStatusEnum.PENDING_CREATION)
    with patch("app.api.v1.endpoints.islands.island_service") as svc:
        svc.start_island_instance = AsyncMock(return_value=resp_data)
        r = await client.post(f"{API}/start/{PLAYER_UUID}?player_name=Steve")

    assert r.status_code == 202
    assert r.json()["status"] == "PENDING_CREATION"


@pytest.mark.asyncio
async def test_start_island_already_pending_returns_202(client):
    resp_data = island_resp(status=IslandStatusEnum.PENDING_START)
    with patch("app.api.v1.endpoints.islands.island_service") as svc:
        svc.start_island_instance = AsyncMock(return_value=resp_data)
        r = await client.post(f"{API}/start/{PLAYER_UUID}?player_name=Steve")

    assert r.status_code == 202


@pytest.mark.asyncio
async def test_start_island_conflict_already_running(client):
    with patch("app.api.v1.endpoints.islands.island_service") as svc:
        svc.start_island_instance = AsyncMock(
            side_effect=ValueError("Island cannot be started: already running")
        )
        r = await client.post(f"{API}/start/{PLAYER_UUID}?player_name=Steve")

    assert r.status_code == 409


@pytest.mark.asyncio
async def test_start_island_bad_state_returns_409(client):
    with patch("app.api.v1.endpoints.islands.island_service") as svc:
        svc.start_island_instance = AsyncMock(
            side_effect=ValueError("Island cannot be started from its current state: DELETING")
        )
        r = await client.post(f"{API}/start/{PLAYER_UUID}?player_name=Steve")

    assert r.status_code == 409


@pytest.mark.asyncio
async def test_start_island_missing_player_name_returns_422(client):
    r = await client.post(f"{API}/start/{PLAYER_UUID}")
    assert r.status_code == 422


# ── POST /stop/{player_uuid} ──────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_stop_island_running(client):
    resp_data = island_resp(status=IslandStatusEnum.PENDING_STOP)
    with patch("app.api.v1.endpoints.islands.island_service") as svc:
        svc.stop_island_instance = AsyncMock(return_value=resp_data)
        r = await client.post(f"{API}/stop/{PLAYER_UUID}")

    assert r.status_code == 202
    assert r.json()["status"] == "PENDING_STOP"


@pytest.mark.asyncio
async def test_stop_island_not_found(client):
    with patch("app.api.v1.endpoints.islands.island_service") as svc:
        svc.stop_island_instance = AsyncMock(
            side_effect=ValueError("No island found for this player to stop.")
        )
        r = await client.post(f"{API}/stop/{PLAYER_UUID}")

    assert r.status_code == 404


@pytest.mark.asyncio
async def test_stop_island_bad_state_returns_409(client):
    with patch("app.api.v1.endpoints.islands.island_service") as svc:
        svc.stop_island_instance = AsyncMock(
            side_effect=ValueError("Island cannot be stopped from its current state: PENDING_STOP")
        )
        r = await client.post(f"{API}/stop/{PLAYER_UUID}")

    assert r.status_code == 409


# ── POST /{player_uuid}/freeze ────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_freeze_island_running(client):
    resp_data = island_resp(status=IslandStatusEnum.PENDING_FREEZE)
    with patch("app.api.v1.endpoints.islands.island_service") as svc:
        svc.freeze_island_instance = AsyncMock(return_value=resp_data)
        r = await client.post(f"{API}/{PLAYER_UUID}/freeze")

    assert r.status_code == 202
    assert r.json()["status"] == "PENDING_FREEZE"


@pytest.mark.asyncio
async def test_freeze_island_already_frozen(client):
    resp_data = island_resp(status=IslandStatusEnum.FROZEN)
    with patch("app.api.v1.endpoints.islands.island_service") as svc:
        svc.freeze_island_instance = AsyncMock(return_value=resp_data)
        r = await client.post(f"{API}/{PLAYER_UUID}/freeze")

    assert r.status_code == 202
    assert r.json()["status"] == "FROZEN"


@pytest.mark.asyncio
async def test_freeze_island_not_running_conflict(client):
    with patch("app.api.v1.endpoints.islands.island_service") as svc:
        svc.freeze_island_instance = AsyncMock(
            side_effect=ValueError("Island cannot be frozen from its current state: STOPPED")
        )
        r = await client.post(f"{API}/{PLAYER_UUID}/freeze")

    assert r.status_code == 409


@pytest.mark.asyncio
async def test_freeze_island_not_found(client):
    with patch("app.api.v1.endpoints.islands.island_service") as svc:
        svc.freeze_island_instance = AsyncMock(
            side_effect=ValueError("No island found for this player to freeze.")
        )
        r = await client.post(f"{API}/{PLAYER_UUID}/freeze")

    assert r.status_code == 404


# ── POST /{owner_uuid}/ready ──────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_mark_island_ready_success(client):
    with patch("app.api.v1.endpoints.islands.island_service") as svc:
        svc.mark_island_as_ready_for_players = AsyncMock(return_value=None)
        r = await client.post(f"{API}/{PLAYER_UUID}/ready")

    assert r.status_code == 200
    assert r.json()["message"] == "Island marked as ready for players."


@pytest.mark.asyncio
async def test_mark_island_ready_not_found(client):
    with patch("app.api.v1.endpoints.islands.island_service") as svc:
        svc.mark_island_as_ready_for_players = AsyncMock(
            side_effect=ValueError("Team or island not found for this owner.")
        )
        r = await client.post(f"{API}/{PLAYER_UUID}/ready")

    assert r.status_code == 404


@pytest.mark.asyncio
async def test_mark_island_ready_wrong_state_conflict(client):
    with patch("app.api.v1.endpoints.islands.island_service") as svc:
        svc.mark_island_as_ready_for_players = AsyncMock(
            side_effect=ValueError("Island is not in a state to be marked ready")
        )
        r = await client.post(f"{API}/{PLAYER_UUID}/ready")

    assert r.status_code == 409


@pytest.mark.asyncio
async def test_mark_island_ready_server_error(client):
    with patch("app.api.v1.endpoints.islands.island_service") as svc:
        svc.mark_island_as_ready_for_players = AsyncMock(
            side_effect=RuntimeError("unexpected")
        )
        r = await client.post(f"{API}/{PLAYER_UUID}/ready")

    assert r.status_code == 500


# ── Root health check ─────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_root_health(client):
    r = await client.get("/")
    assert r.status_code == 200
    assert "message" in r.json()
