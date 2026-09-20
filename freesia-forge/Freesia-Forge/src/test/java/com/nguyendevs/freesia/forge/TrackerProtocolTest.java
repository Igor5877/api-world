package com.nguyendevs.freesia.forge;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TrackerProtocolTest {
    private static final UUID TARGET = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    private static final UUID WATCHER = UUID.fromString("ffeeddcc-bbaa-9988-7766-554433221100");

    @Test void readsUpstreamScanWithMultibyteCallback() {
        var buf = buffer("01ac0200112233445566778899aabbccddeeff");
        try {
            assertEquals(new TrackerProtocol.Scan(300, TARGET), TrackerProtocol.decodeScan(buf));
        } finally { buf.release(); }
    }

    @Test void rejectsTruncatedOversizedTrailingAndWrongOpcodePackets() {
        for (String hex : List.of(
                "01ac0200112233445566778899aabbccddee",
                "01ac0200112233445566778899aabbccddeeff00",
                "02ac0200112233445566778899aabbccddeeff",
                "0180808080808000112233445566778899aabbccddeeff",
                "00".repeat(27))) {
            var buf = buffer(hex);
            try { assertNull(TrackerProtocol.decodeScan(buf), hex); }
            finally { buf.release(); }
        }
    }

    @Test void encodesExactUpstreamReplyAndTargetBeforeWatcher() {
        assertWire("00ac0201ffeeddccbbaa99887766554433221100", TrackerProtocol.reply(300, List.of(WATCHER)));
        assertWire("00ac0200", TrackerProtocol.reply(300, List.of()));
        assertWire("0200112233445566778899aabbccddeeffffeeddccbbaa99887766554433221100",
                TrackerProtocol.update(TARGET, WATCHER));
    }

    private static FriendlyByteBuf buffer(String hex) {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(HexFormat.of().parseHex(hex)));
    }

    private static void assertWire(String hex, FriendlyByteBuf actual) {
        try {
            byte[] bytes = new byte[actual.readableBytes()];
            actual.readBytes(bytes);
            assertEquals(hex, HexFormat.of().formatHex(bytes));
        } finally { actual.release(); }
    }
}
