"""Discord progress of update campaigns: one live message, edited as it goes."""
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.crud.crud_update import crud_update_campaign
from app.crud.crud_update_queue import crud_update_queue
from app.models.island import Island as IslandModel
from app.models.update import CampaignStatusEnum, UpdateQueueStatusEnum
from app.schemas.island import IslandStatusEnum
from app.services import update_notifier


class _FakeRedis:
    def __init__(self):
        self.data = {}

    async def get(self, k):
        return self.data.get(k)

    async def set(self, k, v, ex=None):
        self.data[k] = v


async def _campaign_with_islands(db: AsyncSession, n=3):
    campaign = await crud_update_campaign.create(db, version="v9.9.9", status=CampaignStatusEnum.IN_PROGRESS,
                                                 requires_restart=True, changed_paths=[["M", "mods/a.jar"]])
    for i in range(n):
        island = IslandModel(container_name=f"skyblock-solo-p{i}", status=IslandStatusEnum.STOPPED)
        db.add(island)
        await db.commit()
        await db.refresh(island)
        await crud_update_queue.add_island(db, campaign_id=campaign.id, island=island)
    return campaign


def _patched(redis, post, edit):
    return (patch.object(update_notifier.settings, "UPDATES_DISCORD_WEBHOOK", "https://discord/hook"),
            patch("app.services.update_notifier.get_redis_client", return_value=redis),
            patch("app.services.update_notifier._post", post),
            patch("app.services.update_notifier._edit", edit))


@pytest.mark.asyncio
async def test_one_live_message_created_then_edited(db_session: AsyncSession):
    campaign = await _campaign_with_islands(db_session)
    redis, post, edit = _FakeRedis(), AsyncMock(return_value="msg-1"), AsyncMock(return_value=True)
    p1, p2, p3, p4 = _patched(redis, post, edit)
    with p1, p2, p3, p4:
        await update_notifier.campaign_started(db_session, campaign)
        entries = await crud_update_queue.get_all_for_campaign(db_session, campaign_id=campaign.id)
        await crud_update_queue.set_status(db_session, entry_id=entries[0].id, status=UpdateQueueStatusEnum.COMPLETED)
        await update_notifier.progress(db_session, campaign, current="оновлюється **skyblock-solo-p1**")

    post.assert_awaited_once()                       # одне повідомлення на кампанію
    first = post.await_args.args[0]
    assert first["title"] == "🔄 Оновлення v9.9.9 — 0/3"
    assert "шаблону" in first["description"]
    msg_id, embed = edit.await_args.args
    assert msg_id == "msg-1"
    assert embed["title"] == "🔄 Оновлення v9.9.9 — 1/3"
    assert "skyblock-solo-p1" in embed["description"] and "✅ оновлено 1" in embed["description"]


@pytest.mark.asyncio
async def test_finish_edits_live_message_and_posts_result(db_session: AsyncSession):
    campaign = await _campaign_with_islands(db_session, n=2)
    entries = await crud_update_queue.get_all_for_campaign(db_session, campaign_id=campaign.id)
    await crud_update_queue.set_status(db_session, entry_id=entries[0].id, status=UpdateQueueStatusEnum.COMPLETED)
    await crud_update_queue.set_status(db_session, entry_id=entries[1].id, status=UpdateQueueStatusEnum.FAILED)
    redis, post, edit = _FakeRedis(), AsyncMock(return_value="msg-2"), AsyncMock(return_value=True)
    redis.data[f"update_campaign_discord_msg:{campaign.id}"] = "msg-2"
    p1, p2, p3, p4 = _patched(redis, post, edit)
    with p1, p2, p3, p4:
        await update_notifier.campaign_finished(db_session, campaign)
    assert edit.await_args.args[1]["title"] == "✅ Оновлення v9.9.9 завершено — 2/2"
    result = post.await_args.args[0]
    assert "❌ помилки 1" in result["description"] and result["color"] == 0xE53935


