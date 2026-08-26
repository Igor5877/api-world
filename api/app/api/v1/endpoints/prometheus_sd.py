import logging
from typing import Any

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core.auth import require_prometheus_sd_token
from app.core.config import settings
from app.db.session import get_db_session as get_db
from app.models.island import Island as IslandModel, IslandStatusEnum

router = APIRouter()
logger = logging.getLogger(__name__)


@router.get("/sd", dependencies=[Depends(require_prometheus_sd_token)])
async def prometheus_service_discovery(db: AsyncSession = Depends(get_db)) -> Any:
    """Prometheus http_sd_config target list — усі RUNNING острови.

    Формат: стандартний http_sd_config ([{"targets": [...], "labels": {...}}]).
    FROZEN/STOPPED/CREATING острови виключені — вони однаково не відповідають
    на scrape, і зникнення з цього списку є для Prometheus нормальним сигналом
    (up=0), а не помилкою.
    """
    result = await db.execute(
        select(IslandModel)
        .options(selectinload(IslandModel.team))
        .where(
            IslandModel.status == IslandStatusEnum.RUNNING,
            IslandModel.internal_ip_address.isnot(None),
        )
    )
    islands = result.scalars().all()

    targets = []
    for island in islands:
        player_label = (island.team.name if island.team else None) or island.player_name or island.container_name
        targets.append(
            {
                "targets": [f"{island.internal_ip_address}:{settings.METRICS_EXPORTER_PORT}"],
                "labels": {
                    "island_uuid": island.container_name,
                    "player": player_label,
                },
            }
        )

    return targets
