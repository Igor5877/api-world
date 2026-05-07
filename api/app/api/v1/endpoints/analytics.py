import logging
from datetime import datetime, timedelta
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select, func, desc
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.session import get_db_session as get_db
from app.models.analytics import MarketStatsHourly, EconomySnapshot, MarketAnomaly
from app.models.market import MarketTransaction

router = APIRouter()
logger = logging.getLogger(__name__)


@router.get("/overview")
async def get_overview(db: AsyncSession = Depends(get_db)) -> Any:
    """
    Загальний стан економіки за останні 24 години.
    Читає тільки pre-aggregated таблиці — не навантажує основну БД.
    """
    since = datetime.utcnow().replace(minute=0, second=0, microsecond=0) - timedelta(hours=24)

    snapshots_result = await db.execute(
        select(EconomySnapshot)
        .where(EconomySnapshot.hour >= since)
        .order_by(EconomySnapshot.hour.asc())
        .limit(24)
    )
    snapshots = snapshots_result.scalars().all()

    total_volume       = sum(float(s.total_volume) for s in snapshots)
    total_transactions = sum(s.total_transactions for s in snapshots)
    total_commission   = sum(float(s.total_commission) for s in snapshots)

    # Кількість невирішених аномалій
    anomalies_result = await db.execute(
        select(func.count(MarketAnomaly.id))
        .where(MarketAnomaly.resolved == False)
    )
    anomalies_count = anomalies_result.scalar() or 0

    return {
        "period_hours": 24,
        "total_volume": round(total_volume, 2),
        "total_transactions": total_transactions,
        "total_commission": round(total_commission, 2),
        "unresolved_anomalies": anomalies_count,
        "hourly": [
            {
                "hour": s.hour.isoformat(),
                "volume": float(s.total_volume),
                "transactions": s.total_transactions,
                "sellers": s.active_sellers,
            }
            for s in snapshots
        ],
    }


@router.get("/anomalies")
async def get_anomalies(
    resolved: bool = False,
    limit: int = 50,
    db: AsyncSession = Depends(get_db),
) -> Any:
    """Список аномалій. resolved=false — тільки активні, resolved=true — всі."""
    result = await db.execute(
        select(MarketAnomaly)
        .where(MarketAnomaly.resolved == resolved)
        .order_by(desc(MarketAnomaly.detected_at))
        .limit(limit)
    )
    anomalies = result.scalars().all()

    return [
        {
            "id": a.id,
            "detected_at": a.detected_at.isoformat() if a.detected_at else None,
            "hour": a.hour.isoformat(),
            "item_id": a.item_id,
            "team_id": a.team_id,
            "actual_volume": float(a.actual_volume),
            "expected_volume": float(a.expected_volume),
            "multiplier": float(a.multiplier),
            "resolved": a.resolved,
        }
        for a in anomalies
    ]


@router.post("/anomalies/{anomaly_id}/resolve", status_code=status.HTTP_200_OK)
async def resolve_anomaly(anomaly_id: int, db: AsyncSession = Depends(get_db)) -> Any:
    """Адмін позначає аномалію як вирішену після перевірки."""
    result = await db.execute(
        select(MarketAnomaly).where(MarketAnomaly.id == anomaly_id)
    )
    anomaly = result.scalars().first()
    if not anomaly:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Anomaly not found.")

    anomaly.resolved = True
    anomaly.resolved_at = datetime.utcnow()
    await db.commit()
    return {"resolved": anomaly_id}


@router.get("/top-sellers")
async def get_top_sellers(
    hours: int = 24,
    limit: int = 20,
    db: AsyncSession = Depends(get_db),
) -> Any:
    """Топ продавців за останні N годин по сумі транзакцій."""
    since = datetime.utcnow() - timedelta(hours=hours)

    result = await db.execute(
        select(
            MarketTransaction.seller_azuriom_id,
            func.sum(MarketTransaction.total_price).label("total_earned"),
            func.sum(MarketTransaction.quantity).label("total_items"),
            func.count(MarketTransaction.id).label("transaction_count"),
        )
        .where(
            MarketTransaction.created_at >= since,
            MarketTransaction.seller_azuriom_id.isnot(None),
            MarketTransaction.seller_paid == True,
        )
        .group_by(MarketTransaction.seller_azuriom_id)
        .order_by(desc("total_earned"))
        .limit(limit)
    )

    return [
        {
            "seller_azuriom_id": row.seller_azuriom_id,
            "total_earned": float(row.total_earned),
            "total_items": int(row.total_items),
            "transaction_count": int(row.transaction_count),
        }
        for row in result.all()
    ]


@router.get("/items")
async def get_item_stats(
    hours: int = 24,
    limit: int = 50,
    db: AsyncSession = Depends(get_db),
) -> Any:
    """Статистика по предметах за останні N годин — читає pre-aggregated таблицю."""
    since = datetime.utcnow().replace(minute=0, second=0, microsecond=0) - timedelta(hours=hours)

    result = await db.execute(
        select(
            MarketStatsHourly.item_id,
            func.sum(MarketStatsHourly.total_sold).label("total_sold"),
            func.sum(MarketStatsHourly.total_volume).label("total_volume"),
            func.sum(MarketStatsHourly.transaction_count).label("transaction_count"),
        )
        .where(MarketStatsHourly.hour >= since)
        .group_by(MarketStatsHourly.item_id)
        .order_by(desc("total_volume"))
        .limit(limit)
    )

    return [
        {
            "item_id": row.item_id,
            "total_sold": int(row.total_sold),
            "total_volume": float(row.total_volume),
            "transaction_count": int(row.transaction_count),
        }
        for row in result.all()
    ]
