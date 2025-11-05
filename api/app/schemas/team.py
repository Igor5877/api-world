from pydantic import BaseModel, Field
import uuid
from typing import List, Optional
from app.models.team import RoleEnum # Import the RoleEnum from your models

# -- Team Member Schemas --

# Base properties for a team member
class TeamMemberBase(BaseModel):
    """Base schema for a team member.

    Attributes:
        player_uuid: The UUID of the player.
        role: The role of the team member.
    """
    player_uuid: str
    role: RoleEnum

# Properties to receive via API on creation (role is optional, defaults to member)
class TeamMemberCreate(TeamMemberBase):
    """Schema for creating a team member."""
    role: RoleEnum = RoleEnum.member

# Properties to return to client
class TeamMember(TeamMemberBase):
    """Schema for a team member."""
    class Config:
        """Pydantic configuration."""
        orm_mode = True

# -- Team Schemas --

# Base properties for a team
class TeamBase(BaseModel):
    """Base schema for a team.

    Attributes:
        name: The name of the team.
    """
    name: str = Field(..., min_length=3, max_length=50)

# Properties to receive on creation
class TeamCreate(TeamBase):
    """Schema for creating a team.

    Attributes:
        owner_uuid: The UUID of the player who owns the team.
    """
    # The owner_uuid will be extracted from the authenticated user/token in a real app
    # For now, we might pass it in the request body for simplicity.
    owner_uuid: str

class TeamUpdate(BaseModel):
    """Schema for updating a team.

    Attributes:
        name: The name of the team.
    """
    name: Optional[str] = Field(None, min_length=3, max_length=50)

# Properties to return to a client
class Team(TeamBase):
    """Schema for a team.

    Attributes:
        id: The unique identifier for the team.
        owner_uuid: The UUID of the player who owns the team.
        members: The members of the team.
        island_id: The ID of the associated island.
    """
    id: int
    owner_uuid: str
    members: List[TeamMember] = []
    island_id: Optional[int] = None # The ID of the associated island

    class Config:
        """Pydantic configuration."""
        orm_mode = True

class TeamCreateResponse(BaseModel):
    """Schema for a team creation response.

    Attributes:
        team: The created team.
        action: The action taken (e.g., "created", "converted").
    """
    team: Team
    action: str # Will be "created" or "converted"

# -- Invitation Schemas --

class TeamInvite(BaseModel):
    """Schema for a team invitation.

    Attributes:
        player_to_invite_uuid: The UUID of the player to invite.
    """
    player_to_invite_uuid: str

class TeamInviteAccept(BaseModel):
    """Schema for accepting a team invitation.

    Attributes:
        team_name: The name of the team to join.
    """
    team_name: str
