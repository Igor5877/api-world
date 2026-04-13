from typing import List
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import delete, select
from app.models.market import MarketItem, MarketPendingExtraction, MarketTransaction
from app.schemas.market import MarketItemCreate
import logging
from sqlalchemy.dialects.mysql import insert as mysql_insert

logger = logging.getLogger(__name__)

class CRUDMarketItem:
    async def sync_island_inventory(self, db_session: AsyncSession, island_uuid: str, items: List[MarketItemCreate]) -> None:
        """
        Syncs the AE2 inventory using UPSERT — стабільні ID, без зростання AUTO_INCREMENT.
        - Існуючі предмети оновлюються (quantity, price, seller_azuriom_id тощо)
        - Нові вставляються
        - Ті що зникли з AE2 — видаляються
        """
        try:
            incoming_keys = {(item.item_id, item.item_nbt) for item in items}

            # 1. UPSERT: вставити або оновити кожен предмет
            if items:
                for item in items:
                    stmt = mysql_insert(MarketItem).values(**item.model_dump())
                    stmt = stmt.on_duplicate_key_update(
                        quantity=stmt.inserted.quantity,
                        price=stmt.inserted.price,
                        is_for_sale=stmt.inserted.is_for_sale,
                        seller_azuriom_id=stmt.inserted.seller_azuriom_id,
                        version=stmt.inserted.version,
                    )
                    await db_session.execute(stmt)

            # 2. Видалити предмети яких більше нема в AE2
            existing_result = await db_session.execute(
                select(MarketItem).where(MarketItem.island_uuid == island_uuid)
            )
            for row in existing_result.scalars().all():
                if (row.item_id, row.item_nbt) not in incoming_keys:
                    await db_session.delete(row)

            await db_session.commit()
            logger.debug(f"Synced {len(items)} items for island {island_uuid} via UPSERT")
        except Exception as e:
            await db_session.rollback()
            logger.error(f"Error syncing market inventory for island {island_uuid}: {e}", exc_info=True)
            raise e

    async def get_island_inventory(self, db_session: AsyncSession, island_uuid: str) -> List[MarketItem]:
        """
        Retrieves the current market inventory for a specific island.
        """
        from sqlalchemy import select
        result = await db_session.execute(
            select(MarketItem).where(MarketItem.island_uuid == island_uuid)
        )
        return result.scalars().all()

    async def purchase_item(
        self,
        db_session: AsyncSession,
        island_uuid: str,
        item_id: str,
        quantity: int,
        buyer_azuriom_id: int = None,
    ) -> dict:
        """
        Deducts quantity after a purchase. Returns the result.
        Raises ValueError if item not found or not enough stock.
        """
        from sqlalchemy import select
        result = await db_session.execute(
            select(MarketItem).where(
                MarketItem.island_uuid == island_uuid,
                MarketItem.item_id == item_id,
                MarketItem.is_for_sale == True,
            )
        )
        item = result.scalars().first()

        if item is None:
            raise ValueError(f"Item '{item_id}' not found or not for sale.")
        if item.quantity < quantity:
            raise ValueError(f"Not enough stock. Available: {item.quantity}, requested: {quantity}.")

        unit_price = item.price
        seller_azuriom_id = item.seller_azuriom_id

        item.quantity -= quantity
        if item.quantity <= 0:
            await db_session.delete(item)

        # Записуємо в історію транзакцій
        tx = MarketTransaction(
            island_uuid=island_uuid,
            item_id=item_id,
            quantity=quantity,
            unit_price=unit_price,
            total_price=unit_price * quantity,
            buyer_azuriom_id=buyer_azuriom_id,
            seller_azuriom_id=seller_azuriom_id,
        )
        db_session.add(tx)

        await db_session.commit()
        return {"item_id": item_id, "purchased": quantity, "remaining": max(0, item.quantity - quantity)}

    # ── Transactions ─────────────────────────────────────────────────────

    async def get_island_transactions(
        self, db_session: AsyncSession, island_uuid: str
    ) -> List[MarketTransaction]:
        result = await db_session.execute(
            select(MarketTransaction)
            .where(MarketTransaction.island_uuid == island_uuid)
            .order_by(MarketTransaction.created_at.desc())
            .limit(100)
        )
        return result.scalars().all()

    # ── Pending extractions ───────────────────────────────────────────────

    async def create_pending_extraction(
        self, db_session: AsyncSession, island_uuid: str, item_id: str, quantity: int
    ) -> MarketPendingExtraction:
        record = MarketPendingExtraction(
            island_uuid=island_uuid,
            item_id=item_id,
            quantity=quantity,
        )
        db_session.add(record)
        await db_session.commit()
        await db_session.refresh(record)
        return record

    async def get_pending_extractions(
        self, db_session: AsyncSession, island_uuid: str
    ) -> List[MarketPendingExtraction]:
        result = await db_session.execute(
            select(MarketPendingExtraction).where(MarketPendingExtraction.island_uuid == island_uuid)
        )
        return result.scalars().all()

    async def confirm_extraction(
        self, db_session: AsyncSession, pending_id: int, island_uuid: str
    ) -> bool:
        result = await db_session.execute(
            select(MarketPendingExtraction).where(
                MarketPendingExtraction.id == pending_id,
                MarketPendingExtraction.island_uuid == island_uuid,
            )
        )
        record = result.scalars().first()
        if record is None:
            return False
        await db_session.delete(record)
        await db_session.commit()
        return True


crud_market = CRUDMarketItem()
