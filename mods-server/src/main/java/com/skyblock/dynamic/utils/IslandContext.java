package com.skyblock.dynamic.utils;

public class IslandContext {
    private final boolean isIslandServer;
    private final String creatorUuid;

    public IslandContext(boolean isIslandServer, String creatorUuid) {
        this.isIslandServer = isIslandServer;
        this.creatorUuid = creatorUuid;
    }

    public boolean isIslandServer() {
        return isIslandServer;
    }

    public String getCreatorUuid() {
        return creatorUuid;
    }

    public static IslandContext getDefault() {
        return new IslandContext(false, null);
    }
}
