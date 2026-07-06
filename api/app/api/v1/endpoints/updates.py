"""Endpoints for the island auto-update system.

Two routers:
    webhook_router — GitHub webhook, authenticated by HMAC signature only
                     (GitHub cannot send X-Api-Key), mounted WITHOUT the
                     API-key dependency.
    router         — admin endpoints (campaigns, rollback, snapshots),
                     mounted with the normal API-key auth.
"""
import hashlib
import hmac
import logging
import uuid as uuid_module

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Request, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.crud.crud_island import crud_island
from app.crud.crud_update import crud_update_campaign
from app.crud.crud_update_queue import crud_update_queue
from app.db.session import get_db_session
from app.models.update import CampaignStatusEnum
from app.schemas.island import MessageResponse
from app.schemas.update import (
    CampaignCreateRequest, CampaignResponse, CampaignDetailResponse,
    QueueEntryResponse, SnapshotInfo, SpawnSyncRequest,
)
from app.services import git_sync
from app.services.lxd_service import lxd_service, LXDContainerNotFoundError
from app.services.update_service import update_service, UpdateServiceError

logger = logging.getLogger(__name__)

webhook_router = APIRouter()
router = APIRouter()


async def _create_campaign_background(tag: str, islands=None):
    """Builds the manifest and creates the campaign outside the request cycle.

    git clone/pull of a repo full of .jar files can take minutes — GitHub
    gives webhooks 10 seconds, so the HTTP handler only validates and
    schedules this task.
    """
    from app.db.session import AsyncSessionLocal
    async with AsyncSessionLocal() as db_session:
        try:
            campaign = await update_service.create_campaign_from_tag(
                db_session, tag=tag, islands=islands)
            logger.info(f"Updates: Campaign {campaign.id} for '{tag}' created from webhook/manual trigger.")
        except ValueError as e:
            logger.warning(f"Updates: Campaign for '{tag}' not created: {e}")
        except Exception as e:
            logger.error(f"Updates: Failed to create campaign for '{tag}': {e}", exc_info=True)


@webhook_router.post("/webhook", status_code=status.HTTP_200_OK)
async def github_webhook(
    request: Request,
    background_tasks: BackgroundTasks,
    db_session: AsyncSession = Depends(get_db_session),
):
    """Receives GitHub push events for the skyblock-updates repository.

    Verifies the HMAC-SHA256 signature, extracts the tag, deduplicates and
    schedules campaign creation in the background. Responds immediately.
    """
    if not settings.GITHUB_WEBHOOK_SECRET:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                            detail="GITHUB_WEBHOOK_SECRET is not configured.")

    body = await request.body()
    signature = request.headers.get("X-Hub-Signature-256", "")
    expected = "sha256=" + hmac.new(settings.GITHUB_WEBHOOK_SECRET.encode(), body, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(signature, expected):
        logger.warning("Updates webhook: Invalid signature.")
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid webhook signature.")

    payload = await request.json()

    ref = payload.get("ref", "")
    if payload.get("deleted"):
        return {"message": "Tag deletion ignored."}
    if not ref.startswith("refs/tags/"):
        return {"message": "Not a tag push — ignored."}

    tag = ref[len("refs/tags/"):]

    if await crud_update_campaign.get_by_version(db_session, version=tag):
        return {"message": f"Campaign for '{tag}' already exists — ignored (webhook redelivery)."}
    active = await crud_update_campaign.get_active(db_session)
    if active:
        logger.warning(f"Updates webhook: Tag '{tag}' received while campaign '{active.version}' is active.")
        return {"message": f"Campaign '{active.version}' is still active; trigger '{tag}' manually once it finishes."}

    background_tasks.add_task(_create_campaign_background, tag)
    logger.info(f"Updates webhook: Campaign creation for '{tag}' scheduled.")
    return {"message": f"Campaign creation for '{tag}' scheduled."}


@router.post("/campaign", response_model=MessageResponse, status_code=status.HTTP_202_ACCEPTED)
async def create_campaign_manually(
    body: CampaignCreateRequest,
    background_tasks: BackgroundTasks,
    db_session: AsyncSession = Depends(get_db_session),
):
    """Manually triggers a campaign for a tag (all islands or a subset)."""
    if await crud_update_campaign.get_by_version(db_session, version=body.tag):
        raise HTTPException(status_code=status.HTTP_409_CONFLICT,
                            detail=f"Campaign for '{body.tag}' already exists.")
    active = await crud_update_campaign.get_active(db_session)
    if active:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT,
                            detail=f"Campaign '{active.version}' is still active.")

    islands = None if body.islands == "all" else body.islands
    background_tasks.add_task(_create_campaign_background, body.tag, islands)
    return MessageResponse(message=f"Campaign creation for '{body.tag}' scheduled.")


def _campaign_with_counters(campaign, counts: dict) -> CampaignResponse:
    """Merges a campaign row with its queue status counters."""
    response = CampaignResponse.model_validate(campaign)
    response.completed = counts.get("COMPLETED", 0)
    response.waiting = counts.get("WAITING", 0)
    response.failed = counts.get("FAILED", 0)
    response.pending = counts.get("PENDING", 0) + counts.get("PROCESSING", 0)
    response.skipped = counts.get("SKIPPED", 0)
    response.total = sum(counts.values())
    return response


