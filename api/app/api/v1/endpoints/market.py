import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_db
from typing import List
from app.crud.crud_market import crud_market
from app.schemas.market import MarketItemSync, MarketItemInDB

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
