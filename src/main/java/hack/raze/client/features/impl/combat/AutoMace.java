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
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

public class AutoMace extends Feature {

    private final ModeSetting mode = new ModeSetting(Concat.of("Mode"), Concat.of("Silent"), Concat.of("Silent"), Concat.of("Smooth"));
    private final FloatSetting range = new FloatSetting(Concat.of("Range"), 4.0F, 1.0F, 6.0F, 0.1F, Concat.of(" blocks"));
    private final FloatSetting aimRange = new FloatSetting(Concat.of("Aim Range"), 5.0F, 1.0F, 8.0F, 0.1F, Concat.of(" blocks"));
    private final FloatSetting fov = new FloatSetting(Concat.of("FOV"), 360.0F, 5.0F, 360.0F, 5.0F);
    private final ModeSetting priority = new ModeSetting(Concat.of("Priority"), Concat.of("Distance"), Concat.of("Distance"), Concat.of("Lowest HP"));
    private final ModeSetting aimPoint = new ModeSetting(Concat.of("Aim Point"), Concat.of("Dynamic"), Concat.of("Dynamic"), Concat.of("Head"), Concat.of("Center"), Concat.of("Closest"));
    private final FloatSetting aimSpeed = new FloatSetting(Concat.of("Aim Speed"), 10.0F, 0.5F, 20.0F, 0.5F);

    private final BoolSetting checkFalling = new BoolSetting(Concat.of("Check Falling"), true);
    private final FloatSetting minFall = new FloatSetting(Concat.of("Min Fall Distance"), 1.5F, 0.5F, 10.0F, 0.5F, o -> checkFalling.getValue());
    private final FloatSetting densityThreshold = new FloatSetting(Concat.of("Density Threshold"), 5.0F, 1.0F, 20.0F, 0.5F);
    private final IntSetting reactionDelay = new IntSetting(Concat.of("Reaction Delay"), 0, 0, 500, " ms");

    private final BoolSetting shieldBreak = new BoolSetting(Concat.of("Stun Slam"), true);

    private final IntSetting stunSlamDelay = new IntSetting(Concat.of("Stun Slam Delay"), 50, 0, 300, " ms", o -> shieldBreak.getValue());

    private final BoolSetting autoSwitch = new BoolSetting(Concat.of("Auto Switch"), true);

    private final BoolSetting restore = new BoolSetting(
            Concat.of("Restore"), true
    ).describedBy(Concat.of("Swap back to your previous slot after the hit instead of staying on the mace"));
    private final IntSetting restoreDelay = new IntSetting(
            Concat.of("Restore Delay"), 0, 0, 20, " ticks", o -> restore.getValue()
    ).describedBy(Concat.of("Ticks to wait before swapping back"));

    private static final boolean RETRY_SLAM = true;
    private static final long RETRY_DELAY_MS = 50L;

    private LivingEntity target;
    private int lastAttackTick = -1;
    private long nextAttackAt;
    private long lastHitAt;
    private long lastMaceHitTime;
    private int savedSlot = -1;
    private int restoreTick = -1;

    private int lastSwapTick = Integer.MIN_VALUE;

    private double fallStartY = -1.0D;
    private boolean isFalling = false;
    private boolean maceHit = false;

    private int slamState = 0;
    private long slamTimer = 0L;

