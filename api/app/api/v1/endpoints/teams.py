from fastapi import APIRouter, Depends, HTTPException, BackgroundTasks
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.future import select
from sqlalchemy.orm import selectinload
import uuid
from uuid import UUID
import logging

from app.db.session import get_db_session
from app.schemas.team import TeamCreate, Team as TeamSchema, TeamMember, TeamCreateResponse, TeamInviteCreate, TeamInviteInfo
from app.crud import crud_team
from app.models.team import Team
from app.services.island_service import island_service
from app.services.websocket_manager import manager as websocket_manager

logger = logging.getLogger(__name__)
router = APIRouter()

async def broadcast_team_update(db: AsyncSession, team_id: int):
    """Fetches the latest team data and broadcasts it via WebSocket."""
    result = await db.execute(select(Team).where(Team.id == team_id).options(selectinload(Team.members)))
    team_with_members = result.scalars().first()
    if team_with_members:
        schema = TeamSchema.model_validate(team_with_members)
        payload = {
            "event": "TEAM_UPDATED",
            "payload": schema.model_dump(mode='json')
        }
        await websocket_manager.send_personal_message(payload, f"island_{team_with_members.owner_uuid}")

@router.post("/create_solo", response_model=TeamSchema, status_code=201)
async def create_solo_island_and_team(
    *,
    db: AsyncSession = Depends(get_db_session),
    player_info: dict, # Expects {"player_uuid": "...", "player_name": "..."}
    background_tasks: BackgroundTasks
):
    """Creates a new solo island for a player.

    This implicitly creates a team of one for the player.

    Args:
        db: The database session.
        player_info: A dictionary containing the player's UUID and name.
        background_tasks: The background tasks to run.

    Returns:
        The created team.

    Raises:
        HTTPException: If the player's UUID or name is not provided, or if an
            error occurs during island creation.
    """
    player_uuid = player_info.get("player_uuid")
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
    player_uuid: str,
    db: AsyncSession = Depends(get_db_session)
):
    """Gets the details of the team a player belongs to.

    Args:
        player_uuid: The UUID of the player.
        db: The database session.

    Returns:
        The team details.

    Raises:
        HTTPException: If the player is not in a team.
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
    player_uuid: str, # This should come from an auth token
    db: AsyncSession = Depends(get_db_session)
):
    """Renames a team.

    Args:
        team_id: The ID of the team to rename.
        team_in: The updated team data.
        player_uuid: The UUID of the player performing the action.
        db: The database session.

    Returns:
        The updated team data.

    Raises:
        HTTPException: If the team is not found, the player is not the owner,
            or the new team name is already taken.
    """
    result = await db.execute(
        select(Team)
        .where(Team.id == team_id)
        .options(selectinload(Team.members))
    )
    team = result.scalars().first()
    if not team:
        raise HTTPException(status_code=404, detail="Team not found.")
    
    if player_uuid != team.owner_uuid:
        raise HTTPException(status_code=403, detail="Only the team owner can rename the team.")

    # Check if new name is already taken
    if team_in.name:
        existing_name = await crud_team.get_team_by_name(db, name=team_in.name)
        if existing_name and existing_name.id != team_id:
            raise HTTPException(status_code=409, detail="A team with this name already exists.")
        
        updated_team = await crud_team.rename_team(db, team=team, new_name=team_in.name)
        await broadcast_team_update(db, updated_team.id)
        return updated_team
    
    return team # Return original if no name was provided in payload


@router.post("/accept_invite", response_model=TeamSchema)
async def accept_invite(
    *,
    db: AsyncSession = Depends(get_db_session),
    invite_data: TeamInviteAccept,
    player_uuid: str, # In a real app, this would come from an auth token
    background_tasks: BackgroundTasks
):
    """Allows a player to accept an invitation to a team.

    This triggers the deletion of their old island.

    Args:
        db: The database session.
        invite_data: The invitation data.
        player_uuid: The UUID of the player accepting the invite.
        background_tasks: The background tasks to run.

    Returns:
        The updated team data.

    Raises:
        HTTPException: If the team is not found or an error occurs during the
            join process.
    """
    team = await crud_team.get_team_by_name(db, name=invite_data.team_name)
    if not team:
        raise HTTPException(status_code=404, detail="Team not found.")

    # Transitional guard (API_TEAMS_TODO.md §1): joining by name is only
    # allowed when the owner actually invited this player — otherwise anyone
    # who knew the team name could join without consent.
    invite = await crud_team.get_invite_for_team_player(db, team_id=team.id, invited_uuid=player_uuid)
    if not invite:
        raise HTTPException(status_code=403,
                            detail="No pending invite to this team. Ask the owner to invite you first.")

    try:
        updated_team = await island_service.handle_join_team(
            db_session=db,
            player_to_join_uuid=player_uuid,
            team_to_join=team,
            background_tasks=background_tasks
        )
        await crud_team.delete_invite(db, invite=invite)
        await broadcast_team_update(db, updated_team.id)
        return updated_team
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/{team_id}/leave", status_code=204)
async def leave_team(
    *,
    team_id: int,
    player_uuid: str, # In a real app, this would come from an auth token
    db: AsyncSession = Depends(get_db_session)
):
    """Allows a player to leave a team.

    If the owner leaves, the team is disbanded.

    Args:
        team_id: The ID of the team to leave.
        player_uuid: The UUID of the player leaving the team.
        db: The database session.

    Raises:
        HTTPException: If the team is not found, the player is not a member of
            the team, or the owner tries to leave.
    """
    team = await db.get(Team, team_id)
    if not team:
        raise HTTPException(status_code=404, detail="Team not found.")

    member = await crud_team.get_member(db, team=team, player_uuid=player_uuid)
    if not member:
        raise HTTPException(status_code=403, detail="Player is not a member of this team.")

    if player_uuid == team.owner_uuid:
        # Owner cannot leave the team. They must delete it or transfer ownership.
        raise HTTPException(
            status_code=400,
            detail="Team owner cannot leave the team. Please transfer ownership or delete the team."
        )
    else:
        # Just remove the member
        await crud_team.remove_member(db, team=team, player_uuid=player_uuid)
        await broadcast_team_update(db, team.id)
    
    return


# ── invite system (API_TEAMS_TODO.md §1) ──────────────────────────────

def _is_owner_or_moderator(team: Team, member, requester_uuid: str) -> bool:
    """True if the requester may manage the team (owner or moderator)."""
    if str(requester_uuid) == team.owner_uuid:
        return True
    from app.models.team import RoleEnum
    return member is not None and member.role in (RoleEnum.owner, RoleEnum.moderator)


@router.post("/{team_id}/invite", response_model=TeamInviteInfo, status_code=201)
async def invite_player(
    *,
    team_id: int,
    invite_in: TeamInviteCreate,
    db: AsyncSession = Depends(get_db_session)
):
    """Invites a player to a team (owner/moderator only).

    Creates a pending invite and pushes a TEAM_INVITE WebSocket event to the
    invited player if they are online.
    """
    team = await db.get(Team, team_id)
    if not team:
        raise HTTPException(status_code=404, detail="Team not found.")

    inviter_member = await crud_team.get_member(db, team=team, player_uuid=invite_in.inviter_uuid)
    if not _is_owner_or_moderator(team, inviter_member, invite_in.inviter_uuid):
        raise HTTPException(status_code=403, detail="Only the team owner or a moderator can invite players.")

    if await crud_team.get_member(db, team=team, player_uuid=invite_in.invited_uuid):
        raise HTTPException(status_code=409, detail="Player is already a member of this team.")
    existing_team = await crud_team.get_team_by_player(db, player_uuid=invite_in.invited_uuid)
    # Players in their own solo team can still be invited (their solo team is
    # disbanded on accept); membership in a real multi-player team blocks it.
    if existing_team and existing_team.id == team.id:
        raise HTTPException(status_code=409, detail="Player is already a member of this team.")

    invite = await crud_team.create_invite(
        db, team=team, invited_uuid=invite_in.invited_uuid,
        invited_name=invite_in.invited_name, inviter_uuid=invite_in.inviter_uuid,
    )

    inviter_name = inviter_member.player_name if inviter_member else None
    payload = {
        "event": "TEAM_INVITE",
        "payload": {
            "invite_id": invite.id,
            "team_id": team.id,
            "team_name": team.name,
            "inviter_name": inviter_name,
        },
    }
    await websocket_manager.send_personal_message(payload, str(invite_in.invited_uuid))

    logger.info(f"Teams: Player {invite_in.invited_uuid} invited to team {team.id} by {invite_in.inviter_uuid}.")
    return TeamInviteInfo(invite_id=invite.id, team_id=team.id, team_name=team.name,
                          inviter_name=inviter_name, created_at=invite.created_at)


@router.get("/invites/{player_uuid}", response_model=list[TeamInviteInfo])
async def list_invites(
    *,
    player_uuid: str,
    db: AsyncSession = Depends(get_db_session)
):
    """Lists the player's active (non-expired) team invitations."""
    invites = await crud_team.get_invites_for_player(db, player_uuid=player_uuid)
    infos = []
    for invite in invites:
        inviter_member = await crud_team.get_member(db, team=invite.team, player_uuid=invite.inviter_uuid)
        infos.append(TeamInviteInfo(
            invite_id=invite.id, team_id=invite.team_id, team_name=invite.team.name,
            inviter_name=inviter_member.player_name if inviter_member else None,
            created_at=invite.created_at,
        ))
    return infos


