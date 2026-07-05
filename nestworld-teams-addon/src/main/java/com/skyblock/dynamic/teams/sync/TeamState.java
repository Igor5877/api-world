package com.skyblock.dynamic.teams.sync;

import java.util.List;
import java.util.UUID;

/**
 * Last known team composition received from the Nestworld API
 * (via TeamDataUpdatedEvent fired by nestworld-mods-server).
 */
public record TeamState(int teamId, String teamName, UUID ownerUuid, List<UUID> memberUuids) {

    public boolean isMember(UUID playerUuid) {
        return memberUuids.contains(playerUuid);
    }

    public boolean isSolo() {
        return memberUuids.size() <= 1;
    }
}
