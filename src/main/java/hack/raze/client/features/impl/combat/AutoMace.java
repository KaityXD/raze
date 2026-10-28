package hack.raze.client.features.impl.combat;

import hack.raze.client.api.InventoryClickCompat;
import hack.raze.client.api.InventoryClickType;
import hack.raze.client.api.ScreenCompat;
import hack.raze.client.event.EventSubscribe;
import hack.raze.client.event.impl.EventMotion;
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
import hack.raze.client.mixin.accessors.MultiPlayerGameModeAccessor;
import hack.raze.client.utils.combat.TargetUtils;
import hack.raze.client.utils.inventory.InventoryUtils;
import hack.raze.client.utils.player.PlayerUtils;
import hack.raze.client.utils.rotation.RotationUtils;
import hack.raze.client.utils.strings.Concat;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

public class AutoMace extends Feature {

    private final ModeSetting mode = new ModeSetting(
            Concat.of("Mode"),
            Concat.of("Silent"),
            Concat.of("Silent"),
            Concat.of("Smooth")
    );
    private final ModeSetting priority = new ModeSetting(
            Concat.of("Priority"),
            Concat.of("Distance"),
            Concat.of("Distance"),
            Concat.of("Lowest HP"),
            Concat.of("Highest HP")
    );
    private final FloatSetting range = new FloatSetting(
            Concat.of("Range"),
            3.0F,
            1.0F,
            6.0F,
            0.1F,
            Concat.of(" blocks")
    );
    private final FloatSetting aimRange = new FloatSetting(
            Concat.of("Aim Range"),
            5.5F,
            1.0F,
            10.0F,
            0.1F,
            Concat.of(" blocks")
    );
    private final FloatSetting fov = new FloatSetting(
            Concat.of("FOV"),
            360.0F,
            5.0F,
            360.0F,
            5.0F
    );
    private final ModeSetting aimPoint = new ModeSetting(
            Concat.of("Aim Point"),
            Concat.of("Nearest Vector"),
            Concat.of("Nearest Vector"),
            Concat.of("Closest"),
            Concat.of("Center"),
            Concat.of("Head")
    );
    private final FloatSetting aimSpeed = new FloatSetting(
            Concat.of("Aim Speed"),
            10.0F,
            0.5F,
            20.0F,
            0.5F
    );

    private final BoolSetting checkFalling = new BoolSetting(
            Concat.of("Check Falling"),
            true
    );
    private final FloatSetting minFall = new FloatSetting(
            Concat.of("Min Fall Distance"),
            1.5F,
            0.5F,
            10.0F,
            0.5F,
            o -> checkFalling.getValue()
    );
    private final FloatSetting densityThreshold = new FloatSetting(
            Concat.of("Density Threshold"),
            5.0F,
            1.0F,
            20.0F,
            0.5F
    );
    private final IntSetting reactionDelay = new IntSetting(
            Concat.of("Reaction Delay"),
            0,
            0,
            500,
            " ms"
    );

    private final BoolSetting shieldBreak = new BoolSetting(
            Concat.of("Stun Slam"),
            true
    );
    private final BoolSetting fastStunSlam = new BoolSetting(
            Concat.of("Fast Stun Slam"),
            true,
            o -> shieldBreak.getValue()
    );
    private final IntSetting stunSlamDelay = new IntSetting(
            Concat.of("Stun Slam Delay"),
            50,
            0,
            300,
            " ms",
            o -> shieldBreak.getValue() && !fastStunSlam.getValue()
    );

    private final BoolSetting retrySlam = new BoolSetting(
            Concat.of("Retry Slam"),
            true
    );
    private final IntSetting retryDelay = new IntSetting(
            Concat.of("Retry Delay"),
            100,
            0,
            1000,
            " ms",
            o -> retrySlam.getValue()
    );

    private final BoolSetting webCheck = new BoolSetting(
            Concat.of("Web Check"),
            true
    );
    private final BoolSetting targetPlayers = new BoolSetting(
            Concat.of("Target Players"),
            true
    );
    private final BoolSetting targetMobs = new BoolSetting(
            Concat.of("Target Mobs"),
            false
    );

    private final BoolSetting autoSwitch = new BoolSetting(
            Concat.of("Auto Switch"),
            true
    );
    private final BoolSetting restore = new BoolSetting(
            Concat.of("Restore"),
            true
    ).describedBy(
            Concat.of("Swap back to your previous slot after the hit instead of staying on the mace")
    );
    private final IntSetting restoreDelay = new IntSetting(
            Concat.of("Restore Delay"),
            0,
            0,
            20,
            " ticks",
            o -> restore.getValue()
    ).describedBy(
            Concat.of("Ticks to wait before swapping back")
    );

