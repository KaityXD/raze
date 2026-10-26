package hack.raze.client.features.impl.combat;

import com.mojang.blaze3d.platform.InputConstants;
import hack.raze.client.api.MinecraftCompat;
import hack.raze.client.event.EventSubscribe;
import hack.raze.client.event.impl.EventClientPlayerTick;
import hack.raze.client.event.impl.EventStartUseItem;
import hack.raze.client.features.Category;
import hack.raze.client.features.Feature;
import hack.raze.client.features.FeatureInfo;
import hack.raze.client.features.settings.impl.BoolSetting;
import hack.raze.client.features.settings.impl.FloatSetting;
import hack.raze.client.features.settings.impl.IntSetting;
import hack.raze.client.features.settings.impl.KeybindSetting;
import hack.raze.client.features.settings.impl.ModeSetting;
import hack.raze.client.handlers.InputHandler;
import hack.raze.client.mixin.accessors.KeyMappingAccessor;
import hack.raze.client.mixin.accessors.MinecraftAccessor;
import hack.raze.client.utils.inventory.InventoryUtils;
import hack.raze.client.utils.strings.Concat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class AnchorMacro extends Feature {

    private static final CharSequence ACTIVATION_HOLD = Concat.of("HOLD");
    private static final CharSequence ACTIVATION_CLICK = Concat.of("CLICK");
    private static final CharSequence CONDITION_ALWAYS = Concat.of("ALWAYS");
    private static final CharSequence CONDITION_HOLD_SLOT = Concat.of("HOLD_SLOT");
    private static final CharSequence CONDITION_KEY_BIND = Concat.of("KEY_BIND");

    private final KeybindSetting activateKey = new KeybindSetting("Activate Key", 0x80000000 | GLFW.GLFW_MOUSE_BUTTON_RIGHT);
    private final KeybindSetting placeKey = new KeybindSetting("Place Key", GLFW.GLFW_KEY_UNKNOWN);
    private final IntSetting switchDelay = new IntSetting("Switch Delay", 0, 0, 20);
    private final IntSetting glowstoneDelay = new IntSetting("Glowstone Delay", 0, 0, 20);
    private final IntSetting explodeDelay = new IntSetting("Explode Delay", 0, 0, 20);
    private final IntSetting totemSlot = new IntSetting("Totem Slot", 1, 1, 9);

    private final BoolSetting doubleAnchor = new BoolSetting("Double Anchor", false);
    private final ModeSetting doubleAnchorCondition = new ModeSetting("Double Condition", CONDITION_ALWAYS, CONDITION_ALWAYS, CONDITION_HOLD_SLOT, CONDITION_KEY_BIND);
    private final KeybindSetting doubleAnchorKey = new KeybindSetting("Double Anchor Key", GLFW.GLFW_KEY_UNKNOWN);
    private final IntSetting doubleAnchorCount = new IntSetting("Double Anchor Count", 2, 2, 5);

    private final BoolSetting switchBack = new BoolSetting("Switch Back", true);
    private final IntSetting switchBackDelay = new IntSetting("Switch Back Delay", 5, 0, 20);
    private final BoolSetting pauseOnKill = new BoolSetting("Pause On Kill", true);
    private final FloatSetting pauseTime = new FloatSetting("Pause Time", 2.0f, 0.5f, 10.0f, 0.5f);
    private final BoolSetting fastMode = new BoolSetting("Fast Mode", false);
    private final ModeSetting activation = new ModeSetting("Activation", ACTIVATION_HOLD, ACTIVATION_HOLD, ACTIVATION_CLICK);

    private int switchCounter;
    private int glowstoneCounter;
    private int explodeCounter;
    private int switchBackCounter;
    private int pauseCounter;
    private int chargeTimeout;
    private int currentDoubleAnchorCount;
    private boolean chargedGlowstone;
    private boolean exploded;
    private boolean needsSwitchBack;
    private boolean activateLatched;
    private boolean activateWasDown;
    private int latchWaitTicks;
    private BlockPos placedAnchorPos;
    private BlockPos fireBreakPending;
    private int fireBreakTicks;
    private int placeTimeout;
    private int noAimTicks;
    private final List<Player> deadPlayers = new ArrayList<>();
    private boolean selfUse;

    public AnchorMacro() {
        super(new FeatureInfo(
                Concat.of("Anchor Macro"),
                Concat.of("Automatically places and blows up respawn anchors"),
                Category.COMBAT
        ));
    }

    @Override
    public void onEnable() {
        super.onEnable();
        resetState();
    }

    @Override
    public void onDisable() {
        super.onDisable();
        resetState();
    }

    private void resetState() {
        switchCounter = 0;
        glowstoneCounter = 0;
        explodeCounter = 0;
        switchBackCounter = 0;
        pauseCounter = 0;
        chargeTimeout = 0;
        currentDoubleAnchorCount = 0;
        chargedGlowstone = false;
        exploded = false;
        needsSwitchBack = false;
        activateLatched = false;
        activateWasDown = false;
        latchWaitTicks = 0;
        placedAnchorPos = null;
        fireBreakPending = null;
        fireBreakTicks = 0;
        placeTimeout = 0;
        noAimTicks = 0;
        deadPlayers.clear();
    }

    private boolean shouldDoubleAnchor() {
        if (!doubleAnchor.getValue()) return false;

        if (doubleAnchorCondition.is(CONDITION_ALWAYS)) {
            return true;
        } else if (doubleAnchorCondition.is(CONDITION_HOLD_SLOT)) {
            int anchorSlot = -1;
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getItem(i).is(Items.RESPAWN_ANCHOR)) {
                    anchorSlot = i;
                    break;
                }
            }
            return anchorSlot != -1 && anchorSlot < mc.options.keyHotbarSlots.length
                    && mc.options.keyHotbarSlots[anchorSlot].isDown();
        } else if (doubleAnchorCondition.is(CONDITION_KEY_BIND)) {
            return InputHandler.isBindDown(doubleAnchorKey.getKey());
        }
        return false;
    }

    @EventSubscribe
    private void onTick(EventClientPlayerTick event) {
        if (isNull() || MinecraftCompat.getScreen() != null || mc.gameMode == null) return;

        if (pauseCounter > 0) {
            pauseCounter--;
            return;
        }

        if (pauseOnKill.getValue() && checkDeadPlayers()) {
            pauseCounter = (int) (pauseTime.getValue() * 20);
            return;
        }

        if (isEatingOrBlocking()) return;

        if (needsSwitchBack && switchBack.getValue()) {
            if (switchBackCounter < switchBackDelay.getValue()) {
                switchBackCounter++;
                return;
            }
            switchBackCounter = 0;
            swapTo(Items.RESPAWN_ANCHOR);
            needsSwitchBack = false;
            return;
        }

        boolean activateKeyDown = InputHandler.isBindDown(activateKey.getKey());
        boolean placeHeld = InputHandler.isBindDown(placeKey.getKey());

        boolean activateHeld;
        if (activation.is(ACTIVATION_CLICK)) {
            if (activateKeyDown && !activateWasDown) {
                activateLatched = true;
                latchWaitTicks = 0;
            }
            activateHeld = activateLatched;
        } else {
            activateHeld = activateKeyDown;
        }
        activateWasDown = activateKeyDown;

        if (!activateHeld && !placeHeld) {
            activateLatched = false;
            latchWaitTicks = 0;
            chargedGlowstone = false;
            exploded = false;
            needsSwitchBack = false;
            currentDoubleAnchorCount = 0;
            placedAnchorPos = null;
            placeTimeout = 0;
            return;
        }

        BlockPos targetPos = null;

        if (placeHeld && !activateHeld) {
            if (placedAnchorPos != null && exploded && currentDoubleAnchorCount == 0) {
                placedAnchorPos = null;
                placeTimeout = 0;
                chargedGlowstone = false;
                exploded = false;
            }

            if (placedAnchorPos == null) {
                if (!(mc.hitResult instanceof BlockHitResult hit)) return;
                BlockPos hitPos = hit.getBlockPos();

                if (mc.level.getBlockState(hitPos).is(Blocks.RESPAWN_ANCHOR)) {
                    placedAnchorPos = hitPos;
                    placeTimeout = 0;
                } else {
                    mc.options.keyUse.setDown(false);

                    mc.options.keyUse.consumeClick();
                    if (!mc.player.getMainHandItem().is(Items.RESPAWN_ANCHOR)) {
                        if (switchCounter < switchDelay.getValue()) {
                            switchCounter++;
                            return;
                        }
                        switchCounter = 0;
                        swapTo(Items.RESPAWN_ANCHOR);
                    }
                    BlockPos expectedPos = mc.level.getBlockState(hitPos).canBeReplaced() ? hitPos : hitPos.relative(hit.getDirection());
                    if (waitForFireClear(expectedPos)) return;
                    startUse();
                    placedAnchorPos = expectedPos;
                    placeTimeout = 0;
                    chargedGlowstone = false;
                    exploded = false;
                    return;
                }
            } else {
                if (!mc.level.getBlockState(placedAnchorPos).is(Blocks.RESPAWN_ANCHOR)) {
                    placeTimeout++;
                    if (placeTimeout > 10) {
                        placedAnchorPos = null;
                        placeTimeout = 0;
                    }
                    return;
                }
            }
            targetPos = placedAnchorPos;
        } else {

            BlockPos aimed = mc.hitResult instanceof BlockHitResult h
                    && mc.level.getBlockState(h.getBlockPos()).is(Blocks.RESPAWN_ANCHOR)
                    ? h.getBlockPos() : null;
            if (aimed != null) {
                placedAnchorPos = aimed;
                targetPos = aimed;
                latchWaitTicks = 0;
            } else if (placedAnchorPos != null
                    && mc.level.getBlockState(placedAnchorPos).is(Blocks.RESPAWN_ANCHOR)) {
                targetPos = placedAnchorPos;
            } else if (exploded && placedAnchorPos != null) {
                if (activation.is(ACTIVATION_CLICK)) {
                    activateLatched = false;
                }
                placedAnchorPos = null;
                placeTimeout = 0;
                chargedGlowstone = false;
                exploded = false;
                return;
            } else {
                if (placedAnchorPos != null) {
                    if (!exploded) {

                        if (++placeTimeout > 10) {
                            placedAnchorPos = null;
                            placeTimeout = 0;
                        }
                    }
                    return;
                }

                if (activation.is(ACTIVATION_CLICK) && ++latchWaitTicks > 20) {
                    activateLatched = false;
                    latchWaitTicks = 0;
                }

                if (keyUseIsActivateKey()) return;
                if (!(mc.hitResult instanceof BlockHitResult hit)) return;
                BlockPos hitPos = hit.getBlockPos();
                if (mc.level.getBlockState(hitPos).is(Blocks.RESPAWN_ANCHOR)) return;

                mc.options.keyUse.setDown(false);
                mc.options.keyUse.consumeClick();
                if (!mc.player.getMainHandItem().is(Items.RESPAWN_ANCHOR)) {
                    if (switchCounter < switchDelay.getValue()) {
                        switchCounter++;
                        return;
                    }
                    switchCounter = 0;
                    swapTo(Items.RESPAWN_ANCHOR);
                }
                BlockPos expectedPos = mc.level.getBlockState(hitPos).canBeReplaced() ? hitPos : hitPos.relative(hit.getDirection());
                if (waitForFireClear(expectedPos)) return;
                startUse();
                placedAnchorPos = expectedPos;
                placeTimeout = 0;
                latchWaitTicks = 0;
                chargedGlowstone = false;
                exploded = false;
                return;
            }
        }

        if (targetPos == null || !mc.level.getBlockState(targetPos).is(Blocks.RESPAWN_ANCHOR)) return;

        mc.options.keyUse.setDown(false);

        mc.options.keyUse.consumeClick();

        if (activation.is(ACTIVATION_CLICK)) {
            if (aimOnTarget(targetPos)) {
                noAimTicks = 0;
            } else if (++noAimTicks > 15) {

                activateLatched = false;
                latchWaitTicks = 0;
                chargedGlowstone = false;
                exploded = false;
                needsSwitchBack = false;
                currentDoubleAnchorCount = 0;
                placedAnchorPos = null;
                placeTimeout = 0;
                noAimTicks = 0;
                if (!mc.player.getMainHandItem().is(Items.RESPAWN_ANCHOR)) {
                    swapTo(Items.RESPAWN_ANCHOR);
                }
                return;
            }
        }

        int charges = mc.level.getBlockState(targetPos).getValue(RespawnAnchorBlock.CHARGE);

        if (charges == 0) {
            exploded = false;
            if (!chargedGlowstone) {
                if (!mc.player.getMainHandItem().is(Items.GLOWSTONE)) {
                    if (switchCounter < switchDelay.getValue()) {
                        switchCounter++;
                        return;
                    }
                    switchCounter = 0;
                    swapTo(Items.GLOWSTONE);
                }
                if (glowstoneCounter < glowstoneDelay.getValue()) {
                    glowstoneCounter++;
                    return;
                }
                glowstoneCounter = 0;

                if (aimOnTarget(targetPos)) {
                    startUse();
                    chargedGlowstone = true;
                    chargeTimeout = 0;
                }
            } else if (++chargeTimeout > 10) {
                chargedGlowstone = false;
                chargeTimeout = 0;
            }
        } else if (charges > 0 && !exploded) {
            chargedGlowstone = false;
            chargeTimeout = 0;
            if (shouldDoubleAnchor()) {
                int targetMax = doubleAnchorCount.getValue();
                int nextCount = currentDoubleAnchorCount + 1;

                if (nextCount < targetMax) {
                    if (!mc.player.getMainHandItem().is(Items.RESPAWN_ANCHOR)) {
                        if (switchCounter < switchDelay.getValue()) {
                            switchCounter++;
                            return;
                        }
                        switchCounter = 0;
                        swapTo(Items.RESPAWN_ANCHOR);
                    }
                    if (explodeCounter < explodeDelay.getValue()) {
                        explodeCounter++;
                        return;
                    }
                    explodeCounter = 0;
                    if (aimOnTarget(targetPos)) {
                        startUse();
                        startUse();
                    }
                    currentDoubleAnchorCount = nextCount;
                    exploded = true;
                } else {
                    int slot = totemSlot.getValue() - 1;
                    if (mc.player.getInventory().getSelectedSlot() != slot) {
                        if (switchCounter < switchDelay.getValue()) {
                            switchCounter++;
                            return;
                        }
                        switchCounter = 0;
                        InventoryUtils.setInvSlot(slot);
                    }
                    if (explodeCounter < explodeDelay.getValue()) {
                        explodeCounter++;
                        return;
                    }
                    explodeCounter = 0;
                    if (aimOnTarget(targetPos)) {
                        startUse();
                    }
                    exploded = true;
                    currentDoubleAnchorCount = 0;
                    if (switchBack.getValue()) {
                        needsSwitchBack = true;
                        switchBackCounter = 0;
                    }
                }
            } else {
                int slot = totemSlot.getValue() - 1;
                if (mc.player.getInventory().getSelectedSlot() != slot) {
                    if (switchCounter < switchDelay.getValue()) {
                        switchCounter++;
                        return;
                    }
                    switchCounter = 0;
                    InventoryUtils.setInvSlot(slot);
                }
                if (explodeCounter < explodeDelay.getValue()) {
                    explodeCounter++;
                    return;
                }
                explodeCounter = 0;
                if (aimOnTarget(targetPos)) {
                    startUse();
                }
                exploded = true;
                if (switchBack.getValue()) {
                    needsSwitchBack = true;
                    switchBackCounter = 0;
                }
            }
        }
    }

    @EventSubscribe
    private void onStartUseItem(EventStartUseItem.Pre event) {
        if (selfUse) return;
        if (event.getPlayer() != mc.player) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (MinecraftCompat.getScreen() != null || activateKey.isListening() || placeKey.isListening()) return;
        if (!keyUseIsActivateKey()) return;
        if (!InputHandler.isBindDown(activateKey.getKey()) && !activateLatched) return;
        if (!(event.getHitResult() instanceof BlockHitResult hit)) return;
        if (!mc.level.getBlockState(hit.getBlockPos()).is(Blocks.RESPAWN_ANCHOR)) return;

        // Vanilla would place the held anchor onto the anchor's face instead of charging it.
        event.cancel();
    }

    private void startUse() {
        selfUse = true;
        try {
            ((MinecraftAccessor) mc).invokeStartUseItem();
        } finally {
            selfUse = false;
        }
    }

    private boolean checkDeadPlayers() {
        for (Player p : mc.level.players()) {
            if (p != mc.player && (p.isDeadOrDying() || p.getHealth() <= 0) && !deadPlayers.contains(p)) {
                deadPlayers.add(p);
                return true;
            }
        }
        deadPlayers.removeIf(p -> !p.isDeadOrDying() && p.getHealth() > 0);
        return false;
    }

    private boolean isEatingOrBlocking() {
        boolean shield = mc.player.getMainHandItem().getItem() instanceof ShieldItem
                || mc.player.getOffhandItem().getItem() instanceof ShieldItem;
        boolean keyHeld = InputHandler.isBindDown(activateKey.getKey())
                || activateLatched
                || InputHandler.isBindDown(placeKey.getKey());
        return shield && keyHeld;
    }

    private boolean keyUseIsActivateKey() {
        int bind = activateKey.getKey();
        if (bind == -1) return false;
        InputConstants.Key useKey = ((KeyMappingAccessor) mc.options.keyUse).getKey();
        if (useKey == null) return false;
        int code = useKey.getType() == InputConstants.Type.MOUSE
                ? 0x80000000 | useKey.getValue()
                : useKey.getValue();
        return code == bind;
    }

    private void swapTo(Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) {
                InventoryUtils.setInvSlot(i);
                return;
            }
        }
    }

    private boolean waitForFireClear(BlockPos pos) {
        if (mc.level == null || pos == null) return false;
        BlockState state = mc.level.getBlockState(pos);
        if (!state.is(Blocks.FIRE) && !state.is(Blocks.SOUL_FIRE)) {
            fireBreakPending = null;
            fireBreakTicks = 0;
            return false;
        }
        if (!pos.equals(fireBreakPending)) {
            fireBreakPending = pos;
            fireBreakTicks = 0;
        }
        if (fireBreakTicks > 8) fireBreakTicks = 0;
        if (fireBreakTicks == 0) {
            mc.gameMode.startDestroyBlock(pos, Direction.UP);
            mc.player.swing(InteractionHand.MAIN_HAND);
        } else {
            mc.gameMode.continueDestroyBlock(pos, Direction.UP);
        }
        fireBreakTicks++;
        return true;
    }

    private boolean aimOnTarget(BlockPos pos) {
        if (mc.level == null || pos == null) return false;
        if (!mc.level.getBlockState(pos).is(Blocks.RESPAWN_ANCHOR)) return false;
        return mc.hitResult instanceof BlockHitResult h && h.getBlockPos().equals(pos);
    }
}
