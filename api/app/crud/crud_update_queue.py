from typing import List, Optional
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.future import select
from sqlalchemy import update as sqlalchemy_update
from datetime import datetime

from app.db.base import UpdateQueue as UpdateQueueModel, Island as IslandModel


class CRUDUpdateQueue:
    """CRUD operations for the update queue."""
    async def add_island_to_queue(self, db_session: AsyncSession, *, island: IslandModel) -> UpdateQueueModel:
        """Adds an island to the update queue.

        If the island is already in the queue with a 'PENDING' or 'PROCESSING'
        status, a ValueError is raised. If a 'FAILED' entry exists, it is
        reset. Otherwise, a new entry is created.

        Args:
            db_session: The database session.
            island: The island to add to the queue.

        Returns:
            The created or updated queue entry.

        Raises:
            ValueError: If the island is already in the queue.
        """
        # Check if already in queue
        existing_entry = await self.get_by_island_id(db_session, island_id=island.id)
        if existing_entry and existing_entry.status in ['PENDING', 'PROCESSING']:
            raise ValueError("Island is already in the update queue.")

        # If a FAILED entry exists, reset it. Otherwise, create a new one.
        if existing_entry:
            stmt = (
                sqlalchemy_update(UpdateQueueModel)
                .where(UpdateQueueModel.island_id == island.id)
                .values(
                    status='PENDING',
                    added_to_queue_at=datetime.utcnow(),
                    processing_started_at=None,
                    completed_at=None,
                    retry_count=0,
                    error_message=None
                )
            )
            await db_session.execute(stmt)
            await db_session.commit()
            # Re-fetch to return the updated object
            return await self.get_by_island_id(db_session, island_id=island.id)
        else:
            new_entry = UpdateQueueModel(
                island_id=island.id,
                player_uuid=str(island.player_uuid)
            )
            db_session.add(new_entry)
            await db_session.commit()
            await db_session.refresh(new_entry)
            return new_entry

    async def get_by_island_id(self, db_session: AsyncSession, *, island_id: int) -> Optional[UpdateQueueModel]:
        """Gets a queue entry by island ID.

        Args:
            db_session: The database session.
            island_id: The ID of the island.

        Returns:
            The queue entry, or None if not found.
        """
        result = await db_session.execute(
            select(UpdateQueueModel).filter(UpdateQueueModel.island_id == island_id)
        )
        return result.scalars().first()

    async def get_next_pending_island(self, db_session: AsyncSession) -> Optional[UpdateQueueModel]:
        """Gets the next pending island in the queue.

        Args:
            db_session: The database session.

        Returns:
            The next pending island, or None if not found.
        """
        result = await db_session.execute(
            select(UpdateQueueModel)
            .filter(UpdateQueueModel.status == 'PENDING')
            .order_by(UpdateQueueModel.added_to_queue_at)
            .limit(1)
        )
        return result.scalars().first()

    async def get_all_pending(self, db_session: AsyncSession) -> List[UpdateQueueModel]:
        """Gets all pending items in the queue.

        Args:
            db_session: The database session.

        Returns:
            A list of all pending items in the queue.
        """
        result = await db_session.execute(
            select(UpdateQueueModel).filter(UpdateQueueModel.status == 'PENDING')
        )
        return result.scalars().all()

    async def set_status_processing(self, db_session: AsyncSession, *, queue_entry_id: int) -> UpdateQueueModel:
        """Sets the status of a queue entry to 'PROCESSING'.

        Args:
            db_session: The database session.
            queue_entry_id: The ID of the queue entry.

        Returns:
            The updated queue entry.
        """
        stmt = (
            sqlalchemy_update(UpdateQueueModel)
            .where(UpdateQueueModel.id == queue_entry_id)
            .values(status='PROCESSING', processing_started_at=datetime.utcnow())
            .returning(UpdateQueueModel)
        )
        result = await db_session.execute(stmt)
        await db_session.commit()
        return result.scalar_one()

    async def set_status_completed(self, db_session: AsyncSession, *, queue_entry_id: int) -> UpdateQueueModel:
        """Sets the status of a queue entry to 'COMPLETED'.

        Args:
            db_session: The database session.
            queue_entry_id: The ID of the queue entry.

        Returns:
            The updated queue entry.
        """
        stmt = (
            sqlalchemy_update(UpdateQueueModel)
            .where(UpdateQueueModel.id == queue_entry_id)
            .values(status='COMPLETED', completed_at=datetime.utcnow())
            .returning(UpdateQueueModel)
        )
        result = await db_session.execute(stmt)
        await db_session.commit()
        return result.scalar_one()

    async def set_status_failed(
        self, db_session: AsyncSession, *, queue_entry_id: int, error_message: str, retry_count: int
    ) -> UpdateQueueModel:
        """Sets the status of a queue entry to 'FAILED'.

        Args:
            db_session: The database session.
            queue_entry_id: The ID of the queue entry.
            error_message: The error message.
            retry_count: The number of retries.

        Returns:
            The updated queue entry.
        """
        stmt = (
            sqlalchemy_update(UpdateQueueModel)
            .where(UpdateQueueModel.id == queue_entry_id)
            .values(status='FAILED', error_message=error_message, retry_count=retry_count)
            .returning(UpdateQueueModel)
        )
        result = await db_session.execute(stmt)
        await db_session.commit()
        return result.scalar_one()

crud_update_queue = CRUDUpdateQueue()
