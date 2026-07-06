from typing import AsyncGenerator
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest_asyncio
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.db.base import Base  # registers all models with metadata
from app.db.session import get_db_session

SQLALCHEMY_DATABASE_URL = "sqlite+aiosqlite:///:memory:"
engine = create_async_engine(SQLALCHEMY_DATABASE_URL, echo=False)
TestingSessionLocal = async_sessionmaker(
    autocommit=False, autoflush=False, bind=engine, expire_on_commit=False
)


@pytest_asyncio.fixture
async def db_session() -> AsyncGenerator[AsyncSession, None]:
    """Fresh SQLite in-memory session for each test function."""
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async with TestingSessionLocal() as session:
        yield session

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)


@pytest_asyncio.fixture
async def client(db_session: AsyncSession):
    """Async HTTP test client backed by SQLite and mocked Redis/workers."""
    from app.main import app

    async def override_db():
        yield db_session

    app.dependency_overrides[get_db_session] = override_db

    mock_redis = MagicMock()
    mock_redis.set = AsyncMock(return_value=True)  # always startup leader

    mock_ws = MagicMock()
    mock_ws.redis_listener = AsyncMock()
    mock_ws.connect = AsyncMock()
    mock_ws.send_personal_message = AsyncMock()
    mock_ws.send_message_to_clients = AsyncMock()
    mock_ws.disconnect = MagicMock()

    with (
        patch("app.main.init_redis_pool", new_callable=AsyncMock),
        patch("app.main.close_redis_pool", new_callable=AsyncMock),
        patch("app.main.get_redis_client", return_value=mock_redis),
        patch("app.main.websocket_manager", mock_ws),
        patch("app.main.reconcile_island_states", new_callable=AsyncMock),
        patch("app.main.start_creation_worker", new_callable=AsyncMock),
        patch("app.main.start_start_worker", new_callable=AsyncMock),
        patch("app.main.start_analytics_worker", new_callable=AsyncMock),
        patch("app.main.start_update_worker"),
        patch("app.main.start_health_worker"),
    ):
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
            yield ac

    app.dependency_overrides.clear()
