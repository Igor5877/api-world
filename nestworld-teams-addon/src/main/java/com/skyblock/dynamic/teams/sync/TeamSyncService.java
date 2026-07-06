package com.skyblock.dynamic.teams.sync;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.data.PartyTeam;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reconciles the FTB Teams party on this island server with the team
 * composition recorded in the Nestworld API.
 *
 * The API is the single source of truth. This service only ever pushes
 * API state INTO FTB Teams, never the other way around:
 *  - ensures a party exists for the island owner (created when the owner is online),
 *  - joins online members into the party,
 *  - kicks players who are in the party but no longer in the API team (works offline).
 *
 * Members that are offline are picked up on their next login
 * (Velocity only routes players to this island if the API says they belong here).
 */
public class TeamSyncService {
    private static final Logger LOGGER = LogUtils.getLogger();

    private volatile TeamState lastState;
    /** Set while we mutate FTB Teams ourselves so PartyGuard ignores our own events. */
    private final AtomicBoolean reconciling = new AtomicBoolean(false);

    public TeamState getLastState() {
        return lastState;
    }

    public boolean isReconciling() {
        return reconciling.get();
    }

    public void updateState(TeamState state) {
        this.lastState = state;
    }

    /**
     * Runs one reconciliation pass for the island's own team (lastState).
     * Must be called on the server thread.
     */
    public void reconcile(MinecraftServer server) {
        reconcileTeam(server, lastState);
    }

    /**
     * Reconciles ONE team's FTB party with the given API state. Used both by
     * the island flow (lastState) and by the hub, where many teams coexist
     * and each is reconciled when one of its members logs in.
     * Must be called on the server thread.
     */
    public void reconcileTeam(MinecraftServer server, TeamState state) {
        if (state == null || state.ownerUuid() == null) {
            return;
        }
        if (!FTBTeamsAPI.api().isManagerLoaded()) {
            return;
        }

        reconciling.set(true);
        try {
            Team ownerTeam = FTBTeamsAPI.api().getManager().getTeamForPlayerID(state.ownerUuid()).orElse(null);

            if (state.isSolo()) {
                // Solo team: a leftover party from earlier membership is harmless,
                // but stale extra members in it must go.
                if (ownerTeam != null && ownerTeam.isPartyTeam()) {
                    kickStaleMembers(server, (PartyTeam) ownerTeam, state);
                }
                return;
            }

            // Multi-member team: make sure the owner has a party. The full
            // createParty overload accepts a null ServerPlayer, so a party can
            // be created even while the owner is offline (hub case).
            if (ownerTeam == null || !ownerTeam.isPartyTeam()) {
                ServerPlayer ownerPlayer = server.getPlayerList().getPlayer(state.ownerUuid());
                try {
                    String name = state.teamName() != null && !state.teamName().isBlank()
                            ? state.teamName()
                            : (ownerPlayer != null ? ownerPlayer.getGameProfile().getName()
                                                   : state.ownerUuid().toString());
                    ownerTeam = TeamManagerImpl.INSTANCE.createParty(
                            state.ownerUuid(), ownerPlayer, name, null, null);
                    LOGGER.info("Created FTB Teams party '{}' for team owner {}{}.",
                            name, state.ownerUuid(), ownerPlayer == null ? " (offline)" : "");
                } catch (Exception e) {
                    LOGGER.error("Failed to create FTB Teams party for owner {}", state.ownerUuid(), e);
                    return;
                }
            }

            PartyTeam party = (PartyTeam) ownerTeam;
            joinOnlineMembers(server, party, state);
            kickStaleMembers(server, party, state);
        } finally {
            reconciling.set(false);
        }
    }

    private void joinOnlineMembers(MinecraftServer server, PartyTeam party, TeamState state) {
        Set<UUID> ftbMembers = party.getMembers();
        for (UUID member : state.memberUuids()) {
            if (member.equals(state.ownerUuid()) || ftbMembers.contains(member)) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player == null) {
                continue; // joined on their next login
            }
            try {
                party.join(player);
                LOGGER.info("Added {} to party '{}'.", player.getGameProfile().getName(), party.getShortName());
            } catch (Exception e) {
                LOGGER.error("Failed to add {} to party '{}'", member, party.getShortName(), e);
            }
        }
    }

    private void kickStaleMembers(MinecraftServer server, PartyTeam party, TeamState state) {
        List<GameProfile> toKick = new ArrayList<>();
        for (UUID ftbMember : Set.copyOf(party.getMembers())) {
            if (ftbMember.equals(party.getOwner()) || state.isMember(ftbMember)) {
                continue;
            }
            GameProfile profile = server.getProfileCache() != null
                    ? server.getProfileCache().get(ftbMember).orElse(new GameProfile(ftbMember, ""))
                    : new GameProfile(ftbMember, "");
            toKick.add(profile);
        }
        if (toKick.isEmpty()) {
            return;
        }
        try {
            party.kick(server.createCommandSourceStack(), toKick);
            LOGGER.info("Kicked {} stale member(s) from party '{}'.", toKick.size(), party.getShortName());
        } catch (Exception e) {
            LOGGER.error("Failed to kick stale members from party '{}'", party.getShortName(), e);
        }
    }
}
