package hack.raze.client.features.impl.misc;

import hack.raze.client.api.InventoryClickCompat;
import hack.raze.client.api.InventoryClickType;
import hack.raze.client.api.MinecraftCompat;
import hack.raze.client.event.EventSubscribe;
import hack.raze.client.event.impl.EventSystemUpdate;
import hack.raze.client.features.Category;
import hack.raze.client.features.Feature;
import hack.raze.client.features.FeatureInfo;
import hack.raze.client.features.settings.impl.BoolSetting;
import hack.raze.client.features.settings.impl.IntSetting;
import hack.raze.client.features.settings.impl.RangeSetting;
import hack.raze.client.mixin.accessors.MultiPlayerGameModeAccessor;
import hack.raze.client.utils.inventory.InventoryUtils;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;

/** Keeps a totem in the offhand when one is available in the player's inventory. */


//TODO: make auto switch swich the same tick when open the inventory and make the delay effect the hotbar slot totem too dylen?
public final class AutoTotem extends Feature {
    private static final int OFFHAND_SWAP_BUTTON = 40;

    private final BoolSetting inventoryOnly = new BoolSetting("Inventory Only", false);
    private final BoolSetting randomSlot = new BoolSetting("Random Slot", true);
    private final BoolSetting Hotbar = new BoolSetting("Hotbar", true);
    private final IntSetting hotbarSlot = new IntSetting("Hotbar Slot", 1, 1, 9, ignored -> Hotbar.getValue());
    private final BoolSetting Switch = new BoolSetting("Auto Switch", true);
    private final RangeSetting delay = new RangeSetting("Delay", 50, 200, 0, 1000, 1, " ms");
    private final BoolSetting extraRandomization = new BoolSetting("Extra Randomization", true);
    private final BoolSetting showTotemCount = new BoolSetting("Show Totem Count", false);
    private final BoolSetting openInventory = new BoolSetting("Open Inventory", true);
    private final BoolSetting silentOpen = new BoolSetting("Silent Open", false, ignored -> openInventory.getValue());
    private final BoolSetting autoClose = new BoolSetting("Auto Close", false, ignored -> openInventory.getValue());

    private long nextSwapAt;
    private boolean forcedOpen;
    private boolean wasInventoryOpen;
    private int pendingSwitchSlot = -1;

    public AutoTotem() {
        super(new FeatureInfo("AutoTotem", "Automatically equips a totem in the offhand and a chosen hotbar slot", Category.UTILITY));
    }

    @Override
    public void onEnable() {
        super.onEnable();
        nextSwapAt = 0L;
        forcedOpen = false;
        wasInventoryOpen = false;
        pendingSwitchSlot = -1;
    }

    @Override
    public void onDisable() {
        super.onDisable();
        forcedOpen = false;
        wasInventoryOpen = false;
        pendingSwitchSlot = -1;
    }

    @EventSubscribe
    private void onUpdate(EventSystemUpdate event) {
        if (!event.shouldRunGameTick() || isNull() || mc.gameMode == null) return;

        int hotbarTarget = hotbarSlot.getValue() - 1;
        boolean inventoryScreenOpen = MinecraftCompat.getScreen() instanceof InventoryScreen;
        if (!inventoryScreenOpen) {
            if (wasInventoryOpen) applyPendingSwitch();
            forcedOpen = false;
        }
        wasInventoryOpen = inventoryScreenOpen;

        handleTotem(hotbarTarget, inventoryScreenOpen);

        if (inventoryScreenOpen) maybeAutoClose(hotbarTarget);
    }

    private void handleTotem(int hotbarTarget, boolean inventoryScreenOpen) {
        if (System.currentTimeMillis() < nextSwapAt) return;

        boolean needOffhand = !mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        boolean needHotbar = Hotbar.getValue()
                && !mc.player.getInventory().getItem(hotbarTarget).is(Items.TOTEM_OF_UNDYING);
        if (!needOffhand && !needHotbar) return;

        if (inventoryOnly.getValue() && !inventoryScreenOpen) return;

        if (!inventoryScreenOpen && openInventory.getValue() && !silentOpen.getValue()) {
            // Open only when no other screen is active; never replace a chest or another UI.
            if (MinecraftCompat.getScreen() != null) return;
            forcedOpen = true;
            MinecraftCompat.setScreen(new InventoryScreen(mc.player));
            return;
        }
        if (!inventoryScreenOpen && !openInventory.getValue() && !silentOpen.getValue()) return;

        // Don't send inventory clicks against a chest, crafting menu, or other open container.
        if (mc.player.containerMenu != mc.player.inventoryMenu) return;

        if (Switch.getValue() && Hotbar.getValue()) {
            pendingSwitchSlot = hotbarTarget;
        }

        int targetSlot = needOffhand ? -1 : hotbarTarget;
        int inventorySlot = findTotemSlot(mc.player.getInventory(), targetSlot);
        if (inventorySlot < 0) return;

        int menuSlot = inventorySlot < 9 ? inventorySlot + 36 : inventorySlot;
        int button = needOffhand ? OFFHAND_SWAP_BUTTON : hotbarTarget;
        InventoryClickCompat.handleClick(
                mc.player.inventoryMenu.containerId,
                menuSlot,
                button,
                InventoryClickType.SWAP,
                mc.player
        );
        long wait = Math.max(0L, (long) delay.getRandom());
        if (extraRandomization.getValue()) wait += (long) (Math.random() * 180.0);
        nextSwapAt = System.currentTimeMillis() + wait;
    }

    private void maybeAutoClose(int hotbarTarget) {
        if (!autoClose.getValue() || inventoryOnly.getValue() || !forcedOpen) return;

        boolean needOffhand = !mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        boolean needHotbar = Hotbar.getValue()
                && !mc.player.getInventory().getItem(hotbarTarget).is(Items.TOTEM_OF_UNDYING);
        if (needOffhand || needHotbar) {
            int target = needOffhand ? -1 : hotbarTarget;
            if (findTotemSlot(mc.player.getInventory(), target) >= 0) return;
        }

        forcedOpen = false;
        mc.player.closeContainer();
    }

    private void applyPendingSwitch() {
        if (pendingSwitchSlot < 0) return;
        int slot = pendingSwitchSlot;
        pendingSwitchSlot = -1;
        InventoryUtils.setInvSlot(slot);
        if (mc.gameMode != null) {
            ((MultiPlayerGameModeAccessor) mc.gameMode).syncSlot();
        }
    }

    @Override
    public String getInfo() {
        if (!showTotemCount.getValue() || isNull()) return "";
        int count = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
                ? mc.player.getOffhandItem().getCount() : 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.is(Items.TOTEM_OF_UNDYING)) count += stack.getCount();
        }
        return Integer.toString(count);
    }

    private int findTotemSlot(Inventory inventory, int exclude) {
        int[] candidates = new int[36];
        int count = 0;
        // Prefer main inventory slots, preserving hotbar items where possible.
        for (int slot = 9; slot < 36; slot++) {
            if (slot != exclude && inventory.getItem(slot).is(Items.TOTEM_OF_UNDYING)) candidates[count++] = slot;
        }
        if (count == 0) {
            for (int slot = 0; slot < 9; slot++) {
                if (slot != exclude && inventory.getItem(slot).is(Items.TOTEM_OF_UNDYING)) candidates[count++] = slot;
            }
        }
        if (count == 0) return -1;
        return candidates[randomSlot.getValue() ? (int) (Math.random() * count) : 0];
    }
}
