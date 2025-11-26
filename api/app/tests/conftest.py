import asyncio
from typing import AsyncGenerator

import pytest
import pytest_asyncio
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.orm import sessionmaker

from app.db.base_class import Base

# Use a consistent in-memory SQLite database URL
SQLALCHEMY_DATABASE_URL = "sqlite+aiosqlite:///:memory:"

# Create an async engine
engine = create_async_engine(SQLALCHEMY_DATABASE_URL, echo=True)

# Create an async session factory
TestingSessionLocal = async_sessionmaker(
    autocommit=False, autoflush=False, bind=engine, class_=AsyncSession, expire_on_commit=False
)

@pytest_asyncio.fixture(scope="session")
async def setup_database() -> AsyncGenerator[None, None]:
    """
    Fixture to set up the database once per test session.
    Creates all tables and drops them after the session ends.
    """
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    yield

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)

@pytest_asyncio.fixture(scope="function")
async def db_session(setup_database: None) -> AsyncGenerator[AsyncSession, None]:
    """
    Fixture that provides a transactional session for each test function.

    Each test will run within a transaction that is rolled back at the end.
    This ensures test isolation.
    """
    async with TestingSessionLocal() as session:
        await session.begin()
        try:
            yield session
        finally:
            await session.rollback()
