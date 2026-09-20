package com.nguyendevs.freesia.forge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;

import java.util.Set;
import java.util.UUID;

/** Forge counterpart of FreesiaII's Paper player tracker; no client mod required. */
@Mod("freesia_backend")
public final class FreesiaBackend {
    private static final ResourceLocation TRACKER = new ResourceLocation("freesia", "tracker_sync");
    private final TrackerState state = new TrackerState();
    private final EventNetworkChannel channel;

    public FreesiaBackend() {
        // Raw plugin messages, without SimpleChannel's additional discriminator.
        // Missing channels are allowed: only the proxy consumes these messages.
        channel = NetworkRegistry.newEventChannel(TRACKER, () -> "1", version -> true, version -> true);
        channel.addListener(this::onPayload);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onPayload(NetworkEvent.ClientCustomPayloadEvent event) {
        var context = event.getSource().get();
        context.setPacketHandled(true);
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) return;
        ServerPlayer carrier = context.getSender();
        TrackerProtocol.Scan scan = TrackerProtocol.decodeScan(event.getPayload());
        if (carrier == null || scan == null) return;
        // Decode before enqueueWork: Forge owns and releases the incoming buffer.
        context.enqueueWork(() -> {
            if (carrier.hasDisconnected()) return;
            Set<UUID> viewers = state.snapshot(scan.target());
            send(carrier, TrackerProtocol.reply(scan.callback(), viewers));
        });
    }

    @SubscribeEvent
    public void onTrack(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer watcher && event.getTarget() instanceof ServerPlayer target) {
            if (state.start(target.getUUID(), watcher.getUUID())) notifyTracker(target, watcher);
        }
    }

    @SubscribeEvent
    public void onUntrack(PlayerEvent.StopTracking event) {
        if (event.getEntity() instanceof ServerPlayer watcher && event.getTarget() instanceof ServerPlayer target) {
            state.stop(target.getUUID(), watcher.getUUID());
        }
    }

    @SubscribeEvent
    public void onJoin(PlayerEvent.PlayerLoggedInEvent event) { notifySelf(event); }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) { notifySelf(event); }

    @SubscribeEvent
    public void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) { notifySelf(event); }

    private void notifySelf(PlayerEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) notifyTracker(player, player);
    }

    @SubscribeEvent
    public void onQuit(PlayerEvent.PlayerLoggedOutEvent event) { state.remove(event.getEntity().getUUID()); }

    @SubscribeEvent
    public void onStop(ServerStoppedEvent event) { state.clear(); }

    private void notifyTracker(ServerPlayer target, ServerPlayer watcher) {
        if (watcher.hasDisconnected()) return;
        send(watcher, TrackerProtocol.update(target.getUUID(), watcher.getUUID()));
    }

    private static void send(ServerPlayer player, FriendlyByteBuf data) {
        // Heap-backed buffers avoid pooled-buffer leaks; packet encoding is asynchronous.
        player.connection.send(new ClientboundCustomPayloadPacket(TRACKER, data));
    }
}
