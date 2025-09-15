import pytest
from sqlalchemy.ext.asyncio import AsyncSession
import uuid

from app.crud.crud_island import crud_island
from app.crud import crud_team
from app.schemas.island import IslandStatusEnum
from app.schemas.team import TeamCreate
from app.models.island import Island as IslandModel

@pytest.mark.asyncio
async def test_create_and_update_solo_island(db_session: AsyncSession):
    """
    Tests that a solo island can be created with a player_uuid,
    and then its status can be updated using the same string UUID.
    This verifies the fix for the inconsistent UUID handling and the
    new island creation logic.
    """
    # 1. Create a team, which is a prerequisite for creating an island.
    owner_uuid = str(uuid.uuid4())
    team_name = "test-team-for-solo-island"
    team_create_data = TeamCreate(name=team_name, owner_uuid=owner_uuid)
    team_db = await crud_team.create_team(db=db_session, team_in=team_create_data)
    await db_session.commit()
    await db_session.refresh(team_db)

    # 2. Create a solo island, passing the player_uuid during creation.
    solo_player_uuid = str(uuid.uuid4())
    container_name = "test-solo-container"
    island_db = await crud_island.create(
        db_session=db_session,
        team_id=team_db.id,
        container_name=container_name,
        player_uuid=solo_player_uuid,
        initial_status=IslandStatusEnum.PENDING_CREATION
    )
    await db_session.commit()
    await db_session.refresh(island_db)

    assert island_db.player_uuid == solo_player_uuid
    assert island_db.status == IslandStatusEnum.PENDING_CREATION

    # 3. Update the island's status using the string UUID.
    new_status = IslandStatusEnum.RUNNING
    updated_island = await crud_island.update_status(
        db_session=db_session, player_uuid=solo_player_uuid, status=new_status
    )

    # 4. Verify the update.
    assert updated_island is not None
    assert updated_island.status == new_status
    assert updated_island.player_uuid == solo_player_uuid

    # 5. Fetch the island again to be absolutely sure the change persisted.
    refetched_island = await crud_island.get_by_player_uuid(db_session, player_uuid=solo_player_uuid)
    assert refetched_island is not None
    assert refetched_island.status == new_status
