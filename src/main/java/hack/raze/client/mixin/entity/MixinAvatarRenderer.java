package hack.raze.client.mixin.entity;

import hack.raze.client.Raze;
import hack.raze.client.handlers.RotationHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CameraType;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public class MixinAvatarRenderer {

    // Third-person silent aim: the head points at the sent rotation while the
    // body keeps the vanilla tick simulation and interpolates like any other
    // LivingEntityRenderer state.
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void raze$applyServerHeadRotation(Avatar player, AvatarRenderState state, float tickProgress, CallbackInfo ci) {
        if (Raze.isDestroyed) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || player == null) return;
        if (player != mc.player) return;
        CameraType perspective = mc.options.getCameraType();
        if (perspective.isFirstPerson()) return;
        if (!RotationHandler.isHasSilentRotation()) return;

        RotationHandler.updateFrameRenderRotations();

        float bodyYaw = Mth.rotLerp(tickProgress, player.yBodyRotO, player.yBodyRot);
        state.bodyRot = bodyYaw;
        state.yRot = Mth.wrapDegrees(RotationHandler.getFrameRenderYaw() - bodyYaw);
        state.xRot = Mth.clamp(RotationHandler.getFrameRenderPitch(), -90.0f, 90.0f);
    }



}

