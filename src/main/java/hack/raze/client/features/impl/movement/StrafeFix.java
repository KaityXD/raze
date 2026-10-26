package hack.raze.client.features.impl.movement;

import hack.raze.client.event.EventSubscribe;
import hack.raze.client.event.impl.EventMotion;
import hack.raze.client.event.impl.EventMovementInput;
import hack.raze.client.features.Category;
import hack.raze.client.features.Feature;
import hack.raze.client.features.FeatureInfo;
import hack.raze.client.features.settings.impl.BoolSetting;
import hack.raze.client.features.settings.impl.ModeSetting;
import hack.raze.client.handlers.RotationHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

public class StrafeFix extends Feature {

    private final ModeSetting md = new ModeSetting("Mode", "Silent", "Silent", "Strict");
    private final BoolSetting sf = new BoolSetting("Sprint Fix", false);

    private boolean swapped;
    private float svy;
    private float svp;
    private float cr = 1.0F;
    private float sr;
    private boolean corrected;

    public StrafeFix() {
        super(new FeatureInfo("Strafe Fix", "Corrects your walk direction when looking around silently", Category.MOVEMENT));
    }

    @Override
    public void onDisable() {
        super.onDisable();
        if (swapped && mc.player != null) {
            mc.player.setYRot(svy);
            mc.player.setXRot(svp);
        }
        swapped = false;
        corrected = false;
        RotationHandler.setHasViewSwap(false);
    }

    @EventSubscribe
    private void tk(EventMotion.Pre e) {
        if (swapped && mc.player != null) {
            mc.player.setYRot(svy);
            mc.player.setXRot(svp);
        }
        swapped = false;
        corrected = false;
        RotationHandler.setHasViewSwap(false);

        if (!isEnabled() || isNull()) return;
        if (!RotationHandler.isHasSilentRotation()) return;

        LocalPlayer p = mc.player;

        float dy = Mth.wrapDegrees(RotationHandler.getServerYaw() - p.getYRot());
        float dx = Mth.clamp(RotationHandler.getServerPitch(), -90.0F, 90.0F) - p.getXRot();
        if (Math.abs(dy) < 0.05F && Math.abs(dx) < 0.05F) return;

        if (!sf.getValue() && p.isSprinting()) {
            float f = (mc.options.keyUp.isDown() ? 1.0F : 0.0F) - (mc.options.keyDown.isDown() ? 1.0F : 0.0F);
            float st = (mc.options.keyLeft.isDown() ? 1.0F : 0.0F) - (mc.options.keyRight.isDown() ? 1.0F : 0.0F);
            if (f != 0.0F || st != 0.0F) {
                double ang = Math.toDegrees(Math.atan2(-st, f));
                if (Math.abs(ang) > 35.0F) p.setSprinting(false);
            }
        }

        svy = p.getYRot();
        svp = p.getXRot();
        swapped = true;
        p.setYRot(RotationHandler.getServerYaw());
        p.setXRot(Mth.clamp(RotationHandler.getServerPitch(), -90.0F, 90.0F));
        RotationHandler.setViewSwapOrigYaw(svy);
        RotationHandler.setViewSwapOrigPitch(svp);
        RotationHandler.setHasViewSwap(true);

        if (md.is("Silent")) {
            float r = (float) Math.toRadians(-dy);
            cr = Mth.cos(r);
            sr = Mth.sin(r);
            corrected = true;
        }
    }

    @EventSubscribe
    private void ip(EventMovementInput e) {
        if (!isEnabled() || !corrected) return;

        float x = (e.left ? 1.0F : 0.0F) - (e.right ? 1.0F : 0.0F);
        float z = (e.forward ? 1.0F : 0.0F) - (e.back ? 1.0F : 0.0F);

        int s = Math.round(x * cr - z * sr);
        int f = Math.round(z * cr + x * sr);

        e.forward = f > 0;
        e.back = f < 0;
        e.left = s > 0;
        e.right = s < 0;
    }

    @EventSubscribe
    private void rp(EventMotion.Post e) {
        if (swapped && mc.player != null) {
            mc.player.setYRot(svy);
            mc.player.setXRot(svp);
            swapped = false;
            RotationHandler.setHasViewSwap(false);
        }
    }
}
