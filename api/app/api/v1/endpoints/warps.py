import logging
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.session import get_db_session as get_db
from app.crud.crud_warps import crud_warps
from app.models.island import Island
from app.services.websocket_manager import manager as websocket_manager

router = APIRouter()
logger = logging.getLogger(__name__)

HUB_CLIENT_ID = "spawn_hub"


async def _enqueue(player_uuid: str, command: str, db: AsyncSession):
    """Зберігає команду в БД і намагається надіслати через WS якщо hub онлайн."""
    pending = await crud_warps.create_pending(db, player_uuid, command)
    await websocket_manager.send_personal_message(
        {"type": command, "uuid": player_uuid, "pending_id": pending.id},
        HUB_CLIENT_ID,
    )
    logger.info(f"[Warps] {command} queued (id={pending.id}) for {player_uuid}")
    return pending


@router.post("/{player_uuid}/create", status_code=status.HTTP_200_OK)
async def warp_create(player_uuid: str, db: AsyncSession = Depends(get_db)):
    """Створити варп-платформу для гравця."""
    pending = await _enqueue(player_uuid, "island_create", db)
    return {"queued": True, "pending_id": pending.id}


@router.post("/{player_uuid}/suspend", status_code=status.HTTP_200_OK)
async def warp_suspend(player_uuid: str, db: AsyncSession = Depends(get_db)):
    """Зберегти і приховати платформу (підписка призупинена)."""
    pending = await _enqueue(player_uuid, "island_suspend", db)
    return {"queued": True, "pending_id": pending.id}


@router.post("/{player_uuid}/restore", status_code=status.HTTP_200_OK)
async def warp_restore(player_uuid: str, db: AsyncSession = Depends(get_db)):
    """Відновити платформу (підписка поновлена)."""
    pending = await _enqueue(player_uuid, "island_restore", db)
    return {"queued": True, "pending_id": pending.id}


@router.post("/{player_uuid}/delete", status_code=status.HTTP_200_OK)
async def warp_delete(player_uuid: str, db: AsyncSession = Depends(get_db)):
    """Остаточно видалити збережені дані платформи."""
    pending = await _enqueue(player_uuid, "island_delete", db)
    return {"queued": True, "pending_id": pending.id}


@router.post("/confirm/{pending_id}", status_code=status.HTTP_200_OK)
async def warp_confirm(pending_id: int, db: AsyncSession = Depends(get_db)):
    """Мод викликає після виконання команди — видаляє запис з черги."""
    ok = await crud_warps.confirm(db, pending_id)
    if not ok:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Pending command not found.")
    return {"confirmed": pending_id}


@router.get("/status", status_code=status.HTTP_200_OK)
async def warp_hub_status(db: AsyncSession = Depends(get_db)):
    """Стан spawn_hub і кількість команд в черзі."""
    pending = await crud_warps.get_all_pending(db)
    return {
        "spawn_hub_online": HUB_CLIENT_ID in websocket_manager.active_connections,
        "pending_count": len(pending),
    }


@router.get("/player-uuid/{player_name}", status_code=status.HTTP_200_OK)
async def get_uuid_by_name(player_name: str, db: AsyncSession = Depends(get_db)):
    """Повертає UUID гравця за його ніком (шукає в таблиці islands)."""
    result = await db.execute(
        select(Island.player_uuid).where(Island.player_name == player_name).limit(1)
    )
    player_uuid = result.scalar_one_or_none()
    if not player_uuid:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND,
                            detail=f"Player '{player_name}' not found.")
    return {"player_name": player_name, "player_uuid": player_uuid}
