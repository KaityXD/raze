package hack.raze.client.features.impl.utility;

import hack.raze.client.Raze;
import hack.raze.client.features.Category;
import hack.raze.client.features.Feature;
import hack.raze.client.features.FeatureInfo;
import hack.raze.client.features.settings.impl.BoolSetting;
import hack.raze.client.features.settings.impl.FloatSetting;
import hack.raze.client.features.settings.impl.ModeSetting;
import hack.raze.client.utils.strings.Concat;

/**
 * Global silent-rotation behaviour. The rotation engine reads this module when it
 * computes and returns rotations, so every combat module follows the same config.
 */
public class SilentRotation extends Feature {

    private static final CharSequence MODE_SILENT = Concat.of("Silent");
    private static final CharSequence MODE_CLIENT = Concat.of("Client");

    private final ModeSetting mode = new ModeSetting(
            Concat.of("Mode"),
            MODE_SILENT,
            MODE_SILENT,
            MODE_CLIENT
    ).describedBy(Concat.of("Silent keeps your camera still, Client turns the camera with the aim"));

    private final BoolSetting gcdFix = new BoolSetting(
            Concat.of("GCD Fix"), true
    ).describedBy(Concat.of("Snap rotations to the mouse sensitivity ladder (prevents AimModulo360)"));

    private final BoolSetting returnToView = new BoolSetting(
            Concat.of("Return To View"), true
    ).describedBy(Concat.of("Glide back to your camera rotation when the aim is released"));

    private final FloatSetting returnSpeed = new FloatSetting(
            Concat.of("Return Speed"), 150.0F, 10.0F, 600.0F, 10.0F,
            o -> returnToView.getValue()
    ).describedBy(Concat.of("Degrees per second used when returning to your view"));

    // Hardcoded: every aim always starts from the current camera rotation instead of
    // the last sent one. Kept as a constant (no setting) so it can never be turned off.
    private static final boolean START_FROM_CAMERA = true;

    private final FloatSetting speedMultiplier = new FloatSetting(
            Concat.of("Speed Multiplier"), 1.0F, 0.1F, 3.0F, 0.05F
    ).describedBy(Concat.of("Scales the aim speed of every module"));

    private final BoolSetting pitchLimit = new BoolSetting(
            Concat.of("Limit Pitch"), true
    ).describedBy(Concat.of("Clamp silent pitch to vanilla's -90..90 range"));

    public SilentRotation() {
        super(new FeatureInfo(
                Concat.of("Silent Rotation"),
                Concat.of("Configures how silent rotations are sent for every module"),
                Category.UTILITY
        ));
    }

    public boolean isSilent() {
        return mode.is(MODE_SILENT);
    }

    public boolean isClient() {
        return mode.is(MODE_CLIENT);
    }

    public boolean isGcdFix() {
        return gcdFix.getValue();
    }

    public boolean isReturnToView() {
        return returnToView.getValue();
    }

    public float getReturnSpeed() {
        return returnSpeed.getValue();
    }

    public boolean isStartFromCamera() {
        return START_FROM_CAMERA;
    }

    public float getSpeedMultiplier() {
        return speedMultiplier.getValue();
    }

    public boolean isLimitPitch() {
        return pitchLimit.getValue();
    }

    // -- static access used by the rotation engine --

    private static SilentRotation cached;

    public static SilentRotation get() {
        if (Raze.featureManager == null) return null;
        if (cached == null || !cached.isEnabled()) {
            SilentRotation found = Raze.featureManager.getFeatureByClass(SilentRotation.class);
            cached = found != null && found.isEnabled() ? found : null;
        }
        return cached;
    }

    public static boolean gcdFixEnabled() {
        SilentRotation sr = get();
        return sr == null || sr.isGcdFix();
    }

    public static boolean returnToViewEnabled() {
        SilentRotation sr = get();
        return sr == null || sr.isReturnToView();
    }

    public static float returnSpeedOr(float fallback) {
        SilentRotation sr = get();
        return sr == null ? fallback : sr.getReturnSpeed();
    }

    public static float speedMultiplier() {
        SilentRotation sr = get();
        return sr == null ? 1.0F : sr.getSpeedMultiplier();
    }

    public static boolean limitPitchEnabled() {
        SilentRotation sr = get();
        return sr == null || sr.isLimitPitch();
    }

    public static boolean startFromCameraEnabled() {
        return true;
    }
}
