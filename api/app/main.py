import logging
import asyncio
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from contextlib import asynccontextmanager
import uuid # Required for UUID conversion if player_uuid is handled as str in some parts

from app.api.v1.endpoints import islands as islands_router_module
from app.core.config import settings
from app.db.session import AsyncSessionLocal # For creating sessions in startup tasks
# from app.db.session import init_db # If you decide to use it
from app.services.lxd_service import lxd_service, LXDContainerNotFoundError, LXDServiceError
from app.crud.crud_island import crud_island
from app.models.island import Island as IslandModel # For type hinting if needed directly
from app.schemas.island import IslandStatusEnum
from app.services.websocket_manager import manager as websocket_manager

logger = logging.getLogger(__name__) # Get logger for main module

async def reconcile_island_states():
    """
    Checks islands in active/transient DB states against actual LXD container states
    and updates the DB to reflect reality or a safe error state.
    This is intended to run at API startup.
    """
    logger.info("Starting island state reconciliation...")
    
    if AsyncSessionLocal is None:
        logger.error("Reconciliation: Database session factory (AsyncSessionLocal) is not available. Aborting reconciliation.")
        return

    # Define states that need checking
    # These are states where the island is supposed to be active,
    # in transition, or in a state that might need cleanup/verification after a restart.
    statuses_to_check = [
        IslandStatusEnum.RUNNING,
        IslandStatusEnum.FROZEN,
        IslandStatusEnum.PENDING_START,
        IslandStatusEnum.PENDING_FREEZE,
        IslandStatusEnum.PENDING_STOP,
        IslandStatusEnum.ERROR_START, # Check if it's actually stopped
    ]

    # Limit the number of islands fetched at once if there could be many thousands.
    # For most Skyblock servers, a high limit or fetching all should be fine.
    fetch_limit = 1000 

    async with AsyncSessionLocal() as db_session:
        try:
            logger.info(f"Reconciliation: Fetching islands with statuses: {', '.join(s.value for s in statuses_to_check)}")
            islands_to_reconcile = await crud_island.get_islands_by_statuses(
                db_session=db_session, statuses=statuses_to_check, limit=fetch_limit
            )
            
            if not islands_to_reconcile:
                logger.info("Reconciliation: No islands found in states requiring reconciliation.")
                return

            logger.info(f"Reconciliation: Found {len(islands_to_reconcile)} islands to check.")

            for island_db in islands_to_reconcile:
                logger.info(f"Reconciliation: Checking island ID {island_db.id}, Player UUID {island_db.player_uuid}, Container: {island_db.container_name}, DB Status: {island_db.status.value}")
                actual_lxd_state_info = None
                lxd_status_str: str | None = None # e.g., "Running", "Stopped", "Frozen"
                
                try:
                    actual_lxd_state_info = await lxd_service.get_container_state(island_db.container_name)
                    if actual_lxd_state_info:
                        lxd_status_str = actual_lxd_state_info.get("status")
                    logger.info(f"Reconciliation: LXD state for {island_db.container_name}: {lxd_status_str if actual_lxd_state_info else 'Not Found'}")
                except LXDContainerNotFoundError:
                    logger.warning(f"Reconciliation: Container {island_db.container_name} for island ID {island_db.id} not found in LXD.")
                    lxd_status_str = "NOT_FOUND" # Special status for our logic
                except LXDServiceError as e:
                    logger.error(f"Reconciliation: LXDServiceError checking container {island_db.container_name}: {e}. Skipping this island.")
                    continue # Skip to next island if LXD is having issues with this one specifically
                except Exception as e:
                    logger.error(f"Reconciliation: Unexpected error checking container {island_db.container_name}: {e}. Skipping this island.", exc_info=True)
                    continue

                db_status = island_db.status
                new_status: IslandStatusEnum | None = None
                extra_fields_to_update: dict = {}

                # Main reconciliation logic based on design
                if lxd_status_str == "NOT_FOUND":
                    if db_status in [
                        IslandStatusEnum.RUNNING, IslandStatusEnum.FROZEN, 
                        IslandStatusEnum.PENDING_START, IslandStatusEnum.PENDING_FREEZE, 
                        IslandStatusEnum.PENDING_STOP, IslandStatusEnum.ERROR_START
                    ]:
                        logger.error(f"Reconciliation CRITICAL: Container {island_db.container_name} for island ID {island_db.id} (DB status {db_status.value}) is MISSING in LXD!")
                        new_status = IslandStatusEnum.ERROR # Or a more specific ERROR_MISSING_CONTAINER
                        extra_fields_to_update = {"internal_ip_address": None, "internal_port": None, "external_port": None}

                elif db_status == IslandStatusEnum.RUNNING:
                    if lxd_status_str == "Stopped":
                        logger.info(f"Reconciliation: Island ID {island_db.id} (DB: RUNNING, LXD: Stopped). Correcting to STOPPED.")
                        new_status = IslandStatusEnum.STOPPED
                        extra_fields_to_update = {"internal_ip_address": None, "internal_port": None, "external_port": None}
                    elif lxd_status_str == "Frozen":
                        logger.info(f"Reconciliation: Island ID {island_db.id} (DB: RUNNING, LXD: Frozen). Correcting to FROZEN.")
                        new_status = IslandStatusEnum.FROZEN
                        # IP might still be valid if it was frozen gracefully, leave as is unless LXD state says otherwise
                    # If LXD is Running, no status change. IP could be verified if actual_lxd_state_info has it.
                    elif lxd_status_str == "Running" and actual_lxd_state_info:
                         lxd_ip = actual_lxd_state_info.get("ip_address")
                         if lxd_ip and island_db.internal_ip_address != lxd_ip:
                             logger.info(f"Reconciliation: Island ID {island_db.id} (DB: RUNNING, LXD: Running) IP mismatch. DB IP: {island_db.internal_ip_address}, LXD IP: {lxd_ip}. Updating DB IP.")
                             extra_fields_to_update["internal_ip_address"] = lxd_ip
                         # else: IP matches or no IP from LXD, no IP change needed for now.

                elif db_status == IslandStatusEnum.FROZEN:
                    if lxd_status_str == "Stopped":
                        logger.info(f"Reconciliation: Island ID {island_db.id} (DB: FROZEN, LXD: Stopped). Correcting to STOPPED.")
                        new_status = IslandStatusEnum.STOPPED
                        extra_fields_to_update = {"internal_ip_address": None, "internal_port": None, "external_port": None}
                    elif lxd_status_str == "Running":
                        logger.info(f"Reconciliation: Island ID {island_db.id} (DB: FROZEN, LXD: Running). Correcting to RUNNING.")
                        new_status = IslandStatusEnum.RUNNING
                        if actual_lxd_state_info and actual_lxd_state_info.get("ip_address"):
                            extra_fields_to_update["internal_ip_address"] = actual_lxd_state_info.get("ip_address")


                elif db_status == IslandStatusEnum.PENDING_START:
                    if lxd_status_str == "Running":
                        logger.info(f"Reconciliation: Island ID {island_db.id} (DB: PENDING_START, LXD: Running). Finalizing to RUNNING.")
                        new_status = IslandStatusEnum.RUNNING
                        if actual_lxd_state_info and actual_lxd_state_info.get("ip_address"):
                            extra_fields_to_update["internal_ip_address"] = actual_lxd_state_info.get("ip_address")
                        else: # Could not get IP, might be an issue
                            logger.warning(f"Reconciliation: Island ID {island_db.id} became RUNNING but failed to get IP from LXD state. Setting to ERROR_START.")
                            new_status = IslandStatusEnum.ERROR_START # Or keep RUNNING and let other mechanisms handle IP if available
                    elif lxd_status_str == "Stopped":
                        logger.info(f"Reconciliation: Island ID {island_db.id} (DB: PENDING_START, LXD: Stopped). Correcting to STOPPED (start likely failed).")
                        new_status = IslandStatusEnum.STOPPED
                    elif lxd_status_str == "Frozen": # Unlikely, but handle
                        logger.info(f"Reconciliation: Island ID {island_db.id} (DB: PENDING_START, LXD: Frozen). Correcting to FROZEN.")
                        new_status = IslandStatusEnum.FROZEN

                elif db_status == IslandStatusEnum.PENDING_FREEZE:
                    if lxd_status_str == "Frozen":
                        logger.info(f"Reconciliation: Island ID {island_db.id} (DB: PENDING_FREEZE, LXD: Frozen). Finalizing to FROZEN.")
                        new_status = IslandStatusEnum.FROZEN
                    elif lxd_status_str == "Running": # Freeze failed or was reverted
                        logger.info(f"Reconciliation: Island ID {island_db.id} (DB: PENDING_FREEZE, LXD: Running). Correcting to RUNNING (freeze failed).")
                        new_status = IslandStatusEnum.RUNNING
                    elif lxd_status_str == "Stopped":
                        logger.info(f"Reconciliation: Island ID {island_db.id} (DB: PENDING_FREEZE, LXD: Stopped). Correcting to STOPPED.")
                        new_status = IslandStatusEnum.STOPPED
                
                elif db_status == IslandStatusEnum.PENDING_STOP:
                    if lxd_status_str == "Stopped":
                        logger.info(f"Reconciliation: Island ID {island_db.id} (DB: PENDING_STOP, LXD: Stopped). Finalizing to STOPPED.")
                        new_status = IslandStatusEnum.STOPPED
                        extra_fields_to_update = {"internal_ip_address": None, "internal_port": None, "external_port": None}
                    elif lxd_status_str == "Running": # Stop failed
                        logger.info(f"Reconciliation: Island ID {island_db.id} (DB: PENDING_STOP, LXD: Running). Correcting to RUNNING (stop failed).")
                        new_status = IslandStatusEnum.RUNNING
                    elif lxd_status_str == "Frozen": # Stop might have been interrupted, or was frozen before stop
                        logger.info(f"Reconciliation: Island ID {island_db.id} (DB: PENDING_STOP, LXD: Frozen). Correcting to FROZEN (stop failed).")
                        new_status = IslandStatusEnum.FROZEN
                
                elif db_status == IslandStatusEnum.ERROR_START:
                    if lxd_status_str == "Stopped":
                        logger.info(f"Reconciliation: Island ID {island_db.id} (DB: ERROR_START, LXD: Stopped). Consistent, no change.")
                    elif lxd_status_str in ["Running", "Frozen"]:
                        logger.warning(f"Reconciliation: Island ID {island_db.id} (DB: ERROR_START, LXD: {lxd_status_str}). LXD state is active. Correcting DB to {lxd_status_str}.")
                        if lxd_status_str == "Running": new_status = IslandStatusEnum.RUNNING
                        if lxd_status_str == "Frozen": new_status = IslandStatusEnum.FROZEN
                        if actual_lxd_state_info and actual_lxd_state_info.get("ip_address") and lxd_status_str == "Running":
                             extra_fields_to_update["internal_ip_address"] = actual_lxd_state_info.get("ip_address")


                update_payload = extra_fields_to_update.copy()
                if new_status:
                    update_payload["status"] = new_status

                if update_payload:
                    logger.info(f"Reconciliation: Updating island ID {island_db.id} with payload: {update_payload}")
                    await crud_island.update_by_id(
                        db_session=db_session,
                        island_id=island_db.id,
                        obj_in=update_payload
                    )

            # await db_session.commit() # crud_island methods already commit.
            logger.info("Reconciliation: Island state reconciliation process finished.")

        except Exception as e:
            logger.error(f"Reconciliation: General error during island state reconciliation: {e}", exc_info=True)
            # await db_session.rollback() # crud_island methods handle their own rollback on error.
        finally:
            await db_session.close()
            logger.info("Reconciliation: Database session closed.")

