"""CRUD for the island incident journal (island_events)."""
from datetime import datetime, timedelta
from typing import List, Optional

from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.future import select

from app.models.island import IslandEvent as IslandEventModel


class CRUDIslandEvent:
    """CRUD operations for island lifecycle events."""

    async def add(self, db_session: AsyncSession, *, island_id: int,
                  event_type: str, details: Optional[str] = None) -> IslandEventModel:
        """Records one event for an island."""
        event = IslandEventModel(island_id=island_id, event_type=event_type,
                                 details=(details or "")[:1024] or None)
        db_session.add(event)
        await db_session.commit()
        await db_session.refresh(event)
        return event

    async def list_for_island(self, db_session: AsyncSession, *, island_id: int,
                              limit: int = 50) -> List[IslandEventModel]:
        """Lists the newest events of an island."""
        result = await db_session.execute(
            select(IslandEventModel)
            .filter(IslandEventModel.island_id == island_id)
            .order_by(IslandEventModel.created_at.desc(), IslandEventModel.id.desc())
            .limit(limit)
        )
        return list(result.scalars().all())

    async def has_recent_event(self, db_session: AsyncSession, *, island_id: int,
                               event_type: str, within_seconds: int) -> bool:
        """Checks whether an event of the given type happened recently.

        Used by the watchdog to tell a clean self-initiated stop ("stopping"
        was signalled) apart from a hang.
        """
        cutoff = datetime.utcnow() - timedelta(seconds=within_seconds)
        result = await db_session.execute(
            select(IslandEventModel.id)
            .filter(IslandEventModel.island_id == island_id,
                    IslandEventModel.event_type == event_type,
                    IslandEventModel.created_at >= cutoff)
            .limit(1)
        )
        return result.scalars().first() is not None


crud_island_event = CRUDIslandEvent()
