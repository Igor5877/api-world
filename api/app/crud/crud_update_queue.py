from typing import List, Optional
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.future import select
from sqlalchemy import update as sqlalchemy_update, func
from datetime import datetime

from app.models.update import UpdateQueue as UpdateQueueModel, UpdateQueueStatusEnum
from app.models.island import Island as IslandModel


class CRUDUpdateQueue:
    """CRUD operations for the per-campaign island update queue."""

    async def add_island(self, db_session: AsyncSession, *, campaign_id: int, island: IslandModel) -> UpdateQueueModel:
        """Adds an island to a campaign's queue (idempotent per campaign).

        Args:
            db_session: The database session.
            campaign_id: The campaign the entry belongs to.
            island: The island to queue.

        Returns:
            The existing or newly created queue entry.
        """
        existing = await self.get_by_campaign_and_island(db_session, campaign_id=campaign_id, island_id=island.id)
        if existing:
            return existing
        entry = UpdateQueueModel(
            campaign_id=campaign_id,
            island_id=island.id,
            player_uuid=str(island.player_uuid) if island.player_uuid else None,
        )
        db_session.add(entry)
        await db_session.commit()
        await db_session.refresh(entry)
        return entry

    async def get_by_campaign_and_island(self, db_session: AsyncSession, *, campaign_id: int, island_id: int) -> Optional[UpdateQueueModel]:
        """Gets a queue entry by campaign and island."""
        result = await db_session.execute(
            select(UpdateQueueModel)
            .filter(UpdateQueueModel.campaign_id == campaign_id, UpdateQueueModel.island_id == island_id)
        )
        return result.scalars().first()

    async def get_next_pending(self, db_session: AsyncSession, *, campaign_id: int) -> Optional[UpdateQueueModel]:
        """Gets the oldest PENDING entry of a campaign."""
        result = await db_session.execute(
            select(UpdateQueueModel)
            .filter(UpdateQueueModel.campaign_id == campaign_id,
                    UpdateQueueModel.status == UpdateQueueStatusEnum.PENDING)
            .order_by(UpdateQueueModel.added_to_queue_at)
            .limit(1)
        )
        return result.scalars().first()

    async def get_by_statuses(self, db_session: AsyncSession, *, campaign_id: int,
                              statuses: List[UpdateQueueStatusEnum]) -> List[UpdateQueueModel]:
        """Gets all entries of a campaign in the given statuses."""
        result = await db_session.execute(
            select(UpdateQueueModel)
            .filter(UpdateQueueModel.campaign_id == campaign_id,
                    UpdateQueueModel.status.in_(statuses))
            .order_by(UpdateQueueModel.added_to_queue_at)
        )
        return list(result.scalars().all())

    async def get_waiting_for_island(self, db_session: AsyncSession, *, island_id: int) -> Optional[UpdateQueueModel]:
        """Gets a WAITING entry for an island (any active campaign)."""
        result = await db_session.execute(
            select(UpdateQueueModel)
            .filter(UpdateQueueModel.island_id == island_id,
                    UpdateQueueModel.status == UpdateQueueStatusEnum.WAITING)
            .limit(1)
        )
        return result.scalars().first()

    async def get_all_for_campaign(self, db_session: AsyncSession, *, campaign_id: int) -> List[UpdateQueueModel]:
        """Gets all entries of a campaign."""
        result = await db_session.execute(
            select(UpdateQueueModel)
            .filter(UpdateQueueModel.campaign_id == campaign_id)
            .order_by(UpdateQueueModel.added_to_queue_at)
        )
        return list(result.scalars().all())

    async def count_by_status(self, db_session: AsyncSession, *, campaign_id: int) -> dict:
        """Returns {status: count} for a campaign."""
        result = await db_session.execute(
            select(UpdateQueueModel.status, func.count())
            .filter(UpdateQueueModel.campaign_id == campaign_id)
            .group_by(UpdateQueueModel.status)
        )
        return {status.value: count for status, count in result.all()}

    async def set_status(self, db_session: AsyncSession, *, entry_id: int,
                         status: UpdateQueueStatusEnum, error_message: Optional[str] = None,
                         increment_retry: bool = False) -> None:
        """Updates the status (and bookkeeping timestamps) of a queue entry."""
        values: dict = {"status": status}
        if status == UpdateQueueStatusEnum.PROCESSING:
            values["processing_started_at"] = datetime.utcnow()
        if status in (UpdateQueueStatusEnum.COMPLETED, UpdateQueueStatusEnum.FAILED, UpdateQueueStatusEnum.SKIPPED):
            values["completed_at"] = datetime.utcnow()
        if error_message is not None:
            values["error_message"] = error_message
        stmt = (
            sqlalchemy_update(UpdateQueueModel)
            .where(UpdateQueueModel.id == entry_id)
            .values(**values)
        )
        if increment_retry:
            stmt = stmt.values(retry_count=UpdateQueueModel.retry_count + 1)
        await db_session.execute(stmt)
        await db_session.commit()

    async def defer_entry(self, db_session: AsyncSession, *, entry_id: int) -> None:
        """Pushes an entry to the back of the queue (island busy in a transient state)."""
        await db_session.execute(
            sqlalchemy_update(UpdateQueueModel)
            .where(UpdateQueueModel.id == entry_id)
            .values(added_to_queue_at=datetime.utcnow())
        )
        await db_session.commit()

    async def reset_stale_processing(self, db_session: AsyncSession) -> int:
        """Recovery on startup: entries stuck in PROCESSING (worker died mid-update)
        are returned to PENDING so the campaign can resume.

        Returns:
            The number of entries reset.
        """
        stmt = (
            sqlalchemy_update(UpdateQueueModel)
            .where(UpdateQueueModel.status == UpdateQueueStatusEnum.PROCESSING)
            .values(status=UpdateQueueStatusEnum.PENDING, processing_started_at=None)
        )
        result = await db_session.execute(stmt)
        await db_session.commit()
        return result.rowcount or 0

    async def requeue_failed(self, db_session: AsyncSession, *, campaign_id: int) -> int:
        """Returns all FAILED entries of a campaign to PENDING (manual admin retry)."""
        stmt = (
            sqlalchemy_update(UpdateQueueModel)
            .where(UpdateQueueModel.campaign_id == campaign_id,
                   UpdateQueueModel.status == UpdateQueueStatusEnum.FAILED)
            .values(status=UpdateQueueStatusEnum.PENDING, error_message=None)
        )
        result = await db_session.execute(stmt)
        await db_session.commit()
        return result.rowcount or 0


crud_update_queue = CRUDUpdateQueue()
