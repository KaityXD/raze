package hack.raze.client.features.impl.blatant;

import hack.raze.client.api.AttackRangeCompat;
import hack.raze.client.api.MinecraftCompat;
import hack.raze.client.event.EventSubscribe;
import hack.raze.client.event.impl.EventStartAttack;
import hack.raze.client.event.impl.EventStartUseItem;
import hack.raze.client.event.impl.EventTick;
import hack.raze.client.features.Category;
import hack.raze.client.features.Feature;
import hack.raze.client.features.FeatureInfo;
import hack.raze.client.features.settings.impl.BoolSetting;
import hack.raze.client.features.settings.impl.IntSetting;
import hack.raze.client.features.settings.impl.ItemPickerSetting;
import hack.raze.client.features.settings.impl.KeybindSetting;
import hack.raze.client.features.settings.impl.RangeSetting;
import hack.raze.client.handlers.InputHandler;
import hack.raze.client.handlers.impl.SwapStateManager;
import hack.raze.client.utils.blocks.BlockUtils;
import hack.raze.client.utils.inventory.InventoryUtils;
import hack.raze.client.utils.math.TimerUtils;
import hack.raze.client.utils.rotation.RotationUtils;
import hack.raze.client.utils.strings.Concat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Crystal Aura - the merged crystal module of this client.
 *
 * <p>It combines the two former modules into one feature:</p>
 * <ul>
 *     <li><b>Auto Crystal</b> - places and detonates end crystals, including the
 *     "hit crystal" variant that first places obsidian to block a target crystal.</li>
 *     <li><b>Crystal Optimizer</b> - removes a crystal client side the moment the attack
 *     that destroys it server side is fired.</li>
 * </ul>
 *
 * <p>Every action of this module stays inside the rules the server enforces, so the
 * behaviour is indistinguishable from a normal player:</p>
 * <ul>
 *     <li>crystals are only placed on a real support block (obsidian / bedrock) with free
 *     space above it, and never on a block that already carries a crystal,</li>
 *     <li>crystals are only detonated while they are really in attack range,</li>
 *     <li>a crystal is only removed client side when the server would destroy it as well
 *     (weakness / strength / weapon rules are respected),</li>
 *     <li>actions are sent through the regular key mappings, never through raw packets,</li>
 *     <li>delays are jittered and the module waits for a reaction before it acts on a
 *     newly appeared target, so it never runs on a perfect metronome.</li>
 * </ul>
 */

 // TODO: remove this ai ass comment by dylen wtf???
public class CrystalAura extends Feature {

    public CrystalAura() {
        super(new FeatureInfo(
            Concat.of("Crystal Aura"),
            Concat.of("Giving aura to user and fight with the jews"),
            Category.BLATANT
        ));
        itemWhitelist.setSelected(Items.END_CRYSTAL, true);
    }

    // ------------------------------------------------------------------ settings

    private final IntSetting placeDelay = new IntSetting(
        Concat.of("Place Delay"),
        1, 0, 20,
        Concat.of(" ticks")
    );

    private final IntSetting breakDelay = new IntSetting(
        Concat.of("Break Delay"),
        1, 0, 20,
        Concat.of(" ticks")
    );

    private final IntSetting failChance = new IntSetting(Concat.of("Fail Chance"), 0, 0, 100);

    /**
     * Random amount of extra ticks that is added to every delay. A fixed cadence is one of
     * the easiest things to filter for, so the module spreads its actions out a bit.
     */
    private final IntSetting timingVariance = new IntSetting(
        Concat.of("Timing Variance"),
        0, 0, 5,
        Concat.of(" ticks")
    );

    /** Ticks the module waits before it reacts on a freshly appeared target. */
    private final IntSetting reactionDelay = new IntSetting(
        Concat.of("Reaction Delay"),
        0, 0, 10,
        Concat.of(" ticks")
    );

    private final BoolSetting inputSimulation = new BoolSetting(Concat.of("Input Simulation"), false);
    private final BoolSetting stopOnKill = new BoolSetting(Concat.of("Stop on kill"), false);

