package com.skyblock.dynamic.teamsclient;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.skyblock.dynamic.teamsclient.gui.InviteByNameScreen;
import com.skyblock.dynamic.teamsclient.net.ClientNetworkHandler;
import com.skyblock.dynamic.teamsclient.net.TeamActionC2S;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Client companion for nestworld-teams-addon. Registers the shared network
 * channel, the /nwteam client command (client commands are not shadowed by
 * the Velocity proxy, unlike /team), and a post-login pending-invites check.
 */
@Mod(NestworldTeamsClient.MOD_ID)
public class NestworldTeamsClient {
    public static final String MOD_ID = "nestworld_teams_client";

    /** Ticks left until the automatic invites poll after joining a server. */
    private int invitePollDelay = -1;

    public NestworldTeamsClient() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(ClientNetworkHandler::register);
    }

    @SubscribeEvent
    public void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        invitePollDelay = 100; // ~5 seconds after join
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || invitePollDelay < 0) {
            return;
        }
        if (--invitePollDelay == 0) {
            invitePollDelay = -1;
            if (ClientNetworkHandler.isManagedNetwork()) {
                ClientState.suppressEmptyInvites = true;
                ClientNetworkHandler.send(TeamActionC2S.simple(TeamActionC2S.Action.REQUEST_INVITES));
            }
        }
    }

    @SubscribeEvent
    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(literal("nwteam")
                .then(literal("invite")
                        .executes(ctx -> {
                            Minecraft mc = Minecraft.getInstance();
                            mc.execute(() -> mc.setScreen(new InviteByNameScreen(null)));
                            return 1;
                        })
                        .then(argument("player", StringArgumentType.word())
                                .executes(ctx -> send(TeamActionC2S.invite(StringArgumentType.getString(ctx, "player"))))))
                .then(literal("kick")
                        .then(argument("player", StringArgumentType.word())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "player");
                                    UUID uuid = UUID.nameUUIDFromBytes(
                                            ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
                                    return send(TeamActionC2S.kick(uuid));
                                })))
                .then(literal("accept")
                        .then(argument("id", IntegerArgumentType.integer(0))
                                .executes(ctx -> send(TeamActionC2S.acceptInvite(IntegerArgumentType.getInteger(ctx, "id"))))))
                .then(literal("decline")
                        .then(argument("id", IntegerArgumentType.integer(0))
                                .executes(ctx -> send(TeamActionC2S.declineInvite(IntegerArgumentType.getInteger(ctx, "id"))))))
                .then(literal("join")
                        .then(argument("teamName", StringArgumentType.greedyString())
                                .executes(ctx -> send(TeamActionC2S.acceptByTeamName(StringArgumentType.getString(ctx, "teamName"))))))
                .then(literal("invites")
                        .executes(ctx -> send(TeamActionC2S.simple(TeamActionC2S.Action.REQUEST_INVITES))))
                .then(literal("leave")
                        .executes(ctx -> send(TeamActionC2S.simple(TeamActionC2S.Action.LEAVE))))
                .then(literal("info")
                        .executes(ctx -> send(TeamActionC2S.simple(TeamActionC2S.Action.REQUEST_TEAM_INFO))))
                .then(literal("progress")
                        .executes(ctx -> send(TeamActionC2S.simple(TeamActionC2S.Action.REQUEST_ALL_PROGRESS)))));
    }

    private static int send(TeamActionC2S packet) {
        if (!ClientNetworkHandler.isManagedNetwork()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable("nestworld_teams.msg.not_on_network"), false);
            }
            return 0;
        }
        ClientNetworkHandler.send(packet);
        return 1;
    }
}
