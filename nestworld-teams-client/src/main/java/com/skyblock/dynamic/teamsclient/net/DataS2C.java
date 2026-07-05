package com.skyblock.dynamic.teamsclient.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Mirror of the server addon's DataS2C. Renders invite lists, team info and
 * the all-teams quest progress summary in chat.
 */
public class DataS2C {

    public enum DataType {
        INVITES_LIST,
        TEAM_INFO,
        ALL_PROGRESS
    }

    public static final int MAX_JSON_BYTES = 900_000;

    private final DataType type;
    private final String json;

    public DataS2C(DataType type, String json) {
        this.type = type;
        this.json = json == null ? "{}" : json;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(type);
        buf.writeUtf(json, MAX_JSON_BYTES);
    }

    public static DataS2C decode(FriendlyByteBuf buf) {
        return new DataS2C(buf.readEnum(DataType.class), buf.readUtf(MAX_JSON_BYTES));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                return;
            }
            switch (type) {
                case INVITES_LIST -> showInvites(mc);
                case TEAM_INFO -> showTeamInfo(mc);
                case ALL_PROGRESS -> showAllProgress(mc);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private void showInvites(Minecraft mc) {
        JsonArray invites = parseArray();
        boolean silent = com.skyblock.dynamic.teamsclient.ClientState.suppressEmptyInvites;
        com.skyblock.dynamic.teamsclient.ClientState.suppressEmptyInvites = false;
        if (invites.isEmpty()) {
            if (!silent) {
                chat(mc, Component.translatable("nestworld_teams.msg.no_invites").withStyle(ChatFormatting.GRAY));
            }
            return;
        }
        chat(mc, Component.translatable("nestworld_teams.msg.invites_header", invites.size())
                .withStyle(ChatFormatting.GOLD));
        for (JsonElement el : invites) {
            JsonObject invite = el.getAsJsonObject();
            int id = invite.has("invite_id") ? invite.get("invite_id").getAsInt()
                    : invite.has("id") ? invite.get("id").getAsInt() : -1;
            String teamName = invite.has("team_name") ? invite.get("team_name").getAsString() : "?";
            String inviter = invite.has("inviter_name") ? invite.get("inviter_name").getAsString() : "?";

            MutableComponent line = Component.literal("  » ")
                    .append(Component.translatable("nestworld_teams.msg.invite_line", teamName, inviter));
            line.append(" ");
            line.append(Component.translatable("nestworld_teams.msg.accept_button")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/nwteam accept " + id))));
            line.append(" ");
            line.append(Component.translatable("nestworld_teams.msg.decline_button")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.RED)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/nwteam decline " + id))));
            chat(mc, line);
        }
    }

    private void showTeamInfo(Minecraft mc) {
        JsonObject team;
        try {
            team = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            team = new JsonObject();
        }
        if (!team.has("name")) {
            chat(mc, Component.translatable("nestworld_teams.msg.no_team").withStyle(ChatFormatting.GRAY));
            return;
        }
        chat(mc, Component.translatable("nestworld_teams.msg.team_header", team.get("name").getAsString())
                .withStyle(ChatFormatting.GOLD));
        if (team.has("members") && team.get("members").isJsonArray()) {
            for (JsonElement el : team.getAsJsonArray("members")) {
                JsonObject member = el.getAsJsonObject();
                String name = member.has("player_name") && !member.get("player_name").isJsonNull()
                        ? member.get("player_name").getAsString()
                        : member.get("player_uuid").getAsString();
                String role = member.has("role") ? member.get("role").getAsString() : "member";
                chat(mc, Component.literal("  • " + name + " ")
                        .append(Component.literal("(" + role + ")").withStyle(ChatFormatting.DARK_GRAY)));
            }
        }
    }

    private void showAllProgress(Minecraft mc) {
        JsonArray teams = parseArray();
        if (teams.isEmpty()) {
            chat(mc, Component.translatable("nestworld_teams.msg.no_progress_data").withStyle(ChatFormatting.GRAY));
            return;
        }
        chat(mc, Component.translatable("nestworld_teams.msg.progress_header", teams.size())
                .withStyle(ChatFormatting.GOLD));
        for (JsonElement el : teams) {
            JsonObject team = el.getAsJsonObject();
            String name = team.has("team_name") ? team.get("team_name").getAsString()
                    : "team " + (team.has("team_id") ? team.get("team_id").getAsString() : "?");
            String updated = team.has("updated_at") ? team.get("updated_at").getAsString() : "";
            chat(mc, Component.literal("  • " + name + " ")
                    .append(Component.literal(updated).withStyle(ChatFormatting.DARK_GRAY)));
        }
    }

    private JsonArray parseArray() {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed.isJsonArray()) {
                return parsed.getAsJsonArray();
            }
        } catch (Exception ignored) {
        }
        return new JsonArray();
    }

    private static void chat(Minecraft mc, Component message) {
        mc.player.displayClientMessage(message, false);
    }
}