    private final BoolSetting elytraDive = new BoolSetting(
            Concat.of("Elytra Dive"),
            true
    ).describedBy(
            Concat.of("Allows aiming and mace attacks while gliding down with an Elytra")
    );
    private final BoolSetting autoUnequipElytra = new BoolSetting(
            Concat.of("Auto Unequip Elytra"),
            false,
            o -> elytraDive.getValue()
    ).describedBy(
            Concat.of("Equips a hotbar chestplate right before impact so the hit registers as a full smash")
    );
    private final BoolSetting reEquipElytra = new BoolSetting(
            Concat.of("Re-equip Elytra"),
            false,
            o -> elytraDive.getValue() && autoUnequipElytra.getValue()
    ).describedBy(
            Concat.of("Puts the Elytra back on when bouncing upwards after the hit")
    );

    private static final int NO_SLOT = -1;
    private static final int CHEST_ARMOR_MENU_SLOT = 6;

    /**
     * Aim deviation we tolerate before holding an attack back.
     * <p>
     * Ported from Open-respect's AutoMace: with an instant (silent) rotation the deviation is
     * always ~0 in practice, so this only ever gates the smoothed mode.
     */
    private static final float AIM_TOLERANCE = 55.0F;
    /** Silent rotations are set with an instant speed, so they are treated as always aimed. */
    private static final boolean SILENT_IS_INSTANT = true;

    private LivingEntity target;
    private long lastMaceHitTime;
    private int savedSlot = NO_SLOT;

    private long restoreAt;
    private boolean restorePending;
    private int lastSwapTick = Integer.MIN_VALUE;

    private double fallStartY = -1.0D;
    private boolean isFalling = false;
    private boolean maceHit = false;

    private int slamState = 0;
    private long slamTimer = 0L;

    private long targetTime = 0L;
    private long currentReactionDelay = 0L;
    private boolean wasTargetingLastFrame = false;

    private boolean shouldReequipElytra = false;

    // Cache of current target's calculated aim point and rotations for deviation checking
    private Vec3 currentAimPoint = null;
    private float targetRawYaw = 0.0F;
    private float targetRawPitch = 0.0F;

