from sqlalchemy import Column, Integer, String, BigInteger, Float, Boolean, DateTime, Index
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
    seller_azuriom_id = Column(Integer, nullable=True)

    updated_at = Column(DateTime, server_default=func.now(), onupdate=func.now(), index=True)

    __table_args__ = (
        Index('uq_island_item', 'island_uuid', 'item_id', 'item_nbt',
              unique=True, mysql_length={'item_id': 100, 'item_nbt': 100}),
    )


class MarketTransaction(Base):
    """
    Історія кожної купівлі/продажу на маркеті.
    """
    __tablename__ = "market_transactions"

    id                = Column(Integer, primary_key=True, autoincrement=True)
    island_uuid       = Column(String(36), nullable=False, index=True)
    item_id           = Column(String(255), nullable=False)
    quantity          = Column(Integer, nullable=False)
    unit_price        = Column(Float, nullable=False)
    total_price       = Column(Float, nullable=False)
    buyer_azuriom_id  = Column(Integer, nullable=True)
    seller_azuriom_id = Column(Integer, nullable=True)
    created_at        = Column(DateTime, server_default=func.now(), index=True)


class MarketPendingExtraction(Base):
    """
    Зберігає купівлі, які ще не були підтверджені островом (extraction з AE2).
    Якщо острів офлайн під час купівлі — запис залишається тут.
    При підключенні острова через WebSocket — API надсилає всі pending записи.
    Після успішного extraction острів викликає confirm → запис видаляється.
    """
    __tablename__ = "market_pending_extractions"

    id         = Column(Integer, primary_key=True, autoincrement=True)
    island_uuid = Column(String(36), nullable=False, index=True)
    item_id    = Column(String(255), nullable=False)
    quantity   = Column(Integer, nullable=False)
    created_at = Column(DateTime, server_default=func.now())

