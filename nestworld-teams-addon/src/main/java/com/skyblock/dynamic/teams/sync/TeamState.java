package com.skyblock.dynamic.teams.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
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

    /**
     * Будує стан із відповіді GET /teams/my_team/{uuid}
     * ({"id":..,"name":..,"owner_uuid":..,"members":[{"player_uuid":..},..]}).
     */
    public static TeamState fromJson(JsonObject team) {
        int id = team.get("id").getAsInt();
        String name = team.has("name") && !team.get("name").isJsonNull()
                ? team.get("name").getAsString() : null;
        UUID owner = UUID.fromString(team.get("owner_uuid").getAsString());
        List<UUID> members = new ArrayList<>();
        if (team.has("members") && team.get("members").isJsonArray()) {
            for (JsonElement el : team.getAsJsonArray("members")) {
                members.add(UUID.fromString(el.getAsJsonObject().get("player_uuid").getAsString()));
            }
        }
        return new TeamState(id, name, owner, members);
    }
}
