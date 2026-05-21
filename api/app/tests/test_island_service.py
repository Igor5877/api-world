"""
Service-layer tests for IslandService.

Uses a real SQLite in-memory DB via the db_session fixture.
LXD calls and WebSocket notifications are mocked so no infrastructure needed.
"""
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.crud import crud_team
from app.crud.crud_island import crud_island
from app.schemas.island import IslandStatusEnum
from app.schemas.team import TeamCreate
from app.services.island_service import island_service


# ── Helpers ───────────────────────────────────────────────────────────────────

async def _make_team_and_island(
    db: AsyncSession,
    player_uuid: str,
    status: IslandStatusEnum = IslandStatusEnum.STOPPED,
):
    """Creates a team owned by player_uuid with one island at the given status."""
    team = await crud_team.create_team(
        db=db,
        team_in=TeamCreate(
            name=f"t-{player_uuid[:8]}",
            owner_uuid=player_uuid,
            owner_name="TestPlayer",
        ),
    )
    await db.flush()

    island = await crud_island.create(
        db_session=db,
        team_id=team.id,
        container_name=f"c-{player_uuid[:8]}",
        player_uuid=player_uuid,
        initial_status=status,
    )
    team.island = island
    db.add(team)
    await db.commit()
    await db.refresh(team, attribute_names=["island", "members"])
    return team, island


def _bg():
    """Returns a MagicMock that records background_tasks.add_task calls."""
    return MagicMock()


def _ws_patch():
    """Context manager that mocks websocket_manager inside island_service."""
    return patch(
        "app.services.island_service.websocket_manager",
        send_message_to_clients=AsyncMock(),
        send_personal_message=AsyncMock(),
    )


# ── get_island_by_player_uuid ─────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_get_island_by_player_uuid_returns_none_when_no_island(db_session):
    player_uuid = str(uuid.uuid4())
    result = await island_service.get_island_by_player_uuid(
        db_session=db_session, player_uuid=player_uuid
    )
    assert result is None


@pytest.mark.asyncio
async def test_get_island_by_player_uuid_via_team(db_session):
    player_uuid = str(uuid.uuid4())
    await _make_team_and_island(db_session, player_uuid, IslandStatusEnum.RUNNING)

    result = await island_service.get_island_by_player_uuid(
        db_session=db_session, player_uuid=player_uuid
    )
    assert result is not None
    assert result.status == IslandStatusEnum.RUNNING


@pytest.mark.asyncio
async def test_get_island_by_player_uuid_solo_island(db_session):
    """Legacy solo island (no team) is found via player_uuid."""
    player_uuid = str(uuid.uuid4())
    # Create island directly without a team
    island = await crud_island.create(
        db_session=db_session,
        team_id=None,
        container_name=f"solo-{player_uuid[:8]}",
        player_uuid=player_uuid,
        initial_status=IslandStatusEnum.STOPPED,
    )
    await db_session.commit()

    result = await island_service.get_island_by_player_uuid(
        db_session=db_session, player_uuid=player_uuid
    )
    assert result is not None
    assert result.status == IslandStatusEnum.STOPPED


# ── start_island_instance ─────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_start_stopped_island_transitions_to_pending_start(db_session):
    player_uuid = str(uuid.uuid4())
    await _make_team_and_island(db_session, player_uuid, IslandStatusEnum.STOPPED)
    bg = _bg()

    with _ws_patch():
        result = await island_service.start_island_instance(
            db_session=db_session,
            player_uuid=player_uuid,
            player_name="TestPlayer",
            background_tasks=bg,
        )

    assert result.status == IslandStatusEnum.PENDING_START
    bg.add_task.assert_called_once()


@pytest.mark.asyncio
async def test_start_frozen_island_transitions_to_pending_start(db_session):
    player_uuid = str(uuid.uuid4())
    await _make_team_and_island(db_session, player_uuid, IslandStatusEnum.FROZEN)
    bg = _bg()

    with _ws_patch():
        result = await island_service.start_island_instance(
            db_session=db_session,
            player_uuid=player_uuid,
            player_name="TestPlayer",
            background_tasks=bg,
        )

    assert result.status == IslandStatusEnum.PENDING_START
    bg.add_task.assert_called_once()