    /** Removes the crystal client side as soon as the attack that destroys it is fired. */
    private final BoolSetting optimize = new BoolSetting(Concat.of("Optimize"), true);

    /** Slowly turns the view onto a crystal instead of requiring the player to aim manually. */
    private final BoolSetting rotateAssist = new BoolSetting(Concat.of("Rotate Assist"), false);
    private final RangeSetting rotationSpeed = new RangeSetting(
        Concat.of("Rotation Speed"),
        25f, 45f, 1f, 180f, 1f,
        Concat.of(" deg/tick"),
        p -> rotateAssist.getValue()
    );

    // Hit crystal (place obsidian) settings
    private final BoolSetting placeObsidian = new BoolSetting(Concat.of("Place Obsidian"), false);
    private final IntSetting switchDelay = new IntSetting(
        Concat.of("Switch Delay"),
        1, 0, 10,
        Concat.of(" ticks"),
        p -> placeObsidian.getValue()
    );
    private final IntSetting placeObsidianDelay = new IntSetting(
        Concat.of("Place Obsidian Delay"),
        1, 0, 10,
        Concat.of(" ticks"),
        p -> placeObsidian.getValue()
    );
    private final BoolSetting switchBack = new BoolSetting(Concat.of("Switch Back"), true, p -> placeObsidian.getValue());

    private final ItemPickerSetting itemWhitelist = new ItemPickerSetting(Concat.of("Item Whitelist"));
    private final KeybindSetting activateKey = new KeybindSetting(Concat.of("Activate Key"), -1);

    // ------------------------------------------------------------------ state

    private enum Stage {
        IDLE,
        SWITCH_OBSIDIAN,
        PLACE_OBSIDIAN,
        SWITCH_CRYSTAL
    }

    private final TimerUtils placeTimer = new TimerUtils();
    private final TimerUtils breakTimer = new TimerUtils();
    private final TimerUtils reactionTimer = new TimerUtils();

    private Stage stage = Stage.IDLE;
    private int switchCooldown;
    private int placeCooldown;
    private boolean placedObsidian;
    private BlockPos expectedPlacementPos;

    private int currentPlaceDelay;
    private int currentBreakDelay;
    private int currentReactionDelay;

    /** Identity of the target the reaction timer is currently running for. */
    private String targetKey = "";
    private boolean waitingForReaction;

    @Override
    public void onEnable() {
        super.onEnable();
        placeTimer.reset();
        breakTimer.reset();
        reactionTimer.reset();
        currentPlaceDelay = rollDelay(placeDelay.getValue());
        currentBreakDelay = rollDelay(breakDelay.getValue());
        currentReactionDelay = rollDelay(reactionDelay.getValue());
        targetKey = "";
        waitingForReaction = false;
        resetSequence();
    }

    @Override
    public void onDisable() {
        resetSequence();
        releaseRotation();
        super.onDisable();
    }

    // ------------------------------------------------------------------ events

    @EventSubscribe
    private void onTick(EventTick event) {
        if (isNull()) return;
        if (MinecraftCompat.getScreen() != null || mc.player.isUsingItem() || mc.player.isDeadOrDying()) {
            resetSequence();
            return;
        }
        if (stopOnKill.getValue() && isDeadBodyNearby()) {
            resetSequence();
            return;
        }

        boolean active = isActive();

        if (stage != Stage.IDLE) {
            if (!active) {
                resetSequence();
                return;
            }
            handleObsidianSequence();
            return;
        }

        if (!active) {
            releaseRotation();
            return;
        }

        if (placeObsidian.getValue() && startObsidianSequence()) {
            handleObsidianSequence();
            return;
        }

        handleCombat();
    }

    /**
     * Keeps the vanilla attack on a support block from mining it while the module owns the
     * right click. Only relevant when the clicks are not simulated, because a simulated
     * click never reaches this code path.
     */
    @EventSubscribe
    private void onSuppressVanillaAttack(EventStartAttack event) {
        if (inputSimulation.getValue()) return;
        if (!isEnabled() || mc.player == null || mc.level == null) return;
        if (!(mc.hitResult instanceof BlockHitResult blockHit)) return;

        Block block = mc.level.getBlockState(blockHit.getBlockPos()).getBlock();
        if ((block == Blocks.OBSIDIAN || block == Blocks.BEDROCK) && isActive()) {
            event.cancel();
        }
    }