@router.get("/campaigns", response_model=list[CampaignResponse])
async def list_campaigns(db_session: AsyncSession = Depends(get_db_session)):
    """Lists campaigns with rollout progress, newest first."""
    campaigns = await crud_update_campaign.list_all(db_session)
    result = []
    for campaign in campaigns:
        counts = await crud_update_queue.count_by_status(db_session, campaign_id=campaign.id)
        result.append(_campaign_with_counters(campaign, counts))
    return result


@router.get("/campaign/{campaign_id}", response_model=CampaignDetailResponse)
async def get_campaign_detail(campaign_id: int, db_session: AsyncSession = Depends(get_db_session)):
    """Detailed campaign status with the per-island breakdown."""
    campaign = await crud_update_campaign.get(db_session, campaign_id=campaign_id)
    if not campaign:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Campaign not found.")
    counts = await crud_update_queue.count_by_status(db_session, campaign_id=campaign_id)
    entries = await crud_update_queue.get_all_for_campaign(db_session, campaign_id=campaign_id)
    detail = CampaignDetailResponse(**_campaign_with_counters(campaign, counts).model_dump())
    detail.entries = [QueueEntryResponse.model_validate(e) for e in entries]
    return detail


@router.post("/campaign/{campaign_id}/requeue_failed", response_model=MessageResponse)
async def requeue_failed_islands(campaign_id: int, db_session: AsyncSession = Depends(get_db_session)):
    """Returns all FAILED islands of a campaign to the queue (admin retry)."""
    campaign = await crud_update_campaign.get(db_session, campaign_id=campaign_id)
    if not campaign:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Campaign not found.")
    count = await crud_update_queue.requeue_failed(db_session, campaign_id=campaign_id)
    if count and campaign.status in (CampaignStatusEnum.COMPLETED, CampaignStatusEnum.FAILED):
        await crud_update_campaign.set_status(db_session, campaign_id=campaign_id,
                                              status=CampaignStatusEnum.IN_PROGRESS)
    return MessageResponse(message=f"{count} islands re-queued.")


async def _resolve_island(db_session: AsyncSession, player_uuid: str):
    """Finds an island by owner UUID (team or legacy solo)."""
    island_response = None
    try:
        uuid_module.UUID(player_uuid)
    except ValueError:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid UUID.")
    from app.services.island_service import island_service
    island_response = await island_service.get_island_by_player_uuid(db_session, player_uuid=player_uuid)
    if not island_response or island_response.id is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Island not found for this player.")
    island = await crud_island.get(db_session, island_id=island_response.id)
    if not island:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Island not found.")
    return island


@router.post("/rollback/island/{player_uuid}", response_model=MessageResponse)
async def rollback_island(
    player_uuid: str,
    campaign_id: int | None = None,
    db_session: AsyncSession = Depends(get_db_session),
):
    """Rolls one island back to the state before a campaign.

    Without campaign_id the island's newest file backup determines the campaign.
    """
    island = await _resolve_island(db_session, player_uuid)

    if campaign_id is None:
        from app.crud.crud_update import crud_island_backup_ops
        backup = await crud_island_backup_ops.get_latest_files_backup(db_session, island_id=island.id)
        if not backup or not backup.campaign_id:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND,
                                detail="No file backup found for this island.")
        campaign_id = backup.campaign_id

    campaign = await crud_update_campaign.get(db_session, campaign_id=campaign_id)
    if not campaign:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Campaign not found.")

    try:
        await update_service.rollback_island(db_session, island, campaign)
    except UpdateServiceError as e:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(e))
    return MessageResponse(message=f"Island rolled back to {campaign.previous_version or 'previous state'}.")


@router.post("/rollback/campaign/{campaign_id}", response_model=MessageResponse)
async def rollback_campaign(campaign_id: int, db_session: AsyncSession = Depends(get_db_session)):
    """Rolls back every island that was updated by a campaign."""
    campaign = await crud_update_campaign.get(db_session, campaign_id=campaign_id)
    if not campaign:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Campaign not found.")
    ok, failed = await update_service.rollback_campaign(db_session, campaign)
    return MessageResponse(message=f"Rollback finished: {ok} islands restored, {failed} failed.")


@router.get("/snapshots/{player_uuid}", response_model=list[SnapshotInfo])
async def list_island_snapshots(player_uuid: str, db_session: AsyncSession = Depends(get_db_session)):
    """Lists LXD snapshots of an island (for manual emergency restore)."""
    island = await _resolve_island(db_session, player_uuid)
    try:
        snapshots = await lxd_service.list_snapshots(island.container_name)
    except LXDContainerNotFoundError:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Container not found in LXD.")
    return [SnapshotInfo(**snap) for snap in snapshots]


@router.post("/spawn/sync", response_model=MessageResponse)
async def sync_spawn(body: SpawnSyncRequest):
    """Pushes content directly to the spawn/hub container and restarts it.

    Bypasses campaigns, islands, and the update queue entirely — for when
    only the spawn server needs the latest mods/config/quests (e.g. an
    already-rolled-out tag that islands got but spawn didn't).
    """
    try:
        if body.tag:
            await git_sync.clone_or_pull(settings.UPDATES_REPO_URL, settings.UPDATES_REPO_LOCAL_PATH)
            await git_sync.checkout_tag(settings.UPDATES_REPO_LOCAL_PATH, body.tag)
        await update_service.update_spawn_container(body.tag or "current checkout")
    except Exception as e:
        logger.error(f"Updates: Manual spawn sync failed: {e}", exc_info=True)
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(e))
    return MessageResponse(message="Spawn sync complete.")
