from sqlalchemy import Column, Integer, String, DateTime, Enum as SQLAlchemyEnum, ForeignKey, Text, Boolean
from sqlalchemy.sql import func
from sqlalchemy.orm import relationship
from app.db.base_class import Base
from app.schemas.island import IslandStatusEnum # Use the same Enum for consistency
import enum

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

class QueueItemStatusEnum(str, enum.Enum):
    PENDING = "PENDING"
    PROCESSING = "PROCESSING"
    FAILED = "FAILED"

class Island(Base):
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
    
    # Timestamps
    created_at = Column(DateTime, server_default=func.now())
    updated_at = Column(DateTime, server_default=func.now(), onupdate=func.now())
    last_seen_at = Column(DateTime, nullable=True, index=True)

    # Relationships
    team = relationship("Team", back_populates="island")


class IslandQueue(Base):
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


class IslandSetting(Base):
    __tablename__ = "island_settings"

    # Using island.id as foreign key as per schema.sql
    island_id = Column(Integer, ForeignKey("islands.id", ondelete="CASCADE"), primary_key=True)
    setting_key = Column(String(255), primary_key=True)
    setting_value = Column(Text, nullable=True)

    # Relationship to Island (optional)
    # island = relationship("Island", back_populates="settings")


class IslandBackup(Base):
    __tablename__ = "island_backups"

    id = Column(Integer, primary_key=True, autoincrement=True)
    # Using island.id as foreign key as per schema.sql
    island_id = Column(Integer, ForeignKey("islands.id", ondelete="CASCADE"), nullable=False, index=True)
    snapshot_name = Column(String(255), nullable=False)
    created_at = Column(DateTime, server_default=func.now())
    description = Column(Text, nullable=True)

    # Relationship to Island (optional)
    # island = relationship("Island", back_populates="backups")

# Note on Enum:
# The `SQLAlchemyEnum` uses a VARCHAR on the database side.
# `name="island_status_enum"` helps in some DBs to name the type if it creates one (e.g. PostgreSQL ENUM type).
# `create_constraint=True` ensures the ENUM constraint is created on the DB if the DB supports it.
# `validate_strings=True` ensures that only valid enum values are accepted from Python side.

# Note on Timestamps:
# `server_default=func.now()` makes the DB generate the timestamp on insert.
# `onupdate=func.now()` makes the DB update the timestamp on update (works well in MySQL).
# For cross-DB compatibility or more control, you might handle `updated_at` in your application logic
# using SQLAlchemy event listeners (e.g., before_update).
# For `DateTime` without `timezone=True`, it's naive. If you need timezone awareness, add `timezone=True`.
# MySQL stores TIMESTAMP in UTC and converts to session timezone. PostgreSQL stores as UTC if using TIMESTAMPTZ.
