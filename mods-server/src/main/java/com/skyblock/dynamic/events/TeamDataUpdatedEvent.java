package com.skyblock.dynamic.events;

import net.minecraftforge.eventbus.api.Event;

import java.util.List;
import java.util.UUID;

/**
 * Fired on the Forge event bus whenever fresh team data has been processed —
 * either fetched from the API, loaded from cache, or pushed via the
 * TEAM_UPDATED WebSocket event.
 *
 * Addons (e.g. nestworld-teams-addon) subscribe to this to reconcile
 * external systems (FTB Teams parties, chunk claims) with the API team state.
 *
 * The event is fired on whatever thread processed the data; handlers that
 * touch level/player state must re-schedule onto the server thread themselves.
 */
public class TeamDataUpdatedEvent extends Event {

    private final int teamId;
    private final String teamName;
    private final UUID ownerUuid;
    private final List<UUID> memberUuids;

    public TeamDataUpdatedEvent(int teamId, String teamName, UUID ownerUuid, List<UUID> memberUuids) {
        this.teamId = teamId;
        this.teamName = teamName;
        this.ownerUuid = ownerUuid;
        this.memberUuids = List.copyOf(memberUuids);
    }

    /** API team id, or -1 if it was missing from the payload. */
    public int getTeamId() {
        return teamId;
    }

    /** Team name, or null if it was missing from the payload. */
    public String getTeamName() {
        return teamName;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    /** Full member list including the owner. Immutable. */
    public List<UUID> getMemberUuids() {
        return memberUuids;
    }
}
