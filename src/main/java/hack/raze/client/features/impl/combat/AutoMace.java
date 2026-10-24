package hack.raze.client.features.impl.combat;

import hack.raze.client.api.ScreenCompat;
import hack.raze.client.event.EventSubscribe;
import hack.raze.client.event.impl.EventHandleInput;
import hack.raze.client.event.impl.EventMove;
import hack.raze.client.event.impl.EventSystemUpdate;
import hack.raze.client.features.Category;
import hack.raze.client.features.Feature;
import hack.raze.client.features.FeatureInfo;
import hack.raze.client.features.settings.impl.BoolSetting;
import hack.raze.client.features.settings.impl.FloatSetting;
import hack.raze.client.features.settings.impl.IntSetting;
import hack.raze.client.features.settings.impl.ModeSetting;
import hack.raze.client.handlers.RotationHandler;
import hack.raze.client.handlers.impl.SwapStateManager;
import hack.raze.client.utils.combat.Aim;
import hack.raze.client.utils.combat.TargetUtils;
import hack.raze.client.utils.inventory.InventoryUtils;
import hack.raze.client.utils.player.PlayerUtils;
import hack.raze.client.utils.rotation.RotationUtils;
import hack.raze.client.utils.strings.Concat;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class AutoMace extends Feature {

    private final FloatSetting range = new FloatSetting(Concat.of("Range"), 3.0F, 1.0F, 6.0F, 0.1F, Concat.of(" blocks"));
    private final FloatSetting aimRange = new FloatSetting(Concat.of("Aim Range"), 4.5F, 1.0F, 8.0F, 0.1F, Concat.of(" blocks"));
    private final FloatSetting fov = new FloatSetting(Concat.of("FOV"), 360.0F, 5.0F, 360.0F, 5.0F);
    private final ModeSetting priority = new ModeSetting(Concat.of("Priority"), Concat.of("Distance"), Concat.of("Distance"), Concat.of("Lowest HP"));
    private final ModeSetting aimPoint = new ModeSetting(Concat.of("Aim Point"), Concat.of("Dynamic"), Concat.of("Dynamic"), Concat.of("Head"), Concat.of("Center"), Concat.of("Closest"));
    private final FloatSetting aimSpeed = new FloatSetting(Concat.of("Aim Speed"), 7.0F, 0.5F, 20.0F, 0.5F);

    private final BoolSetting checkFalling = new BoolSetting(Concat.of("Check Falling"), true);
    private final FloatSetting minFall = new FloatSetting(Concat.of("Min Fall Distance"), 3.0F, 1.0F, 10.0F, 0.5F, o -> checkFalling.getValue());
    private final FloatSetting densityThreshold = new FloatSetting(Concat.of("Density Threshold"), 5.0F, 1.0F, 20.0F, 0.5F);
    private final IntSetting reactionDelay = new IntSetting(Concat.of("Reaction Delay"), 0, 0, 500, " ms");

    private final BoolSetting shieldBreak = new BoolSetting(Concat.of("Stun Slam"), true);
    private final BoolSetting autoSwitch = new BoolSetting(Concat.of("Auto Switch"), true);
    private final BoolSetting stayOnMace = new BoolSetting(Concat.of("Stay On Mace"), false);
    private final BoolSetting swapBack = new BoolSetting(Concat.of("Swap Back On Hit"), true);
    private final IntSetting swapBackDelay = new IntSetting(Concat.of("Swap Back Delay"), 2, 0, 10, " ticks");

    private final BoolSetting retrySlam = new BoolSetting(Concat.of("Retry Slam"), true);
    private final IntSetting retryDelay = new IntSetting(Concat.of("Retry Delay"), 250, 0, 1000, " ms");

    private LivingEntity target;
    private int lastAttackTick = -1;
    private long nextAttackAt;
    private long lastHitAt;
    private int savedSlot = -1;
    private int restoreTick = -1;
    private double fallStartY = -1.0D;

    public AutoMace() {
        super(new FeatureInfo(
                Concat.of("AutoMace"),
                Concat.of("Killaura but for maces: aims, swaps to the best mace and slams"),
                Category.COMBAT
        ));
    }

    @Override
    public void onEnable() {
        super.onEnable();
        reset();
    }

    @Override
    public void onDisable() {
        super.onDisable();
        if (SwapStateManager.isActive(this)) SwapStateManager.cancel(this);
        reset();
    }

    private void reset() {
        target = null;
        lastAttackTick = -1;
        nextAttackAt = 0L;
        lastHitAt = 0L;
        savedSlot = -1;
        restoreTick = -1;
        fallStartY = -1.0D;
    }

    @EventSubscribe
    private void onUpdate(EventSystemUpdate e) {
        if (!isEnabled() || isNull()) {
            target = null;
            return;
        }
        if (ScreenCompat.hasScreen(mc)) {
            target = null;
            return;
        }

        LocalPlayer p = mc.player;
        restoreSlotIfDue(p);
        trackFall(p);

        if (!canTarget(p)) {
            target = null;
            if (RotationUtils.isControlledBy(this)) {
                RotationUtils.cleanup(this, true, aimSpeed.getValue() * 15.0F, aimSpeed.getValue() * 15.0F,
                        RotationUtils.AimType.REGULAR, true);
            }
            return;
        }

        if (target != null && !valid(p, target)) target = null;
        if (target == null) target = findTarget(p);

        if (target == null) {
            if (RotationUtils.isControlledBy(this)) {
                RotationUtils.cleanup(this, true, aimSpeed.getValue() * 15.0F, aimSpeed.getValue() * 15.0F,
                        RotationUtils.AimType.REGULAR, true);
            }
            return;
        }

        RotationUtils.aim(this)
                .silent()
                .speed(aimSpeed.getValue() * 15.0F)
                .to(Aim.point(target, point(), 1.0F));
    }

    @EventSubscribe
    private void mv(EventMove.Pre e) {
        if (!isEnabled()) return;
        if (!RotationHandler.isHasSilentRotation()) return;
        e.setYaw(RotationHandler.getServerYaw());
        e.setPitch(RotationHandler.getServerPitch());
    }

    @EventSubscribe
    private void onHandleInput(EventHandleInput.Early e) {
        if (!isEnabled() || isNull() || ScreenCompat.hasScreen(mc)) return;
        if (target == null) return;

        LocalPlayer p = mc.player;
        int tick = p.tickCount;
        if (tick == lastAttackTick) return;

        long now = System.currentTimeMillis();
        if (now < nextAttackAt) return;

        if (!reachable(p, target)) return;
        if (!aimed(p, target)) return;
        if (!timing(p)) return;

        if (shieldBreak.getValue() && isBlocking(target)) {
            if (breakShield(p, target)) {
                lastAttackTick = tick;
                nextAttackAt = now + 100L;
                return;
            }
        }

        int mace = findMace(p);
        if (mace == -1) return;

        int current = p.getInventory().getSelectedSlot();
        if (mace != current) {
            if (!autoSwitch.getValue()) return;
            if (savedSlot == -1) savedSlot = current;
            InventoryUtils.setInvSlot(mace);
        }

        attack(p, target);
        lastAttackTick = tick;
        lastHitAt = now;

        if (!stayOnMace.getValue() && swapBack.getValue() && savedSlot != -1 && mace != savedSlot) {
            restoreTick = tick + swapBackDelay.getValue();
        } else {
            savedSlot = -1;
            restoreTick = -1;
        }

        long delay = reactionDelay.getValue();
        if (retrySlam.getValue() && !p.onGround()) delay = Math.min(delay, retryDelay.getValue());
        nextAttackAt = now + Math.max(0, delay);
    }

    private void restoreSlotIfDue(LocalPlayer p) {
        if (restoreTick < 0 || savedSlot < 0) return;
        if (p.tickCount < restoreTick) return;
        if (!stayOnMace.getValue()) {
            InventoryUtils.setInvSlot(savedSlot);
        }
        savedSlot = -1;
        restoreTick = -1;
        fallStartY = -1.0D;
    }

    private boolean valid(LocalPlayer p, LivingEntity t) {
        if (!t.isAlive() || t.isRemoved() || t.isSpectator()) return false;
        if (t == p) return false;
        if (!TargetUtils.isTargetAllowed(t)) return false;
        if (t instanceof Player q) {
            if (q.isCreative()) return false;
            if (PlayerUtils.isFriend(q.getGameProfile().name())) return false;
        }
        if (t.distanceTo(p) > aimRange.getValue()) return false;
        return inFov(p, t);
    }

    private LivingEntity findTarget(LocalPlayer p) {
        LivingEntity manual = PlayerUtils.getManualSelectTarget();
        if (manual != null && valid(p, manual)) return manual;

        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        boolean lowestHp = priority.is(Concat.of("Lowest HP"));

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!valid(p, living)) continue;

            double score = lowestHp ? living.getHealth() : p.distanceTo(living);
            if (score < bestScore) {
                bestScore = score;
                best = living;
            }
        }
        return best;
    }

    private boolean inFov(LocalPlayer p, LivingEntity t) {
        float half = fov.getValue() / 2.0F;
        if (half >= 180.0F) return true;
        float[] r = RotationUtils.calculate(p.getEyePosition(1.0F), Aim.point(t, point(), 1.0F));
        float yd = Math.abs(Mth.wrapDegrees(r[0] - p.getYRot()));
        float pd = Math.abs(r[1] - p.getXRot());
        return Math.hypot(yd, pd) <= half;
    }

    private boolean reachable(LocalPlayer p, LivingEntity t) {
        AABB box = t.getBoundingBox();
        double reach = range.getValue();
        if (box.distanceToSqr(p.getEyePosition()) > reach * reach) return false;
        return TargetUtils.isTargetAllowed(t);
    }

    private boolean aimed(LocalPlayer p, LivingEntity t) {
        if (!RotationUtils.hasSilentRotation()) return false;

        Vec3 tp = Aim.point(t, point(), 1.0F);
        if (tp == null) return false;

        float[] rr = RotationUtils.calculate(p.getEyePosition(1.0F), tp);
        float yd = Mth.wrapDegrees(RotationUtils.getSilentYaw() - rr[0]);
        float pd = RotationUtils.getSilentPitch() - rr[1];
        return Math.abs(yd) <= 12.0F && Math.abs(pd) <= 12.0F;
    }

    private void trackFall(LocalPlayer p) {
        if (p.onGround()) {
            fallStartY = -1.0D;
            return;
        }
        if (fallStartY == -1.0D || p.getY() > fallStartY) {
            fallStartY = p.getY();
        }
    }

    private double fallHeight(LocalPlayer p) {
        if (fallStartY == -1.0D) return 0.0D;
        return Math.max(0.0D, fallStartY - p.getY());
    }

    private boolean canTarget(LocalPlayer p) {
        if (!checkFalling.getValue()) return true;
        if (p.onGround() || p.isFallFlying()) return false;
        if (p.getDeltaMovement().y >= -0.05D) return false;
        return fallHeight(p) >= minFall.getValue();
    }

    private boolean timing(LocalPlayer p) {
        return canTarget(p);
    }

    private boolean isBlocking(LivingEntity t) {
        if (!(t instanceof Player q)) return false;
        return q.isBlocking();
    }

    private boolean breakShield(LocalPlayer p, LivingEntity t) {
        int axe = findAxe(p);
        if (axe == -1) return false;
        if (p.getInventory().getSelectedSlot() != axe) {
            InventoryUtils.setInvSlot(axe);
            return false;
        }
        attack(p, t);
        return true;
    }

    private int findMace(LocalPlayer p) {
        boolean falling = p.fallDistance >= densityThreshold.getValue();
        int preferred = falling
                ? InventoryUtils.findMaceWithEnchantmentInHotbar(Enchantments.DENSITY, false)
                : InventoryUtils.findMaceWithEnchantmentInHotbar(Enchantments.BREACH, false);
        if (preferred != -1) return preferred;

        int fallback = falling
                ? InventoryUtils.findMaceWithEnchantmentInHotbar(Enchantments.BREACH, false)
                : InventoryUtils.findMaceWithEnchantmentInHotbar(Enchantments.DENSITY, false);
        if (fallback != -1) return fallback;

        return InventoryUtils.findItemWithPredicateInHotbar(stack -> stack.getItem() == Items.MACE);
    }

    private int findAxe(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            Item item = p.getInventory().getItem(i).getItem();
            if (item instanceof AxeItem) return i;
        }
        return -1;
    }

    private void attack(LocalPlayer p, LivingEntity t) {
        if (mc.gameMode == null) return;
        if (p.isUsingItem()) mc.gameMode.releaseUsingItem(p);
        mc.gameMode.attack(p, t);
        p.swing(InteractionHand.MAIN_HAND);
        TargetUtils.onAttack(t);
    }

    private Aim.Point point() {
        if (aimPoint.is(Concat.of("Head"))) return Aim.Point.HEAD;
        if (aimPoint.is(Concat.of("Center"))) return Aim.Point.CENTER;
        if (aimPoint.is(Concat.of("Closest"))) return Aim.Point.CLOSEST;
        return Aim.Point.DYNAMIC;
    }
}
