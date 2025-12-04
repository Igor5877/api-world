from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.future import select
from sqlalchemy.orm import selectinload

from app.models.team import Team, TeamMember, RoleEnum
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
    # This function is functionally identical to get_team_by_owner but named for clarity
    # on its behavior of loading relationships, as used in island_service.
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

async def get_team_by_owner(db: AsyncSession, *, owner_uuid: str) -> Team | None:
    """Fetches a team by its owner's UUID.

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
        role=RoleEnum.owner,
        team=new_team
    )
    
    db.add(new_team)
    db.add(owner_member)
    
    # The session will be flushed by the service layer's transaction manager
    # to get the new_team.id for island creation.
    return new_team

async def add_member(db: AsyncSession, *, team: Team, player_uuid: str, role: RoleEnum = RoleEnum.member) -> TeamMember:
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
