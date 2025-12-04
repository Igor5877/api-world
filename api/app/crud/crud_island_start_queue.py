from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.future import select
from sqlalchemy import func, delete, update
from typing import Optional

from app.models.island_start_queue import IslandStartQueue
from app.models.island import QueueItemStatusEnum

import logging
logger = logging.getLogger(__name__)

class CRUDIslandStartQueue:
    """CRUD operations for the island start queue."""
    async def add_to_queue(self, db_session: AsyncSession, *, player_uuid: str, player_name: Optional[str] = None) -> IslandStartQueue:
        """Adds a player to the island_start_queue.

        If the player is already in the queue with a PENDING status, it returns the
        existing entry. If the player is in the queue with a different status, it
        updates the status to PENDING and refreshes the requested_at timestamp.

        Args:
            db_session: The database session.
            player_uuid: The UUID of the player.
            player_name: The name of the player.

        Returns:
            The created or updated queue item.
        """
        existing_entry = await self.get_by_player_uuid(db_session, player_uuid=player_uuid)

        if existing_entry:
            if existing_entry.status == QueueItemStatusEnum.PENDING:
                logger.info(f"Player {player_uuid} already in start queue with PENDING status.")
                return existing_entry
            else:
                stmt = (
                    update(IslandStartQueue)
                    .where(IslandStartQueue.player_uuid == player_uuid)
                    .values(status=QueueItemStatusEnum.PENDING, requested_at=func.now(), player_name=player_name)
                )
                await db_session.execute(stmt)
                await db_session.commit()
                updated_entry = await self.get_by_player_uuid(db_session, player_uuid=player_uuid)
                logger.info(f"Re-queued player {player_uuid} for start with status PENDING.")
                return updated_entry

        new_queue_item = IslandStartQueue(
            player_uuid=player_uuid,
            player_name=player_name,
            status=QueueItemStatusEnum.PENDING
        )
        db_session.add(new_queue_item)
        await db_session.commit()
        await db_session.refresh(new_queue_item)
        logger.info(f"Added player {player_uuid} ({player_name}) to island_start_queue.")
        return new_queue_item

    async def get_next_in_queue(self, db_session: AsyncSession) -> Optional[IslandStartQueue]:
        """Gets the next player in the queue.

        This returns the oldest PENDING request.

        Args:
            db_session: The database session.

        Returns:
            The next queue item, or None if the queue is empty.
        """
        result = await db_session.execute(
            select(IslandStartQueue)
            .filter(IslandStartQueue.status == QueueItemStatusEnum.PENDING)
            .order_by(IslandStartQueue.requested_at.asc())
            .limit(1)
        )
        return result.scalars().first()

    async def remove_from_queue(self, db_session: AsyncSession, *, player_uuid: str) -> bool:
        """Removes a player from the queue by player_uuid.

        Args:
            db_session: The database session.
            player_uuid: The UUID of the player to remove.

        Returns:
            True if an item was deleted, False otherwise.
        """
        stmt = (
            delete(IslandStartQueue)
            .where(IslandStartQueue.player_uuid == player_uuid)
        )
        result = await db_session.execute(stmt)
        await db_session.commit()
        deleted_count = result.rowcount
        if deleted_count > 0:
            logger.info(f"Removed player {player_uuid} from island_start_queue.")
        else:
            logger.info(f"Player {player_uuid} not found in island_start_queue for removal or already removed.")
        return deleted_count > 0

    async def update_queue_item_status(self, db_session: AsyncSession, *, player_uuid: str, status: QueueItemStatusEnum) -> Optional[IslandStartQueue]:
        """Updates the status of a specific queue item by player_uuid.

        Args:
            db_session: The database session.
            player_uuid: The UUID of the player.
            status: The new status.

        Returns:
            The updated queue item, or None if not found.
        """
        stmt = (
            update(IslandStartQueue)
            .where(IslandStartQueue.player_uuid == player_uuid)
            .values(status=status)
        )
        await db_session.execute(stmt)
        await db_session.commit()

        updated_item = await self.get_by_player_uuid(db_session, player_uuid=player_uuid)

        if updated_item:
            logger.info(f"Updated island_start_queue status for player {player_uuid} to {status.value}.")
            return updated_item
        else:
            logger.warning(f"Could not find player {player_uuid} in island_start_queue to update status to {status.value}.")
            return None

    async def get_by_player_uuid(self, db_session: AsyncSession, *, player_uuid: str) -> Optional[IslandStartQueue]:
        """Gets a queue entry by player UUID.

        Args:
            db_session: The database session.
            player_uuid: The UUID of the player.

        Returns:
            The queue item, or None if not found.
        """
        result = await db_session.execute(
            select(IslandStartQueue).filter(IslandStartQueue.player_uuid == player_uuid)
        )
        return result.scalars().first()

crud_island_start_queue = CRUDIslandStartQueue()
