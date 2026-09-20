package com.nguyendevs.freesia.forge;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class TrackerStateTest {
    @Test void trackingIsIdempotentAndSnapshotsAreIsolated() {
        var state = new TrackerState();
        UUID target = UUID.randomUUID(), watcher = UUID.randomUUID();
        assertTrue(state.start(target, watcher));
        assertFalse(state.start(target, watcher));
        var before = state.snapshot(target);
        state.stop(target, watcher);
        assertEquals(Set.of(watcher), before);
        assertTrue(state.snapshot(target).isEmpty());
    }

    @Test void logoutRemovesBothDirectionsWithoutRemovingOtherWatchers() {
        var state = new TrackerState();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        state.start(a, b);
        state.start(b, a);
        state.start(b, c);
        state.remove(a);
        assertTrue(state.snapshot(a).isEmpty());
        assertEquals(Set.of(c), state.snapshot(b));
        state.clear();
        assertTrue(state.snapshot(b).isEmpty());
    }
}
