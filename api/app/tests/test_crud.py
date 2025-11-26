import pytest
from sqlalchemy.ext.asyncio import AsyncSession
import uuid

from app.crud.crud_island import crud_island
from app.crud import crud_team
from app.schemas.island import IslandStatusEnum
from app.schemas.team import TeamCreate
from app.models.island import Island as IslandModel

@pytest.mark.asyncio
async def test_create_and_update_island_by_team_id(db_session: AsyncSession):
    """
    Tests that an island's status can be updated using its team_id.
    This verifies the fix for the inconsistent island update logic.
    """
    # 1. Create a team, which is a prerequisite for creating an island.
    owner_uuid = str(uuid.uuid4())
    team_name = "test-team-for-island-update"
    team_create_data = TeamCreate(name=team_name, owner_uuid=owner_uuid)
    team_db = await crud_team.create_team(db=db_session, team_in=team_create_data)
    await db_session.commit()
    await db_session.refresh(team_db)

    # 2. Create an island associated with the team.
    container_name = "test-container-for-team-update"
    island_db = await crud_island.create(
        db_session=db_session,
        team_id=team_db.id,
        container_name=container_name,
        initial_status=IslandStatusEnum.PENDING_CREATION
    )
    await db_session.commit()
    await db_session.refresh(island_db)

    assert island_db.team_id == team_db.id
    assert island_db.status == IslandStatusEnum.PENDING_CREATION

    # 3. Update the island's status using the team_id.
    new_status = IslandStatusEnum.RUNNING
    updated_island = await crud_island.update_status_by_team_id(
        db_session=db_session, team_id=team_db.id, status=new_status
    )

    # 4. Verify the update.
    assert updated_island is not None
    assert updated_island.status == new_status
    assert updated_island.team_id == team_db.id

    # 5. Fetch the island again to be absolutely sure the change persisted.
    refetched_island = await crud_island.get_by_team_id(db_session, team_id=team_db.id)
    assert refetched_island is not None
    assert refetched_island.status == new_status
