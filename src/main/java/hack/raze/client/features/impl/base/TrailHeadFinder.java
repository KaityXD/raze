package hack.raze.client.features.impl.base;

import hack.raze.client.event.EventSubscribe;
import hack.raze.client.api.ChunkPosCompat;
import hack.raze.client.event.impl.EventSystemUpdate;
import hack.raze.client.features.settings.impl.IntSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Comparator;
import java.util.Set;

/** Finds the furthest marked fresh chunk as a simple travel-trail endpoint. */
public final class TrailHeadFinder extends BaseFinderFeature {
    private final IntSetting minimumTrailChunks = new IntSetting("Minimum trail chunks", 8, 2, 100);
    private BlockPos trailHead;
    private int trailLength;
    private int updateTicks;

    public TrailHeadFinder() { super("Trail Head Finder", "Tracks visited chunk paths and reports long fresh-chunk trails"); }

    @EventSubscribe private void update(EventSystemUpdate event) {
        if (!event.shouldRunGameTick() || isNull()) return;
        if (++updateTicks < 60) return;
        updateTicks = 0;
        Set<Long> chunks = ChunkFlowTracker.markedChunks();
        if (chunks.size() < minimumTrailChunks.getValue()) { trailHead = null; trailLength = chunks.size(); return; }
        int px = ChunkPosCompat.x(mc.player.chunkPosition()), pz = ChunkPosCompat.z(mc.player.chunkPosition());
        trailHead = chunks.stream()
                .filter(key -> Math.abs((int) (key.longValue() >> 32) - px) <= Math.round(scanRange.getMaxValue())
                        && Math.abs(key.intValue() - pz) <= Math.round(scanRange.getMaxValue()))
                .max(Comparator.comparingLong(key -> {
                    long dx = (int) (key.longValue() >> 32) - px;
                    long dz = key.intValue() - pz;
                    return dx * dx + dz * dz;
                }))
                .map(key -> new BlockPos((int) (key.longValue() >> 32) * 16 + 8, mc.player.blockPosition().getY(), key.intValue() * 16 + 8))
                .orElse(null);
        trailLength = chunks.size();
        if (trailHead != null) publish("Trail", "Fresh-chunk trail endpoint", trailHead, trailLength);
    }

    @Override protected void scanChunk(LevelChunk chunk) { }
    @Override public String getInfo() { return Integer.toString(trailLength); }
}