@pytest.mark.asyncio
async def test_start_error_start_island_transitions_to_pending_start(db_session):
    player_uuid = str(uuid.uuid4())
    await _make_team_and_island(db_session, player_uuid, IslandStatusEnum.ERROR_START)
    bg = _bg()

    with _ws_patch():
        result = await island_service.start_island_instance(
            db_session=db_session,
            player_uuid=player_uuid,
            player_name="TestPlayer",
            background_tasks=bg,
        )

    assert result.status == IslandStatusEnum.PENDING_START


@pytest.mark.asyncio
async def test_start_running_island_returns_running_no_bg_task(db_session):
    player_uuid = str(uuid.uuid4())
    await _make_team_and_island(db_session, player_uuid, IslandStatusEnum.RUNNING)
    bg = _bg()

    with _ws_patch():
        result = await island_service.start_island_instance(
            db_session=db_session,
            player_uuid=player_uuid,
            player_name="TestPlayer",
            background_tasks=bg,
        )

    assert result.status == IslandStatusEnum.RUNNING
    bg.add_task.assert_not_called()


@pytest.mark.asyncio
async def test_start_island_invalid_state_raises_value_error(db_session):
    player_uuid = str(uuid.uuid4())
    await _make_team_and_island(db_session, player_uuid, IslandStatusEnum.DELETING)
    bg = _bg()

    with _ws_patch(), pytest.raises(ValueError, match="cannot be started"):
        await island_service.start_island_instance(
            db_session=db_session,
            player_uuid=player_uuid,
            player_name="TestPlayer",
            background_tasks=bg,
        )


@pytest.mark.asyncio
async def test_start_island_no_island_creates_new_pending_creation(db_session):
    """Player with no team and no island gets a new island in PENDING_CREATION."""
    player_uuid = str(uuid.uuid4())
    bg = _bg()

    with _ws_patch():
        result = await island_service.start_island_instance(
            db_session=db_session,
            player_uuid=player_uuid,
            player_name="NewPlayer",
            background_tasks=bg,
        )

    assert result.status == IslandStatusEnum.PENDING_CREATION
    bg.add_task.assert_called_once()


# ── stop_island_instance ──────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_stop_running_island_transitions_to_pending_stop(db_session):
    player_uuid = str(uuid.uuid4())
    await _make_team_and_island(db_session, player_uuid, IslandStatusEnum.RUNNING)
    bg = _bg()

    with _ws_patch():
        result = await island_service.stop_island_instance(
            db_session=db_session,
            player_uuid=player_uuid,
            background_tasks=bg,
        )

    assert result.status == IslandStatusEnum.PENDING_STOP
    bg.add_task.assert_called_once()


@pytest.mark.asyncio
async def test_stop_already_stopped_island_returns_stopped(db_session):
    player_uuid = str(uuid.uuid4())
    await _make_team_and_island(db_session, player_uuid, IslandStatusEnum.STOPPED)
    bg = _bg()

    with _ws_patch():
        result = await island_service.stop_island_instance(
            db_session=db_session,
            player_uuid=player_uuid,
            background_tasks=bg,
        )

    assert result.status == IslandStatusEnum.STOPPED
    bg.add_task.assert_not_called()


@pytest.mark.asyncio
async def test_stop_island_not_found_raises_value_error(db_session):
    player_uuid = str(uuid.uuid4())  # no island in DB
    bg = _bg()

    with _ws_patch(), pytest.raises(ValueError, match="No island found"):
        await island_service.stop_island_instance(
            db_session=db_session,
            player_uuid=player_uuid,
            background_tasks=bg,
        )


@pytest.mark.asyncio
async def test_stop_frozen_island_transitions_to_pending_stop(db_session):
    player_uuid = str(uuid.uuid4())
    await _make_team_and_island(db_session, player_uuid, IslandStatusEnum.FROZEN)
    bg = _bg()

    with _ws_patch():
        result = await island_service.stop_island_instance(
            db_session=db_session,
            player_uuid=player_uuid,
            background_tasks=bg,
        )

    assert result.status == IslandStatusEnum.PENDING_STOP


