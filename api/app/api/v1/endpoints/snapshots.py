import logging
from typing import List
from fastapi import APIRouter, Depends, HTTPException, BackgroundTasks, Security
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import get_api_key
from app.crud import crud_island
from app.schemas.snapshot import SnapshotRestore
from app.services.lxd_service import lxd_service, LXDContainerNotFoundError

router = APIRouter()
logger = logging.getLogger(__name__)

@router.get("/islands/{owner_uuid}/snapshots", response_model=List[str])
async def list_snapshots(
    *,
    db: AsyncSession = Depends(get_api_key),
    owner_uuid: str,
    api_key: str = Security(get_api_key),
):
    """
    List all snapshots for a specific island.
    """
    island = await crud_island.get_island_by_player_uuid(db, player_uuid=owner_uuid)
    if not island:
        raise HTTPException(status_code=404, detail="Island not found.")

    try:
        snapshots = await lxd_service.list_snapshots(island.container_name)
        return snapshots
    except LXDContainerNotFoundError:
        raise HTTPException(status_code=404, detail=f"Container for island with owner UUID {owner_uuid} not found.")


@router.post("/islands/{owner_uuid}/snapshots/restore", status_code=202)
async def restore_snapshot(
    *,
    db: AsyncSession = Depends(get_api_key),
    owner_uuid: str,
    snapshot_in: SnapshotRestore,
    background_tasks: BackgroundTasks,
    api_key: str = Security(get_api_key),
):
    """
    Restores an island from a specific snapshot.
    This is a long-running operation, so it runs in the background.
    """
    island = await crud_island.get_island_by_player_uuid(db, player_uuid=owner_uuid)
    if not island:
        raise HTTPException(status_code=404, detail="Island not found.")

    async def restore_task():
        try:
            await lxd_service.stop_container(island.container_name, force=True)
            await lxd_service.restore_snapshot(island.container_name, snapshot_in.snapshot_name)
            await lxd_service.start_container(island.container_name)
        except LXDContainerNotFoundError as e:
            logger.error(f"Failed to restore snapshot for island {owner_uuid}: Container or snapshot not found.", exc_info=True)
        except Exception as e:
            logger.error(f"An unexpected error occurred during snapshot restoration for island {owner_uuid}: {e}", exc_info=True)

    background_tasks.add_task(restore_task)
    return {"message": f"Restoration of island {owner_uuid} from snapshot {snapshot_in.snapshot_name} has been initiated."}


@router.delete("/islands/{owner_uuid}/snapshots/{snapshot_name}", status_code=202)
async def delete_snapshot(
    *,
    db: AsyncSession = Depends(get_api_key),
    owner_uuid: str,
    snapshot_name: str,
    api_key: str = Security(get_api_key),
):
    """
    Deletes a specific snapshot from an island.
    """
    island = await crud_island.get_island_by_player_uuid(db, player_uuid=owner_uuid)
    if not island:
        raise HTTPException(status_code=404, detail="Island not found.")

    try:
        await lxd_service.delete_snapshot(island.container_name, snapshot_name)
        return {"message": f"Snapshot {snapshot_name} has been successfully deleted."}
    except LXDContainerNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e))
