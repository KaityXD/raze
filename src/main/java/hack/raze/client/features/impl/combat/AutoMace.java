package hack.raze.client.features.impl.combat;

import hack.raze.client.api.ScreenCompat;
import hack.raze.client.event.EventSubscribe;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public class AutoMace extends Feature {

  private final ModeSetting mode = new ModeSetting(Concat.of("Mode"), Concat.of("Silent"), Concat.of("Silent"),
      Concat.of("Smooth"));
  private final FloatSetting range = new FloatSetting(Concat.of("Range"), 3.0F, 1.0F, 6.0F, 0.1F, Concat.of(" blocks"));
  private final FloatSetting aimRange = new FloatSetting(Concat.of("Aim Range"), 5.0F, 1.0F, 8.0F, 0.1F,
      Concat.of(" blocks"));
  private final FloatSetting fov = new FloatSetting(Concat.of("FOV"), 360.0F, 5.0F, 360.0F, 5.0F);
  private final ModeSetting priority = new ModeSetting(Concat.of("Priority"), Concat.of("Distance"),
      Concat.of("Distance"), Concat.of("Lowest HP"));
  private final ModeSetting aimPoint = new ModeSetting(Concat.of("Aim Point"), Concat.of("Respect Closed"),
      Concat.of("Respect Closed"), Concat.of("Respect Closest"), Concat.of("Dynamic"), Concat.of("Head"),
      Concat.of("Center"), Concat.of("Closest"));
  private final FloatSetting aimSpeed = new FloatSetting(Concat.of("Aim Speed"), 10.0F, 0.5F, 20.0F, 0.5F);

  private final BoolSetting checkFalling = new BoolSetting(Concat.of("Check Falling"), true);
  private final FloatSetting minFall = new FloatSetting(Concat.of("Min Fall Distance"), 1.5F, 0.5F, 10.0F, 0.5F,
      o -> checkFalling.getValue());
  private final FloatSetting densityThreshold = new FloatSetting(Concat.of("Density Threshold"), 5.0F, 1.0F, 20.0F,
      0.5F);
  private final IntSetting reactionDelay = new IntSetting(Concat.of("Reaction Delay"), 0, 0, 500, " ms");

  private final BoolSetting shieldBreak = new BoolSetting(Concat.of("Stun Slam"), true);
  private final IntSetting stunSlamDelay = new IntSetting(Concat.of("Stun Slam Delay"), 50, 0, 300, " ms",
      o -> shieldBreak.getValue());

  private final BoolSetting autoSwitch = new BoolSetting(Concat.of("Auto Switch"), true);

  private final BoolSetting restore = new BoolSetting(
      Concat.of("Restore"), true)
      .describedBy(Concat.of("Swap back to your previous slot after the hit instead of staying on the mace"));
  private final IntSetting restoreDelay = new IntSetting(
      Concat.of("Restore Delay"), 0, 0, 20, " ticks", o -> restore.getValue())
      .describedBy(Concat.of("Ticks to wait before swapping back"));

  private static final long RETRY_DELAY_MS = 50L;
  private static final float AIM_PARTIAL_TICK = 1.0F;
  private static final int NO_SLOT = -1;

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

  public AutoMace() {
    super(new FeatureInfo(
        Concat.of("AutoMace"),
        Concat.of("Auto Mace idk what to say just stop asking me nigga"),
        Category.COMBAT));
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
    if (SwapStateManager.isActive(this))
      SwapStateManager.cancel(this);
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

  @EventSubscribe
  private void onUpdate(EventSystemUpdate event) {
    if (!isEnabled() || isNull() || ScreenCompat.hasScreen(mc)) {
      target = null;
      cleanupAim();
      return;
    }

    LocalPlayer p = mc.player;
    updateFall(p);

    if (p.isFallFlying()) {
      target = null;
      cleanupAim();
      return;
    }

    if (findAnyMace(p) == NO_SLOT) {
      target = null;
      cleanupAim();
      return;
    }

    double fallDist = fallStartY == -1.0D ? 0.0D : Math.max(0.0D, fallStartY - p.getY());

    if (checkFalling.getValue()) {
      if (p.onGround() || !isFalling || p.getDeltaMovement().y >= -0.05D) {
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

    if (target != null && !valid(p, target))
      target = null;
    if (target == null)
      target = findTarget(p);

    if (target == null) {
      cleanupAim();
      return;
    }

    if (!applyAim(p, target)) {
      cleanupAim();
      return;
    }
  }

  @EventSubscribe
  private void onMotionPre(EventMove.Pre event) {
    if (!isEnabled() || isNull() || ScreenCompat.hasScreen(mc)) {
      target = null;
      return;
    }

    LocalPlayer p = mc.player;
    updateFall(p);
    try {
      syncRotation(event, p);
      tickAttack(event, p);
    } finally {
      processPendingRestore(p);
    }
  }

  private void tickAttack(EventMove.Pre event, LocalPlayer p) {
    if (p.isFallFlying())
      return;
    if (target == null || !valid(p, target))
      return;

    double fallDist = fallStartY == -1.0D ? 0.0D : Math.max(0.0D, fallStartY - p.getY());
    if (!canAttack(p, fallDist))
      return;

    if (!wasTargetingLastFrame) {
      targetTime = System.currentTimeMillis();
      currentReactionDelay = reactionDelay.getValue();
      wasTargetingLastFrame = true;
    }

    if (maceHit
        && isFalling && p.getDeltaMovement().y < -0.05D
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

    if (System.currentTimeMillis() - targetTime < currentReactionDelay) {
      return;
    }

    handleMaceAttack(p, target, fallDist);
  }

  private void handleMaceAttack(LocalPlayer p, LivingEntity t, double fallDist) {
    if (mc.gameMode == null)
      return;

    if (shieldBreak.getValue() && handleShieldBreak(p, t, fallDist)) {
      return;
    }

    if (maceHit)
      return;

    if (!isHoldingMace(p)) {
      if (!autoSwitch.getValue())
        return;
      int maceSlot = findMace(p, fallDist);
      if (maceSlot == NO_SLOT)
        return;
      if (!swapTo(p, maceSlot))
        return;
    }

    if (!isHoldingMace(p))
      return;

    if (!isRayReachable(p, t, range.getValue()))
      return;

    int maceSlot = p.getInventory().getSelectedSlot();
    attack(p, t);
    registerMaceHit(p, maceSlot);
  }

  private boolean handleShieldBreak(LocalPlayer p, LivingEntity t, double fallDist) {
    if (mc.gameMode == null)
      return false;

    boolean targetBlocking = isShieldBlocking(t);

    if (shieldBreak.getValue() && (targetBlocking || slamState > 0)) {
      int axeSlot = findAxe(p);
      if (axeSlot != NO_SLOT && autoSwitch.getValue()) {
        if (slamState == 0) {
          if (!isRayReachable(p, t, range.getValue()))
            return true;
          if (!swapTo(p, axeSlot))
            return true;
          attack(p, t);

          slamState = 1;
          slamTimer = System.currentTimeMillis();
          return true;
        } else if (slamState == 1) {
          if (System.currentTimeMillis() - slamTimer >= stunSlamDelay.getValue()) {
            int maceSlot = findMace(p, fallDist);
            if (maceSlot != NO_SLOT) {
              if (!isRayReachable(p, t, range.getValue()))
                return true;
              if (!swapTo(p, maceSlot))
                return true;
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
    if (!RotationUtils.isControlledBy(this))
      return;
    RotationUtils.cleanup(this, true, aimSpeed.getValue() * 15.0F, aimSpeed.getValue() * 15.0F,
        RotationUtils.AimType.REGULAR, true);
  }

  private void syncRotation(EventMove.Pre event, LocalPlayer p) {
    if (!RotationUtils.isControlledBy(this))
      return;
    if (p.isFallFlying())
      return;

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

  private boolean isRayReachable(LocalPlayer p, LivingEntity t, double maxRange, float pitch, float yaw) {
    if (p == null || t == null || !t.isAlive() || t.isRemoved())
      return false;
    if (!TargetUtils.isTargetAllowed(t))
      return false;

    Vec3 eye = p.getEyePosition(AIM_PARTIAL_TICK);
    AABB box = t.getBoundingBox();

    double maxAllowed = Math.min(maxRange, 2.95D);
    double distToBox = Math.sqrt(box.distanceToSqr(eye));
    if (distToBox > maxAllowed)
      return false;

    if (box.contains(eye))
      return true;

    Vec3 look = RotationHandler.getRotationVector(pitch, yaw);
    Vec3 end = eye.add(look.scale(maxAllowed));

    Optional<Vec3> hit = box.clip(eye, end);
    if (hit.isEmpty())
      return false;

    double hitDistance = eye.distanceTo(hit.get());
    if (hitDistance > maxAllowed)
      return false;

    BlockHitResult blockHit = mc.level.clip(new ClipContext(
        eye,
        end,
        ClipContext.Block.COLLIDER,
        ClipContext.Fluid.NONE,
        p));

    if (blockHit.getType() != HitResult.Type.MISS) {
      double blockDistSqr = blockHit.getLocation().distanceToSqr(eye);
      double entityDistSqr = hit.get().distanceToSqr(eye);
      if (blockDistSqr < entityDistSqr) {
        return false;
      }
    }

    return true;
  }

  private boolean isRayReachable(LocalPlayer p, LivingEntity t, double maxRange) {
    return isRayReachable(p, t, maxRange, sentPitch(), sentYaw());
  }

  private boolean valid(LocalPlayer p, LivingEntity t) {
    if (!t.isAlive() || t.isRemoved() || t.isSpectator())
      return false;
    if (t == p)
      return false;
    if (!TargetUtils.isTargetAllowed(t))
      return false;
    if (t instanceof Player q) {
      if (q.isCreative())
        return false;
      if (PlayerUtils.isFriend(q.getGameProfile().name()))
        return false;
    }
    if (p.distanceTo(t) > aimRange.getValue())
      return false;
    return inFov(p, t);
  }

  private LivingEntity findTarget(LocalPlayer p) {
    LivingEntity manual = PlayerUtils.getManualSelectTarget();
    if (manual != null && valid(p, manual))
      return manual;

    LivingEntity best = null;
    double bestScore = Double.MAX_VALUE;
    boolean lowestHp = priority.is(Concat.of("Lowest HP"));

    for (Entity entity : mc.level.entitiesForRendering()) {
      if (!(entity instanceof LivingEntity living))
        continue;
      if (!valid(p, living))
        continue;

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
    if (half >= 180.0F)
      return true;
    Vec3 pt = Aim.point(t, point(), AIM_PARTIAL_TICK);
    if (pt == null)
      pt = t.position().add(0, t.getBbHeight() * 0.5D, 0);
    float[] r = RotationUtils.calculate(p.getEyePosition(AIM_PARTIAL_TICK), pt);
    float yd = Math.abs(Mth.wrapDegrees(r[0] - p.getYRot()));
    float pd = Math.abs(r[1] - p.getXRot());
    return Math.hypot(yd, pd) <= half;
  }

  private void attack(LocalPlayer p, LivingEntity t) {
    if (mc.gameMode == null)
      return;
    if (p.isUsingItem())
      mc.gameMode.releaseUsingItem(p);
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
    if (!(t instanceof Player q))
      return false;
    if (q.isBlocking())
      return q.getTicksUsingItem() >= 5;
    if (!q.isUsingItem())
      return false;
    ItemStack useItem = q.getUseItem();
    return !useItem.isEmpty() && useItem.getItem() instanceof ShieldItem && q.getTicksUsingItem() >= 5;
  }

  /** One hotbar change per tick, otherwise the server drops the second one. */
  private boolean setSlot(LocalPlayer p, int slot) {
    if (slot < 0 || slot > 8)
      return false;
    if (p.getInventory().getSelectedSlot() == slot)
      return true;
    if (p.tickCount == lastSwapTick)
      return false;

    lastSwapTick = p.tickCount;
    InventoryUtils.setInvSlot(slot);
    flushSlot(p, slot);
    return true;
  }

  private void flushSlot(LocalPlayer p, int slot) {
    if (mc.gameMode == null)
      return;
    try {
      ((MultiPlayerGameModeAccessor) mc.gameMode).syncSlot();
    } catch (Throwable ignored) {
      InventoryUtils.setInvSlot(slot);
    }
  }

  private boolean swapTo(LocalPlayer p, int slot) {
    int previous = p.getInventory().getSelectedSlot();
    if (previous == slot)
      return true;
    if (!setSlot(p, slot))
      return false;
    if (savedSlot == NO_SLOT)
      savedSlot = previous;
    return true;
  }

  private void processPendingRestore(LocalPlayer p) {
    if (!restorePending)
      return;
    if (System.currentTimeMillis() < restoreAt)
      return;
    if (restore.getValue() && savedSlot != NO_SLOT && !setSlot(p, savedSlot))
      return;
    savedSlot = NO_SLOT;
    restorePending = false;
  }

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
    if (p.isFallFlying())
      return false;
    if (!checkFalling.getValue())
      return true;
    if (p.onGround() || !isFalling || p.getDeltaMovement().y >= -0.05D)
      return false;
    return fallDist >= minFall.getValue();
  }

  private boolean isMaceStack(ItemStack stack) {
    if (stack == null || stack.isEmpty())
      return false;
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
    if (preferred != NO_SLOT)
      return preferred;

    int fallback = useDensity
        ? InventoryUtils.findMaceWithEnchantmentInHotbar(Enchantments.BREACH, false)
        : InventoryUtils.findMaceWithEnchantmentInHotbar(Enchantments.DENSITY, false);
    if (fallback != NO_SLOT)
      return fallback;

    return findAnyMace(p);
  }

  private int findAnyMace(LocalPlayer p) {
    return InventoryUtils.findItemWithPredicateInHotbar(this::isMaceStack);
  }

  private int findAxe(LocalPlayer p) {
    for (int i = 0; i < 9; i++) {
      Item item = p.getInventory().getItem(i).getItem();
      if (item instanceof AxeItem)
        return i;
    }
    return NO_SLOT;
  }

  private Aim.Point point() {
    if (aimPoint.is(Concat.of("Head")))
      return Aim.Point.HEAD;
    if (aimPoint.is(Concat.of("Center")))
      return Aim.Point.CENTER;
    if (aimPoint.is(Concat.of("Respect Closed")) || aimPoint.is(Concat.of("Respect Closest")))
      return Aim.Point.RESPECT_CLOSEST;
    if (aimPoint.is(Concat.of("Closest")))
      return Aim.Point.CLOSEST;
    return Aim.Point.DYNAMIC;
  }
}
