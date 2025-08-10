import logging
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from contextlib import asynccontextmanager
import uuid

from app.api.v1.endpoints import islands as islands_router_module
from app.core.config import settings
from app.db.session import AsyncSessionLocal
from app.services.lxd_service import lxd_service, LXDContainerNotFoundError, LXDServiceError
from app.crud.crud_island import crud_island
from app.models.island import Island as IslandModel
from app.schemas.island import IslandStatusEnum
from app.services.websocket_manager import manager as websocket_manager
# Import all the workers
from app.services.creation_worker import start_creation_worker
from app.services.start_worker import start_start_worker
from app.services.update_worker import start_update_worker, stop_update_worker

logger = logging.getLogger(__name__)

async def reconcile_island_states():
    # Your existing reconciliation logic here...
    logger.info("Starting island state reconciliation...")
    # This is a simplified version of your logic for brevity
    if AsyncSessionLocal is None:
        return
    async with AsyncSessionLocal() as db_session:
        statuses_to_check = [
            IslandStatusEnum.RUNNING, IslandStatusEnum.FROZEN, IslandStatusEnum.PENDING_START,
            IslandStatusEnum.PENDING_FREEZE, IslandStatusEnum.PENDING_STOP, IslandStatusEnum.ERROR_START,
        ]
        islands = await crud_island.get_islands_by_statuses(db_session, statuses=statuses_to_check, limit=10000)
        logger.info(f"Reconciliation: Found {len(islands)} islands to check.")


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Code to run on startup
    logger.info("Starting up SkyBlock LXD Manager API...")
    await reconcile_island_states()
    
    # Start all workers
    await start_creation_worker()
    await start_start_worker()
    start_update_worker() # <-- THIS IS THE IMPORTANT ADDED LINE
    
    yield
    
    # Code to run on shutdown
    logger.info("Shutting down SkyBlock LXD Manager API...")
    stop_update_worker() # <-- AND THE CORRESPONDING STOP CALL

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

app.include_router(
    islands_router_module.router,
    prefix=f"{settings.API_V1_STR}/islands", 
    tags=["Islands"]
)
