package hack.raze.client.features.impl.blatant;

import hack.raze.client.api.ScreenCompat;
import hack.raze.client.event.EventSubscribe;
import hack.raze.client.event.impl.EventHandleInput;
import hack.raze.client.event.impl.EventMove;
import hack.raze.client.event.impl.EventSystemUpdate;
import hack.raze.client.features.Category;
import hack.raze.client.features.Feature;
import hack.raze.client.features.FeatureInfo;
import hack.raze.client.features.settings.impl.FloatSetting;
import hack.raze.client.features.settings.impl.ModeSetting;
import hack.raze.client.handlers.RotationHandler;
import hack.raze.client.utils.combat.Aim;
import hack.raze.client.utils.player.PlayerUtils;
import hack.raze.client.utils.rotation.RotationUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public class KillAura extends Feature {

    private final FloatSetting lr = new FloatSetting("Aim Range", 4.5F, 1.0F, 8.0F, 0.1F, " blocks");
    private final FloatSetting wr = new FloatSetting("Wall Range", 0.0F, 0.0F, 8.0F, 0.1F, " blocks");
    private final FloatSetting ar = new FloatSetting("Range", 3.0F, 1.0F, 6.0F, 0.1F, " blocks");

    private final ModeSetting md = new ModeSetting("Mode", "Cooldown", "CPS", "Cooldown");
    private final FloatSetting cps = new FloatSetting("CPS", 8.0F, 0.0F, 20.0F, 0.5F, o -> md.is("CPS"));
    private final ModeSetting ap = new ModeSetting("Aim Point", "Respect Closed", "Respect Closed", "Respect Closest", "Dynamic", "Head", "Center", "Closest");
    private final FloatSetting sp = new FloatSetting("Aim Speed", 7.0F, 0.5F, 20.0F, 0.5F);

    private LivingEntity tg;
    private long lat;
    private double ndl;

    public KillAura() {
        super(new FeatureInfo("KillAura", "Automatically attacks nearby targets", Category.BLATANT));
    }

    @Override
    public void onEnable() {
        super.onEnable();
        lat = 0L;
        ndl = 0.0D;
        tg = null;
    }

    @Override
    public void onDisable() {
        super.onDisable();
        if (RotationUtils.isControlledBy(this)) {
            RotationUtils.tryTakeControl(this, EventSubscribe.Priority.HIGHEST);
            RotationUtils.stopTracking();
        }
        tg = null;
    }

    @EventSubscribe
    private void uf(EventSystemUpdate e) {
        if (!isEnabled() || isNull()) {
            tg = null;
            return;
        }
        if (ScreenCompat.hasScreen(mc)) {
            tg = null;
            return;
        }

        LocalPlayer p = mc.player;

        if (tg != null && !vl(p, tg, lr.getValue())) tg = null;
        if (tg == null) tg = fd(p);

        if (tg == null) {
            if (RotationUtils.isControlledBy(this)) {
                RotationUtils.cleanup(this, true, sp.getValue() * 15.0F, sp.getValue() * 15.0F,
                        RotationUtils.AimType.REGULAR, true);
            }
            return;
        }

        if (!RotationUtils.aim(this).silent().speed(sp.getValue() * 15.0F).to(Aim.point(tg, apm(), 1.0F))) {
            tg = null;
            return;
        }
    }


    @EventSubscribe
    private void onHandleInputTick(EventHandleInput.Early e) {
        if (!isEnabled() || isNull() || ScreenCompat.hasScreen(mc)) return;

        LocalPlayer p = mc.player;
        if (tg == null || !RotationUtils.isControlledBy(this)) return;

        if (!ck(p, tg)) return;
        if (!tm(p)) return;
        if (!am(p, tg)) return;

        at(p, tg);
        lat = System.currentTimeMillis();
        if (md.is("CPS")) ndl = nf();
    }

    @EventSubscribe
    private void mv(EventMove.Pre e) {
        if (!isEnabled()) return;
        if (!RotationHandler.isHasSilentRotation()) return;
        e.setYaw(RotationHandler.getServerYaw());
        e.setPitch(RotationHandler.getServerPitch());
    }

    private LivingEntity fd(LocalPlayer p) {
        LivingEntity b = null;
        double bv = Double.MAX_VALUE;

        for (Entity en : mc.level.entitiesForRendering()) {
            if (!(en instanceof LivingEntity l) || l == p) continue;
            if (!vl(p, l, lr.getValue())) continue;
            double d = p.distanceTo(l);
            if (d < bv) {
                bv = d;
                b = l;
            }
        }
        return b;
    }

    private boolean vl(LocalPlayer p, LivingEntity l, double max) {
        if (l == null || l == p || !l.isAlive() || l.isRemoved() || l.isSpectator()) return false;
        if (l instanceof Player q) {
            if (q.isCreative()) return false;
            if (PlayerUtils.isFriend(q.getGameProfile().name())) return false;
        } else return false;

        double d = p.distanceTo(l);
        if (d > max) return false;
        return d <= wr.getValue() || vis(p, l);
    }

    private double maxReach(LocalPlayer p) {
        double limit = p.isCreative() ? 5.0D : 2.95D;
        return Math.min((double) ar.getValue(), limit);
    }

    private boolean ck(LocalPlayer p, LivingEntity t) {
        if (t == null || !t.isAlive() || t.isRemoved()) return false;
        AABB box = t.getBoundingBox();
        double distToBox = Math.sqrt(box.distanceToSqr(p.getEyePosition()));
        if (distToBox > maxReach(p)) return false;
        return vis(p, t);
    }

    private boolean tm(LocalPlayer p) {
        if (md.is("Cooldown")) return p.getAttackStrengthScale(0.5F) >= 1.0F;
        return System.currentTimeMillis() - lat >= ndl;
    }

    private boolean am(LocalPlayer p, LivingEntity t) {
        if (!RotationUtils.hasSilentRotation()) return false;
        return rh(p, t);
    }

    private boolean rh(LocalPlayer p, LivingEntity t) {
        if (t == null || !t.isAlive() || t.isRemoved()) return false;
        Vec3 eye = p.getEyePosition();
        AABB box = t.getBoundingBox();
        double maxAllowed = maxReach(p);

        double distToBox = Math.sqrt(box.distanceToSqr(eye));
        if (distToBox > maxAllowed) return false;

        if (box.contains(eye)) return true;

        Vec3 look = RotationHandler.getRotationVector(RotationUtils.getSilentPitch(), RotationUtils.getSilentYaw());
        Vec3 end = eye.add(look.scale(maxAllowed));

        Optional<Vec3> hit = box.clip(eye, end);
        if (hit.isEmpty()) return false;

        double dist = eye.distanceTo(hit.get());
        if (dist > maxAllowed) return false;

        BlockHitResult block = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return block.getType() == HitResult.Type.MISS
                || block.getLocation().distanceToSqr(eye) >= hit.get().distanceToSqr(eye);
    }

    private void at(LocalPlayer p, LivingEntity t) {
        if (mc.gameMode == null) return;
        mc.gameMode.attack(p, t);
        p.swing(InteractionHand.MAIN_HAND);
    }

    private double nf() {
        double c = ThreadLocalRandom.current().nextDouble(cps.getValue() - 2.0D, cps.getValue());
        return 1000.0D / Math.max(0.5D, c);
    }

    private Aim.Point apm() {
        if (ap.is("Head")) return Aim.Point.HEAD;
        if (ap.is("Center")) return Aim.Point.CENTER;
        if (ap.is("Respect Closed") || ap.is("Respect Closest")) return Aim.Point.RESPECT_CLOSEST;
        if (ap.is("Closest")) return Aim.Point.CLOSEST;
        return Aim.Point.DYNAMIC;
    }

    private boolean vis(LocalPlayer p, LivingEntity l) {
        Vec3 a = p.getEyePosition();
        Vec3 b = l.getEyePosition();
        return mc.level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p))
                .getType() == HitResult.Type.MISS;
    }
}
