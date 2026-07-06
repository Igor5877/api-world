-- Система запрошень у команди (API_TEAMS_TODO.md §1).
-- Виконати один раз на існуючій базі.

CREATE TABLE IF NOT EXISTS team_invites (
    id INT AUTO_INCREMENT PRIMARY KEY,
    team_id INT NOT NULL,
    invited_uuid VARCHAR(36) NOT NULL,
    invited_name VARCHAR(32) NULL,
    inviter_uuid VARCHAR(36) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NULL,
    UNIQUE KEY uq_team_invite (team_id, invited_uuid),
    KEY idx_team_invites_invited (invited_uuid),
    CONSTRAINT fk_team_invites_team FOREIGN KEY (team_id)
        REFERENCES teams (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
