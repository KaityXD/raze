package hack.raze.client.features.impl.base;

import hack.raze.client.event.EventSubscribe;
import hack.raze.client.event.impl.EventSystemUpdate;
import hack.raze.client.features.settings.impl.IntSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Finds loaded chunks containing amethyst growth and budding blocks. */
public final class GeodeScout extends BaseFinderFeature {
    private final IntSetting minimumClusters = new IntSetting("Minimum grown clusters", 6, 1, 64);

    public GeodeScout() { super("Geode Scout", "Finds amethyst geode growth in loaded chunks"); }

    @EventSubscribe private void update(EventSystemUpdate event) { scanTick(event); }

    @Override protected void scanChunk(LevelChunk chunk) {
        int grown = 0, budding = 0, xSum = 0, ySum = 0, zSum = 0;
        LevelChunkSection[] sections = chunk.getSections();
        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection section = sections[si];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(s -> s.is(Blocks.AMETHYST_CLUSTER) || s.is(Blocks.BUDDING_AMETHYST))) continue;
            int baseY = (chunk.getMinSectionY() + si) << 4;
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                var state = section.getBlockState(x, y, z);
                if (state.is(Blocks.AMETHYST_CLUSTER)) grown++;
                else if (state.is(Blocks.BUDDING_AMETHYST)) budding++;
                else continue;
                xSum += chunk.getPos().getMinBlockX() + x; ySum += baseY + y; zSum += chunk.getPos().getMinBlockZ() + z;
            }
        }
        if (grown < minimumClusters.getValue() || grown + budding == 0) return;
        publish("Geode", "Amethyst geode", new BlockPos(xSum / (grown + budding), ySum / (grown + budding), zSum / (grown + budding)), grown);
    }
}
