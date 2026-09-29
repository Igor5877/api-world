-- Міграція: warm spare pool (services/spare_pool.py)
-- Виконати один раз: mysql -u <user> -p <db> < migration_2026-09_spare_pool.sql

ALTER TABLE islands ADD COLUMN is_spare BOOLEAN NOT NULL DEFAULT FALSE AFTER proxy_left_at;
ALTER TABLE islands ADD COLUMN spare_claimed_at DATETIME NULL AFTER is_spare;
CREATE INDEX idx_islands_is_spare ON islands (is_spare);
