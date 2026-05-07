import uuid as _uuid
from contextlib import asynccontextmanager

import redis.asyncio as aioredis
from redis.asyncio import Redis
from redis.exceptions import RedisError
from app.core.config import settings
import logging
import asyncio

logger = logging.getLogger(__name__)

redis_client: Redis | None = None

async def init_redis_pool():
    """Initializes the Redis connection pool.

    This function initializes the Redis connection pool with timeouts and robust
    error handling.

    Raises:
        ConnectionError: If the connection to Redis fails.
    """
    global redis_client
    try:
        logger.info(f"Attempting to connect to Redis at {settings.REDIS_URL}...")
        redis_client = aioredis.from_url(
            settings.REDIS_URL,
            encoding="utf-8",
            decode_responses=True,
            socket_connect_timeout=5  # Add a 5-second connection timeout
        )
        # Wrap the ping in a timeout to prevent indefinite hanging
        await asyncio.wait_for(redis_client.ping(), timeout=5.0)
        logger.info("Successfully connected to Redis and received a pong.")
    except asyncio.TimeoutError:
        logger.error("Redis connection timed out after 5 seconds.")
        redis_client = None
        raise ConnectionError("Failed to connect to Redis: connection timed out.")
    except RedisError as e:
        logger.error(f"A Redis error occurred: {e}", exc_info=True)
        redis_client = None
        raise ConnectionError(f"Failed to connect to Redis: {e}")
    except Exception as e:
        logger.error(f"An unexpected error occurred during Redis initialization: {e}", exc_info=True)
        redis_client = None
        raise ConnectionError(f"An unexpected error occurred while connecting to Redis: {e}")

async def close_redis_pool():
    """Closes the Redis connection pool."""
    global redis_client
    if redis_client:
        await redis_client.close()
        logger.info("Redis connection pool closed.")

def get_redis_client() -> Redis:
    """Returns the application's Redis client instance.

    Returns:
        The Redis client instance.

    Raises:
        RuntimeError: If the Redis client has not been initialized or the
            connection failed.
    """
    if redis_client is None:
        raise RuntimeError("Redis client has not been initialized or the connection failed.")
    return redis_client


_RELEASE_LOCK_SCRIPT = """
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
"""


@asynccontextmanager
async def purchase_lock(buyer_azuriom_id: int, timeout_sec: int = 30):
    """Distributed per-buyer lock to prevent TOCTOU race on balance check (C-5).

    Raises ValueError if a purchase for this buyer is already in progress.
    """
    redis = get_redis_client()
    key = f"purchase_lock:{buyer_azuriom_id}"
    token = str(_uuid.uuid4())
    acquired = await redis.set(key, token, ex=timeout_sec, nx=True)
    if not acquired:
        raise ValueError("Purchase already in progress for this buyer")
    try:
        yield
    finally:
        await redis.eval(_RELEASE_LOCK_SCRIPT, 1, key, token)
