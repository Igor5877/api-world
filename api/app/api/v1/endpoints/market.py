import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_db
from typing import List
from app.crud.crud_market import crud_market
from app.schemas.market import MarketItemSync, MarketItemInDB, PurchaseRequest, MarketTransactionInDB
from app.services.websocket_manager import manager as websocket_manager

router = APIRouter()
logger = logging.getLogger(__name__)

@router.get("/islands/{island_uuid}/inventory", response_model=List[MarketItemInDB])
async def get_island_inventory(
    island_uuid: str,
    db: AsyncSession = Depends(get_db),
) -> Any:
    """
    Retrieves the current market inventory of an island.
    This endpoint is called by the Hub server to populate the shop GUI.
    """
    items = await crud_market.get_island_inventory(db_session=db, island_uuid=island_uuid)
    return items

@router.post("/islands/{island_uuid}/purchase", status_code=status.HTTP_200_OK)
async def purchase_item(
    island_uuid: str,
    payload: PurchaseRequest,
    db: AsyncSession = Depends(get_db),
) -> Any:
    """
    Called by the Hub mod after a player successfully pays for an item.
    Deducts the purchased quantity from the island's market inventory.
    """
    try:
        result = await crud_market.purchase_item(
            db_session=db,
            island_uuid=island_uuid,
            item_id=payload.item_id,
            quantity=payload.quantity,
            buyer_azuriom_id=payload.buyer_azuriom_id,
        )
        # Зберігаємо pending — острів може бути офлайн
        pending = await crud_market.create_pending_extraction(
            db_session=db,
            island_uuid=island_uuid,
            item_id=payload.item_id,
            quantity=payload.quantity,
        )
        # Намагаємось надіслати WS зараз (якщо острів онлайн)
        await websocket_manager.send_personal_message(
            {
                "type": "market_purchase",
                "pending_id": pending.id,
                "item_id": payload.item_id,
                "quantity": payload.quantity,
            },
            f"island_{island_uuid}",
        )
        return result
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
    """
    Called by the island mod after successfully extracting items from AE2.
    Deletes the pending extraction record.
    """
    deleted = await crud_market.confirm_extraction(
        db_session=db, pending_id=pending_id, island_uuid=island_uuid
    )
    if not deleted:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Pending extraction not found.")
    return {"confirmed": pending_id}


@router.get("/islands/{island_uuid}/transactions", response_model=List[MarketTransactionInDB])
async def get_island_transactions(
    island_uuid: str,
    db: AsyncSession = Depends(get_db),
) -> Any:
    """Повертає історію купівель для конкретного острова."""
    return await crud_market.get_island_transactions(db_session=db, island_uuid=island_uuid)


@router.post("/islands/{island_uuid}/inventory/sync", status_code=status.HTTP_200_OK)
async def sync_island_inventory(
    island_uuid: str,
    payload: MarketItemSync,
    db: AsyncSession = Depends(get_db),
) -> Any:
    """
    Syncs the AE2 inventory of an island to the market database.
    This endpoint replaces the old inventory of the given `island_uuid` with the new items list.
    
    Returns standard HTTP 200 upon success.
    """
    try:
        # Pass the extracted items to the CRUD service
        await crud_market.sync_island_inventory(db_session=db, island_uuid=island_uuid, items=payload.items)
        return {"message": f"Successfully synced inventory for island {island_uuid}"}
    except Exception as e:
        logger.error(f"Failed to sync inventory for {island_uuid}: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="An error occurred while syncing market inventory."
        )
