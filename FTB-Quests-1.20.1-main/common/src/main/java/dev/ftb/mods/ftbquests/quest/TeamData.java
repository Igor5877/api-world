package dev.ftb.mods.ftbquests.quest;

import java.util.UUID;

/**
 * @deprecated For backward compatibility with addons that still reference the
 * pre-fork class name/methods (e.g. Mixins targeting {@code TeamData.canStartTasks}).
 * Delegates everything to the real {@link IslandData} so those Mixins keep working.
 */
@Deprecated
public class TeamData {
    public final IslandData islandData;

    public TeamData(UUID islandId, BaseQuestFile file) {
        this.islandData = new IslandData(islandId, file);
    }

    public TeamData(UUID islandId, BaseQuestFile file, String name) {
        this.islandData = new IslandData(islandId, file, name);
    }

    /** Wraps an existing IslandData instead of creating a new one. */
    public TeamData(IslandData islandData) {
        this.islandData = islandData;
    }

    public boolean canStartTasks(Quest quest) {
        return islandData.canStartTasks(quest);
    }

    public boolean areDependenciesComplete(Quest quest) {
        return islandData.areDependenciesComplete(quest);
    }

    public boolean areDependenciesVisible(Quest quest) {
        return islandData.areDependenciesVisible(quest);
    }

    public BaseQuestFile getFile() {
        return islandData.getFile();
    }

    public UUID getTeamId() {
        return islandData.getTeamId();
    }

    public void markDirty() {
        islandData.markDirty();
    }
}