    public AutoMace() {
        super(new FeatureInfo(
                Concat.of("AutoMace"),
                Concat.of("Automatically targets and attacks with a Mace when falling or diving"),
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
            RotationUtils.tryTakeControl(this, EventSubscribe.Priority.HIGHEST);
            RotationUtils.stopTracking();
        }
        if (SwapStateManager.isActive(this)) {
            SwapStateManager.cancel(this);
        }
        if (shouldReequipElytra && mc.player != null) {
            tryReequipElytra(mc.player);
        }
        if (savedSlot != NO_SLOT && mc.player != null && restore.getValue()) {
            setSlot(mc.player, savedSlot);
        }
        reset();
    }

    private void reset() {
        target = null;
        currentAimPoint = null;
        lastMaceHitTime = 0L;
        savedSlot = NO_SLOT;
        restoreAt = 0L;
        restorePending = false;
        lastSwapTick = Integer.MIN_VALUE;
        targetTime = 0L;
        currentReactionDelay = 0L;
        wasTargetingLastFrame = false;
        shouldReequipElytra = false;
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
    private void onUpdate(EventSystemUpdate event) {
        if (!isEnabled() || isNull() || ScreenCompat.hasScreen(mc)) {
            target = null;
            cleanupAim();
            return;
        }

        LocalPlayer p = mc.player;
        updateFall(p);

        if (findAnyMace(p) == NO_SLOT) {
            target = null;
            cleanupAim();
            return;
        }

        double fallDist = fallStartY == -1.0D ? 0.0D : Math.max(0.0D, fallStartY - p.getY());

        if (checkFalling.getValue()) {
            if (p.onGround()) {
                target = null;
                cleanupAim();
                return;
            }
            if (p.isFallFlying()) {
                if (!elytraDive.getValue()) {
                    // Elytra flight without dive: still keep target calculated for when elytra un-equips
                    target = findTarget(p);
                    cleanupAim();
                    return;
                }
                if (p.getDeltaMovement().y > 0.05D || fallDist < minFall.getValue()) {
                    target = null;
                    cleanupAim();
                    return;
                }
            } else {
                if (!isFalling || p.getDeltaMovement().y >= -0.05D || fallDist < minFall.getValue()) {
                    target = null;
                    cleanupAim();
                    return;
                }
            }
        }

        if (target != null && !isValidTarget(p, target, aimRange.getValue())) {
            target = null;
        }
        if (target == null) {
            target = findTarget(p);
        }

        if (target == null) {
            cleanupAim();
            return;
        }

        // Open-respect rotation calculation
        currentAimPoint = calculateTargetPoint(p, target);
        float[] rawRot = calculateAdvancedRotations(p, currentAimPoint);

        // Snap to the mouse GCD grid so the rotation we verify against is the one the
        // server can actually receive. This used to be computed and then thrown away.
        float[] gcdRot = applyGCDMouseFix(p, rawRot[0], rawRot[1]);
        targetRawYaw = gcdRot[0];
        targetRawPitch = gcdRot[1];

        boolean isSilent = mode.is(Concat.of("Silent"));
        RotationUtils.aim(this)
                .silent(isSilent)
                .priority(EventSubscribe.Priority.HIGHEST)
                .aimType(isSilent ? RotationUtils.AimType.BLATANT : RotationUtils.AimType.REGULAR)
                .speed(aimSpeed.getValue() * 15.0F)
                .to(currentAimPoint);
    }

    @EventSubscribe
    private void onMotionPre(EventMove.Pre event) {
        if (!isEnabled() || isNull() || ScreenCompat.hasScreen(mc)) {
            return;
        }

        LocalPlayer p = mc.player;

        if (RotationUtils.isControlledBy(this) && RotationHandler.isHasSilentRotation()) {
            if (p.isFallFlying() && !elytraDive.getValue()) {
                return;
            }
            event.setYaw(RotationHandler.getServerYaw());
            event.setPitch(RotationHandler.getServerPitch());
        }
    }

    /**
     * Runs at {@code aiStep} HEAD, the same point Open-respect's AutoMace runs its whole
     * decision pass from {@code MotionEvent.PRE}: pre-movement, so the position we test
     * reach from is the one the server currently has.
     */
    @EventSubscribe
    private void onMotion(EventMotion.Pre event) {
        if (!isEnabled() || isNull() || ScreenCompat.hasScreen(mc)) {
            target = null;
            return;
        }

        LocalPlayer p = mc.player;
        updateFall(p);
        processPendingRestore(p);
        tickAttack(p);
    }

    private void tickAttack(LocalPlayer p) {
        if (p.isFallFlying() && !elytraDive.getValue()) {
            return;
        }
        if (target == null || !isValidTarget(p, target, aimRange.getValue())) {
            return;
        }

        double fallDist = fallStartY == -1.0D ? 0.0D : Math.max(0.0D, fallStartY - p.getY());
        if (!canAttack(p, fallDist)) {
            return;
        }

        // Aim deviation verification (from Open-respect)
        float silentYaw = RotationHandler.isHasSilentRotation() ? RotationHandler.getServerYaw() : p.getYRot();
        float silentPitch = RotationHandler.isHasSilentRotation() ? RotationHandler.getServerPitch() : p.getXRot();
        float yawDeviation = Math.abs(Mth.wrapDegrees(silentYaw - targetRawYaw));
        float pitchDeviation = Math.abs(silentPitch - targetRawPitch);

        boolean isSilent = mode.is(Concat.of("Silent"));
        // Open-respect's form: a silent rotation is instant (silentSpeed 10.0f), so it is
        // treated as always aimed; the deviation only ever gates the smoothed mode.
        boolean aimed = (yawDeviation <= AIM_TOLERANCE && pitchDeviation <= AIM_TOLERANCE)
                || (isSilent && SILENT_IS_INSTANT);
        if (!aimed) {
            return;
        }

        // Reach check, Open-respect style: player-to-target centre distance against the
        // slider. This is what keeps us inside the anticheat's threshold - an eye-to-hitbox
        // test lets a 3.0 setting land ~3.5 blocks away by the server's measure.
        if (!inReach(p, target)) {
            wasTargetingLastFrame = false;
            return;
        }
        double distToBox = distanceToTarget(p, target);
        if (!p.hasLineOfSight(target)) {
            return;
        }

        // Reaction delay handling (from Open-respect)
        if (!wasTargetingLastFrame) {
            targetTime = System.currentTimeMillis();
            currentReactionDelay = reactionDelay.getValue();
            wasTargetingLastFrame = true;
        }

        // Mid-air retry slam combo logic (from Open-respect)
        if (maceHit) {
            if (retrySlam.getValue()
                    && isFalling && p.getDeltaMovement().y < -0.05D
                    && fallDist >= minFall.getValue()
                    && p.getAttackStrengthScale(0.0F) >= 0.9F
                    && System.currentTimeMillis() - lastMaceHitTime >= retryDelay.getValue()) {
                maceHit = false;
                slamState = 0;
            } else {
                return;
            }
        }

        if (System.currentTimeMillis() - targetTime < currentReactionDelay) {
            return;
        }

        // Auto Unequip Elytra right before landing impact
        if (autoUnequipElytra.getValue() && p.isFallFlying()) {
            double unequipReach = (double) range.getValue() + 1.2D;
            if (distToBox <= unequipReach && fallDist >= minFall.getValue()) {
                int chestSlot = InventoryUtils.findChestplateSlot();
                if (chestSlot != NO_SLOT) {
                    InventoryClickCompat.handleClick(
                            p.inventoryMenu.containerId,
                            CHEST_ARMOR_MENU_SLOT,
                            chestSlot,
                            InventoryClickType.SWAP,
                            p
                    );
                    shouldReequipElytra = reEquipElytra.getValue();
                }
            }
        }

        handleMaceAttack(p, target, fallDist);
    }

    private void handleMaceAttack(LocalPlayer p, LivingEntity t, double fallDist) {
        if (mc.gameMode == null) return;

        // Shield Break / Stun Slam (from Open-respect)
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

        if (!inReach(p, t)) return;

        int maceSlot = p.getInventory().getSelectedSlot();
        attack(p, t);
        registerMaceHit(p, maceSlot);
    }

    private boolean handleShieldBreak(LocalPlayer p, LivingEntity t, double fallDist) {
        if (mc.gameMode == null) return false;
        // Both the axe hit and the follow-up mace hit run off one reach check at the top of
        // tickAttack; re-assert it here so the extra swing never outranges the setting.
        if (!inReach(p, t)) return false;

        boolean targetBlocking = isShieldBlocking(t);

        if (targetBlocking || slamState > 0) {
            int axeSlot = findAxe(p);
            if (axeSlot != NO_SLOT && autoSwitch.getValue()) {
                if (slamState == 0) {
                    if (savedSlot == NO_SLOT) savedSlot = p.getInventory().getSelectedSlot();
                    if (!swapTo(p, axeSlot)) return true;
                    attack(p, t);

                    slamState = 1;
                    slamTimer = System.currentTimeMillis();

                    if (fastStunSlam.getValue()) {
                        int maceSlot = findMace(p, fallDist);
                        if (maceSlot != NO_SLOT && inReach(p, t)) {
                            lastSwapTick = Integer.MIN_VALUE; // allow fast combo swap in same tick
                            if (setSlot(p, maceSlot)) {
                                attack(p, t);
                                slamState = 2;
                                registerMaceHit(p, maceSlot);
                            }
                        }
                    }
                    return true;
                } else if (slamState == 1) {
                    if (System.currentTimeMillis() - slamTimer >= stunSlamDelay.getValue()) {
                        int maceSlot = findMace(p, fallDist);
                        if (maceSlot != NO_SLOT && inReach(p, t)) {
                            if (!swapTo(p, maceSlot)) return true;
                            attack(p, t);
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

    private void cleanupAim() {
        wasTargetingLastFrame = false;
        if (!RotationUtils.isControlledBy(this)) return;
        RotationUtils.cleanup(
                this,
                true,
                aimSpeed.getValue() * 15.0F,
                aimSpeed.getValue() * 15.0F,
                RotationUtils.AimType.REGULAR,
                true
        );
    }

    private Vec3 calculateTargetPoint(LocalPlayer p, LivingEntity entity) {
        // Aim at the box the server will see, not at our stale copy of it. Otherwise the ray
        // lands behind the target and the anticheat reports Reach + Hitboxes together.
        AABB boundingBox = getServerBox(entity);
        Vec3 serverPos = entity.getPositionCodec().getBase();
        // Fall back to client position if codec is uninitialised
        double tX, tY, tZ;
        if (serverPos.equals(Vec3.ZERO) || serverPos.distanceToSqr(entity.position()) > 16.0D) {
            tX = entity.getX();
            tY = entity.getY();
            tZ = entity.getZ();
        } else {
            tX = serverPos.x;
            tY = serverPos.y;
            tZ = serverPos.z;
        }
        Vec3 eyes = p.getEyePosition();
        Vec3 targetPoint;

        if (aimPoint.is(Concat.of("Center"))) {
            targetPoint = new Vec3(tX, tY + (entity.getBbHeight() / 2.0D), tZ);
        } else if (aimPoint.is(Concat.of("Head"))) {
            targetPoint = new Vec3(tX, tY + entity.getEyeHeight(), tZ);
        } else if (aimPoint.is(Concat.of("Nearest Vector"))) {
            float currentYaw = RotationHandler.isHasSilentRotation() ? RotationHandler.getServerYaw() : p.getYRot();
            float currentPitch = RotationHandler.isHasSilentRotation() ? RotationHandler.getServerPitch() : p.getXRot();

            float f = Mth.cos(-currentYaw * 0.017453292F - (float) Math.PI);
            float f1 = Mth.sin(-currentYaw * 0.017453292F - (float) Math.PI);
            float f2 = -Mth.cos(-currentPitch * 0.017453292F);
            float f3 = Mth.sin(-currentPitch * 0.017453292F);

            Vec3 lookVec = new Vec3(f1 * f2, f3, f * f2);

            double maxT = aimRange.getValue();
            double step = maxT / 15.0D;
            double bestDistSq = Double.MAX_VALUE;
            Vec3 bestPoint = null;

            for (int i = 0; i <= 15; i++) {
                double t = i * step;
                Vec3 rayPoint = eyes.add(lookVec.scale(t));

                double clampedX = Math.max(boundingBox.minX, Math.min(rayPoint.x, boundingBox.maxX));
                double clampedY = Math.max(boundingBox.minY, Math.min(rayPoint.y, boundingBox.maxY));
                double clampedZ = Math.max(boundingBox.minZ, Math.min(rayPoint.z, boundingBox.maxZ));

                Vec3 boxPoint = new Vec3(clampedX, clampedY, clampedZ);
                double dSq = rayPoint.distanceToSqr(boxPoint);
                if (dSq < bestDistSq) {
                    bestDistSq = dSq;
                    bestPoint = boxPoint;
                }
            }
            targetPoint = bestPoint;
        } else {
            // Closest
            double minX = Math.max(boundingBox.minX, Math.min(eyes.x, boundingBox.maxX));
            double minY = Math.max(boundingBox.minY, Math.min(eyes.y, boundingBox.maxY));
            double minZ = Math.max(boundingBox.minZ, Math.min(eyes.z, boundingBox.maxZ));
            targetPoint = new Vec3(minX, minY, minZ);
        }

        if (targetPoint == null || !p.hasLineOfSight(entity)) {
            targetPoint = new Vec3(tX, tY + (entity.getBbHeight() * 0.75D), tZ);
        }

        // When approaching or falling, bias aim towards the closest reachable point on the hitbox
        // so the crosshair isn't aimed at a distant unreachable part (e.g. feet while falling).
        if (eyes.distanceTo(targetPoint) > range.getValue()) {
            double cX = Mth.clamp(eyes.x, boundingBox.minX, boundingBox.maxX);
            double cY = Mth.clamp(eyes.y, boundingBox.minY, boundingBox.maxY);
            double cZ = Mth.clamp(eyes.z, boundingBox.minZ, boundingBox.maxZ);
            targetPoint = new Vec3(cX, cY, cZ);
        }

        return targetPoint;
    }

    private float[] calculateAdvancedRotations(LocalPlayer player, Vec3 targetPoint) {
        Vec3 eyes = player.getEyePosition();
        double diffX = targetPoint.x - eyes.x;
        double diffY = targetPoint.y - eyes.y;
        double diffZ = targetPoint.z - eyes.z;
        double hypotenuse = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float yaw = (float) (Math.atan2(diffZ, diffX) * 180.0D / Math.PI) - 90.0F;
        float pitch = (float) -(Math.atan2(diffY, hypotenuse) * 180.0D / Math.PI);

        return new float[]{yaw, pitch};
    }

    /**
     * Snaps a rotation onto the mouse GCD grid (ported 1:1 from Open-respect's
     * {@code AutoMaceTargeting.applyGCDMouseFix}), using the player's real rotation as the
     * base so the result is a rotation the server could actually receive from a mouse.
     */
    private float[] applyGCDMouseFix(LocalPlayer p, float targetYaw, float targetPitch) {
        float sens = mc.options.sensitivity().get().floatValue() * 0.6F + 0.2F;
        float gcd = sens * sens * sens * 1.2F;
        float currentYaw = p.getYRot();
        float currentPitch = p.getXRot();
        float finalYaw = currentYaw + Math.round((targetYaw - currentYaw) / gcd) * gcd;
        float finalPitch = currentPitch + Math.round((targetPitch - currentPitch) / gcd) * gcd;
        finalPitch = Math.max(-90.0F, Math.min(90.0F, finalPitch));
        return new float[]{finalYaw, finalPitch};
    }

    /**
     * Measures eye-to-hitbox distance the way the server does: from the player's eye
     * position to the nearest point on the target's bounding box.
     * <p>
     * Uses the target's <b>server-tracked position</b> ({@code getPositionCodec().getBase()})
     * instead of the client's interpolated position. The client entity lags 2-3 ticks behind
     * due to interpolation buffering, so its {@code getBoundingBox()} can be significantly
     * offset from where the server actually has the entity — easily 0.2-0.4 blocks when
     * either party is moving fast (e.g. a mace dive).
     */
    private double distanceToTarget(LocalPlayer p, LivingEntity t) {
        if (p == null || t == null) return Double.MAX_VALUE;
        Vec3 eye = p.getEyePosition();
        AABB box = getServerBox(t);
        double x = Mth.clamp(eye.x, box.minX, box.maxX);
        double y = Mth.clamp(eye.y, box.minY, box.maxY);
        double z = Mth.clamp(eye.z, box.minZ, box.maxZ);
        return eye.distanceTo(new Vec3(x, y, z));
    }

    /**
     * Reconstructs the target's bounding box at its last known server position rather than
     * the client's interpolated position. The client interpolates entity movement over 2-3
     * ticks, which makes the rendered hitbox trail behind the real server-side hitbox by up
     * to ~0.3 blocks when either player is moving quickly.
     */
    private AABB getServerBox(LivingEntity t) {
        Vec3 serverPos = t.getPositionCodec().getBase();
        Vec3 clientPos = t.position();
        if (serverPos.equals(Vec3.ZERO) || serverPos.distanceToSqr(clientPos) > 16.0D) {
            // Codec uninitialised or wildly stale — fall back to the client box
            return t.getBoundingBox();
        }
        // Shift the client AABB by the delta between server and client positions
        Vec3 offset = serverPos.subtract(clientPos);
        return t.getBoundingBox().move(offset);
    }

    /**
     * Ray-cast reach check matching server-side validation (such as GrimAC / vanilla).
     * <p>
     * Instead of Euclidean distance to the nearest bounding box point (which ignores attack angle
     * and lets attacks pass when perpendicular distance is ~2.6 but ray distance is 3.2-3.4),
     * this tests the actual ray from the player's eye in the look direction and calculates the
     * exact distance to the point of intersection on the target's bounding box.
     * <p>
     * Capping at min(maxRange, 2.95) and compensating for downward velocity completely eliminates
     * 3.02, 3.2, and 3.4 block reach flags.
     */
    private boolean isRayReachable(LocalPlayer p, LivingEntity t, double maxRange) {
        if (p == null || t == null || !t.isAlive() || t.isRemoved()) return false;
        if (!TargetUtils.isTargetAllowed(t)) return false;

        Vec3 eye = p.getEyePosition();
        AABB clientBox = t.getBoundingBox();

        // Max survival attack reach in vanilla is 3.0 blocks. Use 2.95 safety threshold to prevent
        // 3.001 / 3.02 floating-point edge flags.
        double maxAllowed = Math.min(maxRange, 2.95D);

        // Account for downward velocity during a mace dive
        double yVel = p.getDeltaMovement().y;
        if (yVel < -0.1D) {
            maxAllowed -= Math.min(0.15D, Math.abs(yVel) * 0.08D);
        }

        // Quick AABB rejection: if even the closest point is beyond maxAllowed, cannot reach
        if (clientBox.distanceToSqr(eye) > maxAllowed * maxAllowed) {
            return false;
        }

        if (clientBox.contains(eye)) {
            return true;
        }

        // Active look direction the server will use to validate this hit
        float yaw = RotationHandler.isHasSilentRotation() ? RotationHandler.getServerYaw() : p.getYRot();
        float pitch = RotationHandler.isHasSilentRotation() ? RotationHandler.getServerPitch() : p.getXRot();

        Vec3 look = RotationHandler.getRotationVector(pitch, yaw);
        Vec3 end = eye.add(look.scale(maxAllowed));

        // 1. Ray must intersect the target's client bounding box within maxAllowed
        Optional<Vec3> clientHit = clientBox.clip(eye, end);
        if (clientHit.isEmpty()) {
            return false;
        }

        double hitDist = eye.distanceTo(clientHit.get());
        if (hitDist > maxAllowed) {
            return false;
        }

        // 2. Also verify against the server-tracked position box if available
        AABB serverBox = getServerBox(t);
        if (serverBox != clientBox) {
            Optional<Vec3> serverHit = serverBox.clip(eye, end);
            if (serverHit.isEmpty() || eye.distanceTo(serverHit.get()) > maxAllowed) {
                return false;
            }
        }

        // 3. Line of sight / block occlusion
        BlockHitResult blockHit = mc.level.clip(new ClipContext(
                eye,
                clientHit.get(),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                p
        ));
        if (blockHit.getType() != HitResult.Type.MISS
                && blockHit.getLocation().distanceToSqr(eye) < clientHit.get().distanceToSqr(eye) - 1.0E-4) {
            return false;
        }

        return true;
    }

    private boolean inReach(LocalPlayer p, LivingEntity t) {
        return isRayReachable(p, t, range.getValue());
    }

    private boolean isValidTarget(LocalPlayer p, LivingEntity t, double maxRange) {
        if (t == null || t == p || !t.isAlive() || t.isRemoved() || t.isSpectator()) return false;
        if (!TargetUtils.isTargetAllowed(t)) return false;

        if (t instanceof Player q) {
            if (!targetPlayers.getValue()) return false;
            if (q.isCreative() || q.isSpectator()) return false;
            if (PlayerUtils.isFriend(q.getGameProfile().name())) return false;
        } else {
            if (!targetMobs.getValue()) return false;
        }

        if (distanceToTarget(p, t) > maxRange) return false;
        if (webCheck.getValue() && inWeb(t)) return false;
        if (isObstructed(p, t, maxRange)) return false;
        return inFov(p, t);
    }

    private LivingEntity findTarget(LocalPlayer p) {
        LivingEntity manual = PlayerUtils.getManualSelectTarget();
        if (manual != null && isValidTarget(p, manual, aimRange.getValue()) && p.hasLineOfSight(manual)) {
            return manual;
        }

        LivingEntity best = null;
        double bestMetric = Double.MAX_VALUE;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living == p) continue;
            if (!isValidTarget(p, living, aimRange.getValue())) continue;
            if (!p.hasLineOfSight(living)) continue;

            double metricValue;
            if (priority.is(Concat.of("Lowest HP"))) {
                metricValue = living.getHealth();
            } else if (priority.is(Concat.of("Highest HP"))) {
                metricValue = -living.getHealth();
            } else {
                metricValue = p.distanceToSqr(living);
            }

            if (metricValue < bestMetric) {
                bestMetric = metricValue;
                best = living;
            }
        }
        return best;
    }

    private boolean inFov(LocalPlayer p, LivingEntity t) {
        float limit = fov.getValue();
        if (limit >= 360.0F) return true;
        if (p.isFallFlying() && !elytraDive.getValue()) return true;

        Vec3 pt = calculateTargetPoint(p, t);
        float[] r = calculateAdvancedRotations(p, pt);
        float baseYaw = (mode.is(Concat.of("Silent")) && RotationUtils.isControlledBy(this))
                ? (RotationHandler.isHasSilentRotation() ? RotationHandler.getServerYaw() : p.getYRot())
                : p.getYRot();
        float basePitch = (mode.is(Concat.of("Silent")) && RotationUtils.isControlledBy(this))
                ? (RotationHandler.isHasSilentRotation() ? RotationHandler.getServerPitch() : p.getXRot())
                : p.getXRot();

        float yd = Math.abs(Mth.wrapDegrees(r[0] - baseYaw));
        float pd = Math.abs(r[1] - basePitch);
        return Math.hypot(yd, pd) <= (limit / 2.0F);
    }

    private boolean inWeb(Entity entity) {
        if (entity == null || mc.level == null) return false;
        AABB box = entity.getBoundingBox().inflate(0.05D);
        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.floor(box.maxX);
        int minY = (int) Math.floor(box.minY);
        int maxY = (int) Math.floor(box.maxY);
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.floor(box.maxZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (mc.level.getBlockState(pos.set(x, y, z)).is(Blocks.COBWEB)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isObstructed(LocalPlayer self, Entity target, double reach) {
        if (self == null || target == null || mc.level == null) return false;
        AABB targetBox = target.getBoundingBox();
        AABB search = targetBox.inflate(2.0D);
        List<Entity> nearby = mc.level.getEntities(self, search,
                e -> e != target && e != self && e.isPickable());

        Vec3 eyes = self.getEyePosition();
        Vec3 center = targetBox.getCenter();
        double centerDistSq = eyes.distanceToSqr(center);

        for (Entity obstacle : nearby) {
            if (obstacle instanceof EndCrystal) {
                if (targetBox.intersects(obstacle.getBoundingBox())) return true;
                Optional<Vec3> hit = obstacle.getBoundingBox()
                        .inflate(obstacle.getPickRadius())
                        .clip(eyes, center);
                if (hit.isPresent() && eyes.distanceToSqr(hit.get()) < centerDistSq - 1.0E-7) {
                    return true;
                }
            }
        }
        return false;
    }

    private void attack(LocalPlayer p, LivingEntity t) {
        if (mc.gameMode == null) return;
        if (p.isUsingItem()) {
            mc.gameMode.releaseUsingItem(p);
        }
        mc.gameMode.attack(p, t);
        p.swing(InteractionHand.MAIN_HAND);
        TargetUtils.onAttack(t);
    }

    private void registerMaceHit(LocalPlayer p, int maceSlot) {
        maceHit = true;
        long now = System.currentTimeMillis();
        lastMaceHitTime = now;

        if (restore.getValue() && savedSlot != NO_SLOT && maceSlot != savedSlot) {
            restoreAt = now + (restoreDelay.getValue() * 50L);
            restorePending = true;
        } else {
            savedSlot = NO_SLOT;
            restorePending = false;
        }
    }

    private boolean isShieldBlocking(LivingEntity t) {
        if (!(t instanceof Player q)) return false;
        if (q.isBlocking()) return q.getTicksUsingItem() >= 5;
        if (!q.isUsingItem()) return false;
        ItemStack useItem = q.getUseItem();
        return !useItem.isEmpty() && useItem.getItem() instanceof ShieldItem && q.getTicksUsingItem() >= 5;
    }

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
        if (p.tickCount == lastSwapTick) return;
        if (restore.getValue() && savedSlot != NO_SLOT && !setSlot(p, savedSlot)) return;
        savedSlot = NO_SLOT;
        restorePending = false;
    }

    private void updateFall(LocalPlayer p) {
        boolean onGround = p.onGround();
        double currentY = p.getY();
        double yVel = p.getDeltaMovement().y;

        if (onGround) {
            if (isFalling) {
                resetFall();
            }
            shouldReequipElytra = false;
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
            if (shouldReequipElytra && !p.isFallFlying()) {
                tryReequipElytra(p);
            }
        } else if (p.isFallFlying()) {
            double fallDist = fallStartY == -1.0D ? 0.0D : Math.max(0.0D, fallStartY - currentY);
            if (!isFalling && (fallDist >= minFall.getValue() || yVel < 0.0D)) {
                isFalling = true;
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

    private void tryReequipElytra(LocalPlayer p) {
        int elytraSlot = findElytraInHotbar(p);
        if (elytraSlot != NO_SLOT) {
            InventoryClickCompat.handleClick(
                    p.inventoryMenu.containerId,
                    CHEST_ARMOR_MENU_SLOT,
                    elytraSlot,
                    InventoryClickType.SWAP,
                    p
            );
        }
        shouldReequipElytra = false;
    }

    private int findElytraInHotbar(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = p.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.ELYTRA) {
                return i;
            }
        }
        return NO_SLOT;
    }

    private boolean canAttack(LocalPlayer p, double fallDist) {
        if (p.isFallFlying() && !elytraDive.getValue()) return false;
        if (!checkFalling.getValue()) return true;
        if (p.onGround()) return false;
        if (p.isFallFlying() && elytraDive.getValue()) {
            if (p.getDeltaMovement().y > 0.05D) return false;
            return fallDist >= minFall.getValue();
        }
        if (!isFalling || p.getDeltaMovement().y >= -0.05D) return false;
        return fallDist >= minFall.getValue();
    }

    private boolean isMaceStack(ItemStack stack) {
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
}
