package hack.raze.client.features.impl.base;

import hack.raze.client.features.Category;
import hack.raze.client.features.Feature;
import hack.raze.client.features.FeatureInfo;
import hack.raze.client.api.ChatCompat;
import net.minecraft.network.chat.Component;

/** Prints recent local scan results when toggled on. */
public final class FindingsList extends Feature {
    public FindingsList() { super(new FeatureInfo("Radar Results", "Prints recent local base-finding results", Category.UTILITY)); }

    @Override public void onEnable() {
        super.onEnable();
        if (mc.player != null) {
            var results = BaseFinderFeature.getRecentResults();
            if (results.isEmpty()) ChatCompat.addMessage(Component.literal("[Raze] No recent radar results."));
            else for (String result : results) ChatCompat.addMessage(Component.literal("[Raze] " + result));
        }
        setEnabled(false);
    }
}
