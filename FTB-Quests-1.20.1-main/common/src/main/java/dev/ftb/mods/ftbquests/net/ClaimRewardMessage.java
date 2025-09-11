package dev.ftb.mods.ftbquests.net;

import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseC2SMessage;
import dev.architectury.networking.simple.MessageType;
import com.skyblock.dynamic.nestworld.mods.NestworldModsServer;
import dev.ftb.mods.ftbquests.quest.IslandData;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * @author LatvianModder
 */
public class ClaimRewardMessage extends BaseC2SMessage {
	private final long id;
	private final boolean notify;

	ClaimRewardMessage(FriendlyByteBuf buffer) {
		id = buffer.readLong();
		notify = buffer.readBoolean();
	}

	public ClaimRewardMessage(long i, boolean n) {
		id = i;
		notify = n;
	}

	@Override
	public MessageType getType() {
		return FTBQuestsNetHandler.CLAIM_REWARD;
	}

	@Override
	public void write(FriendlyByteBuf buffer) {
		buffer.writeLong(id);
		buffer.writeBoolean(notify);
	}

	@Override
	public void handle(NetworkManager.PacketContext context) {
		ServerPlayer player = (ServerPlayer) context.getPlayer();
		Reward reward = ServerQuestFile.INSTANCE.getReward(id);

		if (reward == null) {
			return;
		}

		// Use the already-modified getOrCreateIslandData to get the relevant data object (team or player)
		IslandData islandData = ServerQuestFile.INSTANCE.getOrCreateIslandData(player);

		if (islandData != null && islandData.isCompleted(reward.getQuest())) {
			// Now, verify the player is actually a member of this team.
			// This prevents guests from claiming rewards.
			NestworldModsServer.ISLAND_PROVIDER.refreshAndGetTeamId(player.getUUID()).thenAcceptAsync(teamId -> {
				if (teamId != null && teamId.equals(islandData.getTeamId())) {
					// Player is a member of the team that owns this data, proceed to claim.
					islandData.claimReward(player, reward, notify);
				}
				// If teamId is null or doesn't match, do nothing. The player is a guest or not on the team.
			}, player.getServer()); // Ensure the callback runs on the server thread
		}
	}
}