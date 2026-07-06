package dev.ftb.mods.ftbquests.events;

import dev.ftb.mods.ftbquests.quest.IslandData;
import dev.ftb.mods.ftbquests.quest.QuestObject;
import dev.ftb.mods.ftbquests.quest.TeamData;
import net.minecraft.server.level.ServerPlayer;

import java.util.Date;
import java.util.List;

public abstract class ObjectProgressEvent<T extends QuestObject> {
    protected final QuestProgressEventData<T> data;

    protected ObjectProgressEvent(QuestProgressEventData<T> d) {
        data = d;
    }

    public boolean isCancelable() {
        return true;
    }

    public Date getTime() {
        return data.getTime();
    }

    public IslandData getIslandData() {
        return data.getIslandData();
    }

    /**
     * @deprecated Binary-compat shim for addons compiled against the pre-fork
     * API (e.g. ftb-xmod-compat's KubeJS integration calls this exact
     * signature via reflection/ASM). Use {@link #getIslandData()} instead.
     */
    @Deprecated
    public TeamData getData() {
        return new TeamData(data.getIslandData());
    }

    public T getObject() {
        return data.getObject();
    }

    public List<ServerPlayer> getOnlineMembers() {
        return data.getOnlineMembers();
    }

    public List<ServerPlayer> getNotifiedPlayers() {
        return data.getNotifiedPlayers();
    }
}
