package com.skyblock.dynamic.teams;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Addon config (config/nestworld_teams-common.toml).
 * API base URL and key are NOT duplicated here — they are read from the
 * nestworld-mods-server config (skyblock-common.toml) via com.skyblock.dynamic.Config.
 */
public class TeamsAddonConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue AUTO_CLAIM_ENABLED;
    public static final ForgeConfigSpec.IntValue AUTO_CLAIM_RADIUS;
    public static final ForgeConfigSpec.BooleanValue PROGRESS_PUSH_ENABLED;
    public static final ForgeConfigSpec.IntValue PROGRESS_PUSH_INTERVAL_SECONDS;
    public static final ForgeConfigSpec.ConfigValue<String> PROGRESS_FILES_DIR;
    public static final ForgeConfigSpec.BooleanValue LOCK_MANUAL_PARTY_MANAGEMENT;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("chunk_claims");
        AUTO_CLAIM_ENABLED = b
                .comment("Automatically claim chunks around the world spawn for the island team on first sync (island servers only, requires FTB Chunks).")
                .define("autoClaimEnabled", true);
        AUTO_CLAIM_RADIUS = b
                .comment("Claim radius in chunks around the spawn chunk. 2 = 5x5 chunks.")
                .defineInRange("autoClaimRadius", 2, 0, 8);
        b.pop();

        b.push("quest_progress");
        PROGRESS_PUSH_ENABLED = b
                .comment("Periodically push the FTB Quests team progress snapshot to the Nestworld API.",
                        "Keep disabled until the API implements PUT /quests/progress/{team_id} (see api/API_TEAMS_TODO.md).")
                .define("progressPushEnabled", false);
        PROGRESS_PUSH_INTERVAL_SECONDS = b
                .comment("How often to push the progress snapshot, in seconds.")
                .defineInRange("progressPushIntervalSeconds", 300, 30, 3600);
        PROGRESS_FILES_DIR = b
                .comment("Directory (relative to the server root) holding FTB Quests per-team progress SNBT files.")
                .define("progressFilesDir", "world/data/ftbquests/team");
        b.pop();

        b.push("party_guard");
        LOCK_MANUAL_PARTY_MANAGEMENT = b
                .comment("Prevent players from creating/joining FTB Teams parties manually; the API stays the single source of truth.")
                .define("lockManualPartyManagement", true);
        b.pop();

        SPEC = b.build();
    }
}
