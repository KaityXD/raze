package hack.raze.client.features.impl.base;

import hack.raze.client.event.EventSubscribe;
import hack.raze.client.event.impl.EventSystemUpdate;
import hack.raze.client.features.settings.impl.BoolSetting;
import hack.raze.client.features.settings.impl.IntSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;

/** Reports dense storage clusters from already-loaded chunks; it never requests chunk loads. */
public final class StashRadar extends BaseFinderFeature {
    private final IntSetting minimumStorage = new IntSetting("Minimum storage blocks", 4, 2, 24);
    private final BoolSetting includeBarrels = new BoolSetting("Include barrels and shulkers", true);

    public StashRadar() { super("Stash Radar", "Finds clusters of storage blocks in loaded chunks"); }

    @EventSubscribe private void update(EventSystemUpdate event) { scanTick(event); }

    @Override protected void scanChunk(LevelChunk chunk) {
        List<BlockPos> positions = new ArrayList<>();
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            boolean storage = be instanceof ChestBlockEntity
                    || (includeBarrels.getValue() && (be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity));
            if (storage) positions.add(be.getBlockPos());
        }
        if (positions.size() < minimumStorage.getValue()) return;
        int x = 0, y = 0, z = 0;
        for (BlockPos p : positions) { x += p.getX(); y += p.getY(); z += p.getZ(); }
        publish("Stash", "Storage cluster", new BlockPos(x / positions.size(), y / positions.size(), z / positions.size()), positions.size());
    }
}
