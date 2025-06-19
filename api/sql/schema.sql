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
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NULL, -- Timestamp of when the player was last on their island or connected to proxy
    
    INDEX idx_player_uuid (player_uuid),
    INDEX idx_status (status),
    INDEX idx_container_name (container_name),
    INDEX idx_last_seen_at (last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Table for managing the queue of players waiting for an island to start
CREATE TABLE IF NOT EXISTS island_queue (
    id INT AUTO_INCREMENT PRIMARY KEY,
    player_uuid VARCHAR(36) NOT NULL UNIQUE, -- Minecraft player UUID of the player in queue
    requested_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, -- When the player was added to the queue
    
    FOREIGN KEY (player_uuid) REFERENCES islands(player_uuid) ON DELETE CASCADE,
    INDEX idx_requested_at (requested_at)
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
