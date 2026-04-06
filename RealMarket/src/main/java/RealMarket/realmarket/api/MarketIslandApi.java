package RealMarket.realmarket.api;

import java.util.UUID;
import java.util.function.Function;

/**
 * Interface bridge to decouple RealMarket from mods-server.
 * It allows mods-server to register a provider via reflection during startup,
 * which will resolve a player's team/island UUID.
 */
public class MarketIslandApi {
    private static Function<UUID, UUID> provider = (playerUuid) -> null;

    /**
     * Registers the Island UUID provider.
     * Called by mods-server via reflection during setup.
     *
     * @param newProvider A function that takes a player UUID and returns their Island UUID.
     */
    public static void registerProvider(Function<UUID, UUID> newProvider) {
        provider = newProvider;
    }

    /**
     * Retrieves the Island UUID for the given player UUID.
     *
     * @param playerUuid The player's UUID.
     * @return The Island/Team UUID, or null if unknown or not registered.
     */
    public static UUID getIslandUuid(UUID playerUuid) {
        return provider.apply(playerUuid);
    }
}
