package hack.raze.client.features.impl.utility;

import hack.raze.client.event.EventSubscribe;
import hack.raze.client.event.impl.EventClientPlayerTick;
import hack.raze.client.features.Category;
import hack.raze.client.features.Feature;
import hack.raze.client.features.FeatureInfo;
import hack.raze.client.features.settings.impl.IntSetting;
import hack.raze.client.utils.strings.Concat;

public class JumpReset extends Feature {

    private final IntSetting d = new IntSetting(Concat.of("Delay"), 70, 0, 100, " ms");

    private int t = -1;
    private int c;
    private boolean q;

    public JumpReset() {
        super(new FeatureInfo(
                Concat.of("Jump Reset"),
                Concat.of("Jumps after being hit to reduce knockback"),
                Category.UTILITY
        ));
    }

    @Override
    public void onEnable() {
        super.onEnable();
        r();
    }

    @Override
    public void onDisable() {
        super.onDisable();
        r();
    }

    private void r() {
        t = -1;
        c = 0;
        q = false;
    }

    @EventSubscribe
    private void onTick(EventClientPlayerTick e) {
        if (isNull()) return;
        if (q) {
            mc.player.setJumping(false);
            q = false;
        }
        h();
        j();
    }

    private void h() {
        boolean f = mc.player.hurtTime == mc.player.hurtDuration && mc.player.hurtTime > 0;
        if (f && c <= 0) {
            s();
            c = mc.player.hurtDuration;
        }
        if (c > 0) c--;
    }

    private void j() {
        if (t < 0) return;
        int e = mc.player.tickCount - t;
        if (e > 2) { t = -1; return; }
        if (e < k()) return;
        p();
        t = -1;
    }

    private int k() {
        int n = 50 + Math.round(d.getValue() / 100.0F * 50.0F);
        return n >= 100 ? 2 : 1;
    }

    private void s() {
        if (isNull()) return;
        t = mc.player.tickCount;
    }

    private void p() {
        if (isNull()) return;
        if (!mc.player.onGround()) return;
        mc.player.setJumping(true);
        q = true;
    }
}
