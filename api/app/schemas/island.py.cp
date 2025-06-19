from pydantic import BaseModel, Field, ConfigDict
from typing import Optional, List
from datetime import datetime
import enum
import uuid
# Enum for Island Status
class IslandStatusEnum(str, enum.Enum):
    CREATING = "CREATING"
    STOPPED = "STOPPED"
    RUNNING = "RUNNING"
    FROZEN = "FROZEN"
    DELETING = "DELETING"
    ARCHIVED = "ARCHIVED"
    ERROR = "ERROR"
    PENDING_START = "PENDING_START" # Added for queue logic
    PENDING_CREATION = "PENDING_CREATION" # If creation is a longer async process

# Base model for common island attributes
class IslandBase(BaseModel):
    player_name: Optional[str] = Field(None, max_length=16, description="Minecraft player name")

# Schema for creating an island (request model for POST /islands)
class IslandCreate(IslandBase):
    player_uuid: uuid.UUID = Field(..., description="Minecraft player UUID")
    # player_name is optional here, could be fetched from a Mojang API later if needed,
    # but good to store if provided by the Hub mod.
    # No need to specify container_name, status, ip, port here as they are server-assigned.

# Schema for updating an island (used internally or for specific admin updates)
class IslandUpdate(IslandBase):
    status: Optional[IslandStatusEnum] = None
    internal_ip_address: Optional[str] = Field(None, pattern=r"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$")
    internal_port: Optional[int] = Field(None, gt=0, le=65535)
    external_port: Optional[int] = Field(None, gt=0, le=65535)
    last_seen_at: Optional[datetime] = None
    world_seed: Optional[str] = Field(None, max_length=255)

# Schema for representing an island in API responses
class IslandResponse(IslandBase):
    id: Optional[int] = Field(None, description="Island's unique ID in the database (if using DB model)") # From DB
    player_uuid: uuid.UUID
    container_name: str
    status: IslandStatusEnum
    internal_ip_address: Optional[str] = Field(None, pattern=r"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$")
    internal_port: Optional[int] = Field(None, gt=0, le=65535)
    external_port: Optional[int] = Field(None, gt=0, le=65535, description="External port if direct mapping")
    world_seed: Optional[str] = None
    
    created_at: Optional[datetime] = None # From DB
    updated_at: Optional[datetime] = None # From DB
    last_seen_at: Optional[datetime] = None # From DB

    class Config:
        model_config = ConfigDict(from_attributes=True) # For compatibility with SQLAlchemy models / other ORMs
        # Pydantic V2 uses `from_attributes = True` instead of `orm_mode = True`
        # from_attributes = True 


# Schema for the data stored in the database (can be more comprehensive)
# This is useful if your API response model is different from your DB model.
# Often, the Response model can inherit from this if they are similar.
class IslandInDBBase(IslandBase):
    id: int
    player_uuid: uuid.UUID
    container_name: str
    status: IslandStatusEnum
    internal_ip_address: Optional[str] = None
    internal_port: Optional[int] = 25565 # Default Minecraft port
    external_port: Optional[int] = None
    world_seed: Optional[str] = None
    created_at: datetime
    updated_at: datetime
    last_seen_at: Optional[datetime] = None
    
    class Config:
        model_config = ConfigDict(from_attributes=True)
        # from_attributes = True


# Schemas for Island Queue
class IslandQueueBase(BaseModel):
    player_uuid: uuid.UUID

class IslandQueueCreate(IslandQueueBase):
    pass

class IslandQueueResponse(IslandQueueBase):
    id: int
    requested_at: datetime

    class Config:
        model_config = ConfigDict(from_attributes=True)
        # from_attributes = True

# Schemas for Island Backup (example)
class IslandBackupBase(BaseModel):
    island_id: int # Foreign key to islands.id
    snapshot_name: str = Field(..., max_length=255)
    description: Optional[str] = None

class IslandBackupCreate(IslandBackupBase):
    pass

class IslandBackupResponse(IslandBackupBase):
    id: int
    created_at: datetime

    class Config:
        model_config = ConfigDict(from_attributes=True)
        # from_attributes = True

# Message schema for simple API responses
class MessageResponse(BaseModel):
    message: str
