package dev.ftb.mods.ftbquests.quest;

import java.util.UUID;

public class IslandData extends TeamData {
    public static final IslandData UNLOADED = new IslandData(new UUID(0L, 0L), null, "UNLOADED") {
        @Override
        public boolean isMember(UUID playerUuid) {
            return false;
        }
    };

    public IslandData(UUID islandId, BaseQuestFile file) {
        super(islandId, file);
    }

    public IslandData(UUID islandId, BaseQuestFile file, String name) {
        super(islandId, file, name);
    }
}
