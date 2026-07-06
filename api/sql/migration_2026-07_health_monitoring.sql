-- Моніторинг здоров'я островів (heartbeat + watchdog + журнал подій)
-- Виконати один раз на dev і prod базах:
--   mysql -u <user> -p <db> < migration_2026-07_health_monitoring.sql

-- Heartbeat від Forge-мода (кожні ~30с по WebSocket)
ALTER TABLE islands
    ADD COLUMN last_heartbeat_at DATETIME NULL AFTER minecraft_ready,
    ADD COLUMN last_tps FLOAT NULL AFTER last_heartbeat_at,
    ADD COLUMN online_players INT NULL AFTER last_tps;

-- Журнал інцидентів: crashed | hung | stopped_externally | restarted |
-- stopping | state_mismatch
CREATE TABLE IF NOT EXISTS island_events (
    id INT AUTO_INCREMENT PRIMARY KEY,
    island_id INT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    details VARCHAR(1024) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_island_events_island
        FOREIGN KEY (island_id) REFERENCES islands(id) ON DELETE CASCADE,
    INDEX idx_island_events_island_created (island_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
