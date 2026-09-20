package com.nguyendevs.freesia.forge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Owned exclusively by the logical server thread. Does not retain entity instances. */
final class TrackerState {
    private final Map<UUID, Set<UUID>> viewers = new HashMap<>();

    boolean start(UUID target, UUID watcher) {
        return viewers.computeIfAbsent(target, ignored -> new HashSet<>()).add(watcher);
    }

    void stop(UUID target, UUID watcher) {
        Set<UUID> entry = viewers.get(target);
        if (entry != null && entry.remove(watcher) && entry.isEmpty()) viewers.remove(target);
    }

    Set<UUID> snapshot(UUID target) {
        return Set.copyOf(viewers.getOrDefault(target, Set.of()));
    }

    void remove(UUID player) {
        viewers.remove(player);
        viewers.values().forEach(entry -> entry.remove(player));
        viewers.values().removeIf(Set::isEmpty);
    }

    void clear() { viewers.clear(); }
}
