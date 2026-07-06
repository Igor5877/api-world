from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.future import select
from sqlalchemy.orm import selectinload
from uuid import UUID

from app.models.team import Team, TeamMember, TeamInvite, RoleEnum
from app.schemas.team import TeamCreate

async def get_team_by_name(db: AsyncSession, *, name: str) -> Team | None:
    """Fetches a single team by its name.

    This function eagerly loads the team's island and members.

    Args:
        db: The database session.
        name: The name of the team to fetch.

    Returns:
        The team, or None if not found.
    """
    result = await db.execute(
        select(Team)
        .filter(Team.name == name)
        .options(selectinload(Team.island), selectinload(Team.members))
    )
    return result.scalars().first()

async def get_team_by_owner_with_relations(db: AsyncSession, *, owner_uuid: str) -> Team | None:
    """Fetches a team by its owner's UUID.

    This function eagerly loads the team's island and members.

    Args:
        db: The database session.
        owner_uuid: The UUID of the team owner.

    Returns:
        The team, or None if not found.
    """
    result = await db.execute(
        select(Team)
        .where(Team.owner_uuid == owner_uuid)
        .options(selectinload(Team.members), selectinload(Team.island))
    )
    return result.scalars().first()

async def get_team_by_player(db: AsyncSession, *, player_uuid: str) -> Team | None:
    """Fetches the team a player belongs to.

    This function eagerly loads the team's island and members.

    Args:
        db: The database session.
        player_uuid: The UUID of the player.

    Returns:
        The team, or None if the player is not in a team.
    """
    result = await db.execute(
        select(Team)
        .join(TeamMember)
        .filter(TeamMember.player_uuid == player_uuid)
        .options(selectinload(Team.island), selectinload(Team.members)) # Eager load island and members
    )
    return result.scalars().first()

async def create_team(db: AsyncSession, *, team_in: TeamCreate) -> Team:
    """Creates a new team and adds the owner as the first member.

    This function does NOT commit the transaction. The calling service is
    responsible for the transaction.

    Args:
        db: The database session.
        team_in: The team creation data.

    Returns:
        The created team.
    """
    # Create the Team object
    new_team = Team(name=team_in.name, owner_uuid=team_in.owner_uuid)
    
    # Create the owner's TeamMember object
    owner_member = TeamMember(
        player_uuid=team_in.owner_uuid,
        player_name=team_in.owner_name,
        role=RoleEnum.owner,
        team=new_team
    )
    
    db.add(new_team)
    db.add(owner_member)
    
    # The session will be flushed by the service layer's transaction manager
    # to get the new_team.id for island creation.
    return new_team

async def add_member(db: AsyncSession, *, team: Team, player_uuid: str, player_name: str = None, role: RoleEnum = RoleEnum.member) -> TeamMember:
    """Adds a new member to a team.

    Args:
        db: The database session.
        team: The team to add the member to.
        player_uuid: The UUID of the player to add.
        role: The role of the new member.

    Returns:
        The created team member.
    """
    new_member = TeamMember(
        team_id=team.id,
        player_uuid=player_uuid,
        player_name=player_name,
        role=role
    )
    db.add(new_member)
    # The calling function is responsible for the commit
    return new_member

async def remove_member(db: AsyncSession, *, team: Team, player_uuid: str) -> None:
    """Removes a member from a team.

    Args:
        db: The database session.
        team: The team to remove the member from.
        player_uuid: The UUID of the player to remove.
    """
    result = await db.execute(
        select(TeamMember)
        .filter(TeamMember.team_id == team.id, TeamMember.player_uuid == player_uuid)
    )
    member_to_remove = result.scalars().first()

    if member_to_remove:
        await db.delete(member_to_remove)
        await db.commit()
        
async def get_member(db: AsyncSession, *, team: Team, player_uuid: str) -> TeamMember | None:
    """Gets a specific member from a team.

    Args:
        db: The database session.
        team: The team to get the member from.
        player_uuid: The UUID of the player to get.

    Returns:
        The team member, or None if not found.
    """
    result = await db.execute(
        select(TeamMember)
        .filter(TeamMember.team_id == team.id, TeamMember.player_uuid == player_uuid)
    )
    return result.scalars().first()

