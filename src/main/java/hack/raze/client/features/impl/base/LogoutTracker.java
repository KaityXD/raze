package hack.raze.client.features.impl.base;

import hack.raze.client.event.EventSubscribe;
import hack.raze.client.api.ChatCompat;
import hack.raze.client.event.impl.EventSystemUpdate;
import hack.raze.client.features.FeatureInfo;
import hack.raze.client.features.Category;
import hack.raze.client.features.Feature;
import hack.raze.client.features.settings.impl.BoolSetting;
import hack.raze.client.features.settings.impl.IntSetting;
import net.minecraft.network.chat.Component;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Keeps a local last-seen position when another player leaves the client player list. */
public final class LogoutTracker extends Feature {
    private record Seen(String name, double x, double y, double z, int tick) { }
    private final IntSetting confirmationDelay = new IntSetting("Confirm after", 40, 10, 200, " ticks");
    private final BoolSetting chatAlerts = new BoolSetting("Local chat alerts", true);
    private final Map<UUID, Seen> seen = new HashMap<>();
    private final Map<UUID, Integer> pending = new HashMap<>();
    private int tick;

    public LogoutTracker() { super(new FeatureInfo("Last Seen Tracker", "Remembers the last position of players who disconnect", Category.RENDER)); }

    @EventSubscribe private void update(EventSystemUpdate event) {
        if (!event.shouldRunGameTick() || isNull() || mc.getConnection() == null) return;
        tick++;
        Set<UUID> present = new HashSet<>();
        for (AbstractClientPlayer player : mc.level.players()) {
            if (player == mc.player) continue;
            UUID id = player.getUUID(); present.add(id);
            seen.put(id, new Seen(player.getName().getString(), player.getX(), player.getY(), player.getZ(), tick));
            pending.remove(id);
        }
        for (Map.Entry<UUID, Seen> entry : seen.entrySet()) {
            if (!present.contains(entry.getKey())) pending.putIfAbsent(entry.getKey(), tick);
        }
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (present.contains(entry.getKey())) { iterator.remove(); continue; }
            if (tick - entry.getValue() < confirmationDelay.getValue()) continue;
            if (mc.getConnection().getPlayerInfo(entry.getKey()) == null) {
                Seen last = seen.get(entry.getKey());
                if (last != null && chatAlerts.getValue()) ChatCompat.addMessage(Component.literal(
                        "[Raze] " + last.name() + " last seen at " + (int) last.x() + " " + (int) last.y() + " " + (int) last.z()));
                seen.remove(entry.getKey());
            }
            iterator.remove();
        }
        seen.keySet().removeIf(id -> !present.contains(id) && !pending.containsKey(id));
    }
}
