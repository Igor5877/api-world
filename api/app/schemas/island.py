from pydantic import BaseModel, Field, ConfigDict
from typing import Optional
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
    ERROR_CREATE = "ERROR_CREATE" # Specific error during creation (e.g., LXD clone failure)
    ERROR_START = "ERROR_START"   # Specific error during start (e.g., LXD start failure, no IP)
    PENDING_START = "PENDING_START"
    PENDING_CREATION = "PENDING_CREATION"
    PENDING_STOP = "PENDING_STOP"
    PENDING_FREEZE = "PENDING_FREEZE"
# Base model for common island attributes
class IslandBase(BaseModel):
    player_name: Optional[str] = Field(None, max_length=16, description="Minecraft player name")

# Schema for creating an island (request model for POST /islands)
class IslandCreate(IslandBase):
    player_uuid: uuid.UUID = Field(..., description="Minecraft player UUID")

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
    id: Optional[int] = Field(None, description="Island's unique ID in the database")
    player_uuid: uuid.UUID
    container_name: Optional[str] = None
    status: IslandStatusEnum
    internal_ip_address: Optional[str] = Field(None, pattern=r"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$")
    internal_port: Optional[int] = Field(None, gt=0, le=65535)
    external_port: Optional[int] = Field(None, gt=0, le=65535, description="External port if direct mapping")
    world_seed: Optional[str] = None
    created_at: Optional[datetime] = None
    updated_at: Optional[datetime] = None
    last_seen_at: Optional[datetime] = None
    minecraft_ready: bool = Field(False, description="Indicates if the Minecraft server itself is fully loaded and ready for players")
    message: Optional[str] = None

    # FIX: model_config is now a direct attribute of the class
    model_config = ConfigDict(from_attributes=True)

# Schema for the data stored in the database
class IslandInDBBase(IslandBase):
    id: int
    player_uuid: uuid.UUID
    container_name: str
    status: IslandStatusEnum
    internal_ip_address: Optional[str] = None
    internal_port: Optional[int] = 25565
    external_port: Optional[int] = None
    world_seed: Optional[str] = None
    created_at: datetime
    updated_at: datetime
    last_seen_at: Optional[datetime] = None
    minecraft_ready: bool = False # Default to False

    # FIX: model_config is now a direct attribute of the class
    model_config = ConfigDict(from_attributes=True)

# Schemas for Island Queue
class IslandQueueBase(BaseModel):
    player_uuid: uuid.UUID

class IslandQueueCreate(IslandQueueBase):
    pass

class IslandQueueResponse(IslandQueueBase):
    id: int
    requested_at: datetime

    # FIX: model_config is now a direct attribute of the class
    model_config = ConfigDict(from_attributes=True)

# Schemas for Island Backup
class IslandBackupBase(BaseModel):
    island_id: int
    snapshot_name: str = Field(..., max_length=255)
    description: Optional[str] = None

class IslandBackupCreate(IslandBackupBase):
    pass

class IslandBackupResponse(IslandBackupBase):
    id: int
    created_at: datetime

    # FIX: model_config is now a direct attribute of the class
    model_config = ConfigDict(from_attributes=True)

# Message schema for simple API responses
class MessageResponse(BaseModel):
    message: str
