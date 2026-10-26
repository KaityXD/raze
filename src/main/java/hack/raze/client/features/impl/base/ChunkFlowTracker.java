package hack.raze.client.features.impl.base;

import hack.raze.client.event.EventSubscribe;
import hack.raze.client.event.impl.EventBlockUpdate;
import hack.raze.client.event.impl.EventSystemUpdate;
import hack.raze.client.features.settings.impl.IntSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FluidState;

import java.util.LinkedHashSet;
import java.util.Set;

/** Tracks flowing-fluid block updates as a rough, local clue for recently generated chunks. */
public final class ChunkFlowTracker extends BaseFinderFeature {
    private final IntSetting maximumMarks = new IntSetting("Maximum marked chunks", 500, 50, 3000);
    private static final Set<Long> MARKED = new LinkedHashSet<>();
    private final Set<Long> marked = MARKED;
    private Object world;

    public ChunkFlowTracker() { super("Fresh Chunk Tracker", "Marks chunks where flowing fluid updates may indicate recent generation"); }

    @EventSubscribe private void update(EventSystemUpdate event) {
        if (!event.shouldRunGameTick()) return;
        if (mc.level != world) { world = mc.level; marked.clear(); }
    }

    @EventSubscribe private void onBlockUpdate(EventBlockUpdate event) {
        if (isNull() || !isEnabled()) return;
        FluidState fluid = event.getState().getFluidState();
        if (fluid.isEmpty() || fluid.isSource()) return;
        BlockPos pos = event.getPos();
        long key = pack(pos.getX() >> 4, pos.getZ() >> 4);
        if (!marked.add(key)) return;
        while (marked.size() > maximumMarks.getValue()) marked.remove(marked.iterator().next());
        publish("Fresh chunk", "Possible newly generated chunk", new BlockPos((pos.getX() >> 4) * 16 + 8, pos.getY(), (pos.getZ() >> 4) * 16 + 8), 1);
    }

    @Override protected void scanChunk(net.minecraft.world.level.chunk.LevelChunk chunk) { }

    public static Set<Long> markedChunks() { synchronized (MARKED) { return Set.copyOf(MARKED); } }
    static long pack(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    @Override public String getInfo() { return Integer.toString(marked.size()); }
}
