package dev.ftb.mods.ftbquests.nestworld;

import dev.architectury.platform.Platform;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Єдина точка, через яку форк FTB Quests звертається до серверного мода NestWorld
 * (nestworld-mods-server, modId "skyblock").
 *
 * <p>Клієнтський модпак містить цей форк, але не серверний мод. В одиночній грі
 * клієнт запускає вбудований сервер, і пряме звернення до класів мода падало з
 * NoClassDefFoundError на вході у світ (краш-репорт гравця, 2026-10-05).
 *
 * <p>Класи мода згадуються лише у вкладеному {@link Bridge}: JVM завантажує його
 * при першому виклику, а без мода виклику немає. Без мода прогрес квестів
 * ведеться на самого гравця — як в оригінальному FTB Quests.
 */
public final class NestworldHook {
	private static final boolean PRESENT = Platform.isModLoaded("skyblock");

	private NestworldHook() {
	}

	/** Чи це сервер NestWorld (є серверний мод). */
	public static boolean present() {
		return PRESENT;
	}

	/**
	 * Команда (острів) гравця. Без мода — UUID самого гравця, тобто особистий
	 * прогрес. З модом може бути null: команда ще не завантажилась.
	 */
	@Nullable
	public static UUID teamIdOf(UUID player) {
		return PRESENT ? Bridge.cachedTeamId(player) : player;
	}

	/** Чи цей сервер — острів (а не хаб чи одиночна гра). */
	public static boolean isIslandServer() {
		return PRESENT && Bridge.isIslandServer();
	}

	/** Власник острова цього сервера або null (не острів / одиночна гра). */
	@Nullable
	public static String islandOwnerUuid() {
		return PRESENT ? Bridge.ownerUuid() : null;
	}

	/** Чи гравець у команді. Без мода команда гравця — він сам. */
	public static boolean isPlayerOnTeam(UUID player, UUID teamId) {
		return PRESENT ? Bridge.isPlayerOnTeam(player, teamId) : player.equals(teamId);
	}

	/** Лише тут — класи серверного мода. Не завантажується, якщо мода немає. */
	private static final class Bridge {
		static UUID cachedTeamId(UUID player) {
			return com.skyblock.dynamic.nestworld.mods.NestworldModsServer.ISLAND_PROVIDER.getCachedTeamId(player);
		}

		static boolean isIslandServer() {
			return com.skyblock.dynamic.SkyBlockMod.isIslandServer();
		}

		static String ownerUuid() {
			return com.skyblock.dynamic.SkyBlockMod.getOwnerUuid();
		}

		static boolean isPlayerOnTeam(UUID player, UUID teamId) {
			return com.skyblock.dynamic.utils.QuestTeamBridge.getInstance().isPlayerOnTeam(player, teamId);
		}
	}
}
