"""Auth of /prometheus/sd: Bearer header (preferred) and legacy ?token=."""
from unittest.mock import patch

import pytest

URL = "/api/v1/prometheus/sd"


@pytest.mark.asyncio
async def test_sd_accepts_bearer_and_query_rejects_wrong(client):
    with patch("app.core.auth.settings.PROMETHEUS_SD_TOKEN", "secret"):
        assert (await client.get(URL, headers={"Authorization": "Bearer secret"})).status_code == 200
        assert (await client.get(URL + "?token=secret")).status_code == 200
        assert (await client.get(URL, headers={"Authorization": "Bearer nope"})).status_code == 401
        assert (await client.get(URL)).status_code == 401
