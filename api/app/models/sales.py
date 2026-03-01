import enum
from sqlalchemy import Column, Integer, String, DateTime, ForeignKey, Enum as SQLAlchemyEnum, Float, Boolean, Text
from sqlalchemy.orm import relationship
from sqlalchemy.sql import func
from app.db.base_class import Base

class TransactionStatusEnum(str, enum.Enum):
    PENDING = "PENDING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"
    REFUNDED = "REFUNDED"
    PARTIAL = "PARTIAL" # For cases where items were given but funds not deducted, etc.
    ADMIN_REVIEW = "ADMIN_REVIEW" # For Liability Shift cases

class SalesItem(Base):
    """
    Represents an item available for sale from an island.
    Synced from the island's AE2 network.
    """
    __tablename__ = "sales_items"

    id = Column(Integer, primary_key=True, autoincrement=True)
    island_id = Column(Integer, ForeignKey("islands.id", ondelete="CASCADE"), nullable=False, index=True)

    # AE2 Item identification (could be complex NBT, but for MVP we use a string hash or registry name)
    item_id = Column(String(255), nullable=False, index=True) # e.g., "minecraft:diamond" or custom hash
    item_name = Column(String(255), nullable=True) # Display name
    item_nbt = Column(Text, nullable=True) # JSON or stringified NBT for exact matching

    quantity = Column(Integer, default=0, nullable=False)
    price = Column(Float, default=0.0, nullable=False) # Price per unit

    updated_at = Column(DateTime, server_default=func.now(), onupdate=func.now())

    # Relationship
    island = relationship("Island", backref="sales_items")


class Transaction(Base):
    """
    Represents a purchase transaction.
    Implements the Liability Shift model.
    """
    __tablename__ = "sales_transactions"

    id = Column(Integer, primary_key=True, autoincrement=True)

    buyer_uuid = Column(String(36), nullable=False, index=True)
    seller_island_id = Column(Integer, ForeignKey("islands.id", ondelete="SET NULL"), nullable=True)

    item_id = Column(String(255), nullable=False)
    quantity = Column(Integer, nullable=False)
    total_price = Column(Float, nullable=False)

    status = Column(SQLAlchemyEnum(TransactionStatusEnum, name="transaction_status_enum", create_constraint=True, validate_strings=True),
                    default=TransactionStatusEnum.PENDING, nullable=False)

    created_at = Column(DateTime, server_default=func.now())
    completed_at = Column(DateTime, nullable=True)

    # Liability Shift Logs
    error_log = Column(Text, nullable=True) # Details if something went wrong
    admin_notes = Column(Text, nullable=True)

    # Flags for step tracking
    item_given = Column(Boolean, default=False)
    funds_deducted = Column(Boolean, default=False)
    seller_credited = Column(Boolean, default=False)
    inventory_removed = Column(Boolean, default=False) # True if removed from AE2 (or pending removal)

    # Pending Removal Logic
    # If the island was offline during purchase, this flag is False.
    # The island must query for transactions where inventory_removed=False and status=COMPLETED to remove items.
