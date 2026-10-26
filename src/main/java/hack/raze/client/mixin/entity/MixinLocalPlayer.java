package hack.raze.client.mixin.entity;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.authlib.GameProfile;
import hack.raze.client.Raze;
import hack.raze.client.auth.MathProt;
import hack.raze.client.event.impl.*;
import hack.raze.client.handlers.RotationHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class MixinLocalPlayer extends AbstractClientPlayer {
    @Shadow
    @Final
    public ClientPacketListener connection;
    @Shadow
    private double xLast;
    @Shadow
    private double yLast;
    @Shadow
    private double zLast;
    @Shadow
    private float yRotLast;
    @Shadow
    private float xRotLast;
    @Shadow
    private boolean lastOnGround;
    @Shadow
    private boolean lastHorizontalCollision;
    @Shadow
    private boolean crouching;
    @Shadow
    private int positionReminder;
    @Shadow
    @Final
    protected Minecraft minecraft;
    @Shadow
    private boolean autoJumpEnabled = true;

    public MixinLocalPlayer(ClientLevel world, GameProfile profile) {
        super(world, profile);
    }

    @Shadow
    protected abstract boolean isControlledCamera();
    @Shadow
    protected abstract void sendIsSprintingIfNeeded();

    @Unique
    private EventMove raze$currentMoveEvent;

    @Unique
    private boolean raze$renderAdvanced;

    @Inject(method = "sendPosition", at = @At("HEAD"), cancellable = true)
    private void raze$onSendMovementPacketsHead(CallbackInfo ci) {
        if (Raze.isDestroyed) return;
        float enforcedPitch = MathProt.getEnforcedPitch(this.getXRot());
        // Update server rotations every tick to keep them in sync (prevents AimModulo360 flags I think)
        if (!RotationHandler.isHasSilentRotation()) {
            RotationHandler.updateServerRotations(this.getYRot(), enforcedPitch);
        }
        
        EventMove event = new EventMove.Pre(this.getX(), this.getY(), this.getZ(), this.onGround(), this.getYRot(), enforcedPitch);
        event.call();
        if (event.cancelled) {
            this.raze$currentMoveEvent = null;
            ci.cancel();
            return;
        }
        this.raze$currentMoveEvent = event;
    }

    @Inject(method = "sendPosition", at = @At("RETURN"))
    private void raze$onSendMovementPacketsReturn(CallbackInfo ci) {
        if (Raze.isDestroyed) return;
        EventMove postEvent = new EventMove.Post(this.getX(), this.getY(), this.getZ(), this.onGround(), this.getYRot(), this.getXRot());
        postEvent.call();
        this.raze$currentMoveEvent = null;

        raze$syncSilentRenderRotations((LocalPlayer) (Object) this, true);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void raze$onTickReturn(CallbackInfo ci) {
        if (Raze.isDestroyed) return;
        raze$syncSilentRenderRotations((LocalPlayer) (Object) this, false);
    }

    @Unique
    private void raze$syncSilentRenderRotations(LocalPlayer player, boolean advance) {
        if (!RotationHandler.isHasSilentRotation()) {
            RotationHandler.clearRenderBodyYaw();
            return;
        }
        if (advance || !raze$renderAdvanced) {
            RotationHandler.updateRenderRotations(player);
            raze$renderAdvanced = true;
        }
        player.yHeadRot = RotationHandler.getServerYaw();
        player.yBodyRot = RotationHandler.getRenderBodyYaw();
    }

    @ModifyExpressionValue(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getX()D"))
    private double raze$useEventX(double original) {
        if (Raze.isDestroyed) return original;
        return this.raze$currentMoveEvent != null ? this.raze$currentMoveEvent.getX() : original;
    }

    @ModifyExpressionValue(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getY()D"))
    private double raze$useEventY(double original) {
        if (Raze.isDestroyed) return original;
        return this.raze$currentMoveEvent != null ? this.raze$currentMoveEvent.getY() : original;
    }

    @ModifyExpressionValue(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getZ()D"))
    private double raze$useEventZ(double original) {
        if (Raze.isDestroyed) return original;
        return this.raze$currentMoveEvent != null ? this.raze$currentMoveEvent.getZ() : original;
    }

    @ModifyExpressionValue(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getYRot()F"))
    private float raze$useEventYaw(float original) {
        if (Raze.isDestroyed) return original;
        return this.raze$currentMoveEvent != null ? (float) this.raze$currentMoveEvent.getYaw() : original;
    }

    @ModifyExpressionValue(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getXRot()F"))
    private float raze$useEventPitch(float original) {
        if (Raze.isDestroyed) return original;
        float pitchValue = this.raze$currentMoveEvent != null ? (float) this.raze$currentMoveEvent.getPitch() : original;
        return MathProt.getEnforcedPitch(pitchValue);
    }

    @ModifyExpressionValue(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;onGround()Z"))
    private boolean raze$useEventOnGround(boolean original) {
        if (Raze.isDestroyed) return original;
        return this.raze$currentMoveEvent != null ? this.raze$currentMoveEvent.isOnGround() : original;
    }

    @ModifyExpressionValue(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;position()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 raze$useEventEntityPos(Vec3 original) {
        if (Raze.isDestroyed) return original;
        if (this.raze$currentMoveEvent != null) {
            return new Vec3(this.raze$currentMoveEvent.getX(), this.raze$currentMoveEvent.getY(), this.raze$currentMoveEvent.getZ());
        }
        return original;
    }



    @Inject(method = "swing", at = @At("HEAD"), cancellable = true)
    private void onSwingHand(InteractionHand hand, CallbackInfo ci) {
        if (Raze.isDestroyed) return;
        LocalPlayer player = (LocalPlayer) (Object) this;
        EventSwingHand swingHand = new EventSwingHand(player, hand);
        swingHand.call();
        if (swingHand.cancelled) {
            ci.cancel();
        }
    }

    // Totem pop chams
    @Inject(method = "tick", at = @At("HEAD"))
    public void tick(CallbackInfo ci) {
        if (Raze.isDestroyed) return;
        raze$renderAdvanced = false;
        EventClientPlayerTick e = new EventClientPlayerTick();
        e.call();
        if (e.cancelled) {
            ci.cancel();
        }
    }

    @Inject(method = "aiStep", at = @At("HEAD"))
    public void raze$onMotionPre(CallbackInfo ci) {
        if (Raze.isDestroyed) return;
        float enforced = MathProt.getEnforcedPitch(this.getXRot());
        EventMotion event = new EventMotion.Pre(this.getX(), this.getY(), this.getZ(), this.onGround(), this.getYRot(), enforced);
        event.call();
    }

    @Inject(method = "aiStep", at = @At("RETURN"))
    public void raze$onMotionPost(CallbackInfo ci) {
        if (Raze.isDestroyed) return;
        EventMotion event = new EventMotion.Post(this.getX(), this.getY(), this.getZ(), this.onGround(), this.getYRot(), this.getXRot());
        event.call();
    }

    @ModifyExpressionValue(
        method = "aiStep",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isSlowDueToUsingItem()Z"
        )
    )
    private boolean raze$noSlow(boolean original) {
        if (Raze.isDestroyed) return original;
        EventSlowdown event = new EventSlowdown(1.0f, original, false, false);
        event.call();
        if (event.cancelled) {
            return false;
        }
        return event.isSlowDueToItem();
    }

    @ModifyExpressionValue(
        method = "modifyInput",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;itemUseSpeedMultiplier()F"
        )
    )
    private float raze$noSlowItemUseSpeedMultiplier(float original) {
        if (Raze.isDestroyed) return original;
        EventSlowdown event = new EventSlowdown(original, false, false, false);
        event.call();
        if (event.cancelled) {
            return 1.0f;
        }
        return event.getSlowdownMultiplier();
    }

    @ModifyExpressionValue(
        method = "modifyInput",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isMovingSlowly()Z"
        )
    )
    private boolean raze$noSlowIsMovingSlowly(boolean original) {
        if (Raze.isDestroyed) return original;
        EventSlowdown event = new EventSlowdown(1.0f, false, original, false);
        event.call();
        if (event.cancelled) {
            return false;
        }
        return event.isMovingSlowly();
    }

    @Redirect(
        method = "aiStep",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isPassenger()Z",
            ordinal = 1
        )
    )
    private boolean raze$noSlowIsPassengerAiStep(LocalPlayer instance) {
        if (Raze.isDestroyed) return instance.isPassenger();
        if (instance.isPassenger()) {
            return true;
        }
        EventSlowdown event = new EventSlowdown(1.0f, false, false, false);
        event.call();
        return event.isSpoofPassenger();
    }

    @Redirect(
        method = "modifyInput",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isPassenger()Z"
        )
    )
    private boolean raze$noSlowIsPassengerModifyInput(LocalPlayer instance) {
        if (Raze.isDestroyed) return instance.isPassenger();
        if (instance.isPassenger()) {
            return true;
        }
        EventSlowdown event = new EventSlowdown(1.0f, false, false, false);
        event.call();
        return event.isSpoofPassenger();
    }

    @Inject(method = "move",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/player/AbstractClientPlayer;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"),
            cancellable = true)
    private void raze$onMoveHook(MoverType moverType, Vec3 vec3, CallbackInfo ci) {
        if (Raze.isDestroyed) return;
        if (Raze.featureManager == null) return;
        
        EventMovePos event = new EventMovePos(moverType, vec3.x, vec3.y, vec3.z);
        event.call();
        
        if (event.cancelled) {
            ci.cancel();
        }
    }

    // applyInput runs inside aiStep, i.e. inside the window where features like
    // StrafeFix replace the player rotation with the silent server rotation. Its
    // yBob/xBob EMWA feeds the first-person hand wobble in ItemInHandRenderer, so
    // without this shield the held item visibly tilts toward the silent rotation.
    // Restore the camera rotation around it, then re-apply the swap.
    @Inject(method = "applyInput", at = @At("HEAD"))
    private void raze$applyInputRestoreCamera(CallbackInfo ci) {
        if (Raze.isDestroyed) return;
        if (!RotationHandler.isHasViewSwap() || !RotationHandler.isHasSilentRotation()) return;
        this.setYRot(RotationHandler.getViewSwapOrigYaw());
        this.setXRot(RotationHandler.getViewSwapOrigPitch());
    }

    @Inject(method = "applyInput", at = @At("RETURN"))
    private void raze$applyInputReapplySwap(CallbackInfo ci) {
        if (Raze.isDestroyed) return;
        if (!RotationHandler.isHasViewSwap() || !RotationHandler.isHasSilentRotation()) return;
        this.setYRot(RotationHandler.getServerYaw());
        this.setXRot(Mth.clamp(RotationHandler.getServerPitch(), -90.0F, 90.0F));
    }
}
