from fastapi import APIRouter, Depends, HTTPException, BackgroundTasks
from sqlalchemy.ext.asyncio import AsyncSession
import uuid
from uuid import UUID
import logging

from app.db.session import get_db_session
from app.schemas.team import TeamCreate, Team as TeamSchema, TeamMember, TeamCreateResponse
from app.crud import crud_team
from app.models.team import Team
from app.services.island_service import island_service

logger = logging.getLogger(__name__)
router = APIRouter()

@router.post("/create_solo", response_model=TeamSchema, status_code=201)
async def create_solo_island_and_team(
    *,
    db: AsyncSession = Depends(get_db_session),
    player_info: dict, # Expects {"player_uuid": "...", "player_name": "..."}
    background_tasks: BackgroundTasks
):
    """
    Creates a new solo island for a player.
    This implicitly creates a team of one for the player.
    """
    player_uuid = uuid.UUID(player_info.get("player_uuid"))
    player_name = player_info.get("player_name")

    if not player_uuid or not player_name:
        raise HTTPException(status_code=400, detail="player_uuid and player_name are required.")

    try:
        team = await island_service.create_new_solo_island(
            db_session=db,
            player_uuid=player_uuid,
            player_name=player_name,
            background_tasks=background_tasks
        )
        return team
    except ValueError as e:
        raise HTTPException(status_code=409, detail=str(e))
    except Exception as e:
        logger.error(f"Error creating solo island for player {player_uuid}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail="An internal server error occurred.")

@router.get("/my_team/{player_uuid}", response_model=TeamSchema)
async def get_my_team(
    player_uuid: UUID,
    db: AsyncSession = Depends(get_db_session)
):
    """
    Get the details of the team a player belongs to.
    """
    team = await crud_team.get_team_by_player(db, player_uuid=player_uuid)
    if not team:
        raise HTTPException(status_code=404, detail="Player is not in a team.")
    return team

from app.schemas.team import TeamInviteAccept, TeamUpdate

# Placeholder for a real invite system. In a real app, you'd store invites in the DB.
# For now, we'll just simulate the acceptance process.
@router.patch("/{team_id}/rename", response_model=TeamSchema)
async def rename_team_endpoint(
    team_id: int,
    team_in: TeamUpdate, # Using a generic update schema
    player_uuid: UUID, # This should come from an auth token
    db: AsyncSession = Depends(get_db_session)
):
    team = await db.get(Team, team_id)
    if not team:
        raise HTTPException(status_code=404, detail="Team not found.")
    
    if str(player_uuid) != team.owner_uuid:
        raise HTTPException(status_code=403, detail="Only the team owner can rename the team.")

    # Check if new name is already taken
    if team_in.name:
        existing_name = await crud_team.get_team_by_name(db, name=team_in.name)
        if existing_name and existing_name.id != team_id:
            raise HTTPException(status_code=409, detail="A team with this name already exists.")
        
        updated_team = await crud_team.rename_team(db, team=team, new_name=team_in.name)
        return updated_team
    
    return team # Return original if no name was provided in payload


@router.post("/accept_invite", response_model=TeamSchema)
async def accept_invite(
    *,
    db: AsyncSession = Depends(get_db_session),
    invite_data: TeamInviteAccept,
    player_uuid: UUID, # In a real app, this would come from an auth token
    background_tasks: BackgroundTasks
):
    """
    Allows a player to accept an invitation to a team.
    This triggers the deletion of their old island.
    """
    team = await crud_team.get_team_by_name(db, name=invite_data.team_name)
    if not team:
        raise HTTPException(status_code=404, detail="Team not found.")

    try:
        updated_team = await island_service.handle_join_team(
            db_session=db,
            player_to_join_uuid=player_uuid,
            team_to_join=team,
            background_tasks=background_tasks
        )
        return updated_team
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/{team_id}/leave", status_code=204)
async def leave_team(
    *,
    team_id: int,
    player_uuid: UUID, # In a real app, this would come from an auth token
    db: AsyncSession = Depends(get_db_session)
):
    """
    Allows a player to leave a team.
    If the owner leaves, the team is disbanded.
    """
    team = await db.get(Team, team_id)
    if not team:
        raise HTTPException(status_code=404, detail="Team not found.")

    member = await crud_team.get_member(db, team=team, player_uuid=player_uuid)
    if not member:
        raise HTTPException(status_code=403, detail="Player is not a member of this team.")

    if str(player_uuid) == team.owner_uuid:
        # Disband the team
        # This is a destructive action and needs a proper service function.
        # For now, we'll just delete the team record, and cascade should handle the rest.
        logger.info(f"Owner {player_uuid} is leaving team {team_id}. Disbanding team.")
        # In a real app: await island_service.disband_team(db, team=team)
        await db.delete(team)
        await db.commit()
    else:
        # Just remove the member
        await crud_team.remove_member(db, team=team, player_uuid=player_uuid)
    
    return
