from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.future import select
from sqlalchemy import func, delete, update
from uuid import UUID as pyUUID
from typing import Optional, List

# Correctly import IslandQueue and QueueItemStatusEnum from app.models.island
from app.models.island import IslandQueue, QueueItemStatusEnum 
# from app.schemas.island import IslandStatusEnum as MainIslandStatus # Not needed here

import logging
logger = logging.getLogger(__name__)

class CRUDMainIslandQueue: # Renamed class to reflect it's for the main IslandQueue
    async def add_to_queue(self, db_session: AsyncSession, *, player_uuid: pyUUID, player_name: Optional[str] = None) -> IslandQueue:
        """
        Adds a player to the island_queue.
        If player already in queue with PENDING, returns existing.
        If player in queue with e.g. PROCESSING, updates to PENDING and refreshes requested_at.
        """
        player_uuid_str = str(player_uuid)
        existing_entry = await self.get_by_player_uuid(db_session, player_uuid=player_uuid)

        if existing_entry:
            if existing_entry.status == QueueItemStatusEnum.PENDING:
                logger.info(f"Player {player_uuid_str} already in queue with PENDING status.")
                return existing_entry
            else:  # e.g., was PROCESSING and failed, or some other state, re-queue as PENDING
                stmt = (
                    update(IslandQueue)
                    .where(IslandQueue.player_uuid == player_uuid_str)
                    .values(status=QueueItemStatusEnum.PENDING, requested_at=func.now(), player_name=player_name)
                )
                await db_session.execute(stmt)
                await db_session.commit()
                updated_entry = await self.get_by_player_uuid(db_session, player_uuid=player_uuid)
                logger.info(f"Re-queued player {player_uuid_str} with status PENDING.")
                return updated_entry

        # If no existing entry, or if update somehow failed to return an entry
        new_queue_item = IslandQueue(
            player_uuid=player_uuid_str,
            player_name=player_name,  # This should match the column name in your model
            status=QueueItemStatusEnum.PENDING
            # requested_at is server_default
        )
        db_session.add(new_queue_item)
        await db_session.commit()
        await db_session.refresh(new_queue_item)
        logger.info(f"Added player {player_uuid_str} ({player_name}) to island_queue.")
        return new_queue_item

    async def get_next_in_queue(self, db_session: AsyncSession) -> Optional[IslandQueue]:
        """
        Gets the next player in the queue (oldest PENDING request).
        """
        result = await db_session.execute(
            select(IslandQueue)
            .filter(IslandQueue.status == QueueItemStatusEnum.PENDING)
            .order_by(IslandQueue.requested_at.asc())
            .limit(1)
        )
        return result.scalars().first()

    async def remove_from_queue(self, db_session: AsyncSession, *, player_uuid: pyUUID) -> bool:
        """
        Removes a player from the queue by player_uuid.
        Returns True if an item was deleted, False otherwise.
        """
        stmt = (
            delete(IslandQueue)
            .where(IslandQueue.player_uuid == str(player_uuid))
        )
        result = await db_session.execute(stmt)
        await db_session.commit()
        deleted_count = result.rowcount
        if deleted_count > 0:
            logger.info(f"Removed player {str(player_uuid)} from island_queue.")
        else:
            logger.info(f"Player {str(player_uuid)} not found in island_queue for removal or already removed.")
        return deleted_count > 0

    async def update_queue_item_status(self, db_session: AsyncSession, *, player_uuid: pyUUID, status: QueueItemStatusEnum) -> Optional[IslandQueue]:
        """
        Updates the status of a specific queue item by player_uuid.
        """
        player_uuid_str = str(player_uuid)
        stmt = (
            update(IslandQueue)
            .where(IslandQueue.player_uuid == player_uuid_str)
            .values(status=status)
        )
        await db_session.execute(stmt)
        await db_session.commit()
        
        updated_item = await self.get_by_player_uuid(db_session, player_uuid=player_uuid)
        
        if updated_item:
            logger.info(f"Updated island_queue status for player {player_uuid_str} to {status.value}.")
            return updated_item
        else:
            logger.warning(f"Could not find player {player_uuid_str} in island_queue to update status to {status.value}.")
            return None

    async def get_queue_size(self, db_session: AsyncSession, status: Optional[QueueItemStatusEnum] = None) -> int:
        """
        Gets the current size of the queue. Can be filtered by status.
        """
        query = select(func.count(IslandQueue.id)) # Use IslandQueue.id or any other PK
        if status:
            query = query.where(IslandQueue.status == status)
        
        count_result = await db_session.execute(query)
        count = count_result.scalar_one_or_none()
        return count if count is not None else 0

    async def get_by_player_uuid(self, db_session: AsyncSession, *, player_uuid: pyUUID) -> Optional[IslandQueue]:
        """
        Gets a queue entry by player UUID.
        """
        result = await db_session.execute(
            select(IslandQueue).filter(IslandQueue.player_uuid == str(player_uuid))
        )
        return result.scalars().first()

    async def get_all_pending(self, db_session: AsyncSession, limit: int = 100) -> List[IslandQueue]:
        """Gets all PENDING items in the queue, ordered by request time."""
        result = await db_session.execute(
            select(IslandQueue)
            .filter(IslandQueue.status == QueueItemStatusEnum.PENDING)
            .order_by(IslandQueue.requested_at.asc())
            .limit(limit)
        )
        return result.scalars().all()

crud_main_island_queue = CRUDMainIslandQueue()
