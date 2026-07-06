from fastapi import APIRouter, HTTPException, Depends, status, BackgroundTasks, Response
from typing import Any
import uuid # For player_uuid
from sqlalchemy.ext.asyncio import AsyncSession # Added for DB session type hint
import logging

from app.schemas.island import IslandCreate, IslandResponse, IslandStatusEnum, MessageResponse, QuestProgressUpload, QuestProgressResponse, IslandEventResponse
from app.services.island_service import island_service
from app.db.session import get_db_session # Import the dependency

logger = logging.getLogger(__name__)
router = APIRouter()

@router.get("/{player_uuid}", response_model=IslandResponse)
async def get_island_status_endpoint(
    player_uuid: str,
    db_session: AsyncSession = Depends(get_db_session) # Added DB session dependency
):
    """Gets the status and network address of a player's island.

    Args:
        player_uuid: The UUID of the player.
        db_session: The database session.

    Returns:
        The island status and network address.

    Raises:
        HTTPException: If the island is not found for the player.
    """
    logger.info(f"Endpoint: Received request to get status for island UUID: {player_uuid}")
    island = await island_service.get_island_by_player_uuid(db_session=db_session, player_uuid=player_uuid)

    if not island:
        logger.info(f"Endpoint: Island not found for UUID: {player_uuid}")
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Island not found for this player.")
    return island


@router.post("/start/{player_uuid}", response_model=IslandResponse, status_code=status.HTTP_202_ACCEPTED)
async def start_island_endpoint(
    player_uuid: uuid.UUID,
    background_tasks: BackgroundTasks,
    player_name: str, # Passed as a query parameter or in a request body
    db_session: AsyncSession = Depends(get_db_session)
):
    """Starts a player's island.

    If the island does not exist, it will be created.

    Args:
        player_uuid: The UUID of the player.
        background_tasks: The background tasks to run.
        player_name: The name of the player.
        db_session: The database session.

    Returns:
        The updated island data.

    Raises:
        HTTPException: If the island cannot be started.
    """
    logger.info(f"Endpoint: Player {player_uuid} ({player_name}) requesting to start their island.")
    try:
        player_uuid_str = str(player_uuid)
        updated_island = await island_service.start_island_instance(
            db_session=db_session,
            player_uuid=player_uuid_str,
            player_name=player_name,
            background_tasks=background_tasks
        )
        return updated_island
    except ValueError as e:
        logger.warning(f"Endpoint: ValueError during island start for player {player_uuid}: {e}")
        if "not found" in str(e).lower() or "not in a team" in str(e).lower():
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(e))
        elif "cannot be started" in str(e).lower() or "already running" in str(e).lower() or "pending_start" in str(e).lower():
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(e))
        else:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))
    except Exception as e:
        logger.error(f"Endpoint Error: Unexpected error during island start for player {player_uuid}: {e}", exc_info=True)
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="An internal server error occurred while marking the island ready.")

@router.post("/stop/{player_uuid}", response_model=IslandResponse, status_code=status.HTTP_202_ACCEPTED)
async def stop_island_endpoint(
    player_uuid: uuid.UUID,
    background_tasks: BackgroundTasks,
    db_session: AsyncSession = Depends(get_db_session)
):
    """Stops a player's team island.

    Args:
        player_uuid: The UUID of the player.
        background_tasks: The background tasks to run.
        db_session: The database session.

    Returns:
        The updated island data.

    Raises:
        HTTPException: If the island cannot be stopped.
    """
    logger.info(f"Endpoint: Player {player_uuid} requesting to stop their island.")
    try:
        player_uuid_str = str(player_uuid)
        updated_island = await island_service.stop_island_instance(
            db_session=db_session,
            player_uuid=player_uuid_str,
            background_tasks=background_tasks
        )
        return updated_island
    except ValueError as e:
        logger.warning(f"Endpoint: ValueError during island stop for {player_uuid}: {e}")
        if "not found" in str(e).lower() or "no island found" in str(e).lower():
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(e))
        elif "cannot be stopped" in str(e).lower() or "already stopped" in str(e).lower() or "pending_stop" in str(e).lower():
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(e))
        else:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))
    except Exception as e:
        logger.error(f"Endpoint Error: Unexpected error during island stop for {player_uuid}: {e}", exc_info=True)
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="An internal server error occurred while stopping the island.")


@router.post("/{player_uuid}/freeze", response_model=IslandResponse, status_code=status.HTTP_202_ACCEPTED)
async def freeze_island_endpoint(
    player_uuid: uuid.UUID,
    background_tasks: BackgroundTasks,
    db_session: AsyncSession = Depends(get_db_session)
):
    """Freezes a player's island.

    This is an asynchronous process. The initial response indicates acceptance.
    The actual LXD freeze happens in the background.
    The response will reflect the island's status (e.g., PENDING_FREEZE).

    Args:
        player_uuid: The UUID of the player.
        background_tasks: The background tasks to run.
        db_session: The database session.

    Returns:
        The updated island data.

    Raises:
        HTTPException: If the island cannot be frozen.
    """
    logger.info(f"Endpoint: Received request to freeze island UUID: {player_uuid}")
    try:
        player_uuid_str = str(player_uuid)
        updated_island = await island_service.freeze_island_instance(
            db_session=db_session,
            player_uuid=player_uuid_str,
            background_tasks=background_tasks
        )
        return updated_island
    except ValueError as e:
        logger.warning(f"Endpoint: ValueError during island freeze for {player_uuid}: {e}")
        if "not found" in str(e).lower() or "no island found" in str(e).lower():
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(e))
        elif "cannot be frozen" in str(e).lower() or "already frozen" in str(e).lower() or "pending_freeze" in str(e).lower():
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(e))
        else:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))
    except Exception as e:
        logger.error(f"Endpoint Error: Unexpected error during island freeze for {player_uuid}: {e}", exc_info=True)
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="An internal server error occurred while freezing the island.")