from app.core.redis import init_redis_pool, close_redis_pool, get_redis_client
from app.services.creation_worker import start_creation_worker
from app.services.start_worker import start_start_worker

# Lifespan manager for startup and shutdown events
@asynccontextmanager
async def lifespan(app: FastAPI):
    # Code to run on startup
    logger.info("Starting up SkyBlock LXD Manager API worker...")
    
    # Initialize Redis for all workers
    await init_redis_pool()
    
    # Start the Redis listener for WebSocket messages on all workers
    redis_listener_task = asyncio.create_task(websocket_manager.redis_listener())
    
    # Use Redis to ensure that heavy startup tasks are only run by one worker.
    redis_client = get_redis_client()
    # Attempt to acquire a lock. The first worker to set this key wins.
    # The lock expires after 60 seconds to prevent deadlocks if the worker crashes.
    is_startup_leader = await redis_client.set("startup_lock", "1", ex=60, nx=True)
    
    if is_startup_leader:
        logger.info("This worker is the startup leader. Running initial tasks...")
        # Perform island state reconciliation
        await reconcile_island_states()
        
        # Start background workers
        await start_creation_worker()
        await start_start_worker()
        logger.info("Startup leader has finished initial tasks.")
    else:
        logger.info("This worker is not the startup leader. Skipping initial tasks.")

    yield
    
    # Code to run on shutdown
    logger.info("Shutting down SkyBlock LXD Manager API worker...")
    redis_listener_task.cancel()
    await close_redis_pool()

app = FastAPI(
    title="SkyBlock LXD Manager API",
    description="API for managing dynamic SkyBlock player islands on LXD.",
    version="0.1.0",
    lifespan=lifespan
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.websocket("/ws/{client_id}")
async def websocket_endpoint(websocket: WebSocket, client_id: str):
    await websocket_manager.connect(websocket, client_id)
    try:
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        websocket_manager.disconnect(client_id)

@app.get("/")
async def read_root():
    return {"message": "Welcome to the SkyBlock LXD Manager API"}

# Include your API routers
from app.api.v1.endpoints import teams as teams_router_module

app.include_router(
    islands_router_module.router,
    prefix=f"{settings.API_V1_STR}/islands", 
    tags=["Islands"]
)

app.include_router(
    teams_router_module.router,
    prefix=f"{settings.API_V1_STR}/teams",
    tags=["Teams"]
)

# For development, you might run this with: uvicorn app.main:app --reload
# Make sure to install uvicorn and fastapi: pip install fastapi uvicorn sqlalchemy asyncpg databases
# and any other dependencies you add to requirements.txt
