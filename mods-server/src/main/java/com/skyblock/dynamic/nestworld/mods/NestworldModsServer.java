package com.skyblock.dynamic.nestworld.mods;

import java.util.UUID;

public class NestworldModsServer {

    public static final TeamProvider TEAM_PROVIDER = new TeamProvider();

    public static class TeamProvider {
        public UUID getCachedTeamId(UUID playerUuid) {
            // Placeholder implementation
            return null;
        }
    }
}
