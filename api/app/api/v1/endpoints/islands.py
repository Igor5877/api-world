from fastapi import APIRouter, HTTPException, Depends, status, BackgroundTasks
from typing import Any
import uuid # For player_uuid
from sqlalchemy.ext.asyncio import AsyncSession # Added for DB session type hint
import logging

from app.schemas.island import IslandCreate, IslandResponse, IslandStatusEnum, MessageResponse
from app.services.island_service import island_service
from app.db.session import get_db_session # Import the dependency

logger = logging.getLogger(__name__)
router = APIRouter()

@router.post("/", response_model=IslandResponse, status_code=status.HTTP_202_ACCEPTED)
async def create_island_endpoint(
    island_in: IslandCreate,
    background_tasks: BackgroundTasks,
    db_session: AsyncSession = Depends(get_db_session) # Added DB session dependency
):
    """
    Endpoint to request the creation of a new island for a player.
    This process is asynchronous. The initial response indicates acceptance.
    The actual cloning and setup happen in the background.
    """
    try:
        logger.info(f"Endpoint: Received request to create island for player UUID: {island_in.player_uuid}")
        initial_island_response = await island_service.create_new_island(
            db_session=db_session, # Pass the session
            island_create_data=island_in,
            background_tasks=background_tasks 
        )
        return initial_island_response
    except ValueError as e:
        logger.warning(f"Endpoint: ValueError during island creation for {island_in.player_uuid}: {e}")
        if "already exists" in str(e):
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(e))
        else:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e)) # Or 500 if it's unexpected
    except Exception as e:
        logger.error(f"Endpoint Error: Unexpected error during island creation for {island_in.player_uuid}: {e}", exc_info=True)
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="An internal server error occurred while creating the island.")


@router.get("/{player_uuid}", response_model=IslandResponse)
async def get_island_status_endpoint(
    player_uuid: uuid.UUID,
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


@router.post("/{player_uuid}/start", response_model=IslandResponse, status_code=status.HTTP_202_ACCEPTED)
async def start_island_endpoint(
    player_uuid: uuid.UUID,
    background_tasks: BackgroundTasks,
    db_session: AsyncSession = Depends(get_db_session) # Added DB session dependency
):
    """
    Endpoint to start a player's island.
    This is an asynchronous process. The initial response indicates acceptance.
    The actual LXD start and server boot happen in the background.
    The response will reflect the island's status (e.g., PENDING_START).
    """
    logger.info(f"Endpoint: Received request to start island UUID: {player_uuid}")
    try:
        updated_island = await island_service.start_island_instance(
            db_session=db_session, # Pass the session
            player_uuid=player_uuid,
            background_tasks=background_tasks
        )
        return updated_island
    except ValueError as e:
        logger.warning(f"Endpoint: ValueError during island start for {player_uuid}: {e}")
        if "not found" in str(e).lower():
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(e))
        elif "cannot be started" in str(e).lower() or "already running" in str(e).lower() or "pending_start" in str(e).lower():
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(e))
        else:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e)) # Or 500
    except Exception as e:
        logger.error(f"Endpoint Error: Unexpected error during island start for {player_uuid}: {e}", exc_info=True)
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="An internal server error occurred while starting the island.")

# TODO: Add more endpoints from Stage 1 & 2:
# POST /islands/{uuid}/freeze
# POST /islands/{uuid}/stop
# DELETE /islands/{uuid}

# Note: The `player_uuid` in the path is typically a string representation of a UUID.
# FastAPI will automatically validate it if you type hint it as `uuid.UUID`.
# For example: `player_uuid: uuid.UUID` in function parameters.
# For this initial setup, string is fine.
