"""Persistent, renewing Redis-based leader election for background workers.

Replaces the one-shot `SETNX at process startup` pattern that used to gate
`update_worker`/`health_worker` in `main.py`'s `lifespan()`: a worker that
lost that single roll of the dice never got another chance for the rest of
the process's life. Two gunicorn restarts landing within the old lock's TTL
(60s) meant BOTH new worker processes saw the key already held by the
already-dead previous leader and both gave up — zero processes ever ran the
update/health loops again until the next full service restart. This is
exactly what stalled the v0.0.20 update campaign on 2026-08-22.

A `LeaderElection` instance is meant to be polled once per loop iteration via
`tick()`. It keeps retrying to acquire the lock when it isn't leader, and
renews (and can lose) the lock when it is — so a leader process dying just
means the next `tick()` from any surviving contender picks it up, no restart
required anywhere.
"""
import logging

from app.core.redis import get_redis_client

logger = logging.getLogger(__name__)


class LeaderElection:
    """One instance per (lock name, asyncio loop). Not shared across tasks."""

    def __init__(self, lock_key: str, ttl_seconds: int = 30):
        """Args:
            lock_key: Redis key used for the lock. Unique per worker kind.
            ttl_seconds: How long the lock survives without a renewal. Keep
                this a small multiple of the worker's own tick interval so a
                dead leader's slot frees up quickly.
        """
        self._lock_key = lock_key
        self._ttl_seconds = ttl_seconds
        self._is_leader = False

    @property
    def is_leader(self) -> bool:
        return self._is_leader

    async def tick(self) -> bool:
        """Attempts to acquire (if not leader) or renew (if leader) the lock.

        Call this once per loop iteration, before deciding whether to do
        leader-only work this tick. Never raises — a Redis error is treated
        as "not leader" so a flaky Redis connection can't cause two
        processes to both believe they're leader.

        Returns:
            Whether this process is the leader right now.
        """
        try:
            redis_client = get_redis_client()
            if not self._is_leader:
                acquired = await redis_client.set(self._lock_key, "1", ex=self._ttl_seconds, nx=True)
                if acquired:
                    self._is_leader = True
                    logger.info(f"LeaderElection[{self._lock_key}]: acquired leadership.")
            else:
                renewed = await redis_client.set(self._lock_key, "1", ex=self._ttl_seconds, xx=True)
                if not renewed:
                    logger.warning(f"LeaderElection[{self._lock_key}]: lost leadership on renew "
                                   f"(lock expired or was cleared) — will retry acquiring it.")
                    self._is_leader = False
        except Exception as e:
            if self._is_leader:
                logger.warning(f"LeaderElection[{self._lock_key}]: Redis error while renewing, "
                               f"assuming leadership lost: {e}")
            self._is_leader = False
        return self._is_leader

    async def release(self):
        """Best-effort give-up of the lock, e.g. on clean worker shutdown."""
        if not self._is_leader:
            return
        try:
            redis_client = get_redis_client()
            await redis_client.delete(self._lock_key)
            logger.info(f"LeaderElection[{self._lock_key}]: released leadership.")
        except Exception:
            pass
        self._is_leader = False
