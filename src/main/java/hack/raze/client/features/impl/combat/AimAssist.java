package hack.raze.client.features.impl.combat;

import hack.raze.client.api.ScreenCompat;
import hack.raze.client.event.EventSubscribe;
import hack.raze.client.event.impl.EventSystemUpdate;
import hack.raze.client.features.Category;
import hack.raze.client.features.Feature;
import hack.raze.client.features.FeatureInfo;
import hack.raze.client.features.settings.impl.BoolSetting;
import hack.raze.client.features.settings.impl.FloatSetting;
import hack.raze.client.features.settings.impl.ModeSetting;
import hack.raze.client.utils.combat.Aim;
import hack.raze.client.utils.player.PlayerUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public class AimAssist extends Feature {

    private final FloatSetting rg = new FloatSetting("Range", 4.5F, 3.0F, 6.0F, 0.1F);
    private final FloatSetting fv = new FloatSetting("FOV", 90.0F, 1.0F, 360.0F, 1.0F);
    private final FloatSetting sp = new FloatSetting("Speed", 3.0F, 0.1F, 15.0F, 0.1F);
    private final BoolSetting ph = new BoolSetting("Aim Pitch", true);
    private final BoolSetting mm = new BoolSetting("On Mouse Move", true);
    private final ModeSetting ap = new ModeSetting("Aim Point", "Dynamic", "Dynamic", "Head", "Center", "Closest");
    private final ModeSetting at = new ModeSetting("Aim Type", "Smooth", "Smooth", "Linear", "Lerp");
    private final BoolSetting mb = new BoolSetting("Mobs", false);

    private float ly;
    private float lp;
    private long lt = System.nanoTime();
    private float ft = 0.05F;
    private float residualYaw;
    private float residualPitch;
    private LivingEntity lastTarget;

    public AimAssist() {
        super(new FeatureInfo("AimAssist", "Smoothly helps you aim at enemies", Category.COMBAT));
    }

    @Override
    public void onEnable() {
        super.onEnable();
        lt = System.nanoTime();
        lastTarget = null;
        residualYaw = 0.0F;
        residualPitch = 0.0F;
        if (mc.player != null) {
            ly = mc.player.getYRot();
            lp = mc.player.getXRot();
        }
    }

    @EventSubscribe
    private void uf(EventSystemUpdate e) {
        if (!isEnabled() || isNull()) return;
        if (ScreenCompat.hasScreen(mc)) return;

        long n = System.nanoTime();
        ft = (n - lt) / 1.0E9F;
        if (ft > 0.1F) ft = 0.1F;
        if (ft < 0.001F) ft = 0.001F;
        lt = n;

        LocalPlayer p = mc.player;

        float cy = p.getYRot();
        float cp = p.getXRot();
        float dy = wr(cy - ly);
        float dp = cp - lp;
        ly = cy;
        lp = cp;

        LivingEntity t = fd(p);
        if (t == null) {
            lastTarget = null;
            resetResidual();
            return;
        }
        if (t != lastTarget) {
            lastTarget = t;
            resetResidual();
        }

        if (mm.getValue() && Math.hypot(dy, dp) < 1.0E-4) return;

        float[] r = rt(p, Aim.point(t, apm(), 1.0F));
        float yd = wr(r[0] - cy);
        float pd = r[1] - cp;
        float ad = (float) Math.hypot(yd, pd);

        float hf = fv.getValue() / 2.0F;
        if (ad > hf) {
            resetResidual();
            return;
        }

        double k = ad / hf;
        float ff = (float) (1.0 - (3.0 * k * k - 2.0 * k * k * k));

        float x = sp.getValue() * ff;
        float v = sp.getValue() * 0.67F * ff;

        float dh = 1.0F;
        float dv = 1.0F;
        double im = Math.hypot(dy, dp);
        if (im > 1.0E-4) {
            double tm = Math.hypot(yd, pd);
            if (tm > 0.001) {
                double ca = (dy * yd + dp * pd) / (im * tm);
                if (ca < 0.0) {
                    dh = (float) ((1.0 + ca) * 0.65 + 0.35);
                    dv = dh;
                }
            }
        }

        float sy;
        float sx;
        if (at.is("Linear")) {
            float mh = x * 45.0F * ft;
            float mv = v * 45.0F * ft;
            sy = Math.copySign(Math.min(Math.abs(yd), mh), yd) * dh;
            sx = Math.copySign(Math.min(Math.abs(pd), mv), pd) * dv;
        } else if (at.is("Lerp")) {
            float ay = Mth.clamp(sp.getValue() * ft, 0.0F, 1.0F);
            float av = Mth.clamp(sp.getValue() * 0.67F * ft, 0.0F, 1.0F);
            sy = yd * dh * ay;
            sx = pd * dv * av;
        } else {
            sy = yd * x * dh * ft;
            sx = pd * v * dv * ft;

            float mh = x * 45.0F * ft;
            float mv = v * 45.0F * ft;
            sy = Mth.clamp(sy, -mh, mh);
            sx = Mth.clamp(sx, -mv, mv);
        }

        float s = mc.options.sensitivity().get().floatValue() * 0.6F + 0.2F;
        float g = s * s * s * 1.2F;

        float fy = quantizeYaw(sy, g);
        float fx;
        if (ph.getValue()) {
            fx = quantizePitch(sx, g);
        } else {
            residualPitch = 0.0F;
            fx = 0.0F;
        }

        if (fy != 0.0F) {
            float ny = cy + fy;
            p.setYRot(ny);
            ly = ny;
        }
        if (ph.getValue() && fx != 0.0F) {
            float nx = Mth.clamp(cp + fx, -90.0F, 90.0F);
            p.setXRot(nx);
            lp = nx;
        }

        p.yRotO = p.getYRot();
        p.xRotO = p.getXRot();
    }

    private float quantizeYaw(float delta, float step) {
        residualYaw += delta;
        float snapped = Math.round(residualYaw / step) * step;
        residualYaw -= snapped;
        return snapped;
    }

    private float quantizePitch(float delta, float step) {
        residualPitch += delta;
        float snapped = Math.round(residualPitch / step) * step;
        residualPitch -= snapped;
        return snapped;
    }

    private void resetResidual() {
        residualYaw = 0.0F;
        residualPitch = 0.0F;
    }

    private LivingEntity fd(LocalPlayer p) {
        LivingEntity b = null;
        double bv = Double.MAX_VALUE;

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity l)) continue;
            if (e == p || !e.isAlive() || e.isSpectator() || e.isRemoved()) continue;
            if (l instanceof Player q) {
                if (q.isCreative()) continue;
                if (PlayerUtils.isFriend(q.getGameProfile().name())) continue;
            } else if (!mb.getValue()) {
                continue;
            }

            if (p.distanceTo(l) > rg.getValue()) continue;
            if (!pn(p, l)) continue;

            float[] r = rt(p, Aim.point(l, apm(), 1.0F));
            float ad = (float) Math.hypot(wr(r[0] - p.getYRot()), r[1] - p.getXRot());
            if (ad > fv.getValue() / 2.0F) continue;

            if (ad < bv) {
                bv = ad;
                b = l;
            }
        }
        return b;
    }

    private boolean pn(LocalPlayer p, LivingEntity l) {
        Vec3 a = p.getEyePosition();
        Vec3 b = l.getEyePosition();
        BlockHitResult h = mc.level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return h.getType() == HitResult.Type.MISS;
    }

    private Aim.Point apm() {
        if (ap.is("Head")) return Aim.Point.HEAD;
        if (ap.is("Center")) return Aim.Point.CENTER;
        if (ap.is("Closest")) return Aim.Point.CLOSEST;
        return Aim.Point.DYNAMIC;
    }

    private float[] rt(LocalPlayer p, Vec3 a) {
        double dx = a.x - p.getX();
        double dz = a.z - p.getZ();
        double d = a.y - (p.getY() + p.getEyeHeight());

        float y = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float x = (float) -Math.toDegrees(Math.atan2(d, Math.hypot(dx, dz)));
        return new float[]{y, x};
    }

    private float wr(float a) {
        return (((a % 360.0F) + 540.0F) % 360.0F) - 180.0F;
    }
}
