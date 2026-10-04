"""Destroy barrier: NestWorld crash-agent collects a stopped container's
evidence (log tail, crash reports, run outcomes) before api-world deletes it.

Without it a crash report written in the last seconds before deletion — or the
whole history of a spare that crashed while booting — is lost for good.
"""
import asyncio
import logging
import time

import httpx

from app.core.config import settings

logger = logging.getLogger(__name__)


async def collect(container_name: str) -> tuple[bool, str]:
    """One collection request. Returns (complete, reason)."""
    url = settings.CRASH_AGENT_URL.rstrip("/") + "/collect"
    params = {"project": settings.LXD_PROJECT or "default", "container": container_name}
    try:
        async with httpx.AsyncClient(timeout=120) as client:
            resp = await client.post(url, params=params)
        body = resp.json() if resp.headers.get("content-type", "").startswith("application/json") else {}
    except Exception as e:
        return False, f"агент недоступний: {e}"
    if resp.status_code == 404:
        return True, "контейнера вже немає"  # нічого збирати
    if body.get("complete"):
        return True, "зібрано"
    return False, body.get("error") or f"HTTP {resp.status_code}"


async def collect_before_destroy(container_name: str) -> tuple[bool, str]:
    """Retries collect() for up to DESTROY_BARRIER_TIMEOUT_SECONDS.

    (True, …) — the container may be deleted. The barrier is off when
    CRASH_AGENT_URL is empty. The container must already be stopped.
    """
    if not settings.CRASH_AGENT_URL:
        return True, "бар'єр вимкнено"
    deadline = time.monotonic() + settings.DESTROY_BARRIER_TIMEOUT_SECONDS
    while True:
        ok, reason = await collect(container_name)
        if ok:
            return True, reason
        if time.monotonic() >= deadline:
            return False, reason
        logger.warning(f"Destroy barrier: '{container_name}' not collected yet ({reason}) — retrying.")
        await asyncio.sleep(15)
