from pydantic import BaseModel, Field, ConfigDict
from typing import Optional, List, Union
from datetime import datetime
import enum


class UpdateTypeEnum(str, enum.Enum):
    """Represents the type of an update campaign."""
    SERVER_ONLY = "server_only"
    BOTH = "both"
    CRITICAL = "critical"


class CampaignStatusEnum(str, enum.Enum):
    """Represents the lifecycle status of an update campaign."""
    PENDING = "PENDING"
    IN_PROGRESS = "IN_PROGRESS"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    ROLLED_BACK = "ROLLED_BACK"


class UpdateQueueStatusEnum(str, enum.Enum):
    """Represents the status of an island inside an update campaign."""
    PENDING = "PENDING"
    PROCESSING = "PROCESSING"
    WAITING = "WAITING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    SKIPPED = "SKIPPED"


class CampaignCreateRequest(BaseModel):
    """Manual campaign trigger (admin).

    Attributes:
        tag: The git tag to roll out (e.g., "v1.3.0").
        islands: List of player UUIDs, or "all" for every island.
    """
    tag: str = Field(..., max_length=50)
    islands: Union[List[str], str] = Field("all", description='List of player UUIDs or "all"')


class SpawnSyncRequest(BaseModel):
    """Direct spawn sync request — bypasses campaigns/islands entirely.

    Attributes:
        tag: Git tag to check out before pushing. If omitted, pushes whatever
             is currently checked out locally in the updates repo.
    """
    tag: Optional[str] = Field(None, max_length=50)


class CampaignResponse(BaseModel):
    """A campaign with rollout progress counters."""
    id: int
    version: str
    previous_version: Optional[str] = None
    git_commit: Optional[str] = None
    update_type: UpdateTypeEnum
    requires_restart: bool
    reload_commands: Optional[List[str]] = None
    changed_paths: Optional[List[List[str]]] = None
    message: Optional[str] = None
    status: CampaignStatusEnum
    error_message: Optional[str] = None
    created_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None

    # progress counters (filled by the endpoint, not stored)
    total: int = 0
    completed: int = 0
    waiting: int = 0
    failed: int = 0
    pending: int = 0
    skipped: int = 0

    model_config = ConfigDict(from_attributes=True)


class QueueEntryResponse(BaseModel):
    """One island's slot in a campaign."""
    id: int
    campaign_id: int
    island_id: int
    player_uuid: Optional[str] = None
    status: UpdateQueueStatusEnum
    error_message: Optional[str] = None
    retry_count: int = 0
    added_to_queue_at: Optional[datetime] = None
    processing_started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None

    model_config = ConfigDict(from_attributes=True)


class CampaignDetailResponse(CampaignResponse):
    """Campaign with the per-island breakdown."""
    entries: List[QueueEntryResponse] = []


class SnapshotInfo(BaseModel):
    """LXD snapshot metadata for the admin snapshot listing."""
    name: str
    created_at: Optional[str] = None
    expires_at: Optional[str] = None
