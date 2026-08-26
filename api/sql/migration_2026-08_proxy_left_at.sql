-- Міграція: proxy_left_at (idle reaper stop-backstop, lifecycle_stability_plan.md §3)
-- Виконати один раз: mysql -u <user> -p <db> < migration_2026-08_proxy_left_at.sql

ALTER TABLE islands ADD COLUMN proxy_left_at DATETIME NULL AFTER idle_since;
