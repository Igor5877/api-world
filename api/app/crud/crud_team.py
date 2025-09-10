from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.future import select
from sqlalchemy.orm import selectinload
from uuid import UUID

from app.models.team import Team, TeamMember, RoleEnum
from app.schemas.team import TeamCreate

async def get_team_by_name(db: AsyncSession, *, name: str) -> Team | None:
    """
    Fetch a single team by its name, eagerly loading its island and members.
    """
    result = await db.execute(
        select(Team)
        .filter(Team.name == name)
        .options(selectinload(Team.island), selectinload(Team.members))
    )
    return result.scalars().first()

async def get_team_by_player(db: AsyncSession, *, player_uuid: UUID) -> Team | None:
    """
    Fetch the team a player belongs to, eagerly loading its island and members.
    """
    result = await db.execute(
        select(Team)
        .join(TeamMember)
        .filter(TeamMember.player_uuid == str(player_uuid))
        .options(selectinload(Team.island), selectinload(Team.members)) # Eager load island and members
    )
    return result.scalars().first()

async def create_team(db: AsyncSession, *, team_in: TeamCreate) -> Team:
    """
    Create a new team and add the owner as the first member.
    This function does NOT commit. The calling service is responsible for the transaction.
    """
    # Create the Team object
    new_team = Team(name=team_in.name, owner_uuid=str(team_in.owner_uuid))
    
    # Create the owner's TeamMember object
    owner_member = TeamMember(
        player_uuid=str(team_in.owner_uuid),
        role=RoleEnum.owner,
        team=new_team
    )
    
    db.add(new_team)
    db.add(owner_member)
    
    # The session will be flushed by the service layer's transaction manager
    # to get the new_team.id for island creation.
    return new_team

async def add_member(db: AsyncSession, *, team: Team, player_uuid: UUID, role: RoleEnum = RoleEnum.member) -> TeamMember:
    """
    Add a new member to a team.
    """
    new_member = TeamMember(
        team_id=team.id,
        player_uuid=str(player_uuid),
        role=role
    )
    db.add(new_member)
    await db.commit()
    await db.refresh(new_member)
    return new_member

async def remove_member(db: AsyncSession, *, team: Team, player_uuid: UUID) -> None:
    """
    Remove a member from a team.
    """
    result = await db.execute(
        select(TeamMember)
        .filter(TeamMember.team_id == team.id, TeamMember.player_uuid == str(player_uuid))
    )
    member_to_remove = result.scalars().first()

    if member_to_remove:
        await db.delete(member_to_remove)
        await db.commit()
        
async def get_member(db: AsyncSession, *, team: Team, player_uuid: UUID) -> TeamMember | None:
    """
    Get a specific member from a team.
    """
    result = await db.execute(
        select(TeamMember)
        .filter(TeamMember.team_id == team.id, TeamMember.player_uuid == str(player_uuid))
    )
    return result.scalars().first()

async def rename_team(db: AsyncSession, *, team: Team, new_name: str) -> Team:
    """
    Renames an existing team.
    """
    team.name = new_name
    db.add(team)
    await db.commit()
    await db.refresh(team)
    return team