@pytest.mark.asyncio
async def test_discord_failure_never_breaks_the_campaign(db_session: AsyncSession):
    campaign = await _campaign_with_islands(db_session, n=1)
    redis = _FakeRedis()
    post = AsyncMock(side_effect=RuntimeError("discord down"))
    p1, p2, p3, p4 = _patched(redis, post, AsyncMock(return_value=False))
    with p1, p2, p3, p4:
        await update_notifier.campaign_started(db_session, campaign)
        await update_notifier.island_failed(campaign, SimpleNamespace(id=1, container_name="c", player_name=None), "boom")
        await update_notifier.campaign_finished(db_session, campaign)


@pytest.mark.asyncio
async def test_disabled_without_webhook(db_session: AsyncSession):
    campaign = await _campaign_with_islands(db_session, n=1)
    post = AsyncMock()
    with patch.object(update_notifier.settings, "UPDATES_DISCORD_WEBHOOK", ""), \
         patch("app.services.update_notifier._post", post):
        await update_notifier.campaign_started(db_session, campaign)
        await update_notifier.campaign_finished(db_session, campaign)
    post.assert_not_awaited()


# ── per-island messages ────────────────────────────────────────────────

def _isl(i=1, name="skyblock-solo-Hiklee-fec7b171", player="Hiklee"):
    return SimpleNamespace(id=i, container_name=name, player_name=player)


@pytest.mark.asyncio
async def test_each_island_gets_start_and_done_messages():
    campaign = SimpleNamespace(id=7, version="v1.2.3")
    redis, post = _FakeRedis(), AsyncMock()
    p1, p2, p3, p4 = _patched(redis, post, AsyncMock())
    with p1, p2, p3, p4:
        await update_notifier.island_started(campaign, _isl(), restart=True)
        await update_notifier.island_done(campaign, _isl(), 42.7)
    titles = [c.args[0]["title"] for c in post.await_args_list]
    assert titles == ["🔧 v1.2.3: оновлюється skyblock-solo-Hiklee-fec7b171",
                      "✅ v1.2.3: skyblock-solo-Hiklee-fec7b171 оновлено"]
    assert "гравець Hiklee" in post.await_args_list[0].args[0]["description"]
    assert "за 42 с" in post.await_args_list[1].args[0]["description"]


@pytest.mark.asyncio
async def test_pause_reason_sent_once_per_reason():
    campaign = SimpleNamespace(id=7, version="v1.2.3")
    redis, post = _FakeRedis(), AsyncMock()
    p1, p2, p3, p4 = _patched(redis, post, AsyncMock())
    with p1, p2, p3, p4:
        for _ in range(5):  # воркер перевіряє відкладений острів кожні 10 с
            await update_notifier.island_paused(campaign, _isl(), "на острові грають")
        await update_notifier.island_paused(campaign, _isl(), "адмін працює з островом")
        await update_notifier.island_paused(campaign, _isl(2, "skyblock-solo-other", None), "на острові грають")
    assert post.await_count == 3
    assert "**Чому:** на острові грають" in post.await_args_list[0].args[0]["description"]


# ── tags ───────────────────────────────────────────────────────────────

@pytest.mark.parametrize("tag,hint", [
    ("v.0.0.18", "v0.0.18"),
    ("V.0.0.4", "v0.0.4"),
    ("0.0.17", "v0.0.17"),
    ("v0.0..19", "v0.0.19"),
    ("v.0.025", "v0.025"),
    ("v0.0.18", None),          # уже правильний
    ("release-5", None),        # не вгадати
])
def test_suggest_tag(tag, hint):
    assert update_notifier.suggest_tag(tag) == hint


@pytest.mark.asyncio
async def test_rejected_tag_message_shows_fix():
    post = AsyncMock()
    with patch.object(update_notifier.settings, "UPDATES_DISCORD_WEBHOOK", "https://discord/hook"), \
         patch("app.services.update_notifier._post", post):
        await update_notifier.tag_rejected("v.0.0.18", "тег не відповідає формату версії")
    embed = post.await_args.args[0]
    assert embed["title"] == "❌ Тег v.0.0.18: оновлення не запущено"
    assert "**v0.0.18**" in embed["description"]
