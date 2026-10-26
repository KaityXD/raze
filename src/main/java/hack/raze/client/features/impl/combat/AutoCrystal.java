package hack.raze.client.features.impl.combat;

import com.mojang.blaze3d.platform.InputConstants;
import hack.raze.client.Raze;
import hack.raze.client.api.ScreenCompat;
import hack.raze.client.event.EventSubscribe;
import hack.raze.client.event.impl.EventHandleInput;
import hack.raze.client.event.impl.EventItemUse;
import hack.raze.client.event.impl.EventPerformUseItemOn;
import hack.raze.client.features.Category;
import hack.raze.client.features.Feature;
import hack.raze.client.features.FeatureInfo;
import hack.raze.client.features.settings.impl.BoolSetting;
import hack.raze.client.features.settings.impl.FloatSetting;
import hack.raze.client.features.settings.impl.ItemPickerSetting;
import hack.raze.client.features.settings.impl.KeybindSetting;
import hack.raze.client.handlers.InputHandler;
import hack.raze.client.mixin.accessors.KeyMappingAccessor;
import hack.raze.client.utils.inventory.InventoryUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

import java.util.function.Predicate;

import static hack.raze.client.utils.Imports.mc;

/**
 * Camera-driven AutoCrystal, ported from the respect input-simulation design:
 * while the activate key is held the module only queues vanilla use/attack/hotbar
 * key clicks. All packets (use, attack, swing, hotbar change) are produced by the
 * vanilla input pipeline reacting to {@code mc.hitResult}, so there are no direct
 * {@code gameMode.useItemOn}/{@code gameMode.attack} calls from module code.
 */
public final class AutoCrystal extends Feature {

    private static final int PLACE_WAIT_TIMEOUT_TICKS = 40;

    private static volatile long forcedUntil;

    private final FloatSetting placeDelay = new FloatSetting("Place Delay", 1.0F, 0.0F, 20.0F, 1.0F);
    private final FloatSetting breakDelay = new FloatSetting("Break Delay", 1.0F, 0.0F, 20.0F, 1.0F);
    private final FloatSetting failChance = new FloatSetting("Fail Chance", 0.0F, 0.0F, 100.0F, 1.0F);
    private final BoolSetting inputSimulation = new BoolSetting("Input Simulation", false);
    private final BoolSetting blindCycle = new BoolSetting("Alter", false);
    private final BoolSetting stopOnKill = new BoolSetting("Stop on Kill", false);
    private final BoolSetting placeObsidian = new BoolSetting("Place Obsidian", true);
    private final FloatSetting switchDelay = new FloatSetting("Switch Delay", 1.0F, 0.0F, 10.0F, 1.0F, o -> placeObsidian.getValue());
    private final FloatSetting placeObsidianDelay = new FloatSetting("Place Obsidian Delay", 1.0F, 0.0F, 10.0F, 1.0F, o -> placeObsidian.getValue());
    private final BoolSetting switchBack = new BoolSetting("Switch Back", true, o -> placeObsidian.getValue());
    private final KeybindSetting activateKey = new KeybindSetting("Activate Key", 0x80000000 | 1);
    private final ItemPickerSetting activateItems = new ItemPickerSetting("Activate Items");

    private final TickTimer placeTimer = new TickTimer();
    private final TickTimer breakTimer = new TickTimer();

    private Stage stage = Stage.None;
    private int switchCooldown;
    private int placeCooldown;
    private boolean placedObsidian;
    private BlockPos expectedPlacementPos;
    private int placeWaitTicks;
    private int swapRootSlot = -1;

    // input simulator state
    private boolean swallowedUseKey;
    private boolean simulateAttackNext = true;
    private boolean blindPlaceNext = true;

    private enum Stage { None, SwitchObsidian, PlaceObsidian, SwitchCrystal, Done }

    private static final class TickTimer {
        private long lastTick;

        void reset() {
            lastTick = mc.player != null ? mc.player.tickCount : 0L;
        }

