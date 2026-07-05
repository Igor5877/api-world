package com.skyblock.dynamic.teams.progress;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.skyblock.dynamic.teams.NestworldTeamsAddon;
import com.skyblock.dynamic.teams.TeamsAddonConfig;
import com.skyblock.dynamic.teams.sync.TeamState;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Pushes the island team's FTB Quests progress to the API for the spawn-hub
 * viewer. Instead of compiling against FTB Quests internals (which are being
 * reworked), it snapshots the per-team progress SNBT files FTB Quests already
 * persists on disk and ships them as an opaque JSON payload:
 *   {"format": "snbt-files", "files": {"<file name>": "<snbt text>", ...}}
 *
 * Disabled by default (progressPushEnabled) until the API implements
 * PUT /quests/progress/{team_id} — see api/API_TEAMS_TODO.md §2.
 */
public class QuestProgressReporter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private long lastPushMillis = 0;
    private String lastPushedHash = "";
    private boolean apiMissingLogged = false;

    /** Called periodically from the server tick handler (island servers only). */
    public void tick(Path serverRoot) {
        if (!TeamsAddonConfig.PROGRESS_PUSH_ENABLED.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPushMillis < TeamsAddonConfig.PROGRESS_PUSH_INTERVAL_SECONDS.get() * 1000L) {
            return;
        }
        lastPushMillis = now;

        TeamState state = NestworldTeamsAddon.SYNC_SERVICE.getLastState();
        if (state == null || state.teamId() < 0) {
            return;
        }

        Path dir = serverRoot.resolve(TeamsAddonConfig.PROGRESS_FILES_DIR.get());
        if (!Files.isDirectory(dir)) {
            return;
        }

        JsonObject files = new JsonObject();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".snbt"))
                    .forEach(p -> {
                        try {
                            files.addProperty(p.getFileName().toString(), Files.readString(p));
                        } catch (IOException e) {
                            LOGGER.warn("Could not read quest progress file {}", p, e);
                        }
                    });
        } catch (IOException e) {
            LOGGER.warn("Could not list quest progress dir {}", dir, e);
            return;
        }
        if (files.size() == 0) {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("format", "snbt-files");
        payload.add("files", files);
        String json = GSON.toJson(payload);

        String hash = Integer.toHexString(json.hashCode());
        if (hash.equals(lastPushedHash)) {
            return; // nothing changed since the last push
        }

        NestworldTeamsAddon.API_CLIENT.putQuestProgress(state.teamId(), json).thenAccept(r -> {
            if (r.isSuccess()) {
                lastPushedHash = hash;
                apiMissingLogged = false;
            } else if (r.isNotImplementedYet() && !apiMissingLogged) {
                apiMissingLogged = true;
                LOGGER.info("Quest progress endpoint not implemented on the API yet; will keep retrying quietly.");
            }
        });
    }
}
