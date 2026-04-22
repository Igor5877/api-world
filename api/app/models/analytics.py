from sqlalchemy import Column, Integer, String, DateTime, Numeric, Boolean, ForeignKey, UniqueConstraint
from sqlalchemy.sql import func
from app.db.base_class import Base


class MarketStatsHourly(Base):
    """Агрегована статистика продажів по годинах — читається адмін-дашбордом."""
    __tablename__ = "market_stats_hourly"

    id                = Column(Integer, primary_key=True, autoincrement=True)
    hour              = Column(DateTime, nullable=False, index=True)
    item_id           = Column(String(255), nullable=False)
    team_id           = Column(Integer, ForeignKey("teams.id", ondelete="CASCADE"), nullable=False)
    total_sold        = Column(Integer, nullable=False, default=0)
    total_volume      = Column(Numeric(14, 2), nullable=False, default=0)
    transaction_count = Column(Integer, nullable=False, default=0)

    __table_args__ = (
        UniqueConstraint("hour", "item_id", "team_id", name="uq_stats_hour_item_team"),
    )


class EconomySnapshot(Base):
    """Загальний стан економіки за годину — обіг грошей, кількість транзакцій."""
    __tablename__ = "economy_snapshots"

    id                  = Column(Integer, primary_key=True, autoincrement=True)
    hour                = Column(DateTime, nullable=False, unique=True, index=True)
    total_volume        = Column(Numeric(14, 2), nullable=False, default=0)
    total_commission    = Column(Numeric(14, 2), nullable=False, default=0)
    total_transactions  = Column(Integer, nullable=False, default=0)
    active_sellers      = Column(Integer, nullable=False, default=0)


class MarketAnomaly(Base):
    """Виявлені аномалії — різкий стрибок продажів конкретного предмету."""
    __tablename__ = "market_anomalies"

    id              = Column(Integer, primary_key=True, autoincrement=True)
    detected_at     = Column(DateTime, server_default=func.now(), index=True)
    hour            = Column(DateTime, nullable=False)
    item_id         = Column(String(255), nullable=False)
    team_id         = Column(Integer, nullable=True)
    actual_volume   = Column(Numeric(14, 2), nullable=False)
    expected_volume = Column(Numeric(14, 2), nullable=False)
    multiplier      = Column(Numeric(6, 2), nullable=False)   # actual / expected
    resolved        = Column(Boolean, nullable=False, default=False)
    resolved_at     = Column(DateTime, nullable=True)
