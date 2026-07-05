package com.skyblock.dynamic.teams.sync;

import com.mojang.logging.LogUtils;
import com.skyblock.dynamic.teams.TeamsAddonConfig;
import dev.ftb.mods.ftbchunks.api.ChunkTeamData;
import dev.ftb.mods.ftbchunks.api.ClaimResult;
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

    /**
     * Claims chunks if enabled, not done before, and FTB Chunks is loaded.
     * Must be called on the server thread; safe to call repeatedly.
     */
    public void autoClaimIfNeeded(MinecraftServer server, UUID ownerUuid) {
        if (!TeamsAddonConfig.AUTO_CLAIM_ENABLED.get() || ownerUuid == null) {
            return;
        }
        Path marker = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("serverconfig").resolve(MARKER_FILE);
        if (Files.exists(marker)) {
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

        ServerLevel overworld = server.overworld();
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
}