@router.post("/invites/{invite_id}/accept", response_model=TeamSchema)
async def accept_invite_by_id(
    *,
    invite_id: int,
    player_uuid: str,
    background_tasks: BackgroundTasks,
    db: AsyncSession = Depends(get_db_session)
):
    """Accepts a team invitation by id.

    Joins the player to the team (their old island is deleted, same flow as
    the legacy accept_invite) and removes the invite.
    """
    from datetime import datetime

    invite = await crud_team.get_invite(db, invite_id=invite_id)
    if not invite or invite.invited_uuid != str(player_uuid):
        raise HTTPException(status_code=404, detail="Invite not found.")
    if invite.expires_at is not None and invite.expires_at <= datetime.utcnow():
        await crud_team.delete_invite(db, invite=invite)
        raise HTTPException(status_code=410, detail="Invite has expired.")

    team = invite.team
    try:
        updated_team = await island_service.handle_join_team(
            db_session=db,
            player_to_join_uuid=player_uuid,
            team_to_join=team,
            background_tasks=background_tasks,
        )
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    await crud_team.delete_invite(db, invite=invite)
    await broadcast_team_update(db, updated_team.id)
    return updated_team


@router.delete("/invites/{invite_id}", status_code=204)
async def decline_invite(
    *,
    invite_id: int,
    player_uuid: str,
    db: AsyncSession = Depends(get_db_session)
):
    """Declines (deletes) a team invitation."""
    invite = await crud_team.get_invite(db, invite_id=invite_id)
    if not invite or invite.invited_uuid != str(player_uuid):
        raise HTTPException(status_code=404, detail="Invite not found.")
    await crud_team.delete_invite(db, invite=invite)
    return


