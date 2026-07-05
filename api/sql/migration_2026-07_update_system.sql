-- Міграція для існуючої БД: система автоматичних оновлень островів
-- Виконати один раз: mysql -u <user> -p <db> < migration_2026-07_update_system.sql
-- Нові таблиці (update_campaigns, update_queue, island_pending_commands)
-- створюються через schema.sql (CREATE TABLE IF NOT EXISTS) — запусти його теж.

-- islands: версія острова та прапорець "не чіпати"
ALTER TABLE islands ADD COLUMN current_version VARCHAR(50) NULL AFTER minecraft_ready;
ALTER TABLE islands ADD COLUMN skip_auto_updates BOOLEAN NOT NULL DEFAULT FALSE AFTER current_version;

-- island_backups: file-level бекапи для відкату без snapshot
ALTER TABLE island_backups ADD COLUMN backup_type VARCHAR(20) NOT NULL DEFAULT 'snapshot' AFTER description;
ALTER TABLE island_backups ADD COLUMN backup_path VARCHAR(512) NULL AFTER backup_type;
ALTER TABLE island_backups ADD COLUMN changed_paths JSON NULL AFTER backup_path;
ALTER TABLE island_backups ADD COLUMN version VARCHAR(50) NULL AFTER changed_paths;
ALTER TABLE island_backups ADD COLUMN campaign_id INT NULL AFTER version;
ALTER TABLE island_backups ADD CONSTRAINT fk_island_backups_campaign
    FOREIGN KEY (campaign_id) REFERENCES update_campaigns(id) ON DELETE SET NULL;
