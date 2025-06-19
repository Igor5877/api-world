from sqlalchemy.ext.asyncio import create_async_engine, AsyncSession
from sqlalchemy.orm import sessionmaker
from app.core.config import settings
import logging

# Configure logging
logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO)


# Ensure the DATABASE_URL is correctly formatted for SQLAlchemy async drivers
# Example: "mysql+aiomysql://user:password@host:port/db"
# Example: "postgresql+asyncpg://user:password@host:port/db"
_DATABASE_URL = settings.DATABASE_URL

# Automatically adjust MySQL URL if it's missing the async driver part,
# but it's better to have it correctly set in the .env file.
if _DATABASE_URL.startswith("mysql://"):
    _DATABASE_URL = _DATABASE_URL.replace("mysql://", "mysql+aiomysql://")
    logger.warning(f"Adjusted DATABASE_URL to use aiomysql: {_DATABASE_URL}")
elif _DATABASE_URL.startswith("postgresql://"):
    _DATABASE_URL = _DATABASE_URL.replace("postgresql://", "postgresql+asyncpg://")
    logger.warning(f"Adjusted DATABASE_URL to use asyncpg: {_DATABASE_URL}")


engine = None
AsyncSessionLocal = None

try:
    engine = create_async_engine(
        _DATABASE_URL,
        pool_pre_ping=True,  # Enables a "ping" to check DB connection health before using a connection from the pool
        echo=False,          # Set to True to log all SQL statements (can be verbose)
        # pool_recycle=3600, # Optional: recycle connections after 1 hour
        # pool_size=10,      # Optional: set pool size
        # max_overflow=20,   # Optional: set max overflow
    )

    AsyncSessionLocal = sessionmaker(
        autocommit=False,
        autoflush=False,
        bind=engine,
        class_=AsyncSession,
        expire_on_commit=False, # Recommended for FastAPI to prevent issues with background tasks accessing committed objects
    )
    logger.info(f"SQLAlchemy async engine created for URL: {_DATABASE_URL.split('@')[-1]}") # Log DB host/name without credentials
    logger.info("AsyncSessionLocal initialized successfully.")

except ImportError as e:
    logger.error(f"Failed to import SQLAlchemy or database driver: {e}")
    logger.error("Please ensure 'sqlalchemy[asyncio]' and your specific DB driver (e.g., 'aiomysql' for MySQL, 'asyncpg' for PostgreSQL) are installed.")
    # engine and AsyncSessionLocal will remain None
except Exception as e:
    logger.error(f"Failed to create SQLAlchemy async engine or AsyncSessionLocal: {e}")
    # engine and AsyncSessionLocal will remain None


async def get_db_session() -> AsyncSession:
    """
    FastAPI dependency to get an SQLAlchemy asynchronous database session.
    Ensures the session is closed after the request.
    """
    if AsyncSessionLocal is None:
        logger.error("AsyncSessionLocal is not initialized. Database connection might have failed during startup.")
        # This will likely cause a 500 error upstream if an endpoint tries to use it.
        # Consider raising a more specific custom exception if needed.
        raise RuntimeError("Database session factory is not available. Check application logs for DB connection errors.")
    
    async with AsyncSessionLocal() as session:
        try:
            yield session
            # The commit is typically handled at the end of a successful CRUD operation
            # or service method that uses the session.
            # If an operation completes without error, it should commit its own transaction.
            # A blanket commit here might be too broad if multiple operations occurred.
            # For now, let it be handled by the caller of the session.
            # await session.commit() # This might be too aggressive here.
        except Exception as e:
            logger.error(f"Exception during database session, rolling back: {e}")
            await session.rollback()
            raise # Re-raise the exception to be handled by FastAPI error handlers
        finally:
            # The 'async with AsyncSessionLocal() as session:' context manager
            # should handle closing the session automatically.
            # Explicit close might be redundant but doesn't hurt.
            await session.close()
            # logger.debug("Database session closed.")


# Optional: Function to initialize database (create tables)
# This is more for development/testing. Production usually uses migrations (Alembic).
async def init_db():
    """
    Initializes the database by creating all tables defined in Base.metadata.
    This should typically be called once at application startup.
    Make sure all your SQLAlchemy models are imported into app.db.base so Base.metadata knows them.
    """
    if not engine:
        logger.error("Database engine not initialized. Cannot run init_db().")
        return

    try:
        async with engine.begin() as conn:
            # Import Base from where your models are registered
            from app.db.base import Base # Ensure this import path is correct
            
            # This will create tables that don't exist. It won't update existing tables.
            # For schema changes, use a migration tool like Alembic.
            # await conn.run_sync(Base.metadata.drop_all) # Use with EXTREME caution: drops all data!
            await conn.run_sync(Base.metadata.create_all)
        logger.info("Database tables checked/created successfully based on SQLAlchemy models (if init_db was called).")
    except Exception as e:
        logger.error(f"Error during database initialization (init_db): {e}")
        # Depending on the error, you might want to raise it or handle it.
        # For example, if the database user doesn't have permission to create tables.

# To actually run init_db, you might call it in your app's lifespan startup event:
# In main.py:
# from app.db.session import init_db
# @asynccontextmanager
# async def lifespan(app: FastAPI):
#     await init_db()
#     yield
