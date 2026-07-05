package com.skyblock.dynamic.teams.net;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Channel shared with the client addon (nestworld-teams-client).
 * Both sides register the same packet ids in the same order — keep the two
 * NetworkHandler classes in sync when adding packets.
 * Versions are accepted permissively so vanilla-ish clients without the
 * client addon can still join.
 */
public class NetworkHandler {
    public static final String PROTOCOL = "1";
    public static final ResourceLocation CHANNEL_ID = new ResourceLocation("nestworld_teams", "main");

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(CHANNEL_ID)
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(v -> true)
            .serverAcceptedVersions(v -> true)
            .simpleChannel();

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(TeamActionC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(TeamActionC2S::encode)
                .decoder(TeamActionC2S::decode)
                .consumerNetworkThread(TeamActionC2S::handle)
                .add();
        CHANNEL.messageBuilder(ActionResultS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ActionResultS2C::encode)
                .decoder(ActionResultS2C::decode)
                .consumerNetworkThread(ActionResultS2C::handle)
                .add();
        CHANNEL.messageBuilder(DataS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(DataS2C::encode)
                .decoder(DataS2C::decode)
                .consumerNetworkThread(DataS2C::handle)
                .add();
    }

    public static void sendTo(ServerPlayer player, Object packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
