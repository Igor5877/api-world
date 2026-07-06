"""Tests for the health watchdog and update backup retention."""
import uuid
from datetime import datetime, timedelta
from unittest.mock import AsyncMock, patch

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.crud.crud_island import crud_island
from app.crud.crud_island_event import crud_island_event
from app.crud import crud_team
from app.schemas.island import IslandStatusEnum
from app.schemas.team import TeamCreate
from app.services.health_worker import check_running_islands


async def _make_island(db_session: AsyncSession, *, status=IslandStatusEnum.RUNNING,
                       minecraft_ready=True, heartbeat_age_seconds=None):
    """Creates a team + island in the given health state."""
    owner_uuid = str(uuid.uuid4())
    team = await crud_team.create_team(
        db=db_session, team_in=TeamCreate(name=f"team-{owner_uuid[:8]}", owner_uuid=owner_uuid))
    await db_session.commit()
    island = await crud_island.create(
        db_session=db_session, team_id=team.id,
        container_name=f"c-{owner_uuid[:8]}", player_uuid=owner_uuid,
        initial_status=status,
    )
    await db_session.commit()

    fields = {"minecraft_ready": minecraft_ready}
    if heartbeat_age_seconds is not None:
        fields["last_heartbeat_at"] = datetime.utcnow() - timedelta(seconds=heartbeat_age_seconds)
    island = await crud_island.update_by_id(db_session, island_id=island.id, obj_in=fields)
    return island


def _lxd_mock(container_status):
    """Mocked lxd_service: get_container_state returns the given status."""
    lxd = AsyncMock()
    if container_status is None:
        lxd.get_container_state.return_value = None
    else:
        lxd.get_container_state.return_value = {"status": container_status}
    return lxd


@pytest.mark.asyncio
async def test_watchdog_ignores_fresh_heartbeat(db_session: AsyncSession):
    island = await _make_island(db_session, heartbeat_age_seconds=10)
    lxd = _lxd_mock("Running")
    with patch("app.services.health_worker.lxd_service", lxd):
        await check_running_islands(db_session)
    lxd.get_container_state.assert_not_awaited()
    refetched = await crud_island.get(db_session, island_id=island.id)
    assert refetched.status == IslandStatusEnum.RUNNING


@pytest.mark.asyncio
async def test_watchdog_skips_islands_without_heartbeat(db_session: AsyncSession):
    """Old mod inside the container: no heartbeat ever — watchdog must not judge."""
    island = await _make_island(db_session, heartbeat_age_seconds=None)
    lxd = _lxd_mock("Running")
    with patch("app.services.health_worker.lxd_service", lxd):
        await check_running_islands(db_session)
    lxd.get_container_state.assert_not_awaited()
    refetched = await crud_island.get(db_session, island_id=island.id)
    assert refetched.status == IslandStatusEnum.RUNNING


@pytest.mark.asyncio
async def test_watchdog_marks_crashed_when_container_down(db_session: AsyncSession):
    island = await _make_island(db_session, heartbeat_age_seconds=600)
    lxd = _lxd_mock("Stopped")
    with patch("app.services.health_worker.lxd_service", lxd):
        await check_running_islands(db_session)

    refetched = await crud_island.get(db_session, island_id=island.id)
    assert refetched.status == IslandStatusEnum.STOPPED
    assert refetched.minecraft_ready is False
    events = await crud_island_event.list_for_island(db_session, island_id=island.id)
    assert [e.event_type for e in events] == ["crashed"]


@pytest.mark.asyncio
async def test_watchdog_stops_hung_server(db_session: AsyncSession):
    """Container up, Minecraft silent, no clean-stop signal → hung."""
    island = await _make_island(db_session, heartbeat_age_seconds=600)
    lxd = _lxd_mock("Running")
    with patch("app.services.health_worker.lxd_service", lxd):
        await check_running_islands(db_session)

    lxd.stop_container.assert_awaited()
    refetched = await crud_island.get(db_session, island_id=island.id)
    assert refetched.status == IslandStatusEnum.STOPPED
    events = await crud_island_event.list_for_island(db_session, island_id=island.id)
    assert [e.event_type for e in events] == ["hung"]


@pytest.mark.asyncio
async def test_watchdog_respects_clean_shutdown_signal(db_session: AsyncSession):
    """Container up, Minecraft silent, but "stopping" was signalled → not a hang."""
    island = await _make_island(db_session, heartbeat_age_seconds=120)
    await crud_island_event.add(db_session, island_id=island.id, event_type="stopping",
                                details="Minecraft signalled a clean shutdown.")
    lxd = _lxd_mock("Running")
    with patch("app.services.health_worker.lxd_service", lxd):
        await check_running_islands(db_session)

    refetched = await crud_island.get(db_session, island_id=island.id)
    assert refetched.status == IslandStatusEnum.STOPPED
    events = await crud_island_event.list_for_island(db_session, island_id=island.id)
    assert "stopped_externally" in [e.event_type for e in events]
    assert "hung" not in [e.event_type for e in events]


@pytest.mark.asyncio
async def test_watchdog_fixes_frozen_mismatch(db_session: AsyncSession):
    island = await _make_island(db_session, heartbeat_age_seconds=600)
    lxd = _lxd_mock("Frozen")
    with patch("app.services.health_worker.lxd_service", lxd):
        await check_running_islands(db_session)

    refetched = await crud_island.get(db_session, island_id=island.id)
    assert refetched.status == IslandStatusEnum.FROZEN
    events = await crud_island_event.list_for_island(db_session, island_id=island.id)
    assert [e.event_type for e in events] == ["state_mismatch"]


@pytest.mark.asyncio
async def test_prune_file_backups_keeps_newest_versions(db_session: AsyncSession, tmp_path):
    """Only the newest N version dirs per island survive pruning."""
    import os
    from app.services.update_service import update_service
    from app.crud.crud_update import crud_island_backup_ops

    island = await _make_island(db_session)
    island_dir = tmp_path / island.container_name
    for i, version in enumerate(["v1.0.0", "v1.1.0", "v1.2.0"]):
        version_dir = island_dir / version
        version_dir.mkdir(parents=True)
        (version_dir / "dummy.jar").write_text("x")
        os.utime(version_dir, (1000 + i, 1000 + i))  # v1.0.0 = найстаріший
        await crud_island_backup_ops.create(
            db_session, island_id=island.id, snapshot_name=f"files-{version}",
            backup_type="files", version=version, backup_path=str(version_dir),
            description="test backup",
        )

    with patch("app.services.update_service.settings.UPDATES_BACKUP_DIR", str(tmp_path)), \
         patch("app.services.update_service.settings.UPDATE_BACKUP_KEEP_VERSIONS", 2):
        removed = await update_service.prune_file_backups(db_session)

    assert removed == 1
    assert not (island_dir / "v1.0.0").exists()
    assert (island_dir / "v1.1.0").exists()
    assert (island_dir / "v1.2.0").exists()
    # Рядок у БД для видаленого бекапу теж прибраний
    stale = await crud_island_backup_ops.get_latest_files_backup(db_session, island_id=island.id)
    assert stale is not None and stale.backup_path != str(island_dir / "v1.0.0")
