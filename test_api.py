import pytest
from httpx import AsyncClient
from api.app.main import app

@pytest.mark.asyncio
async def test_metrics_endpoint():
    async with AsyncClient(app=app, base_url="http://test") as ac:
        response = await ac.get("/metrics")
    assert response.status_code == 200
    assert b"skyblock_islands_total" in response.content