        boolean hasReachedTicks(int ticks) {
            if (ticks == 0) return true;
            long current = mc.player != null ? mc.player.tickCount : 0L;
            if (current < lastTick) {
                lastTick = current;
                return false;
            }
            return current - lastTick >= ticks;
        }
    }

    public AutoCrystal() {
        super(new FeatureInfo("AutoCrystal", "Places obsidian and detonates end crystals while the activate key is held", Category.COMBAT));
    }

    @Override
    public void onEnable() {
        super.onEnable();
        placeTimer.reset();
        breakTimer.reset();
        resetSequence();
    }

    @Override
    public void onDisable() {
        super.onDisable();
        resetSequence();
        forcedUntil = 0L;
    }

    // -- events --

    @EventSubscribe
    private void onTick(EventHandleInput.Early event) {
        if (!isEnabled() || isNull()) return;
        if (ScreenCompat.hasScreen(mc)) {
            resetSequence();
            placeTimer.reset();
            breakTimer.reset();
            syncKey(mc.options.keyUse);
            syncKey(mc.options.keyAttack);
            return;
        }
        handleCrystalLogic();
    }

    @EventSubscribe
    private void onPerformUseItemOn(EventPerformUseItemOn.Pre event) {
        if (mc.hitResult instanceof BlockHitResult hit && shouldCancelItemUse(hit)) event.cancel();
    }

    @EventSubscribe
    private void onItemUse(EventItemUse.Pre event) {
        if (mc.hitResult instanceof BlockHitResult hit && shouldCancelItemUse(hit)) event.cancel();
    }

    // -- main logic (respect parity) --

    private void handleCrystalLogic() {
        if (mc.player == null || mc.level == null || mc.player.isUsingItem()
                || (stopOnKill.getValue() && isDeadBodyNearby())) return;

        boolean pressed = isActivateKeyDown() || isForced();
        boolean valid = isValidActivationItem(mc.player.getMainHandItem());

        if (placeObsidian.getValue() && pressed && valid
                && stage == Stage.None && canActivateSequence()) {
            activateSequence();
        }

        if (!pressed) {
            handleKeyRelease(switchBack.getValue());
            syncKey(mc.options.keyUse);
            syncKey(mc.options.keyAttack);
        }

        if (isSequenceActive()) swallowManualUse();
        else restoreManualUse();

        if (stage != Stage.None) {
            handleObsidianSequence(this::handleNormalCrystalLogic);
            return;
        }

        if (pressed && valid) handleNormalCrystalLogic();
    }

    private void handleNormalCrystalLogic() {
        if (blindCycle.getValue() && isActivateKeyDown()) {
            handleBlindCycle();
            return;
        }
        if (inputSimulation.getValue()) {
            handleClickSimulation();
            return;
        }
        placeCrystalOnBlock();
        breakCrystal();
    }

    // -- placer --

    private void placeCrystalOnBlock() {
        if (mc.player == null || mc.level == null) return;
        if (!(mc.hitResult instanceof BlockHitResult blockHit)) return;

        BlockPos pos = blockHit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!isValidBase(state)) return;
        if (hasCrystal(pos)) return;

        if (!mc.player.getMainHandItem().is(Items.END_CRYSTAL)) {
            int crystalSlot = InventoryUtils.findItemWithPredicateInHotbar(stack -> stack.is(Items.END_CRYSTAL));
            if (crystalSlot != -1) {
                if (isSequenceActive() && swapRootSlot == -1) swapRootSlot = mc.player.getInventory().getSelectedSlot();
                selectSlot(crystalSlot);
            }
            return;
        }

