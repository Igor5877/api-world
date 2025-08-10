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
    ERROR_CREATE = "ERROR_CREATE"
    ERROR_START = "ERROR_START"
    PENDING_START = "PENDING_START"
    PENDING_CREATION = "PENDING_CREATION"
    PENDING_STOP = "PENDING_STOP"
    PENDING_FREEZE = "PENDING_FREEZE"
    PENDING_UPDATE = "PENDING_UPDATE"
    UPDATING = "UPDATING"
    UPDATE_FAILED = "UPDATE_FAILED"

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
    minecraft_ready: Optional[bool] = None

# Schema for representing an island in API responses
class IslandResponse(IslandBase):
    id: int
    player_uuid: uuid.UUID
    container_name: str
    status: IslandStatusEnum
    internal_ip_address: Optional[str] = None
    minecraft_ready: bool = Field(False, description="Indicates if the Minecraft server itself is fully loaded and ready for players")
    model_config = ConfigDict(from_attributes=True)

# Message schema for simple API responses
class MessageResponse(BaseModel):
    message: str

