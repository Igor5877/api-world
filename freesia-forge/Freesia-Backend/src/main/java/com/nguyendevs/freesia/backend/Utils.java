package com.nguyendevs.freesia.backend;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

public class Utils {
    public static Player randomPlayerIfNotFound(UUID uuid) {
        if (uuid != null) {
            Player expected = Bukkit.getPlayer(uuid);
            if (expected != null) {
                return expected;
            }
        }

        Player cached = FreesiaBackend.INSTANCE.getPayloadPlayer();
        if (cached != null && cached.isOnline()) {
            return cached;
        }

        return null;
    }
}

