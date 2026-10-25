package hack.raze.client.features.impl.combat;

import hack.raze.client.api.ScreenCompat;
import hack.raze.client.event.EventSubscribe;
import hack.raze.client.event.impl.EventMove;
import hack.raze.client.features.Category;
import hack.raze.client.features.Feature;
import hack.raze.client.features.FeatureInfo;
import hack.raze.client.features.settings.impl.BoolSetting;
import hack.raze.client.features.settings.impl.FloatSetting;
import hack.raze.client.features.settings.impl.IntSetting;
import hack.raze.client.features.settings.impl.ModeSetting;
import hack.raze.client.handlers.RotationHandler;
import hack.raze.client.handlers.impl.SwapStateManager;
import hack.raze.client.mixin.accessors.MultiPlayerGameModeAccessor;
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

    // -- Hardcoded tuning ----------------------------------------------------

    /** Retry slam is always on with a fixed cooldown; users don't need to tune it. */
    private static final boolean RETRY_SLAM = true;
    private static final long RETRY_DELAY_MS = 50L;

    /** Partial tick used for eye position / hip point lookups (1.0 = newest position). */
    private static final float AIM_PARTIAL_TICK = 1.0F;

    /** Auto switch target slot when nothing suitable is in the hotbar. */
    private static final int NO_SLOT = -1;

    // -- State ---------------------------------------------------------------

    private LivingEntity target;
    private long lastMaceHitTime;
    private int savedSlot = NO_SLOT;

    /**
     * Absolute time (ms) at which the previous slot should be restored. The reference module
     * schedules this 50ms after a hit; here it is driven by the "Restore Delay" setting,
     * which is in ticks, so it is converted with 50ms per tick.
     */
    private long restoreAt;
    private boolean restorePending;

    /** Tick of the last hotbar change. One slot change per tick, attacks may share the tick. */
    private int lastSwapTick = Integer.MIN_VALUE;

    private double fallStartY = -1.0D;
    private boolean isFalling = false;
    private boolean maceHit = false;

    private int slamState = 0;
    private long slamTimer = 0L;

    private long targetTime = 0L;
    private long currentReactionDelay = 0L;
    private boolean wasTargetingLastFrame = false;

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
        if (savedSlot != NO_SLOT && mc.player != null && restore.getValue()) {
            flushSlot(mc.player, savedSlot);
        }
        reset();
    }

    private void reset() {
        target = null;
        lastMaceHitTime = 0L;
        savedSlot = NO_SLOT;
        restoreAt = 0L;
        restorePending = false;
        lastSwapTick = Integer.MIN_VALUE;
        targetTime = 0L;
        currentReactionDelay = 0L;
        wasTargetingLastFrame = false;
        resetFall();
    }

    private void resetFall() {
        isFalling = false;
        fallStartY = -1.0D;
        maceHit = false;
        slamState = 0;
        slamTimer = 0L;
    }

    // -- Main loop -----------------------------------------------------------

    /**
     * The whole module runs from the motion PRE event, exactly like the reference
     * implementation. This fires before {@code sendPosition()} builds the movement packet,
     * so every packet we emit here (slot change, interact, swing) is ordered ahead of the
     * move packet for the same tick instead of trailing it.
     *
     * That ordering is what the "PacketOrderO / Post ... held item change" checks look at.
     * Emitting the attack from the POST phase (after the move packet was already flushed)
     * is what produced the flagged HELD_ITEM_CHANGE -> INTERACT_ENTITY -> ANIMATION trio.
     */
    @EventSubscribe
    private void onMotionPre(EventMove.Pre event) {
        if (!isEnabled() || isNull() || ScreenCompat.hasScreen(mc)) {
            target = null;
            return;
        }

        LocalPlayer p = mc.player;
        updateFall(p);
        try {
            tickAimAndAttack(event, p);
        } finally {
            // Always last, so a swap-to-mace keeps the tick's one slot change and the
            // restore simply slides to the next tick instead of cancelling the attack.
            processPendingRestore(p);
        }
    }

    private void tickAimAndAttack(EventMove.Pre event, LocalPlayer p) {
        if (p.isFallFlying()) {
            target = null;
            return;
        }

        // No mace anywhere in the hotbar -> nothing to do.
        if (findAnyMace(p) == NO_SLOT) {
            target = null;
            return;
        }

        double fallDist = fallStartY == -1.0D ? 0.0D : Math.max(0.0D, fallStartY - p.getY());

        if (checkFalling.getValue()) {
            if (p.onGround() || !isFalling || p.getDeltaMovement().y >= -0.1D) {
                target = null;
                cleanupAim();
                return;
            }
            if (fallDist < minFall.getValue()) {
                target = null;
                cleanupAim();
                return;
            }
        }

        if (target != null && !valid(p, target)) target = null;
        if (target == null) target = findTarget(p);

        if (target == null) {
            cleanupAim();
            return;
        }

        // Aim first, then verify the rotation we are about to send actually points at the
        // target's hitbox inside reach. This is the same thing the server's attack raycast
        // validates, so passing here means there is nothing left to flag.
        if (!applyAim(p, target)) {
            cleanupAim();
            return;
        }
        syncRotation(event, p);

        if (!aimed(p, target)) return;
        if (!canAttack(p, fallDist)) return;

        if (!wasTargetingLastFrame) {
            targetTime = System.currentTimeMillis();
            currentReactionDelay = reactionDelay.getValue();
            wasTargetingLastFrame = true;
        }

        if (maceHit && RETRY_SLAM
                && isFalling && p.getDeltaMovement().y < -0.1D
                && fallDist >= minFall.getValue()
                && p.getAttackStrengthScale(0.0F) >= 0.9F
                && System.currentTimeMillis() - lastMaceHitTime >= RETRY_DELAY_MS) {
            maceHit = false;
            slamState = 0;
        }

        if (!checkFalling.getValue() && maceHit && p.getAttackStrengthScale(0.0F) >= 1.0F) {
            maceHit = false;
            slamState = 0;
        }

        if (System.currentTimeMillis() - targetTime >= currentReactionDelay) {
            handleMaceAttack(p, target, fallDist);
        }
    }

    // -- Aim -----------------------------------------------------------------
    private boolean applyAim(LocalPlayer p, LivingEntity t) {
        Vec3 aimVec = Aim.point(t, point(), AIM_PARTIAL_TICK);
        if (aimVec == null) {
            aimVec = t.position().add(0, t.getBbHeight() * 0.5D, 0);
        }

        boolean isSilent = mode.is(Concat.of("Silent"));
        return RotationUtils.aim(this)
                .silent()
                .priority(EventSubscribe.Priority.HIGHEST)
                .aimType(isSilent ? RotationUtils.AimType.BLATANT : RotationUtils.AimType.REGULAR)
                .speed(aimSpeed.getValue() * 15.0F)
                .to(aimVec);
    }

    private void cleanupAim() {
        wasTargetingLastFrame = false;
        if (!RotationUtils.isControlledBy(this)) return;
        RotationUtils.cleanup(this, true, aimSpeed.getValue() * 15.0F, aimSpeed.getValue() * 15.0F,
                RotationUtils.AimType.REGULAR, true);
    }

    /**
     * Writes the rotation we actually send into the outgoing move packet, so the server
     * stores the same rotation it is going to validate the attack raycast against.
     */
    private void syncRotation(EventMove.Pre event, LocalPlayer p) {
        if (!RotationUtils.isControlledBy(this)) return;
        if (p.isFallFlying()) return;

        if (RotationHandler.isHasSilentRotation()) {
            event.setYaw(RotationHandler.getServerYaw());
            event.setPitch(RotationHandler.getServerPitch());
        } else {
            event.setYaw(p.getYRot());
            event.setPitch(p.getXRot());
        }
    }

    private float sentYaw() {
        return RotationHandler.isHasSilentRotation() ? RotationHandler.getServerYaw() : mc.player.getYRot();
    }

    private float sentPitch() {
        return RotationHandler.isHasSilentRotation() ? RotationHandler.getServerPitch() : mc.player.getXRot();
    }

    /**
     * True when the ray along the rotation we actually sent hits the target's hitbox.
     *
     * The ray length and the inflation now BOTH come from the Range setting, so this check
     * and reachable() cannot disagree, and there is no extra inflation on top of it.
     */
    private boolean aimed(LocalPlayer p, LivingEntity t) {
        Vec3 eye = p.getEyePosition(AIM_PARTIAL_TICK);
        Vec3 look = RotationHandler.getRotationVector(sentPitch(), sentYaw());
        Vec3 end = eye.add(look.scale(range.getValue()));
        return t.getBoundingBox().clip(eye, end).isPresent();
    }

    // -- Reach ---------------------------------------------------------------
    //
    // Feet -> feet, exactly like every working client does it:
    //   Open-respect : mc.player.distanceTo(target) > range
    //   Vape-v4      : player.getDistanceToEntity(target) <= attackRange
    //   Silkcc       : mc.player.distanceTo(entity) > range
    // All three use vanilla Entity.distanceTo (feet-centre, 3D) and then let the game
    // itself arbitrate the actual hit. Re-implementing the reach check client-side with a
    // tighter eye->hitbox metric was our deviation; this matches them.

    /** Vanilla Entity.distanceTo: feet-centre to feet-centre, 3D. */
    private double feetDistance(LocalPlayer p, LivingEntity t) {
        return p.distanceTo(t);
    }

    private boolean reachable(LocalPlayer p, LivingEntity t) {
        if (!TargetUtils.isTargetAllowed(t)) return false;
        return feetDistance(p, t) <= range.getValue();
    }

    // -- Target selection ----------------------------------------------------

    private boolean valid(LocalPlayer p, LivingEntity t) {
        if (!t.isAlive() || t.isRemoved() || t.isSpectator()) return false;
        if (t == p) return false;
        if (!TargetUtils.isTargetAllowed(t)) return false;
        if (t instanceof Player q) {
            if (q.isCreative()) return false;
            if (PlayerUtils.isFriend(q.getGameProfile().name())) return false;
        }
        if (feetDistance(p, t) > aimRange.getValue()) return false;
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

            double score = lowestHp ? living.getHealth() : feetDistance(p, living);
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
        Vec3 pt = Aim.point(t, point(), AIM_PARTIAL_TICK);
        if (pt == null) pt = t.position().add(0, t.getBbHeight() * 0.5D, 0);
        float[] r = RotationUtils.calculate(p.getEyePosition(AIM_PARTIAL_TICK), pt);
        float yd = Math.abs(Mth.wrapDegrees(r[0] - p.getYRot()));
        float pd = Math.abs(r[1] - p.getXRot());
        return Math.hypot(yd, pd) <= half;
    }

    // -- Attack --------------------------------------------------------------

    private void handleMaceAttack(LocalPlayer p, LivingEntity t, double fallDist) {
        if (mc.gameMode == null) return;

        if (shieldBreak.getValue() && handleShieldBreak(p, t, fallDist)) {
            return;
        }

        if (maceHit) return;

        if (!isHoldingMace(p)) {
            if (!autoSwitch.getValue()) return;
            int maceSlot = findMace(p, fallDist);
            if (maceSlot == NO_SLOT) return;
            if (!swapTo(p, maceSlot)) return;
        }

        if (!isHoldingMace(p)) return;

        // Final sanity gate right before the swing, against the same hitbox metric the
        // server uses. If the target drifted out of reach since selection, skip.
        if (!reachable(p, t)) return;

        int maceSlot = p.getInventory().getSelectedSlot();
        attack(p, t);
        registerMaceHit(p, maceSlot);
    }

    private void attack(LocalPlayer p, LivingEntity t) {
        if (mc.gameMode == null) return;
        if (p.isUsingItem()) mc.gameMode.releaseUsingItem(p);
        mc.gameMode.attack(p, t);
        p.swing(InteractionHand.MAIN_HAND);
        TargetUtils.onAttack(t);
    }

    private void registerMaceHit(LocalPlayer p, int maceSlot) {
        maceHit = true;
        long now = System.currentTimeMillis();
        lastMaceHitTime = now;

        if (restore.getValue() && savedSlot != NO_SLOT && maceSlot != savedSlot) {
            // "Restore Delay" is in ticks; 50ms per tick, matching the reference's fixed 50ms.
            restoreAt = now + (restoreDelay.getValue() * 50L);
            restorePending = true;
        } else {
            savedSlot = NO_SLOT;
            restorePending = false;
        }
    }

    // -- Shield break --------------------------------------------------------

    private boolean handleShieldBreak(LocalPlayer p, LivingEntity t, double fallDist) {
        if (mc.gameMode == null) return false;

        boolean targetBlocking = isBlocking(t);

        if ((targetBlocking || slamState > 0) && shieldBreak.getValue()) {
            int axeSlot = findAxe(p);
            if (axeSlot != NO_SLOT && autoSwitch.getValue()) {
                if (slamState == 0) {
                    // Reach gate BEFORE the swap: this path used to attack unconditionally,
                    // so an axe swing at 3.5 blocks sailed past every reach check.
                    if (!reachable(p, t)) return true;
                    if (!swapTo(p, axeSlot)) return true;
                    attack(p, t);

                    slamState = 1;
                    slamTimer = System.currentTimeMillis();
                    return true;
                } else if (slamState == 1) {
                    if (System.currentTimeMillis() - slamTimer >= stunSlamDelay.getValue()) {
                        int maceSlot = findMace(p, fallDist);
                        if (maceSlot != NO_SLOT) {
                            // Same gate for the mace half of the stun slam.
                            if (!reachable(p, t)) return true;
                            if (!swapTo(p, maceSlot)) return true;
                            attack(p, t);
                            slamState = 2;
                            registerMaceHit(p, maceSlot);
                        }
                    }
                    return true;
                } else {
                    return true;
                }
            }
        }
        return false;
    }

    // -- Slots ---------------------------------------------------------------

    /**
     * Changes the selected hotbar slot and flushes the carried-item packet immediately.
     *
     * The eager flush is the important part: if we let {@code gameMode.attack()} send it
     * lazily, the SET_CARRIED_ITEM can end up ordered after the interact or after the move
     * packet, which is what the packet-order checks flag. Sending it here guarantees
     * HELD_ITEM_CHANGE always precedes INTERACT_ENTITY/ANIMATION for this tick.
     *
     * One slot change per tick is enforced; an attack in the same tick as that one change
     * is allowed.
     */
    private boolean setSlot(LocalPlayer p, int slot) {
        if (slot < 0 || slot > 8) return false;
        if (p.getInventory().getSelectedSlot() == slot) return true;
        if (p.tickCount == lastSwapTick) return false;

        lastSwapTick = p.tickCount;
        InventoryUtils.setInvSlot(slot);
        flushSlot(p, slot);
        return true;
    }

    private void flushSlot(LocalPlayer p, int slot) {
        if (mc.gameMode == null) return;
        try {
            ((MultiPlayerGameModeAccessor) mc.gameMode).syncSlot();
        } catch (Throwable ignored) {
            InventoryUtils.setInvSlot(slot);
        }
    }

    /** Swaps to {@code slot} and records the previous slot as the restore point. */
    private boolean swapTo(LocalPlayer p, int slot) {
        int previous = p.getInventory().getSelectedSlot();
        if (previous == slot) return true;
        if (!setSlot(p, slot)) return false;
        if (savedSlot == NO_SLOT) savedSlot = previous;
        return true;
    }

    private void processPendingRestore(LocalPlayer p) {
        if (!restorePending) return;
        if (System.currentTimeMillis() < restoreAt) return;
        if (restore.getValue() && savedSlot != NO_SLOT && !setSlot(p, savedSlot)) return;
        savedSlot = NO_SLOT;
        restorePending = false;
    }

    // -- Fall tracking -------------------------------------------------------

    private void updateFall(LocalPlayer p) {
        if (p.isFallFlying()) {
            resetFall();
            return;
        }

        double currentY = p.getY();
        double yVel = p.getDeltaMovement().y;

        if (p.onGround()) {
            if (isFalling) {
                resetFall();
            }
            if (savedSlot != NO_SLOT) {
                if (restore.getValue()) {
                    restoreAt = System.currentTimeMillis();
                    restorePending = true;
                } else {
                    savedSlot = NO_SLOT;
                    restorePending = false;
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
                wasTargetingLastFrame = false;
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
        if (p.onGround() || !isFalling || p.getDeltaMovement().y >= -0.1D) return false;
        return fallDist >= minFall.getValue();
    }

    // -- Inventory helpers ---------------------------------------------------

    private boolean isMaceStack(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.getItem() == Items.MACE || stack.getItem() instanceof MaceItem;
    }

    private boolean isHoldingMace(LocalPlayer p) {
        return isMaceStack(p.getInventory().getItem(p.getInventory().getSelectedSlot()));
    }

    private int findMace(LocalPlayer p, double fallDistance) {
        boolean useDensity = p.fallDistance >= densityThreshold.getValue() || fallDistance >= densityThreshold.getValue();
        int preferred = useDensity
                ? InventoryUtils.findMaceWithEnchantmentInHotbar(Enchantments.DENSITY, false)
                : InventoryUtils.findMaceWithEnchantmentInHotbar(Enchantments.BREACH, false);
        if (preferred != NO_SLOT) return preferred;

        int fallback = useDensity
                ? InventoryUtils.findMaceWithEnchantmentInHotbar(Enchantments.BREACH, false)
                : InventoryUtils.findMaceWithEnchantmentInHotbar(Enchantments.DENSITY, false);
        if (fallback != NO_SLOT) return fallback;

        return findAnyMace(p);
    }

    private int findAnyMace(LocalPlayer p) {
        return InventoryUtils.findItemWithPredicateInHotbar(this::isMaceStack);
    }

    private int findAxe(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            Item item = p.getInventory().getItem(i).getItem();
            if (item instanceof AxeItem) return i;
        }
        return NO_SLOT;
    }

    private boolean isBlocking(LivingEntity t) {
        if (!(t instanceof Player q)) return false;
        return q.isBlocking();
    }

    // -- Misc ----------------------------------------------------------------

    private Aim.Point point() {
        if (aimPoint.is(Concat.of("Head"))) return Aim.Point.HEAD;
        if (aimPoint.is(Concat.of("Center"))) return Aim.Point.CENTER;
        if (aimPoint.is(Concat.of("Closest"))) return Aim.Point.CLOSEST;
        return Aim.Point.DYNAMIC;
    }
}
