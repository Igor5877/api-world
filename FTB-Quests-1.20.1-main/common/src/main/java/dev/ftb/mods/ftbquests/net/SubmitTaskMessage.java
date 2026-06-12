package dev.ftb.mods.ftbquests.net;

import com.skyblock.dynamic.nestworld.mods.NestworldModsServer;
import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseC2SMessage;
import dev.architectury.networking.simple.MessageType;
import dev.ftb.mods.ftbquests.quest.IslandData;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * @author LatvianModder
 */
public class SubmitTaskMessage extends BaseC2SMessage {
	private final long taskId;

	SubmitTaskMessage(FriendlyByteBuf buffer) {
		taskId = buffer.readLong();
	}

	public SubmitTaskMessage(long taskId) {
		this.taskId = taskId;
	}

	@Override
	public MessageType getType() {
		return FTBQuestsNetHandler.SUBMIT_TASK;
	}

	@Override
	public void write(FriendlyByteBuf buffer) {
		buffer.writeLong(taskId);
	}

	@Override
	public void handle(NetworkManager.PacketContext context) {
		ServerPlayer player = (ServerPlayer) context.getPlayer();
		Task task = ServerQuestFile.INSTANCE.getTask(taskId);
		if (task == null) {
			return;
		}

		IslandData data = ServerQuestFile.INSTANCE.getOrCreateIslandData(player);

		if (data != IslandData.UNLOADED && !data.isLocked() && data.canStartTasks(task.getQuest())) {
			// MODIFIED: Use the QuestTeamBridge for an authoritative check
			boolean isMember = com.skyblock.dynamic.utils.QuestTeamBridge.getInstance().isPlayerOnTeam(player.getUUID(), data.getTeamId());

			// ADDED: Crucial check to ensure the player is on THEIR OWN island server when submitting tasks.
			// Quest progress must only be credited on the island whose owner == the player's team id.
			// Non-island servers (hub, etc.) are NOT a valid place to progress quests, so deny there.
			boolean isCorrectIsland = false;
			if (com.skyblock.dynamic.SkyBlockMod.isIslandServer()) {
				String ownerUuid = com.skyblock.dynamic.SkyBlockMod.getOwnerUuid();
				isCorrectIsland = ownerUuid != null
						&& java.util.UUID.fromString(ownerUuid).equals(data.getTeamId());
			}

			if (isMember && isCorrectIsland) {
				ServerQuestFile.INSTANCE.withPlayerContext(player, () -> task.submitTask(data, player));
			}
		}
	}
}