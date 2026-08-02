package com.skyblock.dynamic.teams.sync;

import com.mojang.logging.LogUtils;
import com.skyblock.dynamic.teams.TeamsAddonConfig;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.ChunkTeamData;
import dev.ftb.mods.ftbchunks.api.ClaimResult;
import dev.ftb.mods.ftbchunks.api.ClaimedChunkManager;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * One-time automatic chunk claiming around the island spawn, so players never
 * have to touch FTB Chunks manually. The claim belongs to the owner's FTB team
 * (party or personal), so guests are protection-blocked out of the box.
 *
 * A marker file in world/serverconfig prevents re-claiming on every start.
 */
public class ChunkClaimService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MARKER_FILE = "nestworld_teams_autoclaim.done";
    private static final String MIGRATION_MARKER_PREFIX = "nestworld_teams_claim_migrated_";

    /**
     * Claims chunks if enabled, not done before, and FTB Chunks is loaded.
     * Must be called on the server thread; safe to call repeatedly.
     */
    public void autoClaimIfNeeded(MinecraftServer server, UUID ownerUuid) {
        if (!TeamsAddonConfig.AUTO_CLAIM_ENABLED.get() || ownerUuid == null) {
            return;
        }
        if (!FTBChunksAPI.api().isManagerLoaded() || !FTBTeamsAPI.api().isManagerLoaded()) {
            return;
        }

        Team team = FTBTeamsAPI.api().getManager().getTeamForPlayerID(ownerUuid).orElse(null);
        if (team == null) {
            LOGGER.info("Auto-claim deferred: owner {} has no FTB team yet.", ownerUuid);
            return;
        }

        // If the owner claimed their spawn area back when they were still
        // solo, those chunks sit under their PERSONAL team object — forming
        // (or joining) a party does not retroactively move them, so members
        // stay walled out of their own island until this runs once.
        migratePersonalClaimsIfNeeded(server, ownerUuid, team);

        Path marker = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("serverconfig").resolve(MARKER_FILE);
        if (Files.exists(marker)) {
            return;
        }

        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            // Levels aren't loaded yet (e.g. this fired from ServerAboutToStartEvent
            // via a cached-team-data refresh). onServerStarted/onPlayerLoggedIn will
            // retry once the world actually exists.
            LOGGER.info("Auto-claim deferred: overworld not loaded yet.");
            return;
        }
        BlockPos spawn = overworld.getSharedSpawnPos();
        ChunkPos center = new ChunkPos(spawn);
        int radius = TeamsAddonConfig.AUTO_CLAIM_RADIUS.get();

        ChunkTeamData data = FTBChunksAPI.api().getManager().getOrCreateData(team);
        int claimed = 0;
        int failed = 0;
        for (int cx = center.x - radius; cx <= center.x + radius; cx++) {
            for (int cz = center.z - radius; cz <= center.z + radius; cz++) {
                ClaimResult result = data.claim(server.createCommandSourceStack(),
                        new ChunkDimPos(Level.OVERWORLD, cx, cz), false);
                if (result.isSuccess()) {
                    claimed++;
                } else if (!"already_claimed".equals(result.getResultId())) {
                    failed++;
                }
            }
        }
        LOGGER.info("Auto-claim for team '{}': {} chunks claimed, {} failed.", team.getShortName(), claimed, failed);

        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "claimed " + claimed + " chunks around " + center + "\n");
        } catch (IOException e) {
            LOGGER.error("Failed to write auto-claim marker file {}", marker, e);
        }
    }

    /**
     * Moves any chunks claimed under the owner's personal (solo) FTB team
     * over to their current team, if that current team is a party. Land
     * protection is per team-object, so a party member has no access to
     * claims that still belong to the owner's old personal team — this is
     * a one-time migration, guarded by a per-owner marker file.
     */
    private void migratePersonalClaimsIfNeeded(MinecraftServer server, UUID ownerUuid, Team team) {
        if (!team.isPartyTeam()) {
            return; // nothing to migrate onto — the owner is still solo
        }
        Path marker = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("serverconfig").resolve(MIGRATION_MARKER_PREFIX + ownerUuid + ".done");
        if (Files.exists(marker)) {
            return;
        }

        ClaimedChunkManager manager = FTBChunksAPI.api().getManager();
        ChunkTeamData personalData = manager.getPersonalData(ownerUuid);
        List<? extends ClaimedChunk> personalClaims = personalData == null
                ? List.of() : List.copyOf(personalData.getClaimedChunks());

        if (!personalClaims.isEmpty()) {
            ChunkTeamData partyData = manager.getOrCreateData(team);
            int migrated = 0;
            int failed = 0;
            for (ClaimedChunk chunk : personalClaims) {
                ChunkDimPos pos = chunk.getPos();
                boolean wasForceLoaded = chunk.isForceLoaded();
                chunk.unclaim(server.createCommandSourceStack(), false);
                ClaimResult result = partyData.claim(server.createCommandSourceStack(), pos, false);
                if (result.isSuccess()) {
                    migrated++;
                    if (wasForceLoaded) {
                        partyData.forceLoad(server.createCommandSourceStack(), pos, false);
                    }
                } else {
                    failed++;
                }
            }
            LOGGER.info("Migrated {} personal claim(s) to party '{}' for owner {} ({} failed).",
                    migrated, team.getShortName(), ownerUuid, failed);
        }

        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "migrated " + personalClaims.size() + " personal claim(s) to party "
                    + team.getShortName() + "\n");
        } catch (IOException e) {
            LOGGER.error("Failed to write claim-migration marker file {}", marker, e);
        }
    }
}
