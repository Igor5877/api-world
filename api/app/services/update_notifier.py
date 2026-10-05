"""Discord notifications for update campaigns.

One live message per campaign that is edited as it goes ("🔄 v0.0.34 — 3/8,
now updating skyblock-solo-X"), instead of a message per island — a campaign
over a hundred islands would otherwise flood the channel. Separate messages
only for what needs attention: a permanently failed island and the result.

Best effort: a Discord or Redis failure is logged and never affects the
campaign itself.
"""
import logging
from datetime import datetime, timezone
from typing import Optional

import httpx

from app.core.config import settings
from app.core.redis import get_redis_client
from app.crud.crud_update_queue import crud_update_queue

logger = logging.getLogger(__name__)

_MSG_KEY = "update_campaign_discord_msg:{}"   # Redis: id живого повідомлення кампанії
_MSG_TTL = 14 * 24 * 3600

_TYPE_TEXT = {
    "server_only": "лише сервер",
    "both": "сервер і клієнт (гравцям треба оновити клієнт)",
    "critical": "критичне — онлайн-гравців буде кікнуто",
}

# порядок і підписи лічильників
_COUNTS = (
    ("COMPLETED", "✅ оновлено"),
    ("PROCESSING", "🔧 зараз"),
    ("PENDING", "⏳ у черзі"),
    ("WAITING", "🕓 чекають виходу гравця"),
    ("FAILED", "❌ помилки"),
    ("SKIPPED", "⏭ пропущено"),
)


def _enabled() -> bool:
    return bool(settings.UPDATES_DISCORD_WEBHOOK)


async def _post(embed: dict, *, wait: bool = False) -> Optional[str]:
    """Sends an embed; with wait=True returns the new message id."""
    url = settings.UPDATES_DISCORD_WEBHOOK + ("?wait=true" if wait else "")
    async with httpx.AsyncClient(timeout=15) as client:
        resp = await client.post(url, json={"embeds": [embed]})
    resp.raise_for_status()
    return resp.json().get("id") if wait else None


async def _edit(message_id: str, embed: dict) -> bool:
    url = f"{settings.UPDATES_DISCORD_WEBHOOK}/messages/{message_id}"
    async with httpx.AsyncClient(timeout=15) as client:
        resp = await client.patch(url, json={"embeds": [embed]})
    return resp.status_code // 100 == 2


def _campaign_header(campaign) -> str:
    update_type = getattr(campaign.update_type, "value", campaign.update_type)
    parts = [f"**Тип:** {_TYPE_TEXT.get(update_type, update_type)}"]
    parts.append("**Рестарт островів:** " + ("так" if campaign.requires_restart else "ні (перезавантаження без рестарту)"))
    changed = campaign.changed_paths or []
    if changed:
        parts.append(f"**Змінено файлів:** {len(changed)}")
    if campaign.message:
        parts.append(f"**Опис:** {campaign.message[:300]}")
    return "\n".join(parts)


async def _progress_embed(db_session, campaign, current: Optional[str], finished: bool = False) -> dict:
    counts = await crud_update_queue.count_by_status(db_session, campaign_id=campaign.id)
    total = sum(counts.values())
    done = counts.get("COMPLETED", 0) + counts.get("FAILED", 0) + counts.get("SKIPPED", 0)
    lines = [_campaign_header(campaign), ""]
    if not finished:
        lines.append(f"**Зараз:** {current}" if current else "**Зараз:** очікування наступного острова")
    lines.append(" · ".join(f"{label} {counts[status]}" for status, label in _COUNTS if counts.get(status)) or "черга порожня")
    if finished:
        title = f"✅ Оновлення {campaign.version} завершено — {done}/{total}"
        color = 0xE53935 if counts.get("FAILED") else 0x43A047
    else:
        title = f"🔄 Оновлення {campaign.version} — {done}/{total}"
        color = 0x1E88E5
    return {"title": title, "description": "\n".join(lines), "color": color,
            "timestamp": datetime.now(timezone.utc).isoformat()}


async def _update_live(db_session, campaign, current: Optional[str], finished: bool = False):
    if not _enabled():
        return
    try:
        embed = await _progress_embed(db_session, campaign, current, finished)
        redis = get_redis_client()
        key = _MSG_KEY.format(campaign.id)
        message_id = await redis.get(key)
        if message_id and await _edit(message_id, embed):
            return
        # Повідомлення ще немає (або його видалили) — нове.
        message_id = await _post(embed, wait=True)
        if message_id:
            await redis.set(key, message_id, ex=_MSG_TTL)
    except Exception as e:
        logger.warning(f"UpdateNotifier: progress message for {campaign.version} failed: {e}")


async def campaign_started(db_session, campaign):
    await _update_live(db_session, campaign, current="оновлення шаблону островів (першим кроком)")


async def progress(db_session, campaign, current: Optional[str] = None):
    await _update_live(db_session, campaign, current)


async def island_failed(campaign, island, error: str):
    if not _enabled():
        return
    who = f" (гравець {island.player_name})" if getattr(island, "player_name", None) else ""
    try:
        await _post({"title": f"❌ Оновлення {campaign.version}: острів не оновлено",
                     "description": f"**{island.container_name}**{who}\n```{error[:900]}```\n"
                                    f"Острів лишається на попередній версії; повтор — requeue_failed.",
                     "color": 0xE53935})
    except Exception as e:
        logger.warning(f"UpdateNotifier: failure message for island {island.id} failed: {e}")


async def campaign_failed_to_start(campaign):
    if not _enabled():
        return
    try:
        await _post({"title": f"❌ Оновлення {campaign.version} не почалося",
                     "description": "Не вдалося підготувати репозиторій модпаку (git). Подробиці — у журналі API; "
                                    "острови не чіпались.",
                     "color": 0xE53935})
    except Exception as e:
        logger.warning(f"UpdateNotifier: start-failure message for {campaign.version} failed: {e}")


async def campaign_finished(db_session, campaign):
    """Final state into the live message, plus a fresh message so the result
    is seen at the bottom of the channel, not only in an old edited one."""
    await _update_live(db_session, campaign, current=None, finished=True)
    if not _enabled():
        return
    try:
        await _post(await _progress_embed(db_session, campaign, None, finished=True))
    except Exception as e:
        logger.warning(f"UpdateNotifier: result message for {campaign.version} failed: {e}")
