package hack.raze.client.mixin.entity;

import hack.raze.client.Raze;
import hack.raze.client.handlers.RotationHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public class MixinLivingEntityRenderer {

    @Unique private float raze$originalYaw;
    @Unique private float raze$originalYawO;
    @Unique private float raze$originalPitch;
    @Unique private float raze$originalPitchO;
    @Unique private float raze$originalHeadYaw;
    @Unique private float raze$originalHeadYawO;
    @Unique private float raze$originalBodyYaw;
    @Unique private float raze$originalBodyYawO;
    @Unique private boolean raze$swapped;

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void raze$applySilentRenderRotations(LivingEntity entity, LivingEntityRenderState state, float tickProgress, CallbackInfo ci) {
        if (Raze.isDestroyed) return;
        if (entity != Minecraft.getInstance().player) return;
        if (!RotationHandler.isHasSilentRotation()) return;

        raze$swapped = true;
        raze$originalYaw = entity.getYRot();
        raze$originalYawO = entity.yRotO;
        raze$originalPitch = entity.getXRot();
        raze$originalPitchO = entity.xRotO;
        raze$originalHeadYaw = entity.yHeadRot;
        raze$originalHeadYawO = entity.yHeadRotO;
        raze$originalBodyYaw = entity.yBodyRot;
        raze$originalBodyYawO = entity.yBodyRotO;

        entity.setYRot(RotationHandler.getRenderYaw());
        entity.yRotO = RotationHandler.getPrevRenderYaw();
        entity.setXRot(RotationHandler.getRenderPitch());
        entity.xRotO = RotationHandler.getPrevRenderPitch();
        entity.yHeadRot = RotationHandler.getRenderYaw();
        entity.yHeadRotO = RotationHandler.getPrevRenderYaw();
        entity.yBodyRot = RotationHandler.getRenderBodyYaw();
        entity.yBodyRotO = RotationHandler.getPrevRenderBodyYaw();
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void raze$restoreRenderRotations(LivingEntity entity, LivingEntityRenderState state, float tickProgress, CallbackInfo ci) {
        if (!raze$swapped) return;
        raze$swapped = false;

        entity.setYRot(raze$originalYaw);
        entity.yRotO = raze$originalYawO;
        entity.setXRot(raze$originalPitch);
        entity.xRotO = raze$originalPitchO;
        entity.yHeadRot = raze$originalHeadYaw;
        entity.yHeadRotO = raze$originalHeadYawO;
        entity.yBodyRot = raze$originalBodyYaw;
        entity.yBodyRotO = raze$originalBodyYawO;
    }
}
