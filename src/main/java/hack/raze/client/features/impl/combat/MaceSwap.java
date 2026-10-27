package hack.raze.client.features.impl.combat;

import hack.raze.client.event.EventSubscribe;
import hack.raze.client.event.impl.EventOnAttackEntity;
import hack.raze.client.event.impl.EventStartAttack;
import hack.raze.client.features.Category;
import hack.raze.client.features.Feature;
import hack.raze.client.features.FeatureManager;
import hack.raze.client.features.FeatureInfo;
import hack.raze.client.features.settings.impl.BoolSetting;
import hack.raze.client.features.settings.impl.FloatSetting;
import hack.raze.client.features.settings.impl.IntSetting;
import hack.raze.client.features.settings.impl.MultiModeSetting;
import hack.raze.client.handlers.impl.SwapStateManager;
import hack.raze.client.utils.inventory.InventoryUtils;
import hack.raze.client.utils.player.PlayerUtils;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

public class MaceSwap extends Feature {

    private final MultiModeSetting w = new MultiModeSetting("Weapon Types", "Sword", "Axe", "Spear", "Other");
    private final BoolSetting rs = new BoolSetting("Restore", true);
    private final IntSetting rd = new IntSetting("Restore Delay", 0, 0, 10, " ticks", o -> rs.getValue());
    private final BoolSetting bg = new BoolSetting("Breach On Ground", false);
    private final FloatSetting dt = new FloatSetting("Density Threshold", 1.5F, 0.0F, 10.0F, 0.1F);
    private final BoolSetting so = new BoolSetting("Smash Only", false);

    private int lt = -1;

    public MaceSwap() {
        super(new FeatureInfo("MaceSwap", "Swaps to a mace at the perfect moment for max damage", Category.COMBAT));
        w.toggle("Sword");
        w.toggle("Axe");
        w.toggle("Spear");
    }

    @EventSubscribe
    private void ca(EventStartAttack e) {
        if (!isEnabled() || isNull()) return;
        if (e.cancelled || anotherFeatureOwnsSwap()) return;
        if (mc.player.tickCount == lt) return;
        if (!wc()) return;
        if (SwapStateManager.isActive(this)) return;
        if (ShieldStun.busy()) return;

        LivingEntity t = PlayerUtils.getManualSelectTarget();
        if (t == null) return;
        if (t == mc.player || !t.isAlive()) return;
        if (ShieldStun.owns(t) || am(t)) return;

        if (!sa(t)) return;

        e.cancel();
    }

    @EventSubscribe
    private void aa(EventOnAttackEntity.Pre e) {
        if (!isEnabled() || isNull()) return;
        if (e.cancelled || anotherFeatureOwnsSwap()) return;
        if (mc.player.tickCount == lt) return;
        if (!wc()) return;
        if (SwapStateManager.isActive(this)) return;
        if (ShieldStun.busy()) return;

        Entity r = e.getTarget();
        if (!(r instanceof LivingEntity t)) return;
        if (t == mc.player || !t.isAlive()) return;
        if (ShieldStun.owns(t) || am(t)) return;

        if (!sa(t)) return;

        e.cancel();
    }

    @Override
    public void onDisable() {
        SwapStateManager.cancel(this);
    }

    private boolean sa(LivingEntity t) {
        int c = SwapStateManager.getSelectedSlot();
        int m = fm();
        if (m == -1) return false;
        if (m == c) return false;
        if (so.getValue() && !sm()) return false;

        lt = mc.player.tickCount;

        if (rs.getValue()) {
            SwapStateManager.swapTo(this, m, false, rd.getValue() + 1);
        } else {
            InventoryUtils.setInvSlot(m);
        }
        an(t);
        return true;
    }

    private void an(LivingEntity t) {
        if (mc.gameMode == null || mc.player == null || t == null) return;
        mc.gameMode.attack(mc.player, t);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private boolean wc() {
        Item h = mc.player.getMainHandItem().getItem();
        if (w.isEnabled("Other")) return true;
        if (w.isEnabled("Sword") && sd(h)) return true;
        if (w.isEnabled("Axe") && h instanceof AxeItem) return true;
        if (w.isEnabled("Spear") && InventoryUtils.isItemSpear(h)) return true;
        return false;
    }

    private boolean sd(Item i) {
        return InventoryUtils.isSword(i) || i == Items.COPPER_SWORD;
    }

    private boolean am(LivingEntity t) {
        return mc.player.getMainHandItem().getItem() instanceof AxeItem && ShieldStun.blocking(t);
    }

    public int maceForSmash() {
        if (!isEnabled() || isNull()) return -1;
        int m = fm();
        if (m == -1) return -1;
        if (m == SwapStateManager.getSelectedSlot()) return -1;
        return m;
    }

    private boolean anotherFeatureOwnsSwap() {
        if (SwapStateManager.hasActiveSwaps() && !SwapStateManager.isActive(this)) return true;
        return FeatureManager.safeGetFeature(AutoMace.class)
                .map(AutoMace::isSwappingWeapon)
                .orElse(false);
    }

    private int fm() {
        boolean d = mc.player.fallDistance >= dt.getValue();
        if (!d && bg.getValue()) {
            return InventoryUtils.findMaceWithEnchantmentInHotbar(Enchantments.BREACH, false);
        }

        if (so.getValue() && !sm()) return -1;

        int p = d
                ? InventoryUtils.findMaceWithEnchantmentInHotbar(Enchantments.DENSITY, false)
                : InventoryUtils.findMaceWithEnchantmentInHotbar(Enchantments.BREACH, false);
        int s = d
                ? InventoryUtils.findMaceWithEnchantmentInHotbar(Enchantments.BREACH, false)
                : InventoryUtils.findMaceWithEnchantmentInHotbar(Enchantments.DENSITY, false);
        if (p != -1) return p;
        if (s != -1) return s;
        return InventoryUtils.findItemWithPredicateInHotbar(q -> q.getItem() == Items.MACE);
    }

    private boolean sm() {
        return !mc.player.onGround() && mc.player.fallDistance >= 1.5F;
    }
}
