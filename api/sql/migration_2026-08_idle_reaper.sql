-- Міграція: Idle Reaper (island lifecycle reliability)
-- Виконати один раз: mysql -u <user> -p <db> < migration_2026-08_idle_reaper.sql

-- islands: коли heartbeat вперше повідомив online_players=0 (NULL, якщо
-- зараз є гравці або героя острова RUNNING без heartbeat-сигналу взагалі).
-- Не плутати з updated_at — той оновлюється щохартбітом незалежно від
-- к-сті гравців, тож не годиться для "скільки часу острів реально порожній".
ALTER TABLE islands ADD COLUMN idle_since DATETIME NULL AFTER online_players;
