from sqlalchemy import Column, Integer, String, DateTime, Enum as SQLAlchemyEnum
from sqlalchemy.sql import func
from app.db.base_class import Base
from app.models.island import QueueItemStatusEnum

class IslandStartQueue(Base):
    __tablename__ = "island_start_queue"

    id = Column(Integer, primary_key=True, autoincrement=True)
    player_uuid = Column(String(36), unique=True, nullable=False, index=True)
    player_name = Column(String(16), nullable=True)
    status = Column(SQLAlchemyEnum(QueueItemStatusEnum, name="queue_item_status_enum", create_constraint=True, validate_strings=True),
                    nullable=False,
                    default=QueueItemStatusEnum.PENDING,
                    index=True)
    requested_at = Column(DateTime, server_default=func.now(), index=True)
