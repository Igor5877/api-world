from sqlalchemy import Column, Integer, String, DateTime, Enum as SQLAlchemyEnum, ForeignKey, Text, Boolean
from sqlalchemy.sql import func
from sqlalchemy.orm import relationship
from app.db.base_class import Base
from app.schemas.island import IslandStatusEnum # Use the same Enum for consistency
import enum

# This Enum was accidentally removed in my last message, adding it back.
class QueueItemStatusEnum(str, enum.Enum):
    PENDING = "PENDING"
    PROCESSING = "PROCESSING"
    FAILED = "FAILED"

class Island(Base):
    __tablename__ = "islands"

    id = Column(Integer, primary_key=True, autoincrement=True)
    player_uuid = Column(String(36), unique=True, nullable=False, index=True)
    player_name = Column(String(16), nullable=True)
    container_name = Column(String(255), unique=True, nullable=False, index=True)
    
    status = Column(SQLAlchemyEnum(IslandStatusEnum, name="island_status_enum", create_constraint=True, validate_strings=True), 
                    nullable=False, 
                    default=IslandStatusEnum.CREATING, 
                    index=True)
    
    internal_ip_address = Column(String(45), nullable=True)
    internal_port = Column(Integer, default=25565, nullable=True)
    external_port = Column(Integer, unique=True, nullable=True)

    world_seed = Column(String(255), nullable=True)
    minecraft_ready = Column(Boolean, default=False, nullable=False, server_default='0')
    
    created_at = Column(DateTime, server_default=func.now())
    updated_at = Column(DateTime, server_default=func.now(), onupdate=func.now())
    last_seen_at = Column(DateTime, nullable=True, index=True)

class IslandQueue(Base):
    __tablename__ = "island_queue"

    id = Column(Integer, primary_key=True, autoincrement=True)
    player_uuid = Column(String(36), ForeignKey("islands.player_uuid", ondelete="CASCADE"), unique=True, nullable=False)
    # This model also needs the status enum
    status = Column(SQLAlchemyEnum(QueueItemStatusEnum, name="queue_item_status_enum", create_constraint=True, validate_strings=True),
                    nullable=False,
                    default=QueueItemStatusEnum.PENDING,
                    index=True)
    requested_at = Column(DateTime, server_default=func.now(), index=True)

class UpdateQueue(Base):
    __tablename__ = "update_queue"

    id = Column(Integer, primary_key=True, autoincrement=True)
    island_id = Column(Integer, ForeignKey("islands.id", ondelete="CASCADE"), unique=True, nullable=False)
    player_uuid = Column(String(36), nullable=False)
    status = Column(String(50), default='PENDING', nullable=False)
    added_to_queue_at = Column(DateTime, server_default=func.now())
    processing_started_at = Column(DateTime, nullable=True)
    completed_at = Column(DateTime, nullable=True)
    retry_count = Column(Integer, default=0)
    error_message = Column(Text, nullable=True)

    island = relationship("Island")

class IslandSetting(Base):
    __tablename__ = "island_settings"

    island_id = Column(Integer, ForeignKey("islands.id", ondelete="CASCADE"), primary_key=True)
    setting_key = Column(String(255), primary_key=True)
    setting_value = Column(Text, nullable=True)

class IslandBackup(Base):
    __tablename__ = "island_backups"

    id = Column(Integer, primary_key=True, autoincrement=True)
    island_id = Column(Integer, ForeignKey("islands.id", ondelete="CASCADE"), nullable=False, index=True)
    snapshot_name = Column(String(255), nullable=False)
    created_at = Column(DateTime, server_default=func.now())
    description = Column(Text, nullable=True)
