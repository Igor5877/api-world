package com.skyblock.dynamic.utils;

public class IslandContext {
    private final boolean isIslandServer;
    private final String ownerUuid;

    public IslandContext(boolean isIslandServer, String ownerUuid) {
        this.isIslandServer = isIslandServer;
        this.ownerUuid = ownerUuid;
    }

    public boolean isIslandServer() {
        return isIslandServer;
    }

    public String getOwnerUuid() {
        return ownerUuid;
    }

    public static IslandContext getDefault() {
        return new IslandContext(false, null);
    }
}