from typing import List, Optional
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.future import select
from sqlalchemy import update as sqlalchemy_update
from datetime import datetime

from app.models.island import Island as IslandModel
from app.models.island import UpdateQueue as UpdateQueueModel

class CRUDUpdateQueue:
    async def add_island_to_queue(self, db_session: AsyncSession, *, island: IslandModel) -> UpdateQueueModel:
        existing_entry = await self.get_by_island_id(db_session, island_id=island.id)
        if existing_entry and existing_entry.status in ['PENDING', 'PROCESSING']:
            raise ValueError("Island is already in the update queue.")

        if existing_entry:
            stmt = (
                sqlalchemy_update(UpdateQueueModel)
                .where(UpdateQueueModel.island_id == island.id)
                .values(status='PENDING', added_to_queue_at=datetime.utcnow(), processing_started_at=None, completed_at=None, retry_count=0, error_message=None)
            )
            await db_session.execute(stmt)
            await db_session.commit()
            return await self.get_by_island_id(db_session, island_id=island.id)
        else:
            new_entry = UpdateQueueModel(island_id=island.id, player_uuid=str(island.player_uuid))
            db_session.add(new_entry)
            await db_session.commit()
            await db_session.refresh(new_entry)
            return new_entry

    async def get_by_island_id(self, db_session: AsyncSession, *, island_id: int) -> Optional[UpdateQueueModel]:
        result = await db_session.execute(select(UpdateQueueModel).filter(UpdateQueueModel.island_id == island_id))
        return result.scalars().first()

    async def get_next_pending_island(self, db_session: AsyncSession) -> Optional[UpdateQueueModel]:
        result = await db_session.execute(select(UpdateQueueModel).filter(UpdateQueueModel.status == 'PENDING').order_by(UpdateQueueModel.added_to_queue_at).limit(1))
        return result.scalars().first()

    async def set_status_processing(self, db_session: AsyncSession, *, queue_entry_id: int) -> UpdateQueueModel:
        stmt = (
            sqlalchemy_update(UpdateQueueModel)
            .where(UpdateQueueModel.id == queue_entry_id)
            .values(status='PROCESSING', processing_started_at=datetime.utcnow())
        )
        await db_session.execute(stmt)
        await db_session.commit()
        # Fetch the updated object separately, as MySQL doesn't support RETURNING
        updated_entry = await db_session.get(UpdateQueueModel, queue_entry_id)
        return updated_entry

    async def set_status_completed(self, db_session: AsyncSession, *, queue_entry_id: int) -> UpdateQueueModel:
        stmt = (
            sqlalchemy_update(UpdateQueueModel)
            .where(UpdateQueueModel.id == queue_entry_id)
            .values(status='COMPLETED', completed_at=datetime.utcnow())
        )
        await db_session.execute(stmt)
        await db_session.commit()
        updated_entry = await db_session.get(UpdateQueueModel, queue_entry_id)
        return updated_entry

    async def set_status_failed(self, db_session: AsyncSession, *, queue_entry_id: int, error_message: str, retry_count: int) -> UpdateQueueModel:
        stmt = (
            sqlalchemy_update(UpdateQueueModel)
            .where(UpdateQueueModel.id == queue_entry_id)
            .values(status='FAILED', error_message=error_message, retry_count=retry_count)
        )
        await db_session.execute(stmt)
        await db_session.commit()
        updated_entry = await db_session.get(UpdateQueueModel, queue_entry_id)
        return updated_entry

crud_update_queue = CRUDUpdateQueue()
