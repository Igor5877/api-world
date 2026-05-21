package RealMarket.realmarket.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import RealMarket.realmarket.blockentity.MarketLinkBlockEntity;
import RealMarket.realmarket.blockentity.MarketCableBlockEntity;
import java.util.*;

@SuppressWarnings("unused")
public class LinkNetworkManager {
    public static List<MarketLinkBlockEntity> getConnectedLinks(Level world, BlockPos startPos) {
        final List<MarketLinkBlockEntity> links = new ArrayList<>();
        final Set<BlockPos> visited = new HashSet<>();
        final Queue<BlockPos> queue = new LinkedList<>();

        queue.add(startPos);
        visited.add(startPos);

        while (!queue.isEmpty()) {
            final BlockPos cur = queue.poll();
            final BlockEntity be = world.getBlockEntity(cur);

            if (be instanceof MarketLinkBlockEntity link && !cur.equals(startPos)) links.add(link);

            for (final Direction d : Direction.values()) {
                final BlockPos next = cur.relative(d);
                if (!visited.contains(next)) {
                    final BlockEntity nbe = world.getBlockEntity(next);
                    if (nbe instanceof MarketCableBlockEntity || nbe instanceof MarketLinkBlockEntity) {
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }
        }
        return links;
    }
}