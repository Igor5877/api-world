-- Database schema for Dynamic SkyBlock on LXD

-- Main table for storing information about each player's island
CREATE TABLE IF NOT EXISTS islands (
    id INT AUTO_INCREMENT PRIMARY KEY,
    player_uuid VARCHAR(36) NOT NULL UNIQUE, -- Minecraft player UUID
    player_name VARCHAR(16), -- Minecraft player name, can be updated
    container_name VARCHAR(255) NOT NULL UNIQUE, -- LXD container name (e.g., skyblock-playerUUID)
    
    -- Status of the island
    -- CREATING: Container is being provisioned
    -- STOPPED: Container exists but Minecraft server is not running
    -- RUNNING: Container and Minecraft server are active
    -- FROZEN: Container is suspended (ram saved to disk, fast resume)
    -- DELETING: Container is marked for deletion
    -- ARCHIVED: Container is stopped and archived (snapshot/backup exists)
    -- ERROR: An error occurred with this island
    status VARCHAR(50) NOT NULL DEFAULT 'CREATING',
    
    internal_ip_address VARCHAR(45), -- Internal IP address of the LXD container
    internal_port INT DEFAULT 25565, -- Minecraft server port inside the container
    external_port INT UNIQUE, -- Port on the host machine mapped to the container's Minecraft server port (if needed by Velocity setup)
    
    world_seed VARCHAR(255), -- Optional: if each island can have a unique seed
    
    current_version VARCHAR(50) NULL, -- last applied update tag (auto-update system)
    skip_auto_updates BOOLEAN NOT NULL DEFAULT FALSE, -- unique servers are never auto-updated
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NULL, -- Timestamp of when the player was last on their island or connected to proxy
    
    INDEX idx_player_uuid (player_uuid),
    INDEX idx_status (status),
    INDEX idx_container_name (container_name),
    INDEX idx_last_seen_at (last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Table for managing the queue of players waiting for island CREATION
-- NOTE: No FK to islands — island does not yet exist at queue time
CREATE TABLE IF NOT EXISTS island_queue (
    id          INT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_uuid VARCHAR(36) NOT NULL UNIQUE,
    player_name VARCHAR(16) NULL,
    status      ENUM('PENDING','PROCESSING','FAILED') NOT NULL DEFAULT 'PENDING',
    requested_at DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_island_queue_status (status),
    INDEX idx_island_queue_requested_at (requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Optional: Table for island settings or metadata, if needed later
CREATE TABLE IF NOT EXISTS island_settings (
    island_id INT NOT NULL,
    setting_key VARCHAR(255) NOT NULL,
    setting_value TEXT,
    
    PRIMARY KEY (island_id, setting_key),
    FOREIGN KEY (island_id) REFERENCES islands(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Optional: Table for storing information about island backups/snapshots
CREATE TABLE IF NOT EXISTS island_backups (
    id INT AUTO_INCREMENT PRIMARY KEY,
    island_id INT NOT NULL,
    snapshot_name VARCHAR(255) NOT NULL, -- LXD snapshot name
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    description TEXT,
    backup_type VARCHAR(20) NOT NULL DEFAULT 'snapshot', -- 'snapshot' | 'files' (auto-update system)
    backup_path VARCHAR(512) NULL,  -- host-side dir with saved files (backup_type='files')
    changed_paths JSON NULL,        -- [git-status, path] pairs the backup covers
    version VARCHAR(50) NULL,       -- campaign version the backup was made for
    campaign_id INT NULL,
    
    FOREIGN KEY (island_id) REFERENCES islands(id) ON DELETE CASCADE,
    INDEX idx_island_id (island_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Note:
-- Consider character sets and collations based on your needs (utf8mb4 is good for general multilingual support).
-- `player_name` can change, so `player_uuid` is the reliable unique identifier.
-- `external_port` might be managed by Velocity or your API; if Velocity handles all routing to internal IPs, this might not be strictly needed in the DB or could be used differently.
-- Added `updated_at` and `last_seen_at` for lifecycle management.
-- Added `island_queue` table as per Stage 2.
-- Added optional `island_settings` and `island_backups` tables for future enhancements (Stage 3).
-- The `status` ENUM is now a VARCHAR, offering more flexibility than a rigid ENUM type if statuses change. Added 'ARCHIVED' and 'ERROR' as potential statuses.
-- `internal_ip_address` and `internal_port` store the direct address of the Minecraft server within the container.
-- `world_seed` is added as an optional field.
-- `ON DELETE CASCADE` for `island_queue`, `island_settings`, and `island_backups` means if an island is deleted, its related entries in these tables are also automatically deleted.
-- `player_uuid` in `islands` table is UNIQUE.
-- `container_name` in `islands` table is UNIQUE.
-- `external_port` in `islands` table is UNIQUE (if used for direct host port mapping).
-- `player_uuid` in `island_queue` table is UNIQUE.

-- ─────────────────────────────────────────────────────────────────
-- Teams
-- ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS teams (
    id         INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(255) NOT NULL UNIQUE,
    owner_uuid VARCHAR(36)  NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_teams_owner_uuid (owner_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS team_members (
    id         INT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    team_id    INT         NOT NULL,
    player_uuid VARCHAR(36) NOT NULL,
    player_name VARCHAR(32) NULL,
    role       ENUM('owner','moderator','member') NOT NULL DEFAULT 'member',
    joined_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    UNIQUE KEY uq_team_player (team_id, player_uuid),
    INDEX idx_team_members_player_uuid (player_uuid),
    INDEX idx_team_members_player_name (player_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ─────────────────────────────────────────────────────────────────
-- Island start queue (запуск існуючих островів)
-- ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS island_start_queue (
    id          INT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_uuid VARCHAR(36) NOT NULL UNIQUE,
    player_name VARCHAR(16) NULL,
    status      ENUM('PENDING','PROCESSING','FAILED') NOT NULL DEFAULT 'PENDING',
    requested_at DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_island_start_queue_status (status),
    INDEX idx_island_start_queue_requested_at (requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ─────────────────────────────────────────────────────────────────
-- Market
-- ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS market_items (
    id                 INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    island_uuid        VARCHAR(36)  NOT NULL,
    item_id            VARCHAR(255) NOT NULL,
    item_nbt           TEXT         NOT NULL,  -- TEXT щоб уникнути row-size overflow; '' замість NULL для UNIQUE (default керується ORM)
    quantity           BIGINT       NOT NULL DEFAULT 0,
    price              DOUBLE       NOT NULL DEFAULT 10.0,
    is_for_sale        TINYINT(1)   NOT NULL DEFAULT 1,
    version            INT          NOT NULL DEFAULT 1,
    seller_azuriom_id  INT          NULL,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- Prefix-length UNIQUE: item_id(191) + item_nbt(500) вкладається в ліміт InnoDB індексу
    UNIQUE KEY uq_island_item (island_uuid, item_id(191), item_nbt(500)),
    INDEX idx_market_items_island_uuid (island_uuid),
    INDEX idx_market_items_item_id (item_id),
    INDEX idx_market_items_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS market_transactions (
    id                INT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    island_uuid       VARCHAR(36) NOT NULL,
    item_id           VARCHAR(255) NOT NULL,
    quantity          INT         NOT NULL,
    unit_price        DOUBLE      NOT NULL,
    total_price       DOUBLE      NOT NULL,
    buyer_azuriom_id  INT         NULL,
    seller_azuriom_id INT         NULL,
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_market_tx_island_uuid (island_uuid),
    INDEX idx_market_tx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS market_pending_extractions (
    id          INT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    island_uuid VARCHAR(36) NOT NULL,
    item_id     VARCHAR(255) NOT NULL,
    quantity    INT         NOT NULL,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_pending_ext_island_uuid (island_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ─────────────────────────────────────────────────────────────────
-- Migrations (виконувати вручну на існуючій БД)
-- ─────────────────────────────────────────────────────────────────

-- Migration: add seller_azuriom_id to market_items
-- ALTER TABLE market_items ADD COLUMN seller_azuriom_id INT NULL;

-- Migration: fix item_nbt NULL → '' + VARCHAR→TEXT + розширення prefix індексу
-- UPDATE market_items SET item_nbt = '' WHERE item_nbt IS NULL;
-- ALTER TABLE market_items MODIFY COLUMN item_nbt TEXT NOT NULL;
-- DROP INDEX uq_island_item ON market_items;
-- ALTER TABLE market_items ADD UNIQUE KEY uq_island_item (island_uuid, item_id(191), item_nbt(500));

-- Migration: island_uuid → team_id (INT FK) у всіх market таблицях
-- Крок 1: додати нову колонку
-- ALTER TABLE market_items ADD COLUMN team_id INT NULL;
-- ALTER TABLE market_transactions ADD COLUMN team_id INT NULL;
-- ALTER TABLE market_pending_extractions ADD COLUMN team_id INT NULL;
-- Крок 2: заповнити team_id через player_uuid → team_members → teams
-- UPDATE market_items mi
--   JOIN team_members tm ON tm.player_uuid = mi.island_uuid
--   SET mi.team_id = tm.team_id;
-- UPDATE market_transactions mt
--   JOIN team_members tm ON tm.player_uuid = mt.island_uuid
--   SET mt.team_id = tm.team_id;
-- UPDATE market_pending_extractions mp
--   JOIN team_members tm ON tm.player_uuid = mp.island_uuid
--   SET mp.team_id = tm.team_id;
-- Крок 3: зробити NOT NULL і додати FK
-- ALTER TABLE market_items MODIFY COLUMN team_id INT NOT NULL;
-- ALTER TABLE market_items ADD CONSTRAINT fk_market_items_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE;
-- ALTER TABLE market_transactions MODIFY COLUMN team_id INT NOT NULL;
-- ALTER TABLE market_transactions ADD CONSTRAINT fk_market_transactions_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE;
-- ALTER TABLE market_pending_extractions MODIFY COLUMN team_id INT NOT NULL;
-- ALTER TABLE market_pending_extractions ADD CONSTRAINT fk_market_pending_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE;
-- Крок 4: перебудувати UNIQUE індекс
-- DROP INDEX uq_island_item ON market_items;
-- ALTER TABLE market_items ADD UNIQUE KEY uq_team_item (team_id, item_id(191), item_nbt(500));
-- Крок 5: видалити старі колонки
-- ALTER TABLE market_items DROP COLUMN island_uuid;
-- ALTER TABLE market_transactions DROP COLUMN island_uuid;
-- ALTER TABLE market_pending_extractions DROP COLUMN island_uuid;

-- Migration: performance indexes (after team_id migration)
-- ALTER TABLE market_items ADD INDEX idx_team_id (team_id);
-- ALTER TABLE market_pending_extractions ADD INDEX idx_created_at (created_at);

-- Example of how to get the next player from the queue:
-- SELECT player_uuid FROM island_queue ORDER BY requested_at ASC LIMIT 1;

-- Example of how to find islands that haven't been seen for a while (e.g., > 30 days):
-- SELECT * FROM islands WHERE status = 'STOPPED' AND last_seen_at < NOW() - INTERVAL 30 DAY;

-- Example of how to find islands that are FROZEN and last_seen_at is older than 15 minutes (candidate for stopping)
-- SELECT * FROM islands WHERE status = 'FROZEN' AND last_seen_at < NOW() - INTERVAL 15 MINUTE;

-- Example of how to find islands that are RUNNING and player is not online (hypothetically, you'd get this info from Velocity/Hub)
-- and last_seen_at is older than 5 minutes (candidate for freezing)
-- This query would need external info, but the `last_seen_at` is key:
-- SELECT * FROM islands WHERE status = 'RUNNING' AND last_seen_at < NOW() - INTERVAL 5 MINUTE;

-- Migration: add player_name to team_members for UUID lookup by nickname
-- ALTER TABLE team_members ADD COLUMN player_name VARCHAR(32) NULL AFTER player_uuid;
-- ALTER TABLE team_members ADD INDEX idx_team_members_player_name (player_name);

-- Migration: warp_pending_commands (команди для spawn_hub, що чекають виконання)
CREATE TABLE IF NOT EXISTS warp_pending_commands (
    id          INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_uuid VARCHAR(36)  NOT NULL,
    command     VARCHAR(50)  NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_player_uuid (player_uuid)
);

-- ─────────────────────────────────────────────────────────────────
-- Auto-update system (2026-07: UPDATE_SYSTEM_PLAN.md)
-- ─────────────────────────────────────────────────────────────────

-- Одна кампанія = один git-тег репозиторію skyblock-updates
CREATE TABLE IF NOT EXISTS update_campaigns (
    id               INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    version          VARCHAR(50)  NOT NULL UNIQUE,
    previous_version VARCHAR(50)  NULL,
    git_commit       VARCHAR(40)  NULL,
    update_type      ENUM('server_only','both','critical') NOT NULL DEFAULT 'server_only',
    requires_restart BOOLEAN      NOT NULL DEFAULT FALSE,
    reload_commands  JSON         NULL,       -- ["ftbquests reload", "reload"]
    changed_paths    JSON         NULL,       -- пари [git-статус, шлях] з diff --name-status
    message          TEXT         NULL,       -- commit message для гравців/логів
    status           ENUM('PENDING','IN_PROGRESS','COMPLETED','FAILED','ROLLED_BACK') NOT NULL DEFAULT 'PENDING',
    error_message    TEXT         NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at     DATETIME     NULL,
    INDEX idx_update_campaigns_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Черга островів у межах кампанії
-- WAITING = острів онлайн, чекаємо виходу гравця (тригер: POST /islands/{uuid}/player_left)
CREATE TABLE IF NOT EXISTS update_queue (
    id                    INT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    campaign_id           INT         NOT NULL,
    island_id             INT         NOT NULL,
    player_uuid           VARCHAR(36) NULL,
    status                ENUM('PENDING','PROCESSING','WAITING','COMPLETED','FAILED','SKIPPED') NOT NULL DEFAULT 'PENDING',
    error_message         TEXT        NULL,
    retry_count           INT         NOT NULL DEFAULT 0,
    added_to_queue_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processing_started_at DATETIME    NULL,
    completed_at          DATETIME    NULL,
    FOREIGN KEY (campaign_id) REFERENCES update_campaigns(id) ON DELETE CASCADE,
    FOREIGN KEY (island_id)   REFERENCES islands(id)          ON DELETE CASCADE,
    UNIQUE KEY uq_campaign_island (campaign_id, island_id),
    INDEX idx_update_queue_status (status),
    INDEX idx_update_queue_added (added_to_queue_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Команди для острівних серверів, що чекають виконання
-- (досилаються при реконнекті WebSocket — той самий патерн, що market_pending_extractions)
CREATE TABLE IF NOT EXISTS island_pending_commands (
    id          INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    island_id   INT          NOT NULL,
    player_uuid VARCHAR(36)  NOT NULL,
    command     TEXT         NOT NULL,
    campaign_id INT          NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered   BOOLEAN      NOT NULL DEFAULT FALSE,
    FOREIGN KEY (island_id)   REFERENCES islands(id)          ON DELETE CASCADE,
    FOREIGN KEY (campaign_id) REFERENCES update_campaigns(id) ON DELETE SET NULL,
    INDEX idx_ipc_player_uuid (player_uuid),
    INDEX idx_ipc_delivered (delivered)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Migration: нові колонки islands (виконати на існуючій БД)
-- ALTER TABLE islands ADD COLUMN current_version VARCHAR(50) NULL AFTER minecraft_ready;
-- ALTER TABLE islands ADD COLUMN skip_auto_updates BOOLEAN NOT NULL DEFAULT FALSE AFTER current_version;

-- Migration: розширення island_backups для file-level відкатів
-- ALTER TABLE island_backups ADD COLUMN backup_type VARCHAR(20) NOT NULL DEFAULT 'snapshot' AFTER description;
-- ALTER TABLE island_backups ADD COLUMN backup_path VARCHAR(512) NULL AFTER backup_type;
-- ALTER TABLE island_backups ADD COLUMN changed_paths JSON NULL AFTER backup_path;
-- ALTER TABLE island_backups ADD COLUMN version VARCHAR(50) NULL AFTER changed_paths;
-- ALTER TABLE island_backups ADD COLUMN campaign_id INT NULL AFTER version;
-- ALTER TABLE island_backups ADD CONSTRAINT fk_island_backups_campaign FOREIGN KEY (campaign_id) REFERENCES update_campaigns(id) ON DELETE SET NULL;
