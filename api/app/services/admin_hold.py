"""Адмін-режим острова (admin hold).

Адмін позначає острів, над яким працює (/islandadmin hold). Поки позначка
діє, API сам цей острів не чіпає:

- health_worker не зупиняє його за мовчання heartbeat;
- idle_reaper не виправляє статус, не зупиняє і не розбирає "застряглі" переходи;
- кампанії оновлень відкладають його;
- старт гравцем, старт лаунчером, стоп від проксі і заморозка від мода
  отримують 423 — ці запити приходять автоматично, не від адміна.

Адмінські дії (/islandadmin start|stop|restart|join) проходять: вони
передають admin_name. Позначка має термін дії (за замовчуванням 2 год),
щоб забутий hold не лишив острів без нагляду назавжди. Після зняття
last_heartbeat_at скидається: watchdog чекає свіжого heartbeat, а
idle_reaper на наступному тику звіряє статус у БД з реальним станом LXD.
"""
from datetime import datetime
from typing import Optional

from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

DEFAULT_MINUTES = 120
MAX_MINUTES = 24 * 60


def is_held(island, now: Optional[datetime] = None) -> bool:
    until = getattr(island, "admin_hold_until", None)
    return until is not None and until > (now or datetime.utcnow())


async def island_for_player(db_session: AsyncSession, player_uuid: str):
    """Острів гравця: через команду (учасник або власник), інакше легасі-соло."""
    from app.crud import crud_team
    from app.crud.crud_island import crud_island

    team = await crud_team.get_team_by_player(db_session, player_uuid=player_uuid)
    if team and team.island:
        return team.island
    return await crud_island.get_by_player_uuid(db_session, player_uuid=player_uuid)


async def guard_automatic_action(db_session: AsyncSession, player_uuid: str, action: str,
                                 admin_name: Optional[str] = None) -> None:
    """423, якщо острів на hold, а запит не від адміна."""
    if admin_name:
        return
    island = await island_for_player(db_session, player_uuid)
    if island is not None and is_held(island):
        raise HTTPException(
            status_code=status.HTTP_423_LOCKED,
            detail=f"Острів на обслуговуванні (адмін {island.admin_hold_by}, до "
                   f"{island.admin_hold_until:%H:%M} UTC) — {action} недоступний.")