    /**
     * Crystal optimiser: the crystal is removed on the client the moment the attack that
     * destroys it server side is fired. The attack has to be a legal one, otherwise the
     * client and the server would disagree about the crystal and the desync would be way
     * more suspicious than the optimisation itself.
     */
    @EventSubscribe(priority = EventSubscribe.Priority.LOWEST)
    private void onOptimizeAttack(EventStartAttack event) {
        if (isNull()) return;
        if (!optimize.getValue()) return;
        if (event.cancelled) return;
        if (!(mc.hitResult instanceof EntityHitResult entityHit)) return;
        if (!(entityHit.getEntity() instanceof EndCrystal crystal)) return;
        if (!canDestroyCrystal(crystal)) return;

        crystal.setRemoved(Entity.RemovalReason.KILLED);
        crystal.onClientRemoval();
    }

    /**
     * Stops a manual obsidian placement from interfering with the hit crystal sequence.
     */
    @EventSubscribe
    private void onItemUse(EventStartUseItem.Pre event) {
        if (isNull()) return;

        int key = activateKey.getKey();
        if (key == -1) return;

        boolean isRMB = (key & 0x80000000) != 0 && (key & 0xFF) == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
        if (!isRMB) return;
        if (!event.isTargetingBlock()) return;

        BlockHitResult blockHit = event.getBlockHitResult();
        if (blockHit == null) return;

        Block block = mc.level.getBlockState(blockHit.getBlockPos()).getBlock();
        Item heldItem = event.getStack().getItem();
        if ((block == Blocks.OBSIDIAN || block == Blocks.BEDROCK)
            && (heldItem == Items.OBSIDIAN || heldItem == Items.BEDROCK)
            && stage != Stage.PLACE_OBSIDIAN
            && !canPlaceHeldBlockAt(blockHit)
            && InputHandler.isBindDown(key)) {
            if (!hasCrystalItem()) return;
            if (InventoryUtils.isFood(event.getStack())) return;
            event.cancel();
        }
    }

    // ------------------------------------------------------------------ crystal combat

    /**
     * Places and detonates crystals. Both actions share the same target detection, so the
     * module never places and breaks a crystal within the same tick and never performs an
     * action the server would reject.
     */
    private void handleCombat() {
        updateRotationAssist();

        EndCrystal crystal = getTargetCrystal();
        if (crystal != null) {
            if (!isTargetInReach(crystal)) return;
            if (!isReactionElapsed("c" + crystal.getId())) return;
            if (!breakTimer.hasReachedTicks(currentBreakDelay)) return;
            if (shouldFail()) return;

            breakTimer.reset();
            currentBreakDelay = rollDelay(breakDelay.getValue());
            InputHandler.simulateClick(mc.options.keyAttack, inputSimulation.getValue());
            return;
        }

        BlockPos placePos = getPlacementPos();
        if (placePos == null) return;
        if (!isReactionElapsed("b" + placePos.asLong())) return;

        int crystalSlot = findCrystalSlot();
        if (crystalSlot == -1) return;
        if (mc.player.getInventory().getSelectedSlot() != crystalSlot) {
            InventoryUtils.setInvSlot(crystalSlot, inputSimulation.getValue());
            return;
        }

        if (!placeTimer.hasReachedTicks(currentPlaceDelay)) return;
        if (shouldFail()) return;

        placeTimer.reset();
        currentPlaceDelay = rollDelay(placeDelay.getValue());
        InputHandler.simulateClick(mc.options.keyUse, inputSimulation.getValue());
    }

    /**
     * The crystal that is currently under the crosshair.
     */
    @Nullable
    private EndCrystal getTargetCrystal() {
        if (!(mc.hitResult instanceof EntityHitResult entityHit)) return null;
        if (!(entityHit.getEntity() instanceof EndCrystal crystal)) return null;
        if (crystal.isRemoved() || !crystal.isAlive()) return null;
        return crystal;
    }