@router.delete("/{team_id}/members/{player_uuid}", status_code=204)
async def kick_member(
    *,
    team_id: int,
    player_uuid: str,
    requester_uuid: str,
    db: AsyncSession = Depends(get_db_session)
):
    """Kicks a member from the team (owner/moderator only).

    The owner cannot be kicked; kicking yourself is rejected (use leave).
    """
    team = await db.get(Team, team_id)
    if not team:
        raise HTTPException(status_code=404, detail="Team not found.")

    requester_member = await crud_team.get_member(db, team=team, player_uuid=requester_uuid)
    if not _is_owner_or_moderator(team, requester_member, requester_uuid):
        raise HTTPException(status_code=403, detail="Only the team owner or a moderator can kick players.")
    if str(player_uuid) == team.owner_uuid:
        raise HTTPException(status_code=400, detail="The team owner cannot be kicked.")
    if str(player_uuid) == str(requester_uuid):
        raise HTTPException(status_code=400, detail="Use leave instead of kicking yourself.")

    member = await crud_team.get_member(db, team=team, player_uuid=player_uuid)
    if not member:
        raise HTTPException(status_code=404, detail="Player is not a member of this team.")

    await crud_team.remove_member(db, team=team, player_uuid=player_uuid)
    await broadcast_team_update(db, team.id)
    logger.info(f"Teams: Player {player_uuid} kicked from team {team.id} by {requester_uuid}.")
    return
