from typing import List
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import delete
from app.models.market import MarketItem
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

crud_market = CRUDMarketItem()
