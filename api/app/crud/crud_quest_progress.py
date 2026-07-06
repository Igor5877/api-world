from typing import Optional
import logging

from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.future import select

from app.models.island import IslandQuestProgress

logger = logging.getLogger(__name__)


class CRUDQuestProgress:
    """CRUD operations for island quest progress snapshots."""

    async def get_by_owner_uuid(self, db_session: AsyncSession, *, owner_uuid: str) -> Optional[IslandQuestProgress]:
        """Gets the latest quest progress snapshot for an island owner.

        Args:
            db_session: The database session.
            owner_uuid: The island identity UUID.

        Returns:
            The snapshot, or None if the island never uploaded one.
        """
        result = await db_session.execute(
            select(IslandQuestProgress).where(IslandQuestProgress.owner_uuid == str(owner_uuid))
        )
        return result.scalars().first()

    async def upsert(self, db_session: AsyncSession, *, island_id: int, owner_uuid: str,
                     snbt: str) -> IslandQuestProgress:
        """Creates or replaces the quest progress snapshot for an island.

        Args:
            db_session: The database session.
            island_id: The island's DB id.
            owner_uuid: The island identity UUID.
            snbt: The raw SNBT file content.

        Returns:
            The stored snapshot.
        """
        existing = await self.get_by_owner_uuid(db_session, owner_uuid=owner_uuid)
        if existing:
            existing.snbt = snbt
            db_session.add(existing)
            await db_session.commit()
            await db_session.refresh(existing)
            return existing

        snapshot = IslandQuestProgress(island_id=island_id, owner_uuid=str(owner_uuid), snbt=snbt)
        db_session.add(snapshot)
        await db_session.commit()
        await db_session.refresh(snapshot)
        return snapshot


crud_quest_progress = CRUDQuestProgress()