    public AutoMace() {
        super(new FeatureInfo(
                Concat.of("AutoMace"),
                Concat.of("Auto Mace idk what to say just stop asking me nigga"),
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
        if (RotationUtils.isControlledBy(this)) {
            RotationUtils.stopTracking();
        }
        if (SwapStateManager.isActive(this)) SwapStateManager.cancel(this);
        if (savedSlot != -1 && mc.player != null && restore.getValue()) {
            InventoryUtils.setInvSlot(savedSlot);
        }
        reset();
    }

    private void reset() {
        target = null;
        lastAttackTick = -1;
        nextAttackAt = 0L;
        lastHitAt = 0L;
        lastMaceHitTime = 0L;
        savedSlot = -1;
        restoreTick = -1;
        lastSwapTick = Integer.MIN_VALUE;
        resetFall();
    }

    private void resetFall() {
        isFalling = false;
        fallStartY = -1.0D;
        maceHit = false;
        slamState = 0;
        slamTimer = 0L;
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
        updateFall(p);

        double fallDist = fallStartY == -1.0D ? 0.0D : Math.max(0.0D, fallStartY - p.getY());

        if (!shouldAim(p, fallDist)) {
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

        Vec3 aimVec = Aim.point(target, point(), 1.0F);
        if (aimVec == null) {
            aimVec = target.position().add(0, target.getBbHeight() * 0.5, 0);
        }

        boolean isSilent = mode.is(Concat.of("Silent"));
        RotationUtils.aim(this)
                .silent()
                .priority(EventSubscribe.Priority.HIGHEST)
                .aimType(isSilent ? RotationUtils.AimType.BLATANT : RotationUtils.AimType.REGULAR)
                .speed(aimSpeed.getValue() * 15.0F)
                .to(aimVec);
    }

    private boolean shouldAim(LocalPlayer p, double fallDist) {
        if (p.isFallFlying()) return false;
        if (findAnyMace(p) == -1) return false;

        if (checkFalling.getValue()) {
            if (p.onGround() || !isFalling || p.getDeltaMovement().y >= -0.05D) {
                return false;
            }
            if (fallDist < minFall.getValue()) {
                return false;
            }
        }

        return true;
    }

    @EventSubscribe
    private void mv(EventMove.Pre e) {
        if (!isEnabled()) return;
        if (mc.player != null && mc.player.isFallFlying()) return;
        if (!RotationUtils.isControlledBy(this)) return;
        if (!RotationHandler.isHasSilentRotation()) return;
        e.setYaw(RotationHandler.getServerYaw());
        e.setPitch(RotationHandler.getServerPitch());
    }

    @EventSubscribe
    private void onHandleInput(EventHandleInput.Early e) {
        if (!isEnabled() || isNull() || ScreenCompat.hasScreen(mc)) return;

        LocalPlayer p = mc.player;
        tryAttack(p);

        restoreSlotIfDue(p);
    }

    private boolean tryAttack(LocalPlayer p) {
        if (target == null) return false;

        int tick = p.tickCount;
        if (tick == lastAttackTick) return false;

        long now = System.currentTimeMillis();
        if (now < nextAttackAt) return false;

        double fallDist = fallStartY == -1.0D ? 0.0D : Math.max(0.0D, fallStartY - p.getY());

        if (maceHit && RETRY_SLAM
                && isFalling && p.getDeltaMovement().y < -0.05D
                && fallDist >= minFall.getValue()
                && p.getAttackStrengthScale(0.0F) >= 0.9F
                && now - lastMaceHitTime >= RETRY_DELAY_MS) {
            maceHit = false;
            slamState = 0;
        }

        if (!checkFalling.getValue() && maceHit && p.getAttackStrengthScale(0.0F) >= 1.0F) {
            maceHit = false;
            slamState = 0;
        }

        if (!canAttack(p, fallDist)) return false;
        if (!reachable(p, target)) return false;
        if (!aimed(p, target)) return false;

        // Open-respect Stun Slam logic
        if (shieldBreak.getValue() && handleShieldBreak(p, target, fallDist)) {
            return true;
        }

        if (maceHit) return false;

        int mace = findMace(p, fallDist);
        if (mace == -1) return false;

        int current = p.getInventory().getSelectedSlot();
        if (mace != current) {
            if (!autoSwitch.getValue()) return false;
            if (!swapTo(p, mace)) return false;
        }

        attack(p, target);
        registerMaceHit(p, mace);

        long delay = reactionDelay.getValue();
        nextAttackAt = now + Math.max(0, delay);
        return true;
    }

    private void updateFall(LocalPlayer p) {
        if (p.isFallFlying()) {
            resetFall();
            return;
        }

        boolean onGround = p.onGround();
        double currentY = p.getY();
        double yVel = p.getDeltaMovement().y;

        if (onGround) {
            if (isFalling) {
                resetFall();
            }


            if (savedSlot != -1) {
                if (restore.getValue()) {
                    restoreTick = p.tickCount;
                } else {
                    savedSlot = -1;
                    restoreTick = -1;
                }
            }
            return;
        }

        if (fallStartY == -1.0D || currentY > fallStartY) {
            fallStartY = currentY;
        }

        if (yVel > 0.05D) {
            if (maceHit) {
                maceHit = false;
                slamState = 0;
            }
        } else if (yVel < -0.05D) {
            if (!isFalling) {
                isFalling = true;
                maceHit = false;
                slamState = 0;
            }
        }
    }

    private boolean canAttack(LocalPlayer p, double fallDist) {
        if (p.isFallFlying()) return false;
        if (!checkFalling.getValue()) return true;
        if (p.onGround() || !isFalling || p.getDeltaMovement().y >= -0.05D) return false;
        return fallDist >= minFall.getValue();
    }

    private boolean handleShieldBreak(LocalPlayer p, LivingEntity target, double fallDist) {
        if (mc.gameMode == null || p == null) return false;

        boolean targetBlocking = isBlocking(target);

        if ((targetBlocking || slamState > 0) && shieldBreak.getValue()) {
            int axeSlot = findAxe(p);
            if (axeSlot != -1 && autoSwitch.getValue()) {
                if (slamState == 0) {
                    if (!swapTo(p, axeSlot)) return true;
                    attack(p, target);

                    slamState = 1;
                    slamTimer = System.currentTimeMillis();
                    return true;
                } else if (slamState == 1) {
                    if (System.currentTimeMillis() - slamTimer >= stunSlamDelay.getValue()) {
                        int maceSlot = findMace(p, fallDist);
                        if (maceSlot != -1) {
                            if (!swapTo(p, maceSlot)) return true;
                            attack(p, target);
                            slamState = 2;
                            registerMaceHit(p, maceSlot);
                        }
                    }
                    return true;
                } else if (slamState == 2) {
                    return true;
                }
            }
        }
        return false;
    }


    private boolean changeSlot(LocalPlayer p, int slot) {
        if (slot < 0) return false;
        if (p.getInventory().getSelectedSlot() == slot) return true;
        if (p.tickCount == lastSwapTick) return false;
        lastSwapTick = p.tickCount;
        InventoryUtils.setInvSlot(slot);
        return true;
    }


    private boolean swapTo(LocalPlayer p, int slot) {
        int previous = p.getInventory().getSelectedSlot();
        if (previous == slot) return true;
        if (!changeSlot(p, slot)) return false;
        if (savedSlot == -1) savedSlot = previous;
        return true;
    }

    private void registerMaceHit(LocalPlayer p, int maceSlot) {
        maceHit = true;
        lastMaceHitTime = System.currentTimeMillis();
        lastHitAt = lastMaceHitTime;
        lastAttackTick = p.tickCount;

        if (restore.getValue() && savedSlot != -1 && maceSlot != savedSlot) {
            restoreTick = p.tickCount + restoreDelay.getValue();
        } else {
            savedSlot = -1;
            restoreTick = -1;
        }
    }

    private void restoreSlotIfDue(LocalPlayer p) {
        if (restoreTick < 0 || savedSlot < 0) return;
        if (p.tickCount < restoreTick) return;
        if (restore.getValue() && !changeSlot(p, savedSlot)) return; // a swap already happened this tick
        savedSlot = -1;
        restoreTick = -1;
    }

    private boolean reachable(LocalPlayer p, LivingEntity t) {
        if (!TargetUtils.isTargetAllowed(t)) return false;
        Vec3 aim = Aim.point(t, point(), 1.0F);
        if (aim == null) return false;
        return p.getEyePosition().distanceTo(aim) <= range.getValue();
    }

    private boolean valid(LocalPlayer p, LivingEntity t) {
        if (!t.isAlive() || t.isRemoved() || t.isSpectator()) return false;
        if (t == p) return false;
        if (!TargetUtils.isTargetAllowed(t)) return false;
        if (t instanceof Player q) {
            if (q.isCreative()) return false;
            if (PlayerUtils.isFriend(q.getGameProfile().name())) return false;
        }
        Vec3 aim = Aim.point(t, point(), 1.0F);
        if (aim == null) return false;
        if (p.getEyePosition().distanceTo(aim) > aimRange.getValue()) return false;
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

            Vec3 aim = Aim.point(living, point(), 1.0F);
            double dist = aim != null ? p.getEyePosition().distanceTo(aim) : p.distanceTo(living);
            double score = lowestHp ? living.getHealth() : dist;
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
        Vec3 pt = Aim.point(t, point(), 1.0F);
        if (pt == null) pt = t.position().add(0, t.getBbHeight() * 0.5, 0);
        float[] r = RotationUtils.calculate(p.getEyePosition(1.0F), pt);
        float yd = Math.abs(Mth.wrapDegrees(r[0] - p.getYRot()));
        float pd = Math.abs(r[1] - p.getXRot());
        return Math.hypot(yd, pd) <= half;
    }

    private boolean aimed(LocalPlayer p, LivingEntity t) {
        if (mode.is(Concat.of("Silent"))) {
            return RotationUtils.hasSilentRotation() || RotationUtils.isControlledBy(this);
        }

        Vec3 tp = Aim.point(t, point(), 1.0F);
        if (tp == null) tp = t.position().add(0, t.getBbHeight() * 0.5, 0);

        float[] rr = RotationUtils.calculate(p.getEyePosition(1.0F), tp);
        float yd = Math.abs(Mth.wrapDegrees(RotationUtils.getSilentYaw() - rr[0]));
        float pd = Math.abs(RotationUtils.getSilentPitch() - rr[1]);

        if (yd <= 35.0F && pd <= 35.0F) return true;

        return rayHits(p, t);
    }

    private boolean rayHits(LocalPlayer p, LivingEntity t) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = RotationHandler.getRotationVector(RotationUtils.getSilentPitch(), RotationUtils.getSilentYaw());
        Vec3 end = eye.add(look.scale(range.getValue() + 0.5D));
        return t.getBoundingBox().inflate(0.2D).clip(eye, end).isPresent();
    }

    private boolean isBlocking(LivingEntity t) {
        if (!(t instanceof Player q)) return false;
        return q.isBlocking();
    }

    private int findMace(LocalPlayer p, double fallDistance) {
        boolean useDensity = p.fallDistance >= densityThreshold.getValue() || fallDistance >= densityThreshold.getValue();
        int preferred = useDensity
                ? InventoryUtils.findMaceWithEnchantmentInHotbar(Enchantments.DENSITY, false)
                : InventoryUtils.findMaceWithEnchantmentInHotbar(Enchantments.BREACH, false);
        if (preferred != -1) return preferred;

        int fallback = useDensity
                ? InventoryUtils.findMaceWithEnchantmentInHotbar(Enchantments.BREACH, false)
                : InventoryUtils.findMaceWithEnchantmentInHotbar(Enchantments.DENSITY, false);
        if (fallback != -1) return fallback;

        return InventoryUtils.findItemWithPredicateInHotbar(stack -> stack.getItem() == Items.MACE || stack.getItem() instanceof MaceItem);
    }

    private int findAnyMace(LocalPlayer p) {
        return InventoryUtils.findItemWithPredicateInHotbar(stack -> stack.getItem() == Items.MACE || stack.getItem() instanceof MaceItem);
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
