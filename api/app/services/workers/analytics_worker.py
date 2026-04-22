import asyncio
import logging
from datetime import datetime, timedelta

from sqlalchemy import select, func, text
from sqlalchemy.dialects.mysql import insert as mysql_insert

from app.db.session import AsyncSessionLocal
from app.models.market import MarketTransaction
from app.models.analytics import MarketStatsHourly, EconomySnapshot, MarketAnomaly

logger = logging.getLogger(__name__)

# Аномалія = продажі > ANOMALY_THRESHOLD разів від середнього за 7 днів
ANOMALY_THRESHOLD = 3.0
AGGREGATE_INTERVAL = 300  # секунд між запусками (5 хвилин)


async def _aggregate_hour(hour: datetime) -> None:
    """Агрегує транзакції за вказану годину в market_stats_hourly та economy_snapshots."""
    next_hour = hour + timedelta(hours=1)

    async with AsyncSessionLocal() as db:
        try:
            # ── Агрегація по item/team ─────────────────────────────────────────
            rows_result = await db.execute(
                select(
                    MarketTransaction.item_id,
                    MarketTransaction.team_id,
                    func.sum(MarketTransaction.quantity).label("total_sold"),
                    func.sum(MarketTransaction.total_price).label("total_volume"),
                    func.count(MarketTransaction.id).label("transaction_count"),
                )
                .where(
                    MarketTransaction.created_at >= hour,
                    MarketTransaction.created_at < next_hour,
                    MarketTransaction.seller_paid == True,
                )
                .group_by(MarketTransaction.item_id, MarketTransaction.team_id)
            )
            rows = rows_result.all()

            for row in rows:
                stmt = mysql_insert(MarketStatsHourly).values(
                    hour=hour,
                    item_id=row.item_id,
                    team_id=row.team_id,
                    total_sold=int(row.total_sold),
                    total_volume=row.total_volume,
                    transaction_count=int(row.transaction_count),
                )
                stmt = stmt.on_duplicate_key_update(
                    total_sold=stmt.inserted.total_sold,
                    total_volume=stmt.inserted.total_volume,
                    transaction_count=stmt.inserted.transaction_count,
                )
                await db.execute(stmt)

            # ── Economy snapshot ───────────────────────────────────────────────
            totals_result = await db.execute(
                select(
                    func.coalesce(func.sum(MarketTransaction.total_price), 0).label("volume"),
                    func.count(MarketTransaction.id).label("count"),
                    func.count(func.distinct(MarketTransaction.seller_azuriom_id)).label("sellers"),
                )
                .where(
                    MarketTransaction.created_at >= hour,
                    MarketTransaction.created_at < next_hour,
                )
            )
            totals = totals_result.first()

            snap_stmt = mysql_insert(EconomySnapshot).values(
                hour=hour,
                total_volume=totals.volume,
                total_commission=0,   # заповниться коли buy_point система буде готова
                total_transactions=totals.count,
                active_sellers=totals.sellers,
            )
            snap_stmt = snap_stmt.on_duplicate_key_update(
                total_volume=snap_stmt.inserted.total_volume,
                total_transactions=snap_stmt.inserted.total_transactions,
                active_sellers=snap_stmt.inserted.active_sellers,
            )
            await db.execute(snap_stmt)
            await db.commit()

            # ── Виявлення аномалій ─────────────────────────────────────────────
            await _detect_anomalies(db, hour, rows)

            logger.debug(f"Analytics: aggregated {len(rows)} item-groups for hour {hour}")

        except Exception as e:
            await db.rollback()
            logger.error(f"Analytics aggregation error for hour {hour}: {e}", exc_info=True)


async def _detect_anomalies(db, hour: datetime, current_rows) -> None:
    """Порівнює поточну годину з середнім за 7 днів та зберігає аномалії."""
    week_ago = hour - timedelta(days=7)
    hour_of_day = hour.hour

    for row in current_rows:
        try:
            avg_result = await db.execute(
                select(func.avg(MarketStatsHourly.total_volume))
                .where(
                    MarketStatsHourly.item_id == row.item_id,
                    MarketStatsHourly.team_id == row.team_id,
                    MarketStatsHourly.hour >= week_ago,
                    MarketStatsHourly.hour < hour,
                    func.hour(MarketStatsHourly.hour) == hour_of_day,
                )
            )
            avg = float(avg_result.scalar() or 0)

            if avg > 0:
                multiplier = float(row.total_volume) / avg
                if multiplier >= ANOMALY_THRESHOLD:
                    # Перевіряємо чи вже є невирішена аномалія для цього предмета
                    existing = await db.execute(
                        select(MarketAnomaly.id).where(
                            MarketAnomaly.item_id == row.item_id,
                            MarketAnomaly.team_id == row.team_id,
                            MarketAnomaly.resolved == False,
                        )
                    )
                    if not existing.scalar():
                        db.add(MarketAnomaly(
                            hour=hour,
                            item_id=row.item_id,
                            team_id=row.team_id,
                            actual_volume=row.total_volume,
                            expected_volume=avg,
                            multiplier=round(multiplier, 2),
                        ))
                        logger.warning(
                            f"Anomaly detected: {row.item_id} team={row.team_id} "
                            f"volume={row.total_volume:.2f} ({multiplier:.1f}x avg)"
                        )
        except Exception as e:
            logger.error(f"Anomaly detection error for {row.item_id}: {e}")

    await db.commit()


async def start_analytics_worker() -> None:
    """Запускає фоновий воркер агрегації аналітики."""
    asyncio.create_task(_analytics_loop())
    logger.info("Analytics worker started.")


async def _analytics_loop() -> None:
    while True:
        await asyncio.sleep(AGGREGATE_INTERVAL)
        try:
            # Агрегуємо попередню повну годину
            now = datetime.utcnow()
            prev_hour = now.replace(minute=0, second=0, microsecond=0) - timedelta(hours=1)
            await _aggregate_hour(prev_hour)
        except Exception as e:
            logger.error(f"Analytics worker loop error: {e}", exc_info=True)