        if (placeTimer.hasReachedTicks((int) placeDelay.getValue())) {
            if (passesFailCheck()) clickMapping(mc.options.keyUse);
            placeTimer.reset();
        }
    }

    // -- breaker --

    private void breakCrystal() {
        if (mc.player == null || mc.level == null) return;
        if (!(mc.hitResult instanceof EntityHitResult entityHit)) return;
        if (!isValidTarget(entityHit.getEntity())) return;

        if (breakTimer.hasReachedTicks((int) breakDelay.getValue())) {
            if (passesFailCheck()) clickMapping(mc.options.keyAttack);
            breakTimer.reset();
        }
    }

    private boolean isValidTarget(Entity entity) {
        if (!(entity instanceof EndCrystal crystal)) return false;
        return !crystal.isRemoved() && crystal.isAlive();
    }

    private boolean isDeadBodyNearby() {
        if (mc.player == null || mc.level == null) return false;
        for (Player p : mc.level.players()) {
            if (mc.player != p && mc.player.distanceToSqr(p) < 36.0D && p.isDeadOrDying()) return true;
        }
        return false;
    }

    // -- base / placement helpers --

    private boolean isValidBase(BlockState state) {
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
    }

    private BlockPos resolvePlacementPos(BlockHitResult blockHit) {
        if (mc.player == null) return blockHit.getBlockPos();
        BlockPlaceContext context = new BlockPlaceContext(
                mc.player,
                InteractionHand.MAIN_HAND,
                mc.player.getMainHandItem(),
                blockHit
        );
        return context.getClickedPos();
    }

    private boolean canPlaceHeldBlockAt(BlockHitResult blockHit) {
        if (mc.player == null || mc.level == null) return false;
        BlockState placementState = mc.level.getBlockState(resolvePlacementPos(blockHit));
        return placementState.isAir() || placementState.canBeReplaced();
    }

    /** The crystal-volume check from the reference: the block above {@code pos} must be
     * empty, and its 2-block AABB must not intersect any entity. */
    private boolean hasCrystal(BlockPos pos) {
        if (mc.level == null || pos == null) return false;

        BlockPos crystalPos = pos.above();
        if (!mc.level.isEmptyBlock(crystalPos)) return false;

        AABB crystalAABB = new AABB(
                crystalPos.getX(), crystalPos.getY(), crystalPos.getZ(),
                crystalPos.getX() + 1.0, crystalPos.getY() + 2.0, crystalPos.getZ() + 1.0);
        return !mc.level.getEntities(null, crystalAABB).isEmpty();
    }

    private boolean shouldCancelItemUse(BlockHitResult hit) {
        if (mc.player == null || mc.level == null || !isActivateKeyDown()) return false;
        if (stage == Stage.PlaceObsidian) return false;
        if (!isValidBase(mc.level.getBlockState(hit.getBlockPos()))) return false;
        ItemStack held = mc.player.getMainHandItem();
        if (!held.is(Items.OBSIDIAN) && !held.is(Items.BEDROCK)) return false;
        if (canPlaceHeldBlockAt(hit)) return false;
        if (held.has(DataComponents.FOOD)) return false;
        return InventoryUtils.hasItemInHotbar(i -> i == Items.END_CRYSTAL);
    }

    private boolean passesFailCheck() {
        return !(Math.random() * 100.0D < failChance.getValue());
    }

    // -- input simulation --

    private void clickMapping(KeyMapping mapping) {
        if (mapping == null) return;
        boolean wasDown = mapping.isDown();
        KeyMappingAccessor accessor = (KeyMappingAccessor) mapping;
        accessor.setClickCount(accessor.getClickCount() + 1);
        mapping.setDown(true);
        mapping.setDown(false);
        if (wasDown) mapping.setDown(true);
    }

    private void swallowManualUse() {
        if (mc.options == null || mc.options.keyUse == null) return;
        mc.options.keyUse.setDown(false);
        mc.options.keyUse.consumeClick();
        swallowedUseKey = true;
    }

    private void restoreManualUse() {
        if (!swallowedUseKey) return;
        swallowedUseKey = false;
        if (mc.options == null || mc.options.keyUse == null) return;
        KeyMappingAccessor accessor = (KeyMappingAccessor) mc.options.keyUse;
        InputConstants.Key key = accessor.getKey();
        if (key == null) return;
        mc.options.keyUse.setDown(InputHandler.isBindDown(bindCodeOf(key)));
    }

    private void syncKey(KeyMapping mapping) {
        if (mapping == null || mc.player == null) return;
        if (!mapping.isDown()) return;
        KeyMappingAccessor accessor = (KeyMappingAccessor) mapping;
        InputConstants.Key bound = accessor.getKey();
        if (bound == null) return;
        if (!InputHandler.isBindDown(bindCodeOf(bound))) {
            mapping.setDown(false);
            accessor.setClickCount(0);
        }
    }

    private void selectSlot(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return;

        if (inputSimulation.getValue()
                && mc.options != null
                && mc.options.keyHotbarSlots != null
                && slot < mc.options.keyHotbarSlots.length) {
            clickMapping(mc.options.keyHotbarSlots[slot]);
            return;
        }

        InventoryUtils.setInvSlot(slot);
    }

    private void handleClickSimulation() {
        if (mc.player == null || mc.level == null) return;

        boolean hasCrystalInHand = mc.player.getMainHandItem().is(Items.END_CRYSTAL);
        boolean hasCrystalInHotbar = InventoryUtils.hasItemInHotbar(item -> item == Items.END_CRYSTAL);
        boolean hasCrystalInOffhand = mc.player.getOffhandItem().is(Items.END_CRYSTAL);
        if (!hasCrystalInHand && !hasCrystalInHotbar && !hasCrystalInOffhand) return;

        if (!hasCrystalInHand && hasCrystalInHotbar) {
            selectSlot(InventoryUtils.findItemWithPredicateInHotbar(stack -> stack.is(Items.END_CRYSTAL)));
            return;
        }

        if (!isLookingAtValidTarget()) return;

        boolean breakReady = breakTimer.hasReachedTicks((int) breakDelay.getValue());
        boolean placeReady = placeTimer.hasReachedTicks((int) placeDelay.getValue());
        if (!breakReady && !placeReady) return;

        if (simulateAttackNext) {
            if (breakReady) {
                if (passesFailCheck()) clickMapping(mc.options.keyAttack);
                breakTimer.reset();
                simulateAttackNext = false;
                return;
            }

            if (placeReady) {
                if (passesFailCheck()) clickMapping(mc.options.keyUse);
                placeTimer.reset();
                simulateAttackNext = true;
            }
            return;
        }

        if (placeReady) {
            if (passesFailCheck()) clickMapping(mc.options.keyUse);
            placeTimer.reset();
            simulateAttackNext = true;
            return;
        }

        if (breakReady) {
            if (passesFailCheck()) clickMapping(mc.options.keyAttack);
            breakTimer.reset();
            simulateAttackNext = false;
        }
    }

    private void handleBlindCycle() {
        if (mc.player == null || mc.level == null || mc.options == null) return;

        boolean hasCrystalInHand = mc.player.getMainHandItem().is(Items.END_CRYSTAL)
                || mc.player.getOffhandItem().is(Items.END_CRYSTAL);
        if (!hasCrystalInHand) {
            int crystalSlot = InventoryUtils.findItemWithPredicateInHotbar(stack -> stack.is(Items.END_CRYSTAL));
            if (crystalSlot == -1) return;
            selectSlot(crystalSlot);
            return;
        }

        if (blindPlaceNext) {
            if (!placeTimer.hasReachedTicks((int) placeDelay.getValue())) return;
            if (passesFailCheck()) clickMapping(mc.options.keyUse);
            placeTimer.reset();
            blindPlaceNext = false;
            return;
        }

        if (!breakTimer.hasReachedTicks((int) breakDelay.getValue())) return;
        if (passesFailCheck()) clickMapping(mc.options.keyAttack);
        breakTimer.reset();
        blindPlaceNext = true;
    }

    private boolean isLookingAtValidTarget() {
        if (mc.hitResult instanceof EntityHitResult entityHit) {
            return isValidTarget(entityHit.getEntity());
        }
        if (mc.hitResult instanceof BlockHitResult blockHit) {
            return isValidBase(mc.level.getBlockState(blockHit.getBlockPos()));
        }
        return false;
    }

    // -- obsidian sequence --

    private void resetSequence() {
        stage = Stage.None;
        switchCooldown = 0;
        placeCooldown = 0;
        placedObsidian = false;
        expectedPlacementPos = null;
        placeWaitTicks = 0;
        swapRootSlot = -1;
        simulateAttackNext = true;
        blindPlaceNext = true;
        restoreManualUse();
    }

    private boolean isSequenceActive() {
        return stage != Stage.None && stage != Stage.Done;
    }

    private boolean isValidActivationItem(ItemStack s) {
        if (activateItems.getSelectedCount() == 0) return true;
        if (mc.player != null && mc.player.getOffhandItem().is(Items.END_CRYSTAL)
                && activateItems.isSelected(Items.END_CRYSTAL)) return true;
        if (s == null || s.isEmpty()) return false;
        return activateItems.isSelected(s.getItem());
    }

    private boolean canActivateSequence() {
        if (mc.level == null || !(mc.hitResult instanceof BlockHitResult blockHit)) return false;

        Block block = mc.level.getBlockState(blockHit.getBlockPos()).getBlock();
        if (block == Blocks.OBSIDIAN || block == Blocks.BEDROCK) {
            return InventoryUtils.hasItemInHotbar(item -> item == Items.END_CRYSTAL);
        }
        return InventoryUtils.hasItemInHotbar(item -> item == Items.OBSIDIAN || item == Items.BEDROCK)
                && InventoryUtils.hasItemInHotbar(item -> item == Items.END_CRYSTAL);
    }

    private void activateSequence() {
        if (mc.level == null || !(mc.hitResult instanceof BlockHitResult blockHit)) return;
        placeWaitTicks = 0;

        BlockPos pos = blockHit.getBlockPos();
        BlockState hitState = mc.level.getBlockState(pos);
        Block block = hitState.getBlock();

        if (block == Blocks.OBSIDIAN || block == Blocks.BEDROCK) {
            stage = Stage.SwitchCrystal;
            switchCooldown = (int) switchDelay.getValue();
            placedObsidian = false;
            expectedPlacementPos = null;
            return;
        }

        if (hitState.isAir()) return;

        Direction hitFace = blockHit.getDirection();
        BlockPos placementPos = hitState.canBeReplaced() ? pos : pos.relative(hitFace);
        BlockState placementState = mc.level.getBlockState(placementPos);
        if (!placementState.isAir() && !placementState.canBeReplaced()) return;

        stage = Stage.SwitchObsidian;
        switchCooldown = (int) switchDelay.getValue();
        placeCooldown = (int) placeObsidianDelay.getValue();
        placedObsidian = false;
        expectedPlacementPos = null;
    }

    private void handleObsidianSequence(Runnable onDone) {
        Stage previous = null;
        while (stage != Stage.None && stage != previous) {
            previous = stage;

            if (stage == Stage.PlaceObsidian) {
                if (placeCooldown > 0) {
                    placeCooldown--;
                    return;
                }
            } else if (stage == Stage.SwitchObsidian || stage == Stage.SwitchCrystal) {
                if (switchCooldown > 0) {
                    switchCooldown--;
                    return;
                }
            }

            switch (stage) {
                case SwitchObsidian -> {
                    int obsidianSlot = InventoryUtils.findItemWithPredicateInHotbar(stack -> stack.is(Items.OBSIDIAN));
                    if (obsidianSlot == -1) {
                        obsidianSlot = InventoryUtils.findItemWithPredicateInHotbar(stack -> stack.is(Items.BEDROCK));
                    }
                    if (obsidianSlot == -1 || !swapToIfNeeded(obsidianSlot)) {
                        abortSequence();
                        break;
                    }
                    stage = Stage.PlaceObsidian;
                    placeWaitTicks = 0;
                }
                case PlaceObsidian -> {
                    if (placedObsidian && expectedPlacementPos != null) {
                        if (mc.level == null) return;
                        Block placed = mc.level.getBlockState(expectedPlacementPos).getBlock();
                        if (placed == Blocks.OBSIDIAN || placed == Blocks.BEDROCK) {
                            stage = Stage.SwitchCrystal;
                            switchCooldown = (int) switchDelay.getValue();
                            placedObsidian = false;
                            expectedPlacementPos = null;
                            continue;
                        }

                        placedObsidian = false;
                        expectedPlacementPos = null;
                        placeCooldown = (int) placeObsidianDelay.getValue();
                        placeWaitTicks = 0;
                        return;
                    }

                    if (!(mc.hitResult instanceof BlockHitResult blockHit)) {
                        if (++placeWaitTicks > PLACE_WAIT_TIMEOUT_TICKS) abortSequence();
                        return;
                    }

                    if (mc.level == null) return;
                    BlockState clickedState = mc.level.getBlockState(blockHit.getBlockPos());
                    if (isValidBase(clickedState)) {
                        stage = Stage.SwitchCrystal;
                        switchCooldown = (int) switchDelay.getValue();
                        placedObsidian = false;
                        expectedPlacementPos = null;
                        continue;
                    }

                    BlockPos placementPos = resolvePlacementPos(blockHit);
                    BlockState placementState = mc.level.getBlockState(placementPos);
                    Block placementBlock = placementState.getBlock();

                    if (placementBlock == Blocks.OBSIDIAN || placementBlock == Blocks.BEDROCK) {
                        stage = Stage.SwitchCrystal;
                        switchCooldown = (int) switchDelay.getValue();
                        placedObsidian = false;
                        expectedPlacementPos = null;
                    } else if (placementState.isAir() || placementState.canBeReplaced()) {
                        clickMapping(mc.options.keyUse);
                        placedObsidian = true;
                        expectedPlacementPos = placementPos;
                        placeWaitTicks = 0;
                    } else {
                        abortSequence();
                    }
                }
                case SwitchCrystal -> {
                    int crystalSlot = InventoryUtils.findItemWithPredicateInHotbar(stack -> stack.is(Items.END_CRYSTAL));
                    if (crystalSlot == -1 || !swapToIfNeeded(crystalSlot)) {
                        abortSequence();
                        break;
                    }
                    stage = Stage.Done;
                    placeTimer.reset();
                }
                case Done -> {
                    if (onDone != null) onDone.run();
                }
                case None -> { }
            }
        }
    }

    private void abortSequence() {
        stage = Stage.None;
        placedObsidian = false;
        expectedPlacementPos = null;
        restoreSwap(switchBack.getValue());
    }

    private boolean swapToIfNeeded(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return false;
        if (mc.player.getInventory().getSelectedSlot() == slot) return true;

        if (swapRootSlot == -1) {
            swapRootSlot = mc.player.getInventory().getSelectedSlot();
        }
        selectSlot(slot);
        return true;
    }

    private void restoreSwap(boolean restore) {
        if (restore && swapRootSlot >= 0 && swapRootSlot <= 8) {
            selectSlot(swapRootSlot);
        }
        swapRootSlot = -1;
    }

    private void handleKeyRelease(boolean restore) {
        if (stage != Stage.None) {
            restoreSwap(restore);
            stage = Stage.None;
        } else if (swapRootSlot != -1) {
            restoreSwap(restore);
        }
    }

    // -- key helpers --

    private boolean isActivateKeyDown() {
        if (mc == null || ScreenCompat.hasScreen(mc) || activateKey.isListening()) return false;
        return InputHandler.isBindDown(activateKey.getKey());
    }

    private static int bindCodeOf(InputConstants.Key key) {
        if (key == null) return -1;
        if (key.getType() == InputConstants.Type.MOUSE) return 0x80000000 | key.getValue();
        return key.getValue();
    }

    // -- cross-feature forcing API --

    /** Lets other modules force the sequence to run for {@code ticks} game ticks. */
    public static void requestSequence(int ticks) {
        AutoCrystal instance = Raze.featureManager != null
                ? Raze.featureManager.getFeatureByClass(AutoCrystal.class) : null;
        if (instance != null && instance.isEnabled()) {
            forcedUntil = System.currentTimeMillis() + Math.max(1, ticks) * 50L;
        }
    }

    public static boolean isForced() {
        return System.currentTimeMillis() <= forcedUntil;
    }
}
