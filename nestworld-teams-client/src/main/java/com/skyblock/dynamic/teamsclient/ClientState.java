package com.skyblock.dynamic.teamsclient;

/**
 * Small shared client state. suppressEmptyInvites marks the automatic
 * invites poll after login, so an empty inbox stays silent instead of
 * printing "no invites" every time the player joins a server.
 */
public final class ClientState {
    public static volatile boolean suppressEmptyInvites = false;

    private ClientState() {
    }
}
