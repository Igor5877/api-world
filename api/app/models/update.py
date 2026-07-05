from sqlalchemy import Column, Integer, String, DateTime, Enum as SQLAlchemyEnum, ForeignKey, Text, Boolean, JSON
from sqlalchemy.sql import func
from app.db.base_class import Base
import enum


class UpdateTypeEnum(str, enum.Enum):
    """Represents the type of an update campaign (derived from the git tag suffix)."""
    SERVER_ONLY = "server_only"
    BOTH = "both"
    CRITICAL = "critical"


class CampaignStatusEnum(str, enum.Enum):
    """Represents the lifecycle status of an update campaign."""
    PENDING = "PENDING"          # webhook received, manifest is being built
    IN_PROGRESS = "IN_PROGRESS"  # islands are being updated
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"            # manifest build failed — no islands were touched
    ROLLED_BACK = "ROLLED_BACK"


class UpdateQueueStatusEnum(str, enum.Enum):
    """Represents the status of an island inside an update campaign."""
    PENDING = "PENDING"
    PROCESSING = "PROCESSING"
    WAITING = "WAITING"      # island is online — waiting for the player to log out
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    SKIPPED = "SKIPPED"      # skip_auto_updates or island in a non-updatable state


class UpdateCampaign(Base):
    """Represents a single update rollout triggered by a git tag.

    Attributes:
        id: The unique identifier for the campaign.
        version: The version tag (e.g., "v1.3.0").
        previous_version: The previous tag used for the diff (None for the first campaign).
        git_commit: The commit SHA the tag points to.
        update_type: server_only | both | critical.
        requires_restart: Whether the update needs a Minecraft restart (mods changed).
        reload_commands: JSON list of in-game commands for soft updates.
        changed_paths: JSON list of [status, path] pairs from git diff --name-status.
        message: The commit message shown to players and in logs.
        status: The campaign lifecycle status.
        error_message: Failure details when status is FAILED.
        created_at: When the campaign was created.
        completed_at: When all islands were processed.
    """
    __tablename__ = "update_campaigns"

    id = Column(Integer, primary_key=True, autoincrement=True)
    version = Column(String(50), nullable=False, unique=True, index=True)
    previous_version = Column(String(50), nullable=True)
    git_commit = Column(String(40), nullable=True)
    update_type = Column(SQLAlchemyEnum(UpdateTypeEnum, name="update_type_enum", values_callable=lambda e: [m.value for m in e]),
                         nullable=False, default=UpdateTypeEnum.SERVER_ONLY)
    requires_restart = Column(Boolean, nullable=False, default=False, server_default='0')
    reload_commands = Column(JSON, nullable=True)
    changed_paths = Column(JSON, nullable=True)
    message = Column(Text, nullable=True)
    status = Column(SQLAlchemyEnum(CampaignStatusEnum, name="campaign_status_enum", validate_strings=True),
                    nullable=False, default=CampaignStatusEnum.PENDING, index=True)
    error_message = Column(Text, nullable=True)
    created_at = Column(DateTime, server_default=func.now())
    completed_at = Column(DateTime, nullable=True)


class UpdateQueue(Base):
    """Represents one island's slot inside an update campaign.

    Attributes:
        id: The unique identifier for the queue entry.
        campaign_id: The campaign this entry belongs to.
        island_id: The island to update.
        player_uuid: The owner UUID (denormalized for logging/notifications).
        status: PENDING | PROCESSING | WAITING | COMPLETED | FAILED | SKIPPED.
        error_message: Failure details when status is FAILED.
        retry_count: How many times processing was attempted.
        added_to_queue_at: When the entry was created.
        processing_started_at: When processing last started.
        completed_at: When the entry reached a terminal state.
    """
    __tablename__ = "update_queue"

    id = Column(Integer, primary_key=True, autoincrement=True)
    campaign_id = Column(Integer, ForeignKey("update_campaigns.id", ondelete="CASCADE"), nullable=False, index=True)
    island_id = Column(Integer, ForeignKey("islands.id", ondelete="CASCADE"), nullable=False, index=True)
    player_uuid = Column(String(36), nullable=True)
    status = Column(SQLAlchemyEnum(UpdateQueueStatusEnum, name="update_queue_status_enum", validate_strings=True),
                    nullable=False, default=UpdateQueueStatusEnum.PENDING, index=True)
    error_message = Column(Text, nullable=True)
    retry_count = Column(Integer, nullable=False, default=0, server_default='0')
    added_to_queue_at = Column(DateTime, server_default=func.now(), index=True)
    processing_started_at = Column(DateTime, nullable=True)
    completed_at = Column(DateTime, nullable=True)


class IslandPendingCommand(Base):
    """A command that must be executed on an island's Minecraft server.

    Delivered over WebSocket; if the island is offline or the socket is down,
    the command stays here and is re-sent when the island reconnects
    (same pattern as market pending extractions).

    Attributes:
        id: The unique identifier for the command.
        island_id: The island the command targets.
        player_uuid: The owner UUID — used as the WebSocket client id (island_<uuid>).
        command: The Minecraft command to run (without leading slash).
        campaign_id: The campaign that produced the command, if any.
        created_at: When the command was queued.
        delivered: Set once the mod acknowledges execution.
    """
    __tablename__ = "island_pending_commands"

    id = Column(Integer, primary_key=True, autoincrement=True)
    island_id = Column(Integer, ForeignKey("islands.id", ondelete="CASCADE"), nullable=False, index=True)
    player_uuid = Column(String(36), nullable=False, index=True)
    command = Column(Text, nullable=False)
    campaign_id = Column(Integer, ForeignKey("update_campaigns.id", ondelete="SET NULL"), nullable=True)
    created_at = Column(DateTime, server_default=func.now())
    delivered = Column(Boolean, nullable=False, default=False, server_default='0', index=True)