async def rename_team(db: AsyncSession, *, team: Team, new_name: str) -> Team:
    """Renames an existing team.

    Args:
        db: The database session.
        team: The team to rename.
        new_name: The new name for the team.

    Returns:
        The renamed team.
    """
    team_id = team.id  # Get ID before commit, as team object expires after commit
    team.name = new_name
    db.add(team)
    await db.commit()
    
    # Re-fetch the team with relationships eagerly loaded to avoid serialization errors
    result = await db.execute(
        select(Team)
        .where(Team.id == team_id)
        .options(selectinload(Team.island), selectinload(Team.members))
    )
    return result.scalars().first()


# ── team invites ──────────────────────────────────────────────────────

async def create_invite(db: AsyncSession, *, team: Team, invited_uuid: str,
                        invited_name: str | None, inviter_uuid: str,
                        ttl_days: int = 7) -> TeamInvite:
    """Creates (or refreshes) a pending invite of a player to a team.

    If an invite for this (team, player) pair already exists it is refreshed
    instead of raising on the unique constraint.

    Args:
        db: The database session.
        team: The team the player is invited to.
        invited_uuid: The UUID of the invited player.
        invited_name: The invited player's name (display only).
        inviter_uuid: The owner/moderator sending the invite.
        ttl_days: How many days the invite stays valid.

    Returns:
        The created or refreshed invite.
    """
    from datetime import datetime, timedelta

    existing = await get_invite_for_team_player(db, team_id=team.id, invited_uuid=invited_uuid)
    expires = datetime.utcnow() + timedelta(days=ttl_days)
    if existing:
        existing.inviter_uuid = str(inviter_uuid)
        existing.invited_name = invited_name or existing.invited_name
        existing.expires_at = expires
        db.add(existing)
        await db.commit()
        await db.refresh(existing)
        return existing

    invite = TeamInvite(team_id=team.id, invited_uuid=str(invited_uuid),
                        invited_name=invited_name, inviter_uuid=str(inviter_uuid),
                        expires_at=expires)
    db.add(invite)
    await db.commit()
    await db.refresh(invite)
    return invite


async def get_invite(db: AsyncSession, *, invite_id: int) -> TeamInvite | None:
    """Gets an invite by id (with its team, members and island eagerly loaded).

    handle_join_team iterates team.members/island — without eager loading the
    lazy load explodes with MissingGreenlet in the async session.
    """
    result = await db.execute(
        select(TeamInvite).where(TeamInvite.id == invite_id).options(
            selectinload(TeamInvite.team).selectinload(Team.members),
            selectinload(TeamInvite.team).selectinload(Team.island),
        )
    )
    return result.scalars().first()


async def get_invite_for_team_player(db: AsyncSession, *, team_id: int, invited_uuid: str) -> TeamInvite | None:
    """Gets the pending invite of a player to a specific team, if any."""
    result = await db.execute(
        select(TeamInvite).where(TeamInvite.team_id == team_id,
                                 TeamInvite.invited_uuid == str(invited_uuid))
    )
    return result.scalars().first()


async def get_invites_for_player(db: AsyncSession, *, player_uuid: str) -> list[TeamInvite]:
    """Lists all non-expired invites of a player (teams eagerly loaded)."""
    from datetime import datetime

    result = await db.execute(
        select(TeamInvite)
        .where(TeamInvite.invited_uuid == str(player_uuid))
        .options(selectinload(TeamInvite.team))
    )
    invites = list(result.scalars().all())
    now = datetime.utcnow()
    return [i for i in invites if i.expires_at is None or i.expires_at > now]


async def delete_invite(db: AsyncSession, *, invite: TeamInvite) -> None:
    """Deletes an invite (accepted or declined)."""
    await db.delete(invite)
    await db.commit()
