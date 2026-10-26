package hack.raze.client.features.impl.base;

import hack.raze.client.event.EventSubscribe;
import hack.raze.client.event.impl.EventSystemUpdate;
import hack.raze.client.features.settings.impl.BoolSetting;
import hack.raze.client.features.settings.impl.IntSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Scans cached chunk sections for server-visible ancient debris. */
public final class DebrisScout extends BaseFinderFeature {
    private final BoolSetting netherOnly = new BoolSetting("Nether only", true);
    private final IntSetting minimumPerChunk = new IntSetting("Minimum debris per chunk", 1, 1, 16);

    public DebrisScout() { super("Debris Scout", "Finds ancient debris in loaded Nether chunks"); }

    @EventSubscribe private void update(EventSystemUpdate event) { scanTick(event); }

    @Override protected void scanChunk(LevelChunk chunk) {
        if (netherOnly.getValue() && mc.level.dimension() != Level.NETHER) return;
        int count = 0, sumX = 0, sumY = 0, sumZ = 0;
        LevelChunkSection[] sections = chunk.getSections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(s -> s.is(Blocks.ANCIENT_DEBRIS))) continue;
            int baseY = (chunk.getMinSectionY() + sectionIndex) << 4;
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                if (!section.getBlockState(x, y, z).is(Blocks.ANCIENT_DEBRIS)) continue;
                count++; sumX += chunk.getPos().getMinBlockX() + x; sumY += baseY + y; sumZ += chunk.getPos().getMinBlockZ() + z;
            }
        }
        if (count >= minimumPerChunk.getValue())
            publish("Debris", "Ancient debris", new BlockPos(sumX / count, sumY / count, sumZ / count), count);
    }
}
