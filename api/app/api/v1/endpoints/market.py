import hashlib
import logging
from typing import Any, List

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.redis import get_redis_client
from app.core.azuriom_client import credit_seller, refund_buyer
from app.db.session import get_db_session as get_db
from app.crud.crud_market import crud_market
from app.crud.crud_team import get_team_by_player
from app.schemas.market import MarketItemSync, MarketItemInDB, PurchaseRequest, MarketTransactionInDB
from app.services.websocket_manager import manager as websocket_manager

router = APIRouter()
logger = logging.getLogger(__name__)

_HASH_TTL = 3600  # секунд — інвалідація кешу якщо острів довго офлайн


async def _resolve_team_id(island_uuid: str, db: AsyncSession) -> int:
    """Resolves player UUID to team_id. Raises 404 if player has no team."""
    team = await get_team_by_player(db, player_uuid=island_uuid)
    if not team:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No team found for player {island_uuid}."
        )
    return team.id


def _compute_inventory_hash(items) -> str:
    """SHA1 від відсортованого списку предметів. Однаковий інвентар = однаковий хеш."""
    key = "|".join(
        f"{i.item_id}:{i.item_nbt}:{i.quantity}:{i.price}:{i.is_for_sale}"
        for i in sorted(items, key=lambda x: (x.item_id, x.item_nbt))
    )
    return hashlib.sha1(key.encode()).hexdigest()


async def _get_cached_hash(team_id: int) -> str | None:
    """Читає хеш інвентаря з Redis (спільний між усіма Gunicorn workers)."""
    try:
        redis = get_redis_client()
        return await redis.get(f"market:hash:{team_id}")
    except Exception:
        return None  # якщо Redis недоступний — пропускаємо кеш, йдемо в БД


async def _set_cached_hash(team_id: int, hash_value: str) -> None:
    try:
        redis = get_redis_client()
        await redis.set(f"market:hash:{team_id}", hash_value, ex=_HASH_TTL)
    except Exception:
        pass  # Redis помилка не блокує основний флоу


async def _invalidate_cached_hash(team_id: int) -> None:
    """Скинути кеш хешу — викликати коли купівля змінила реальний стан."""
    try:
        redis = get_redis_client()
        await redis.delete(f"market:hash:{team_id}")
    except Exception:
        pass


@router.get("/islands/{island_uuid}/inventory", response_model=List[MarketItemInDB])
async def get_island_inventory(
    island_uuid: str,
    db: AsyncSession = Depends(get_db),
) -> Any:
    """Retrieves the current market inventory of an island (called by Hub server)."""
    team_id = await _resolve_team_id(island_uuid, db)
    return await crud_market.get_island_inventory(db_session=db, team_id=team_id)


@router.post("/islands/{island_uuid}/purchase", status_code=status.HTTP_200_OK)
async def purchase_item(
    island_uuid: str,
    payload: PurchaseRequest,
    db: AsyncSession = Depends(get_db),
) -> Any:
    """Called by Hub mod after a player successfully pays for an item."""
    team_id = await _resolve_team_id(island_uuid, db)
    try:
        result = await crud_market.purchase_item(
            db_session=db,
            team_id=team_id,
            item_id=payload.item_id,
            quantity=payload.quantity,
            buyer_azuriom_id=payload.buyer_azuriom_id,
        )
        pending = await crud_market.create_pending_extraction(
            db_session=db,
            team_id=team_id,
            item_id=payload.item_id,
            quantity=payload.quantity,
        )

        # Скинути кеш хешу — наступний sync повинен пройти в БД
        await _invalidate_cached_hash(team_id)

        # Попросити острів зробити негайний sync + витягнути куплений предмет
        await websocket_manager.send_personal_message(
            {
                "type": "market_purchase",
                "pending_id": pending.id,
                "item_id": payload.item_id,
                "quantity": payload.quantity,
                "request_sync": True,  # острів повинен зробити sync перед extraction
            },
            f"island_{island_uuid}",
        )
        return {**result, "pending_id": pending.id}
    except ValueError as e:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to process purchase for {island_uuid}: {e}")
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="Purchase failed.")