    /**
     * The block a crystal could be placed on. Only obsidian and bedrock are valid support
     * blocks for an end crystal and the space above the block has to be free.
     */
    @Nullable
    private BlockPos getPlacementPos() {
        if (!(mc.hitResult instanceof BlockHitResult blockHit)) return null;

        BlockPos pos = blockHit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK)) return null;

        BlockState above = mc.level.getBlockState(pos.above());
        if (!above.isAir() && !above.canBeReplaced()) return null;

        if (BlockUtils.hasCrystalOnBlock(pos)) return null;
        return pos;
    }

    /**
     * True when the crystal is inside the range the server would accept an attack in.
     */
    private boolean isTargetInReach(EndCrystal crystal) {
        return AttackRangeCompat.isTargetInRange(mc.player, crystal);
    }

    /**
     * Checks everything the server checks before it lets a crystal die: the crystal has to be
     * alive, in range and the attack must actually deal damage.
     */
    private boolean canDestroyCrystal(EndCrystal crystal) {
        if (crystal.isRemoved() || !crystal.isAlive()) return false;
        if (!isTargetInReach(crystal)) return false;

        MobEffectInstance weakness = mc.player.getEffect(MobEffects.WEAKNESS);
        MobEffectInstance strength = mc.player.getEffect(MobEffects.STRENGTH);

        return (weakness == null)
            || (strength != null && strength.getAmplifier() > weakness.getAmplifier())
            || InventoryUtils.isHoldingPickaxeItem()
            || InventoryUtils.isHoldingSwordItem();
    }

    // ------------------------------------------------------------------ hit crystal sequence

    /**
     * Starts the "hit crystal" sequence. It places an obsidian in front of the crystal the
     * player is aiming at so the crystal cannot be hit anymore.
     *
     * @return true when the sequence was started
     */
    private boolean startObsidianSequence() {
        if (!(mc.hitResult instanceof BlockHitResult blockHit)) return false;

        BlockPos pos = blockHit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        Block block = state.getBlock();

        // The player already aims at the support block, only the crystal is missing.
        if (block == Blocks.OBSIDIAN || block == Blocks.BEDROCK) {
            if (!hasCrystalItem()) return false;
            stage = Stage.SWITCH_CRYSTAL;
            switchCooldown = switchDelay.getValue();
            placedObsidian = false;
            expectedPlacementPos = null;
            return true;
        }

        // An obsidian can only be placed against a solid block.
        if (state.isAir()) return false;

        Direction hitFace = blockHit.getDirection();
        BlockPos placementPos = state.canBeReplaced() ? pos : pos.relative(hitFace);
        BlockState placementState = mc.level.getBlockState(placementPos);
        if (!placementState.isAir() && !placementState.canBeReplaced()) return false;

        if (!hasObsidianItem() || !hasCrystalItem()) return false;

        stage = Stage.SWITCH_OBSIDIAN;
        switchCooldown = switchDelay.getValue();
        placeCooldown = placeObsidianDelay.getValue();
        placedObsidian = false;
        expectedPlacementPos = null;
        return true;
    }

    private void handleObsidianSequence() {
        switch (stage) {
            case SWITCH_OBSIDIAN -> {
                if (switchCooldown > 0) {
                    switchCooldown--;
                    return;
                }

                int obsidianSlot = findObsidianSlot();
                if (obsidianSlot == -1) {
                    resetSequence();
                    return;
                }
                if (!SwapStateManager.swapToIfNeeded(this, obsidianSlot, inputSimulation.getValue(), -1, false)) {
                    resetSequence();
                    return;
                }

                stage = Stage.PLACE_OBSIDIAN;
                placeCooldown = placeObsidianDelay.getValue();
            }
            case PLACE_OBSIDIAN -> {
                if (placeCooldown > 0) {
                    placeCooldown--;
                    return;
                }

                // Verify the obsidian really landed before switching to the crystal again.
                if (placedObsidian && expectedPlacementPos != null) {
                    BlockState placedState = mc.level.getBlockState(expectedPlacementPos);
                    if (placedState.is(Blocks.OBSIDIAN) || placedState.is(Blocks.BEDROCK)) {
                        stage = Stage.SWITCH_CRYSTAL;
                        switchCooldown = switchDelay.getValue();
                        placedObsidian = false;
                        expectedPlacementPos = null;
                        return;
                    }
                    placeCooldown = placeObsidianDelay.getValue();
                    return;
                }

                if (!(mc.hitResult instanceof BlockHitResult blockHit)) return;

                BlockPos placementPos = resolvePlacementPos(blockHit);
                BlockState placementState = mc.level.getBlockState(placementPos);
                if (placementState.is(Blocks.OBSIDIAN) || placementState.is(Blocks.BEDROCK)) {
                    stage = Stage.SWITCH_CRYSTAL;
                    switchCooldown = switchDelay.getValue();
                    placedObsidian = false;
                    expectedPlacementPos = null;
                    return;
                }

                InputHandler.simulateClick(mc.options.keyUse, inputSimulation.getValue());
                placedObsidian = true;
                expectedPlacementPos = placementPos;
            }
            case SWITCH_CRYSTAL -> {
                if (switchCooldown > 0) {
                    switchCooldown--;
                    return;
                }

                int crystalSlot = findCrystalSlot();
                if (crystalSlot == -1) {
                    resetSequence();
                    return;
                }
                if (!SwapStateManager.swapToIfNeeded(this, crystalSlot, inputSimulation.getValue(), -1, false)) {
                    resetSequence();
                    return;
                }

                stage = Stage.IDLE;
                placeTimer.reset();
                currentPlaceDelay = rollDelay(placeDelay.getValue());
            }
            default -> {
            }
        }
    }

    private void resetSequence() {
        stage = Stage.IDLE;
        switchCooldown = 0;
        placeCooldown = 0;
        placedObsidian = false;
        expectedPlacementPos = null;
        SwapStateManager.cancel(this, switchBack.getValue());
    }

    private BlockPos resolvePlacementPos(BlockHitResult blockHit) {
        BlockPlaceContext placeContext = new BlockPlaceContext(
            mc.player,
            InteractionHand.MAIN_HAND,
            mc.player.getMainHandItem(),
            blockHit
        );
        return placeContext.getClickedPos();
    }

    private boolean canPlaceHeldBlockAt(BlockHitResult blockHit) {
        if (mc.player == null || mc.level == null) return false;

        BlockPos placementPos = resolvePlacementPos(blockHit);
        BlockState placementState = mc.level.getBlockState(placementPos);
        return placementState.isAir() || placementState.canBeReplaced();
    }

    // ------------------------------------------------------------------ rotation assist

    /**
     * Slowly turns the view onto the closest crystal. The rotation is limited in speed, so
     * it never snaps and it uses the same rotation handler as the other combat modules.
     */
    private void updateRotationAssist() {
        if (!rotateAssist.getValue()) return;
        if (getTargetCrystal() != null) return;

        EndCrystal candidate = findCrystalCandidate();
        if (candidate == null) return;

        RotationUtils.aim(this)
            .speed(rotationSpeed.getRandom(), rotationSpeed.getRandom())
            .silent(false)
            .to(candidate.getBoundingBox().getCenter());
    }

    private void releaseRotation() {
        if (RotationUtils.isControlledBy(this)) {
            RotationUtils.releaseControl(this);
        }
    }

    /**
     * Finds the crystal the module should look at. The crystal with the smallest angle to
     * the current view wins, that keeps the required rotation as small as possible.
     */
    @Nullable
    private EndCrystal findCrystalCandidate() {
        double maxRange = AttackRangeCompat.getMaxAttackRange(mc.player) + 1.5;
        List<EndCrystal> crystals = mc.level.getEntitiesOfClass(
            EndCrystal.class,
            mc.player.getBoundingBox().inflate(maxRange),
            crystal -> true
        );

        EndCrystal best = null;
        double bestAngle = Double.MAX_VALUE;
        for (EndCrystal crystal : crystals) {
            if (crystal.isRemoved() || !crystal.isAlive()) continue;
            if (!isTargetInReach(crystal)) continue;

            double angle = angleTo(crystal.getBoundingBox().getCenter());
            if (angle < bestAngle) {
                bestAngle = angle;
                best = crystal;
            }
        }
        return best;
    }

    /**
     * Angle in degrees between the current view and the given position.
     */
    private double angleTo(Vec3 pos) {
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 diff = pos.subtract(mc.player.getEyePosition(pt));

        double horizontal = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float targetYaw = (float) (Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(diff.y, horizontal)));

        float yawDiff = Math.abs(Mth.wrapDegrees(targetYaw - mc.player.getYRot()));
        float pitchDiff = Math.abs(targetPitch - mc.player.getXRot());
        return Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * True while the activate key is held down. The module stays completely passive when no
     * key is bound.
     */
    private boolean isActive() {
        int key = activateKey.getKey();
        if (key == -1) return false;
        return InputHandler.isBindDown(key);
    }

    /**
     * Waits for the reaction delay whenever the target changes, so a crystal that just
     * appeared is not detonated in the very same tick.
     */
    private boolean isReactionElapsed(String key) {
        if (!key.equals(targetKey)) {
            targetKey = key;
            reactionTimer.reset();
            currentReactionDelay = rollDelay(reactionDelay.getValue());
            waitingForReaction = true;
        }

        if (!waitingForReaction) return true;
        if (!reactionTimer.hasReachedTicks(currentReactionDelay)) return false;

        waitingForReaction = false;
        return true;
    }

    /**
     * Adds a random amount of ticks to the given delay.
     */
    private int rollDelay(int base) {
        int variance = timingVariance.getValue();
        if (variance <= 0) return base;
        return base + (int) (Math.random() * (variance + 1));
    }

    /**
     * The configured chance that an action is skipped.
     */
    private boolean shouldFail() {
        int chance = failChance.getValue();
        return chance > 0 && Math.random() * 100 < chance;
    }

    /**
     * An item of the whitelist is a crystal item and therefore allowed to be used.
     */
    private boolean isCrystalItem(Item item) {
        return itemWhitelist.getSelectedCount() > 0 && itemWhitelist.isSelected(item);
    }

    private boolean hasCrystalItem() {
        return InventoryUtils.hasItemInHotbar(this::isCrystalItem);
    }

    private boolean hasObsidianItem() {
        return InventoryUtils.hasItemInHotbar(item -> item == Items.OBSIDIAN || item == Items.BEDROCK);
    }

    /**
     * Hotbar slot of the crystal that should be used. The item that is already in hand is
     * preferred so the module does not swap back and forth.
     */
    private int findCrystalSlot() {
        ItemStack held = mc.player.getMainHandItem();
        if (!held.isEmpty() && isCrystalItem(held.getItem())) {
            return mc.player.getInventory().getSelectedSlot();
        }
        return InventoryUtils.findItemWithPredicateInHotbar(stack -> isCrystalItem(stack.getItem()));
    }

    private int findObsidianSlot() {
        int slot = InventoryUtils.findItemWithPredicateInHotbar(stack -> stack.getItem() == Items.OBSIDIAN);
        if (slot == -1) {
            slot = InventoryUtils.findItemWithPredicateInHotbar(stack -> stack.getItem() == Items.BEDROCK);
        }
        return slot;
    }

    private boolean isDeadBodyNearby() {
        for (var player : mc.level.players()) {
            if (mc.player == player) continue;

            double distanceSquared = mc.player.distanceToSqr(player);
            if (distanceSquared < 36 && player.isDeadOrDying()) {
                return true;
            }
        }
        return false;
    }

    public int getActivateKey() {
        return activateKey.getKey();
    }

    public boolean isHitCrystalEnabled() {
        return placeObsidian.getValue();
    }

    public boolean isOptimizeEnabled() {
        return optimize.getValue();
    }
}
