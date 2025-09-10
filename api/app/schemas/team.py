from pydantic import BaseModel, Field
import uuid
from typing import List, Optional
from app.models.team import RoleEnum # Import the RoleEnum from your models

# -- Team Member Schemas --

# Base properties for a team member
class TeamMemberBase(BaseModel):
    player_uuid: uuid.UUID
    role: RoleEnum

# Properties to receive via API on creation (role is optional, defaults to member)
class TeamMemberCreate(TeamMemberBase):
    role: RoleEnum = RoleEnum.member

# Properties to return to client
class TeamMember(TeamMemberBase):
    class Config:
        orm_mode = True

# -- Team Schemas --

# Base properties for a team
class TeamBase(BaseModel):
    name: str = Field(..., min_length=3, max_length=50)

# Properties to receive on creation
class TeamCreate(TeamBase):
    # The owner_uuid will be extracted from the authenticated user/token in a real app
    # For now, we might pass it in the request body for simplicity.
    owner_uuid: uuid.UUID

class TeamUpdate(BaseModel):
    name: Optional[str] = Field(None, min_length=3, max_length=50)

# Properties to return to a client
class Team(TeamBase):
    id: int
    owner_uuid: uuid.UUID
    members: List[TeamMember] = []
    island_id: Optional[int] = None # The ID of the associated island

    class Config:
        orm_mode = True

class TeamCreateResponse(BaseModel):
    team: Team
    action: str # Will be "created" or "converted"

# -- Invitation Schemas --

class TeamInvite(BaseModel):
    player_to_invite_uuid: uuid.UUID

class TeamInviteAccept(BaseModel):
    team_name: str
