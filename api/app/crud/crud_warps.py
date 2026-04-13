from sqlalchemy import select, delete
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.market import WarpPendingCommand


class CRUDWarps:

    async def create_pending(
        self,
        db: AsyncSession,
        player_uuid: str,
        command: str,
    ) -> WarpPendingCommand:
        obj = WarpPendingCommand(player_uuid=player_uuid, command=command)
        db.add(obj)
        await db.commit()
        await db.refresh(obj)
        return obj

    async def get_all_pending(self, db: AsyncSession) -> list[WarpPendingCommand]:
        result = await db.execute(select(WarpPendingCommand).order_by(WarpPendingCommand.id))
        return list(result.scalars().all())

    async def confirm(self, db: AsyncSession, pending_id: int) -> bool:
        result = await db.execute(
            delete(WarpPendingCommand).where(WarpPendingCommand.id == pending_id)
        )
        await db.commit()
        return result.rowcount > 0


crud_warps = CRUDWarps()
