
from fastapi import FastAPI
from contextlib import asynccontextmanager

from app.api.v1.endpoints import islands as islands_router_module # renamed to avoid conflict
from app.core.config import settings
# from app.db.session import engine, init_db # For full DB setup
# from app.services.lxd_service import lxd_service # For LXD connection management

# Lifespan manager for startup and shutdown events
@asynccontextmanager
async def lifespan(app: FastAPI):
    # Code to run on startup
    print("Starting up SkyBlock LXD Manager API...")
    
    # Example: Initialize database (if not using Alembic for migrations)
    # print("Initializing database...")
    # await init_db() # Make sure your init_db is async if called with await
    
    # Example: Connect to LXD
    # print("Connecting to LXD...")
    # await lxd_service._connect() # Assuming _connect is the method to establish connection
    # app.state.lxd_client = lxd_service.client # Store client if needed globally, or just ensure service is ready

    yield
    # Code to run on shutdown
    print("Shutting down SkyBlock LXD Manager API...")
    # Example: Disconnect from LXD
    # print("Disconnecting from LXD...")
    # await lxd_service.close()

app = FastAPI(
    title="SkyBlock LXD Manager API",
    description="API for managing dynamic SkyBlock player islands on LXD.",
    version="0.1.0",
    lifespan=lifespan
)

@app.get("/")
async def read_root():
    return {"message": "Welcome to the SkyBlock LXD Manager API"}

# Include your API routers
app.include_router(
    islands_router_module.router, # Access the router object from the imported module
    prefix=f"{settings.API_V1_STR}/islands", 
    tags=["Islands"]
)

# For development, you might run this with: uvicorn app.main:app --reload
# Make sure to install uvicorn and fastapi: pip install fastapi uvicorn sqlalchemy asyncpg databases
# and any other dependencies you add to requirements.txt
