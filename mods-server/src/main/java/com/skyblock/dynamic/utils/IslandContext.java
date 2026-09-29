package com.skyblock.dynamic.utils;

/**
 * Represents the context of an island server.
 */
public class IslandContext {
    private final boolean isIslandServer;
    private final String ownerUuid;
    /** Island id of a warm spare (API spare pool) that nobody owns yet; null otherwise. */
    private final Integer spareIslandId;

    /**
     * Constructs a new IslandContext.
     *
     * @param isIslandServer Whether the server is an island server.
     * @param ownerUuid      The UUID of the island owner.
     */
    public IslandContext(boolean isIslandServer, String ownerUuid) {
        this(isIslandServer, ownerUuid, null);
    }

    private IslandContext(boolean isIslandServer, String ownerUuid, Integer spareIslandId) {
        this.isIslandServer = isIslandServer;
        this.ownerUuid = ownerUuid;
        this.spareIslandId = spareIslandId;
    }

    /**
     * A warm spare: booted ahead of time without an owner. It is deliberately
     * NOT an island server — every owner-bound path (FTB team, quests,
     * freeze, ready signal) stays off until the API sends a claim.
     *
     * @param spareIslandId The spare's island id in the API.
     * @return The spare context.
     */
    public static IslandContext spare(int spareIslandId) {
        return new IslandContext(false, null, spareIslandId);
    }

    /**
     * Checks if the server is an island server.
     *
     * @return True if the server is an island server, false otherwise.
     */
    public boolean isIslandServer() {
        return isIslandServer;
    }

    /**
     * Gets the UUID of the island owner.
     *
     * @return The UUID of the island owner.
     */
    public String getOwnerUuid() {
        return ownerUuid;
    }

    /**
     * @return True if this is an unclaimed warm spare.
     */
    public boolean isSpare() {
        return spareIslandId != null;
    }

    /**
     * @return The spare's island id, or null if this is not a spare.
     */
    public Integer getSpareIslandId() {
        return spareIslandId;
    }

    /**
     * Gets the default island context.
     *
     * @return The default island context.
     */
    public static IslandContext getDefault() {
        return new IslandContext(false, null);
    }
}
