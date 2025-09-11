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

		if (data != null && !data.isLocked() && data.canStartTasks(task.getQuest())) {
			NestworldModsServer.ISLAND_PROVIDER.refreshAndGetTeamId(player.getUUID()).thenAccept(teamId -> {
				player.getServer().execute(() -> {
					if (teamId != null && teamId.equals(data.getTeamId())) {
						// Player is a member of the team that owns this data, proceed to submit.
						ServerQuestFile.INSTANCE.withPlayerContext(player, () -> task.submitTask(data, player));
					}
				});
			});
		}
	}
}