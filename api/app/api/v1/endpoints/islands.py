from fastapi import APIRouter, HTTPException, Depends, status, BackgroundTasks, Response
from typing import Any
import uuid # For player_uuid
from sqlalchemy.ext.asyncio import AsyncSession # Added for DB session type hint
import logging

from app.schemas.island import IslandCreate, IslandResponse, IslandStatusEnum, MessageResponse
from app.services.island_service import island_service
from app.db.session import get_db_session # Import the dependency

logger = logging.getLogger(__name__)
router = APIRouter()

@router.get("/{player_uuid}", response_model=IslandResponse)
async def get_island_status_endpoint(
    player_uuid: str,
    db_session: AsyncSession = Depends(get_db_session) # Added DB session dependency
):
    """
    Get the status and network address of a player's island.
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
    """
    Endpoint to start a player's island. If it doesn't exist, it will be created.
    """
    logger.info(f"Endpoint: Player {player_uuid} ({player_name}) requesting to start their island.")
    try:
        updated_island = await island_service.start_island_instance(
            db_session=db_session,
            player_uuid=player_uuid,
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
    """
    Endpoint to stop a player's team island.
    """
    logger.info(f"Endpoint: Player {player_uuid} requesting to stop their island.")
    try:
        updated_island = await island_service.stop_island_instance(
            db_session=db_session,
            player_uuid=player_uuid,
            background_tasks=background_tasks
        )
        return updated_island
    except ValueError as e:
        logger.warning(f"Endpoint: ValueError during island stop for {player_uuid}: {e}")
        if "not found" in str(e).lower():
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
    """
    Endpoint to freeze a player's island.
    This is an asynchronous process. The initial response indicates acceptance.
    The actual LXD freeze happens in the background.
    The response will reflect the island's status (e.g., PENDING_FREEZE).
    """
    logger.info(f"Endpoint: Received request to freeze island UUID: {player_uuid}")
    try:
        updated_island = await island_service.freeze_island_instance(
            db_session=db_session,
            player_uuid=player_uuid,
            background_tasks=background_tasks
        )
        return updated_island
    except ValueError as e:
        logger.warning(f"Endpoint: ValueError during island freeze for {player_uuid}: {e}")
        if "not found" in str(e).lower():
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(e))
        elif "cannot be frozen" in str(e).lower() or "already frozen" in str(e).lower() or "pending_freeze" in str(e).lower():
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(e))
        else:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))
    except Exception as e:
        logger.error(f"Endpoint Error: Unexpected error during island freeze for {player_uuid}: {e}", exc_info=True)
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="An internal server error occurred while freezing the island.")


# TODO: Add more endpoints from Stage 1 & 2:
# POST /islands/{uuid}/freeze
# POST /islands/{uuid}/stop
# DELETE /islands/{uuid}

# Note: The `player_uuid` in the path is typically a string representation of a UUID.
# FastAPI will automatically validate it if you type hint it as `uuid.UUID`.
# For example: `player_uuid: uuid.UUID` in function parameters.
# For this initial setup, string is fine.

@router.post("/team/{team_id}/ready", response_model=MessageResponse, status_code=status.HTTP_200_OK)
async def mark_island_ready_endpoint(
    team_id: int,
    db_session: AsyncSession = Depends(get_db_session)
):
    """
    Endpoint for a Minecraft server to signal that its team island is fully loaded.
    """
    logger.info(f"Endpoint: Received request to mark island ready for team_id: {team_id}")
    try:
        await island_service.mark_island_as_ready_for_players(
            db_session=db_session,
            team_id=team_id
        )
        return MessageResponse(message="Island marked as ready for players.")
    except ValueError as e:
        logger.warning(f"Endpoint: ValueError marking island ready for {player_uuid}: {e}")
        if "not found" in str(e).lower():
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(e))
        elif "not in a state to be marked ready" in str(e).lower() or "already marked as ready" in str(e).lower():
            # Using 409 Conflict if the state is not appropriate for this action
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(e))
        else:
            # Generic bad request or internal error depending on expected exceptions
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))
    except Exception as e:
        logger.error(f"Endpoint Error: Unexpected error marking island ready for {player_uuid}: {e}", exc_info=True)
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="An internal server error occurred while marking the island ready.")