@router.post("/islands/{island_uuid}/extraction/{pending_id}/confirm", status_code=status.HTTP_200_OK)
async def confirm_extraction(
    island_uuid: str,
    pending_id: int,
    db: AsyncSession = Depends(get_db),
) -> Any:
    """Called by island mod after successfully extracting items from AE2. Credits seller."""
    team_id = await _resolve_team_id(island_uuid, db)
    seller_info = await crud_market.confirm_extraction(db_session=db, pending_id=pending_id, team_id=team_id)
    if seller_info is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Pending extraction not found.")

    if seller_info.get("seller_azuriom_id") and seller_info.get("total_price"):
        credited = await credit_seller(seller_info["seller_azuriom_id"], seller_info["total_price"])
        if not credited:
            logger.error(
                f"Failed to credit seller {seller_info['seller_azuriom_id']} "
                f"amount {seller_info['total_price']} for pending_id={pending_id}"
            )

    return {"confirmed": pending_id}


@router.post("/islands/{island_uuid}/purchase/{pending_id}/cancel", status_code=status.HTTP_200_OK)
async def cancel_purchase(
    island_uuid: str,
    pending_id: int,
    db: AsyncSession = Depends(get_db),
) -> Any:
    """
    Cancels a reserved purchase — restores stock and refunds buyer.
    Called by Hub mod when Azuriom payment failed after API reservation.
    """
    team_id = await _resolve_team_id(island_uuid, db)
    refund_info = await crud_market.cancel_purchase(db_session=db, pending_id=pending_id, team_id=team_id)
    if refund_info is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Pending extraction not found.")

    if refund_info.get("buyer_azuriom_id") and refund_info.get("total_price"):
        refunded = await refund_buyer(refund_info["buyer_azuriom_id"], refund_info["total_price"])
        if not refunded:
            logger.error(
                f"Failed to refund buyer {refund_info['buyer_azuriom_id']} "
                f"amount {refund_info['total_price']} for pending_id={pending_id}"
            )

    return {"cancelled": pending_id}


@router.post("/islands/{island_uuid}/extraction/{pending_id}/fail", status_code=status.HTTP_200_OK)
async def fail_extraction(
    island_uuid: str,
    pending_id: int,
    db: AsyncSession = Depends(get_db),
) -> Any:
    """
    Called by island mod when items are not in AE2 yet (seller is in debt).
    The pending stays in DB — extraction will be retried on next sync when items appear.
    Returns current debt status.
    """
    team_id = await _resolve_team_id(island_uuid, db)
    debt_info = await crud_market.get_pending_extractions(db_session=db, team_id=team_id)
    return {
        "pending_id": pending_id,
        "status": "debt_recorded",
        "total_pending": len(debt_info),
    }


@router.get("/islands/{island_uuid}/transactions", response_model=List[MarketTransactionInDB])
async def get_island_transactions(
    island_uuid: str,
    db: AsyncSession = Depends(get_db),
) -> Any:
    """Повертає історію купівель для конкретного острова."""
    team_id = await _resolve_team_id(island_uuid, db)
    return await crud_market.get_island_transactions(db_session=db, team_id=team_id)


@router.post("/islands/{island_uuid}/inventory/sync", status_code=status.HTTP_200_OK)
async def sync_island_inventory(
    island_uuid: str,
    payload: MarketItemSync,
    db: AsyncSession = Depends(get_db),
) -> Any:
    """Syncs the AE2 inventory of an island. Called by RealMarket Forge mod every 30s or on demand."""
    team_id = await _resolve_team_id(island_uuid, db)

    # Пропустити якщо інвентар не змінився (кеш в Redis — спільний між workers)
    incoming_hash = _compute_inventory_hash(payload.items)
    cached_hash = await _get_cached_hash(team_id)
    if cached_hash == incoming_hash:
        return {"message": "no_change"}

    try:
        triggered_extractions = await crud_market.sync_island_inventory(
            db_session=db, team_id=team_id, items=payload.items
        )
        await _set_cached_hash(team_id, incoming_hash)

        # Якщо sync виявив що борг можна погасити — відправити WS острову
        for extraction in triggered_extractions:
            await websocket_manager.send_personal_message(
                {
                    "type": "market_purchase",
                    "pending_id": extraction["pending_id"],
                    "item_id": extraction["item_id"],
                    "quantity": extraction["quantity"],
                },
                f"island_{island_uuid}",
            )

        return {"message": "synced", "debt_extractions_triggered": len(triggered_extractions)}
    except Exception as e:
        logger.error(f"Failed to sync inventory for {island_uuid}: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="An error occurred while syncing market inventory."
        )
