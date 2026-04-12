from typing import List
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import delete, select
from app.models.market import MarketItem, MarketPendingExtraction
from app.schemas.market import MarketItemCreate
import logging

logger = logging.getLogger(__name__)

class CRUDMarketItem:
    async def sync_island_inventory(self, db_session: AsyncSession, island_uuid: str, items: List[MarketItemCreate]) -> None:
        """
        Replaces the entire market inventory for a specific island with the new synced data.
        Since AE2 inventories change completely every few seconds, the most efficient 
        approach is to delete the old records for that island and insert the fresh ones.
        """
        try:
            # 1. Delete old items for this island
            await db_session.execute(
                delete(MarketItem).where(MarketItem.island_uuid == island_uuid)
            )

            # 2. Insert new items if any exist
            if items:
                # Convert pydantic models to dicts suitable for bulk insert
                items_data = [item.model_dump() for item in items]
                
                # Bulk insert operations are much faster than add_all() for many rows
                db_session.add_all([MarketItem(**data) for data in items_data])
            
            # 3. Commit the transaction
            await db_session.commit()
            logger.debug(f"Successfully synced {len(items)} market items for island {island_uuid}")
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

        item.quantity -= quantity
        if item.quantity <= 0:
            await db_session.delete(item)
        await db_session.commit()
        return {"item_id": item_id, "purchased": quantity, "remaining": max(0, item.quantity - quantity)}

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
