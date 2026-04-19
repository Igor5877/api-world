from sqlalchemy import Column, Integer, String, BigInteger, Boolean, DateTime, Index, Text, Numeric, ForeignKey
from sqlalchemy.sql import func
from app.db.base_class import Base

class MarketItem(Base):
    """Represents an item synced from a player's RealMarket terminal on their Island."""
    __tablename__ = "market_items"

    id                = Column(Integer, primary_key=True, autoincrement=True)
    team_id           = Column(Integer, ForeignKey("teams.id", ondelete="CASCADE"), nullable=False, index=True)
    item_id           = Column(String(255), nullable=False, index=True)
    item_nbt          = Column(Text, nullable=False, default='')  # '' замість NULL — інакше UNIQUE не детектить дублікати
    quantity          = Column(BigInteger, nullable=False, default=0)
    price             = Column(Numeric(12, 2), nullable=False, default=10.0)
    is_for_sale       = Column(Boolean, nullable=False, default=True)
    version           = Column(Integer, nullable=False, default=1)
    seller_azuriom_id = Column(Integer, nullable=True)
    updated_at        = Column(DateTime, server_default=func.now(), onupdate=func.now(), index=True)

    __table_args__ = (
        Index('uq_team_item', 'team_id', 'item_id', 'item_nbt',
              unique=True, mysql_length={'item_id': 191, 'item_nbt': 500}),
    )


class MarketTransaction(Base):
    """Історія кожної купівлі/продажу на маркеті."""
    __tablename__ = "market_transactions"

    id                = Column(Integer, primary_key=True, autoincrement=True)
    team_id           = Column(Integer, ForeignKey("teams.id", ondelete="CASCADE"), nullable=False, index=True)
    item_id           = Column(String(255), nullable=False)
    quantity          = Column(Integer, nullable=False)
    unit_price        = Column(Numeric(12, 2), nullable=False)
    total_price       = Column(Numeric(12, 2), nullable=False)
    buyer_azuriom_id  = Column(Integer, nullable=True)
    seller_azuriom_id = Column(Integer, nullable=True)
    seller_paid       = Column(Boolean, nullable=False, default=False, server_default='0')  # True коли extraction підтверджено
    created_at        = Column(DateTime, server_default=func.now(), index=True)


class WarpPendingCommand(Base):
    """
    Черга команд для спавн-сервера (створення/призупинення/відновлення варп-платформ).
    Зберігається якщо spawn_hub офлайн — надсилається при наступному підключенні.
    """
    __tablename__ = "warp_pending_commands"

    id          = Column(Integer, primary_key=True, autoincrement=True)
    player_uuid = Column(String(36), nullable=False, index=True)
    command     = Column(String(50), nullable=False)
    created_at  = Column(DateTime, server_default=func.now())


class MarketPendingExtraction(Base):
    """
    Зберігає купівлі, які ще не були підтверджені островом (extraction з AE2).
    Якщо острів офлайн під час купівлі — запис залишається тут.
    При підключенні острова через WebSocket — API надсилає всі pending записи.
    """
    __tablename__ = "market_pending_extractions"

    id          = Column(Integer, primary_key=True, autoincrement=True)
    team_id     = Column(Integer, ForeignKey("teams.id", ondelete="CASCADE"), nullable=False, index=True)
    item_id     = Column(String(255), nullable=False)
    quantity    = Column(Integer, nullable=False)
    created_at  = Column(DateTime, server_default=func.now())
