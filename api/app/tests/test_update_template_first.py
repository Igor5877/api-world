"""Template-first update campaigns: the template is refreshed when the campaign
starts, and islands cloned from the old image meanwhile are not left behind."""
import uuid
from datetime import datetime, timedelta
from unittest.mock import AsyncMock, patch

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.crud.crud_island import crud_island
from app.crud.crud_update import crud_update_campaign
from app.crud.crud_update_queue import crud_update_queue
from app.models.island import Island as IslandModel
from app.models.update import CampaignStatusEnum
from app.schemas.island import IslandStatusEnum
from app.services import update_worker


async def _island(db: AsyncSession, *, version, created_at, is_spare=False) -> IslandModel:
    island = IslandModel(container_name=f"skyblock-solo-{uuid.uuid4().hex[:8]}",
                         status=IslandStatusEnum.STOPPED, current_version=version,
                         is_spare=is_spare, created_at=created_at)
    db.add(island)
    await db.commit()
    await db.refresh(island)
    return island


async def _campaign(db: AsyncSession, version="v2.0.0"):
    return await crud_update_campaign.create(db, version=version, status=CampaignStatusEnum.IN_PROGRESS,
                                             created_at=datetime.utcnow() - timedelta(minutes=10))


async def _queued(db, campaign, island) -> bool:
    return await crud_update_queue.get_by_campaign_and_island(
        db, campaign_id=campaign.id, island_id=island.id) is not None


@pytest.mark.asyncio
async def test_island_cloned_from_old_template_during_campaign_is_queued(db_session: AsyncSession):
    campaign = await _campaign(db_session)
    now = datetime.utcnow()
    straggler = await _island(db_session, version="v1.0.0", created_at=now)
    fresh = await _island(db_session, version="v2.0.0", created_at=now)
    older = await _island(db_session, version="v1.0.0", created_at=now - timedelta(days=1))
    spare = await _island(db_session, version="v1.0.0", created_at=now, is_spare=True)

    assert await update_worker.enqueue_islands_cloned_from_old_template(db_session, campaign) == 1
    assert await _queued(db_session, campaign, straggler)
    assert not await _queued(db_session, campaign, fresh)    # уже з нового шаблону
    assert not await _queued(db_session, campaign, older)    # існував до кампанії: черга вирішила сама
    assert not await _queued(db_session, campaign, spare)    # спейри перестворює пул
    # повторний прохід нічого не додає
    assert await update_worker.enqueue_islands_cloned_from_old_template(db_session, campaign) == 0


@pytest.mark.asyncio
async def test_refresh_template_republishes_and_retires_spares(db_session: AsyncSession):
    campaign = await _campaign(db_session)
    lxd = AsyncMock()
    lxd.get_image_version.return_value = "v1.0.0"
    service = AsyncMock()
    retire = AsyncMock()
    with patch("app.services.update_worker.lxd_service", lxd), \
         patch("app.services.update_worker.update_service", service), \
         patch("app.services.update_worker.spare_pool.retire_spares", retire):
        await update_worker.refresh_template(db_session, campaign)
    service.update_template_container.assert_awaited_once_with("v2.0.0", campaign=campaign)
    retire.assert_awaited_once()


@pytest.mark.asyncio
async def test_refresh_template_skips_when_image_already_current(db_session: AsyncSession):
    campaign = await _campaign(db_session)
    lxd = AsyncMock()
    lxd.get_image_version.return_value = "v2.0.0"
    service = AsyncMock()
    with patch("app.services.update_worker.lxd_service", lxd), \
         patch("app.services.update_worker.update_service", service):
        await update_worker.refresh_template(db_session, campaign)
    service.update_template_container.assert_not_awaited()
