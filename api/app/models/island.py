from sqlalchemy import Column, Integer, Float, String, DateTime, Enum as SQLAlchemyEnum, ForeignKey, Text, Boolean, JSON
from sqlalchemy.sql import func
from sqlalchemy.orm import relationship
from app.db.base_class import Base
import enum

class IslandStatusEnum(str, enum.Enum):
    """Represents the status of an island."""
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

class QueueItemStatusEnum(str, enum.Enum):
    """Represents the status of a queue item."""
    PENDING = "PENDING"
    PROCESSING = "PROCESSING"
    FAILED = "FAILED"

class Island(Base):
    """Represents a SkyBlock island.

    Attributes:
        id: The unique identifier for the island.
        player_uuid: The UUID of the player who owns the island. (deprecated)
        player_name: The name of the player who owns the island. (deprecated)
        team_id: The ID of the team that owns the island.
        container_name: The name of the LXD container for the island.
        status: The status of the island.
        internal_ip_address: The internal IP address of the island.
        internal_port: The internal port of the island.
        external_port: The external port of the island.
        world_seed: The seed of the island's world.
        minecraft_ready: Whether the Minecraft server is ready to accept
            players.
        created_at: The timestamp when the island was created.
        updated_at: The timestamp when the island was last updated.
        last_seen_at: The timestamp when the island was last seen.
        team: The team that owns the island.
    """
    __tablename__ = "islands"

    id = Column(Integer, primary_key=True, autoincrement=True)
    # player_uuid and player_name are deprecated in favor of team ownership
    # They can be removed after data migration or kept as nullable for logging.
    player_uuid = Column(String(36), unique=True, nullable=True, index=True)
    player_name = Column(String(16), nullable=True)
    
    # A unique ID to link this island to a team. Enforces a one-to-one relationship.
    team_id = Column(Integer, ForeignKey("teams.id", ondelete="SET NULL"), unique=True, nullable=True, index=True)

    container_name = Column(String(255), unique=True, nullable=False, index=True)
    
    status = Column(SQLAlchemyEnum(IslandStatusEnum, name="island_status_enum", create_constraint=True, validate_strings=True), 
                    nullable=False, 
                    default=IslandStatusEnum.CREATING, 
                    index=True)
    
    internal_ip_address = Column(String(45), nullable=True)
    internal_port = Column(Integer, default=25565, nullable=True)
    external_port = Column(Integer, unique=True, nullable=True) # If direct host port mapping

    world_seed = Column(String(255), nullable=True)
    minecraft_ready = Column(Boolean, default=False, nullable=False, server_default='0') # Added field

    # Health monitoring: the mod sends a heartbeat over WebSocket every ~30s.
    # last_heartbeat_at is reset to NULL on every (re)start so the watchdog
    # only judges islands that have already proven they can heartbeat this boot.
    last_heartbeat_at = Column(DateTime, nullable=True)
    last_tps = Column(Float, nullable=True)
    online_players = Column(Integer, nullable=True)

    # Auto-update system
    current_version = Column(String(50), nullable=True)  # last applied update tag, e.g. "v1.3.0"
    skip_auto_updates = Column(Boolean, default=False, nullable=False, server_default='0')  # unique servers are never touched

    # Timestamps
    created_at = Column(DateTime, server_default=func.now())
    updated_at = Column(DateTime, server_default=func.now(), onupdate=func.now())
    last_seen_at = Column(DateTime, nullable=True, index=True)

    # Relationships
    team = relationship("Team", back_populates="island")


class IslandEvent(Base):
    """Journal of island lifecycle incidents for the admin.

    Written by the health watchdog and lifecycle handlers so crashes, hangs
    and silent restarts are visible via the API instead of only in logs.

    event_type values:
        crashed            — container went down while the island was RUNNING
        hung               — container up but Minecraft stopped answering
        stopped_externally — Minecraft announced shutdown not initiated by the API
        restarted          — /ready arrived for an island already marked ready
        stopping           — the mod signalled a clean shutdown
        state_mismatch     — DB status and real LXD state diverged
    """
    __tablename__ = "island_events"

    id = Column(Integer, primary_key=True, autoincrement=True)
    island_id = Column(Integer, ForeignKey("islands.id", ondelete="CASCADE"), nullable=False, index=True)
    event_type = Column(String(32), nullable=False)
    details = Column(String(1024), nullable=True)
    created_at = Column(DateTime, server_default=func.now(), index=True)


class IslandQuestProgress(Base):
    """Stores the latest FTB Quests progress snapshot for an island.

    The island server is the source of truth: it uploads its
    world/ftbquests/{uuid}.snbt after progress changes; the spawn/hub server
    downloads it on player join for read-only display.

    Attributes:
        island_id: The island the snapshot belongs to (one row per island).
        owner_uuid: The island identity UUID (same as the .snbt file name).
        snbt: The raw SNBT file content.
        updated_at: When the snapshot was last uploaded.
    """
    __tablename__ = "island_quest_progress"

    island_id = Column(Integer, ForeignKey("islands.id", ondelete="CASCADE"), primary_key=True)
    owner_uuid = Column(String(36), unique=True, nullable=False, index=True)
    snbt = Column(Text(16 * 1024 * 1024), nullable=False)  # MEDIUMTEXT on MySQL
    updated_at = Column(DateTime, server_default=func.now(), onupdate=func.now())


class IslandQueue(Base):
    """Represents a queue of players waiting for an island.

    Attributes:
        id: The unique identifier for the queue item.
        player_uuid: The UUID of the player in the queue.
        player_name: The name of the player in the queue.
        status: The status of the queue item.
        requested_at: The timestamp when the player was added to the queue.
    """
    __tablename__ = "island_queue"

    id = Column(Integer, primary_key=True, autoincrement=True)
    player_uuid = Column(String(36), unique=True, nullable=False, index=True)
    player_name = Column(String(16), nullable=True)
    status = Column(SQLAlchemyEnum(QueueItemStatusEnum, name="queue_item_status_enum", create_constraint=True, validate_strings=True),
                    nullable=False,
                    default=QueueItemStatusEnum.PENDING,
                    index=True)
    requested_at = Column(DateTime, server_default=func.now(), index=True)

    # Relationship to Island (optional)
    # island = relationship("Island", back_populates="queue_entry")


class IslandBackup(Base):
    """Represents a backup of an island.

    Attributes:
        id: The unique identifier for the backup.
        island_id: The ID of the island this backup belongs to.
        snapshot_name: The name of the snapshot.
        created_at: The timestamp when the backup was created.
        description: A description of the backup.
    """
    __tablename__ = "island_backups"

    id = Column(Integer, primary_key=True, autoincrement=True)
    # Using island.id as foreign key as per schema.sql
    island_id = Column(Integer, ForeignKey("islands.id", ondelete="CASCADE"), nullable=False, index=True)
    snapshot_name = Column(String(255), nullable=False)
    created_at = Column(DateTime, server_default=func.now())
    description = Column(Text, nullable=True)

    # Auto-update system: file-level backups for soft rollback (world/ is never touched)
    backup_type = Column(String(20), nullable=False, default="snapshot", server_default="snapshot")  # "snapshot" | "files"
    backup_path = Column(String(512), nullable=True)   # host-side directory with the saved files
    changed_paths = Column(JSON, nullable=True)        # [status, path] pairs the backup covers
    version = Column(String(50), nullable=True)        # campaign version the backup was made for
    campaign_id = Column(Integer, ForeignKey("update_campaigns.id", ondelete="SET NULL"), nullable=True, index=True)

    # Relationship to Island (optional)
    # island = relationship("Island", back_populates="backups")
