from sqlalchemy import Column, Integer, String, BigInteger, Float, Boolean, DateTime
from sqlalchemy.sql import func
from app.db.base_class import Base

class MarketItem(Base):
    """Represents an item synced from a player's RealMarket terminal on their Island.

    Attributes:
        id: The unique identifier for the market item record.
        island_uuid: The UUID of the island/team this item belongs to.
        item_id: The Minecraft registry name of the item (e.g., 'minecraft:diamond').
        item_nbt: The NBT data of the item as a JSON string (optional).
        quantity: The total amount of this exact item available in the ME network.
        price: The price set for this item.
        is_for_sale: Whether this item is currently listed for sale.
        version: The version/revision of the market link sync.
        updated_at: The timestamp of the last successful sync.
    """
    __tablename__ = "market_items"

    id = Column(Integer, primary_key=True, autoincrement=True)
    island_uuid = Column(String(36), nullable=False, index=True)
    item_id = Column(String(255), nullable=False, index=True)
    item_nbt = Column(String(10000), nullable=True) # Text length to fit NBT safely
    quantity = Column(BigInteger, nullable=False, default=0)
    price = Column(Float, nullable=False, default=10.0)
    is_for_sale = Column(Boolean, nullable=False, default=True)
    version = Column(Integer, nullable=False, default=1)
    
    updated_at = Column(DateTime, server_default=func.now(), onupdate=func.now(), index=True)

