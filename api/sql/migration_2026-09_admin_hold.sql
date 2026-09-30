-- Міграція: адмін-режим острова (services/admin_hold.py)
-- Виконати один раз: mysql -u <user> -p <db> < migration_2026-09_admin_hold.sql

ALTER TABLE islands ADD COLUMN admin_hold_by VARCHAR(64) NULL AFTER spare_claimed_at;
ALTER TABLE islands ADD COLUMN admin_hold_reason VARCHAR(255) NULL AFTER admin_hold_by;
ALTER TABLE islands ADD COLUMN admin_hold_until DATETIME NULL AFTER admin_hold_reason;