@router.post("/{player_uuid}/player_left", response_model=MessageResponse, status_code=status.HTTP_200_OK)
async def player_left_island_endpoint(
    player_uuid: uuid.UUID,
    db_session: AsyncSession = Depends(get_db_session)
):
    """Called by Velocity when the last player leaves an island.

    Releases a WAITING update-queue entry so the update worker can apply a
    pending hard update once the island stops.

    Args:
        player_uuid: The UUID of the island owner.
        db_session: The database session.

    Returns:
        A message stating whether a pending update was re-queued.
    """
    from app.services.update_worker import on_player_left_island

    island = await island_service.get_island_by_player_uuid(db_session=db_session, player_uuid=str(player_uuid))
    if not island or island.id is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Island not found for this player.")

    requeued = await on_player_left_island(db_session, island.id)
    if requeued:
        return MessageResponse(message="Pending update re-queued for this island.")
    return MessageResponse(message="No pending update for this island.")


@router.post("/{owner_uuid}/ready", response_model=MessageResponse, status_code=status.HTTP_200_OK)
async def mark_island_ready_endpoint(
    owner_uuid: str,
    db_session: AsyncSession = Depends(get_db_session)
):
    """Marks a team island as fully loaded.

    This endpoint is intended to be called by a Minecraft server to signal that it
    is ready to accept players.

    Args:
        owner_uuid: The UUID of the island owner.
        db_session: The database session.

    Returns:
        A message indicating that the island has been marked as ready.

    Raises:
        HTTPException: If the island cannot be marked as ready.
    """
    logger.info(f"Endpoint: Received request to mark island ready for owner_uuid: {owner_uuid}")
    try:
        await island_service.mark_island_as_ready_for_players(
            db_session=db_session,
            owner_uuid=owner_uuid
        )
        return MessageResponse(message="Island marked as ready for players.")
    except ValueError as e:
        logger.warning(f"Endpoint: ValueError marking island ready for {owner_uuid}: {e}")
        if "not found" in str(e).lower():
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(e))
        elif "not in a state to be marked ready" in str(e).lower() or "already marked as ready" in str(e).lower():
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(e))
        else:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))
    except Exception as e:
        logger.error(f"Endpoint Error: Unexpected error marking island ready for {owner_uuid}: {e}", exc_info=True)
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="An internal server error occurred while marking the island ready.")


@router.get("/{owner_uuid}/events", response_model=list[IslandEventResponse])
async def list_island_events_endpoint(
    owner_uuid: str,
    limit: int = 50,
    db_session: AsyncSession = Depends(get_db_session)
):
    """Lists the island's incident journal (crashes, hangs, restarts).

    Written by the health watchdog and lifecycle handlers; newest first.
    """
    from app.crud.crud_island_event import crud_island_event

    island = await _resolve_island_by_owner(db_session, owner_uuid)
    return await crud_island_event.list_for_island(
        db_session, island_id=island.id, limit=max(1, min(limit, 500)))


# ── FTB Quests progress sync (island = source of truth, spawn reads) ──────

async def _resolve_island_by_owner(db_session: AsyncSession, owner_uuid: str):
    """Finds the island for an owner UUID (team owner or legacy solo player).

    Returns:
        The island model.

    Raises:
        HTTPException: 404 if no island exists for this owner.
    """
    from app.crud import crud_team
    from app.crud.crud_island import crud_island

    team = await crud_team.get_team_by_owner_with_relations(db_session, owner_uuid=owner_uuid)
    if team and team.island:
        return team.island
    island = await crud_island.get_by_player_uuid(db_session, player_uuid=owner_uuid)
    if island:
        return island
    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND,
                        detail=f"No island found for owner {owner_uuid}.")


@router.put("/{owner_uuid}/quest-progress", response_model=MessageResponse)
async def upload_quest_progress_endpoint(
    owner_uuid: str,
    payload: QuestProgressUpload,
    db_session: AsyncSession = Depends(get_db_session)
):
    """Stores the island's FTB Quests progress snapshot.

    Called by the island's Forge mod on player logout / server stop, so the
    spawn server can show up-to-date read-only progress.
    """
    from app.crud.crud_quest_progress import crud_quest_progress

    if not payload.snbt.strip():
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="snbt must not be empty.")

    island = await _resolve_island_by_owner(db_session, owner_uuid)
    await crud_quest_progress.upsert(db_session, island_id=island.id,
                                     owner_uuid=owner_uuid, snbt=payload.snbt)
    logger.info(f"Endpoint: Stored quest progress for owner {owner_uuid} ({len(payload.snbt)} bytes).")
    return MessageResponse(message="Quest progress stored.")


@router.get("/{owner_uuid}/quest-progress", response_model=QuestProgressResponse)
async def get_quest_progress_endpoint(
    owner_uuid: str,
    db_session: AsyncSession = Depends(get_db_session)
):
    """Returns the latest stored FTB Quests progress snapshot for an island.

    Called by the spawn/hub server on player join.
    """
    from app.crud.crud_quest_progress import crud_quest_progress

    snapshot = await crud_quest_progress.get_by_owner_uuid(db_session, owner_uuid=owner_uuid)
    if not snapshot:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND,
                            detail=f"No quest progress stored for owner {owner_uuid}.")
    return snapshot