@pytest.mark.asyncio
async def test_stop_pending_creation_island_raises(db_session):
    player_uuid = str(uuid.uuid4())
    await _make_team_and_island(db_session, player_uuid, IslandStatusEnum.PENDING_CREATION)
    bg = _bg()

    with _ws_patch(), pytest.raises(ValueError, match="cannot be stopped"):
        await island_service.stop_island_instance(
            db_session=db_session,
            player_uuid=player_uuid,
            background_tasks=bg,
        )


# ── freeze_island_instance ────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_freeze_running_island_transitions_to_pending_freeze(db_session):
    player_uuid = str(uuid.uuid4())
    await _make_team_and_island(db_session, player_uuid, IslandStatusEnum.RUNNING)
    bg = _bg()

    with _ws_patch():
        result = await island_service.freeze_island_instance(
            db_session=db_session,
            player_uuid=player_uuid,
            background_tasks=bg,
        )

    assert result.status == IslandStatusEnum.PENDING_FREEZE
    bg.add_task.assert_called_once()


@pytest.mark.asyncio
async def test_freeze_already_frozen_island_returns_frozen(db_session):
    player_uuid = str(uuid.uuid4())
    await _make_team_and_island(db_session, player_uuid, IslandStatusEnum.FROZEN)
    bg = _bg()

    with _ws_patch():
        result = await island_service.freeze_island_instance(
            db_session=db_session,
            player_uuid=player_uuid,
            background_tasks=bg,
        )

    assert result.status == IslandStatusEnum.FROZEN
    bg.add_task.assert_not_called()


@pytest.mark.asyncio
async def test_freeze_stopped_island_raises_value_error(db_session):
    player_uuid = str(uuid.uuid4())
    await _make_team_and_island(db_session, player_uuid, IslandStatusEnum.STOPPED)
    bg = _bg()

    with _ws_patch(), pytest.raises(ValueError, match="cannot be frozen"):
        await island_service.freeze_island_instance(
            db_session=db_session,
            player_uuid=player_uuid,
            background_tasks=bg,
        )


@pytest.mark.asyncio
async def test_freeze_island_not_found_raises_value_error(db_session):
    player_uuid = str(uuid.uuid4())
    bg = _bg()

    with _ws_patch(), pytest.raises(ValueError, match="No island found"):
        await island_service.freeze_island_instance(
            db_session=db_session,
            player_uuid=player_uuid,
            background_tasks=bg,
        )


# ── mark_island_as_ready_for_players ─────────────────────────────────────────

@pytest.mark.asyncio
async def test_mark_ready_sets_minecraft_ready_flag(db_session):
    player_uuid = str(uuid.uuid4())
    team, island = await _make_team_and_island(
        db_session, player_uuid, IslandStatusEnum.RUNNING
    )

    with _ws_patch():
        await island_service.mark_island_as_ready_for_players(
            db_session=db_session,
            owner_uuid=player_uuid,
        )

    from app.crud.crud_island import crud_island as ci
    refreshed = await ci.get(db_session, island_id=island.id)
    assert refreshed.minecraft_ready is True


@pytest.mark.asyncio
async def test_mark_ready_not_running_raises(db_session):
    player_uuid = str(uuid.uuid4())
    await _make_team_and_island(db_session, player_uuid, IslandStatusEnum.STOPPED)

    with _ws_patch(), pytest.raises(ValueError, match="not in RUNNING"):
        await island_service.mark_island_as_ready_for_players(
            db_session=db_session,
            owner_uuid=player_uuid,
        )


@pytest.mark.asyncio
async def test_mark_ready_team_not_found_raises(db_session):
    player_uuid = str(uuid.uuid4())  # no team or island

    with _ws_patch(), pytest.raises(ValueError, match="not found"):
        await island_service.mark_island_as_ready_for_players(
            db_session=db_session,
            owner_uuid=player_uuid,
        )


@pytest.mark.asyncio
async def test_mark_ready_idempotent_when_already_ready(db_session):
    """Duplicate ready signal is silently ignored."""
    player_uuid = str(uuid.uuid4())
    team, island = await _make_team_and_island(
        db_session, player_uuid, IslandStatusEnum.RUNNING
    )
    # Set already ready
    await crud_island.update(db_session, db_obj=island, obj_in={"minecraft_ready": True})

    with _ws_patch():
        # Should not raise
        await island_service.mark_island_as_ready_for_players(
            db_session=db_session,
            owner_uuid=player_uuid,
        )
