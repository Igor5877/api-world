-- Міграція: IslandAdminCommand діагностика (MSPT з heartbeat мода)
-- Виконати один раз: mysql -u <user> -p <db> < migration_2026-08_admin_diagnostics.sql

ALTER TABLE islands ADD COLUMN last_mspt FLOAT NULL AFTER last_tps;
