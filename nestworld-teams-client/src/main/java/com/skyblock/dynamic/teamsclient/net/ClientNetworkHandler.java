package com.skyblock.dynamic.teamsclient.net;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Client-side registration of the channel owned by nestworld-teams-addon.
 * Same channel id, protocol and packet order — keep in sync with the server
 * addon's NetworkHandler.
 */
public class ClientNetworkHandler {
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

    /**
     * True when the current server runs nestworld-teams-addon, i.e. team
     * actions must be routed through the Nestworld API instead of FTB Teams'
     * own packets. False in singleplayer or on foreign servers, where the
     * stock FTB Teams behaviour is left untouched.
     */
    public static boolean isManagedNetwork() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return false;
        }
        try {
            return CHANNEL.isRemotePresent(mc.getConnection().getConnection());
        } catch (Exception e) {
            return false;
        }
    }

    public static void send(Object packet) {
        CHANNEL.sendToServer(packet);
    }
}
