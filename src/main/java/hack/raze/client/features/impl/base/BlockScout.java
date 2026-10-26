package hack.raze.client.features.impl.base;

import hack.raze.client.Raze;
import hack.raze.client.event.EventSubscribe;
import hack.raze.client.event.impl.EventSystemUpdate;
import hack.raze.client.features.Category;
import hack.raze.client.features.Feature;
import hack.raze.client.features.FeatureInfo;
import hack.raze.client.features.settings.impl.ColorSetting;
import hack.raze.client.features.settings.impl.RegistryPickerSetting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/** Configurable block highlighting backed by Raze's chunk ESP renderer. */
public final class BlockScout extends Feature {
    private final RegistryPickerSetting<Block> targets = new RegistryPickerSetting<>("Blocks to highlight", () -> BuiltInRegistries.BLOCK);
    private final ColorSetting highlightColor = new ColorSetting("Highlight color", 0xFFFFA040);
    private Object lastLevel;

    public BlockScout() {
        super(new FeatureInfo("Block Scout", "Highlights selected blocks in loaded chunks", Category.RENDER));
        targets.setSelected(Blocks.CHEST, true);
        targets.setSelected(Blocks.BARREL, true);
        targets.setSelected(Blocks.DIAMOND_ORE, true);
        targets.setSelected(Blocks.ANCIENT_DEBRIS, true);
        targets.onChanged(this::refreshTargets);
        highlightColor.onChanged(this::refreshTargets);
    }

    @Override public void onEnable() { super.onEnable(); refreshTargets(); }
    @Override public void onDisable() { if (Raze.blockEspManager != null) Raze.blockEspManager.disableBlocks(this); super.onDisable(); }

    private void refreshTargets() {
        if (!isEnabled() || mc.level == null || Raze.blockEspManager == null) return;
        List<Block> blocks = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) if (targets.isSelected(block)) blocks.add(block);
        if (blocks.isEmpty()) Raze.blockEspManager.disableBlocks(this);
        else Raze.blockEspManager.enableBlocks(this, blocks, highlightColor.getARGB());
    }

    @EventSubscribe private void update(EventSystemUpdate event) {
        if (event.shouldRunGameTick() && mc.level != null && mc.level != lastLevel) {
            lastLevel = mc.level;
            refreshTargets();
        }
    }

    @Override public String getInfo() { return Integer.toString(targets.getSelectedCount()); }
}
