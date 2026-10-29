package hack.raze.client.features.impl.base;

import hack.raze.client.event.impl.EventSystemUpdate;
import hack.raze.client.api.ChatCompat;
import hack.raze.client.api.ChunkPosCompat;
import hack.raze.client.features.Category;
import hack.raze.client.features.Feature;
import hack.raze.client.features.FeatureInfo;
import hack.raze.client.features.settings.impl.BoolSetting;
import hack.raze.client.features.settings.impl.IntSetting;
import hack.raze.client.features.settings.impl.RangeSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.*;

/** Shared loaded-chunk scan budget and local-only result list for Raze's base-finding tools. */
public abstract class BaseFinderFeature extends Feature {
    private static final LinkedHashMap<String, String> RESULTS = new LinkedHashMap<>();

    protected final RangeSetting scanRange = new RangeSetting("Scan radius", 6, 6, 1, 12, 1, " chunks");
    protected final IntSetting rescanDelay = new IntSetting("Rescan delay", 40, 10, 200, " ticks");
    protected final BoolSetting chatAlerts = new BoolSetting("Local chat alerts", true);

    private final List<long[]> queue = new ArrayList<>();
    private int queueIndex;
    private int waitTicks;
    private Object lastLevel;
    private final Set<String> announced = new HashSet<>();

    protected BaseFinderFeature(String name, String description) {
        super(new FeatureInfo(name, description, Category.RENDER));
    }

    protected final void scanTick(EventSystemUpdate event) {
        if (!event.shouldRunGameTick() || isNull()) return;
        if (lastLevel != mc.level) {
            lastLevel = mc.level;
            queue.clear(); queueIndex = 0; waitTicks = rescanDelay.getValue(); announced.clear();
        }
        if (queue.isEmpty()) {
            if (waitTicks++ < rescanDelay.getValue()) return;
            waitTicks = 0;
            int cx = ChunkPosCompat.x(mc.player.chunkPosition()), cz = ChunkPosCompat.z(mc.player.chunkPosition());
            int radius = Math.round(scanRange.getMaxValue());
            for (int dx = -radius; dx <= radius; dx++)
                for (int dz = -radius; dz <= radius; dz++) queue.add(new long[]{cx + dx, cz + dz});
            queue.sort(Comparator.comparingLong(c -> (c[0] - cx) * (c[0] - cx) + (c[1] - cz) * (c[1] - cz)));
            queueIndex = 0;
            onScanStarted();
        }
        int budget = 4;
        while (budget-- > 0 && queueIndex < queue.size()) {
            long[] c = queue.get(queueIndex++);
            LevelChunk chunk = mc.level.getChunkSource().getChunk((int) c[0], (int) c[1], false);
            if (chunk != null) scanChunk(chunk);
        }
        if (queueIndex >= queue.size()) { queue.clear(); queueIndex = 0; onScanFinished(); }
    }

    protected void onScanStarted() {}
    protected void onScanFinished() {}
    protected abstract void scanChunk(LevelChunk chunk);

    protected final void publish(String category, String label, BlockPos pos, int count) {
        String id = category + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
        String line = label + " @ " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                + (count > 1 ? " (" + count + ")" : "");
        synchronized (RESULTS) { RESULTS.put(id, line); while (RESULTS.size() > 200) RESULTS.remove(RESULTS.keySet().iterator().next()); }
        if (chatAlerts.getValue() && announced.add(id) && mc.player != null)
            ChatCompat.addMessage(Component.literal("[Raze] " + line));
    }

    public static List<String> getRecentResults() {
        synchronized (RESULTS) { return new ArrayList<>(RESULTS.values()); }
    }

    @Override public void onDisable() { queue.clear(); queueIndex = 0; super.onDisable(); }
}
