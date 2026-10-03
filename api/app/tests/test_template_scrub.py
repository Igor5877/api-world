"""Перед публікацією образу з шаблону прибирається його ідентичність і історія
запусків NestWorld wrapper'а, щоб клони-острови не успадкували чужий server_id і краші."""
from unittest.mock import AsyncMock, patch

import pytest

from app.services.update_service import update_service


@pytest.mark.asyncio
async def test_scrub_template_identity_removes_server_id_runs_and_crashes():
    lxd = AsyncMock()
    lxd.list_directory.side_effect = lambda c, d: {
        "/var/lib/nestworld/runtime/evidence/runs": ["a.json"],
        "/var/lib/nestworld/runtime/game-events/graceful": ["a.json"],
        "/opt/minecraft/crash-reports": ["crash-1.txt"],
    }[d]
    with patch("app.services.update_service.lxd_service", lxd):
        await update_service._scrub_template_identity("skyblock-base")
    deleted = {c.args[1] for c in lxd.delete_file.await_args_list}
    assert {"/var/lib/nestworld/server_id",
            "/var/lib/nestworld/runtime/evidence/runs.jsonl",
            "/var/lib/nestworld/runtime/evidence/runs/a.json",
            "/var/lib/nestworld/runtime/game-events/graceful/a.json",
            "/opt/minecraft/crash-reports/crash-1.txt"} <= deleted
