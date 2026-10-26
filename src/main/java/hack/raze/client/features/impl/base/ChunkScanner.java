package hack.raze.client.features.impl.base;

import hack.raze.client.event.EventSubscribe;
import hack.raze.client.event.impl.EventSystemUpdate;
import hack.raze.client.features.settings.impl.IntSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/** Local heuristic for chunks with several signs of player construction. */
public final class ChunkScanner extends BaseFinderFeature {
    private final IntSetting minimumScore = new IntSetting("Minimum signal score", 35, 10, 100, "%");

    public ChunkScanner() { super("Settlement Scanner", "Scores loaded chunks for signs of player-built bases"); }

    @EventSubscribe private void update(EventSystemUpdate event) { scanTick(event); }

    @Override protected void scanChunk(LevelChunk chunk) {
        int storage = 0, machines = 0, signs = 0, redstone = 0, beds = 0;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            var block = be.getBlockState().getBlock();
            if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL || block == Blocks.SHULKER_BOX) storage++;
            if (block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER || block == Blocks.HOPPER || block == Blocks.DISPENSER) machines++;
            if (block instanceof SignBlock) signs++;
        }
        var sections = chunk.getSections();
        for (var section : sections) {
            if (section == null || section.hasOnlyAir()) continue;
            if (!section.maybeHas(s -> s.is(Blocks.REDSTONE_WIRE) || s.is(Blocks.REPEATER) || s.is(Blocks.COMPARATOR) || s.getBlock() instanceof BedBlock)) continue;
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                var block = section.getBlockState(x, y, z).getBlock();
                if (block == Blocks.REDSTONE_WIRE || block == Blocks.REPEATER || block == Blocks.COMPARATOR) redstone++;
                else if (block instanceof BedBlock) beds++;
            }
        }
        int score = Math.min(100, storage * 8 + machines * 4 + signs * 5 + redstone * 2 + beds * 8);
        if (score < minimumScore.getValue()) return;
        BlockPos center = new BlockPos(chunk.getPos().getMinBlockX() + 8, mc.player.blockPosition().getY(), chunk.getPos().getMinBlockZ() + 8);
        publish("Settlement", "Possible base, " + score + "% signals", center, storage + machines + signs);
    }
}
