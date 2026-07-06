-- Прогрес FTB Quests: острів заливає снапшот, спавн читає (read-only показ).
-- Виконати один раз на існуючій базі.

CREATE TABLE IF NOT EXISTS island_quest_progress (
    island_id INT NOT NULL,
    owner_uuid VARCHAR(36) NOT NULL,
    snbt MEDIUMTEXT NOT NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (island_id),
    UNIQUE KEY uq_quest_progress_owner (owner_uuid),
    CONSTRAINT fk_quest_progress_island FOREIGN KEY (island_id)
        REFERENCES islands (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
