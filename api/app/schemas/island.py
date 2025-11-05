from pydantic import BaseModel, Field, ConfigDict
from typing import Optional
from datetime import datetime
import enum
import uuid

# Enum for Island Status
class IslandStatusEnum(str, enum.Enum):
    """Represents the status of an island."""
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
    """Base schema for an island.

    Attributes:
        player_name: The Minecraft player name.
    """
    player_name: Optional[str] = Field(None, max_length=16, description="Minecraft player name")

# Schema for creating an island (request model for POST /islands)
class IslandCreate(IslandBase):
    """Schema for creating an island.

    Attributes:
        player_uuid: The Minecraft player UUID.
    """
    player_uuid: uuid.UUID = Field(..., description="Minecraft player UUID")

# Schema for updating an island (used internally or for specific admin updates)
class IslandUpdate(IslandBase):
    """Schema for updating an island.

    Attributes:
        status: The status of the island.
        internal_ip_address: The internal IP address of the island.
        internal_port: The internal port of the island.
        external_port: The external port of the island.
        last_seen_at: The timestamp when the island was last seen.
        world_seed: The seed of the island's world.
    """
    status: Optional[IslandStatusEnum] = None
    internal_ip_address: Optional[str] = Field(None, pattern=r"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$")
    internal_port: Optional[int] = Field(None, gt=0, le=65535)
    external_port: Optional[int] = Field(None, gt=0, le=65535)
    last_seen_at: Optional[datetime] = None
    world_seed: Optional[str] = Field(None, max_length=255)

# Schema for representing an island in API responses
class IslandResponse(IslandBase):
    """Schema for an island response.

    Attributes:
        id: The unique identifier for the island.
        player_uuid: The UUID of the player who owns the island.
        team_id: The ID of the team that owns the island.
        container_name: The name of the LXD container for the island.
        status: The status of the island.
        internal_ip_address: The internal IP address of the island.
        internal_port: The internal port of the island.
        external_port: The external port of the island.
        world_seed: The seed of the island's world.
        created_at: The timestamp when the island was created.
        updated_at: The timestamp when the island was last updated.
        last_seen_at: The timestamp when the island was last seen.
        minecraft_ready: Whether the Minecraft server is ready to accept
            players.
        message: A message associated with the response.
    """
    id: Optional[int] = Field(None, description="Island's unique ID in the database")
    player_uuid: Optional[uuid.UUID] = None
    team_id: Optional[int] = None
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
    """Schema for an island in the database.

    Attributes:
        id: The unique identifier for the island.
        player_uuid: The UUID of the player who owns the island.
        container_name: The name of the LXD container for the island.
        status: The status of the island.
        internal_ip_address: The internal IP address of the island.
        internal_port: The internal port of the island.
        external_port: The external port of the island.
        world_seed: The seed of the island's world.
        created_at: The timestamp when the island was created.
        updated_at: The timestamp when the island was last updated.
        last_seen_at: The timestamp when the island was last seen.
        minecraft_ready: Whether the Minecraft server is ready to accept
            players.
    """
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
    """Base schema for an island queue.

    Attributes:
        player_uuid: The UUID of the player in the queue.
    """
    player_uuid: uuid.UUID

class IslandQueueCreate(IslandQueueBase):
    """Schema for creating an island queue."""
    pass

class IslandQueueResponse(IslandQueueBase):
    """Schema for an island queue response.

    Attributes:
        id: The unique identifier for the queue item.
        requested_at: The timestamp when the player was added to the queue.
    """
    id: int
    requested_at: datetime

    # FIX: model_config is now a direct attribute of the class
    model_config = ConfigDict(from_attributes=True)

# Schemas for Island Backup
class IslandBackupBase(BaseModel):
    """Base schema for an island backup.

    Attributes:
        island_id: The ID of the island this backup belongs to.
        snapshot_name: The name of the snapshot.
        description: A description of the backup.
    """
    island_id: int
    snapshot_name: str = Field(..., max_length=255)
    description: Optional[str] = None

class IslandBackupCreate(IslandBackupBase):
    """Schema for creating an island backup."""
    pass

class IslandBackupResponse(IslandBackupBase):
    """Schema for an island backup response.

    Attributes:
        id: The unique identifier for the backup.
        created_at: The timestamp when the backup was created.
    """
    id: int
    created_at: datetime

    # FIX: model_config is now a direct attribute of the class
    model_config = ConfigDict(from_attributes=True)

# Message schema for simple API responses
class MessageResponse(BaseModel):
    """Schema for a simple message response.

    Attributes:
        message: The message.
    """
    message: str
