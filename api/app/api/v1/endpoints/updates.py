from fastapi import APIRouter, Depends, HTTPException, BackgroundTasks, Security
from sqlalchemy.ext.asyncio import AsyncSession

from app.api import deps
from app.api.dependencies import get_api_key
from app.crud import crud_island
from app.schemas.update import UpdateDeploy, HeartbeatPayload
from app.services.update_service import update_service
from app.models.island import IslandStatusEnum
from app.metrics import SKYBLOCK_UPDATE_EVENTS

router = APIRouter()

@router.post("/deploy", status_code=202)
async def deploy_update(
    *,
    db: AsyncSession = Depends(deps.get_db),
    update_in: UpdateDeploy,
    background_tasks: BackgroundTasks,
    api_key: str = Security(get_api_key),
):
    """
    Initiates an update for a list of islands to a specific version tag.
    """
    if not update_in.island_uuids:
        raise HTTPException(status_code=400, detail="No island UUIDs provided for update.")

    for uuid in update_in.island_uuids:
        island = await crud_island.get_island_by_player_uuid(db, player_uuid=uuid)
        if not island:
            # In a real scenario, we might collect all errors and return them
            # instead of stopping on the first one.
            raise HTTPException(status_code=404, detail=f"Island with owner UUID {uuid} not found.")

        # Add the update task to run in the background
        background_tasks.add_task(
            update_service.update_island,
            db=db,
            island_uuid=uuid,
            version_tag=update_in.version_tag
        )

    return {"message": f"Update process initiated for {len(update_in.island_uuids)} islands to version {update_in.version_tag}."}


@router.post("/islands/{owner_uuid}/heartbeat", status_code=200)
async def island_heartbeat(
    *,
    db: AsyncSession = Depends(deps.get_db),
    owner_uuid: str,
    heartbeat_in: HeartbeatPayload,
):
    """
    Endpoint for mods-server to report its status and version.
    This confirms a successful update or normal operation.
    """
    island = await crud_island.get_island_by_player_uuid(db, player_uuid=owner_uuid)
    if not island:
        raise HTTPException(status_code=404, detail="Island not found.")

    if island.status == IslandStatusEnum.UPDATING:
        SKYBLOCK_UPDATE_EVENTS.labels(status='success').inc()

    # Update the island's version and status
    await crud_island.update_island(
        db,
        db_obj=island,
        obj_in={
            "current_version": heartbeat_in.version,
            "status": IslandStatusEnum.RUNNING, # Mark as running, confirming successful boot
            "minecraft_ready": True
        }
    )

    return {"message": "Heartbeat received. Island status updated."}
