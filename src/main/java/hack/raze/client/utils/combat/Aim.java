package hack.raze.client.utils.combat;

import hack.raze.client.utils.Imports;
import hack.raze.client.utils.rotation.RotationUtils;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class Aim implements Imports {

    private static final double DYNAMIC_NEAR = 1.2D;
    private static final double DYNAMIC_FAR = 2.6D;
    private static final double HEIGHT_OFFSET = 1.0D;

    private Aim() {}

    public enum Point {
        HEAD,
        CENTER,
        CLOSEST,
        DYNAMIC,
        RESPECT_CLOSEST
    }

    public static Point resolve(LivingEntity target, Point mode, float partialTick) {
        if (mode != Point.DYNAMIC || target == null || mc.player == null) return mode;

        AABB box = RotationUtils.getInterpolatedAABB(target, partialTick);
        Vec3 eye = mc.player.getEyePosition(partialTick);
        Vec3 center = box.getCenter();

        if (Math.abs(box.minY - mc.player.getY()) > HEIGHT_OFFSET) {
            return Point.CLOSEST;
        }

        double horizontal = Math.hypot(center.x - eye.x, center.z - eye.z);
        return horizontal >= DYNAMIC_FAR ? Point.CLOSEST : Point.CENTER;
    }

    public static Vec3 point(LivingEntity target, Point mode, float partialTick) {
        if (target == null) return null;
        AABB box = RotationUtils.getInterpolatedAABB(target, partialTick);
        Vec3 center = box.getCenter();
        Vec3 eye = mc.player != null ? mc.player.getEyePosition(partialTick) : center;
        Vec3 head = new Vec3(center.x, box.maxY - 0.1, center.z);

        if (mode == Point.DYNAMIC) {
            if (mc.player != null && Math.abs(box.minY - mc.player.getY()) > HEIGHT_OFFSET) {
                return closestPoint(eye, box);
            }

            double horizontal = Math.hypot(center.x - eye.x, center.z - eye.z);
            if (horizontal >= DYNAMIC_FAR) {
                return closestPoint(eye, box);
            }
            double t = Mth.clamp((horizontal - DYNAMIC_NEAR) / (DYNAMIC_FAR - DYNAMIC_NEAR), 0.0D, 1.0D);
            double chest = box.minY + (box.maxY - box.minY) * 0.6D;
            return new Vec3(center.x, chest + (head.y - chest) * t, center.z);
        }

        switch (mode) {
            case HEAD:
                return head;
            case CENTER:
                return center;
            case RESPECT_CLOSEST:
                return respectClosest(eye, box);
            case CLOSEST:
                return closestPoint(eye, box);
            default:
                return center;
        }
    }

    private static Vec3 respectClosest(Vec3 eye, AABB box) {
        // Open-respect clamp to hitbox boundaries
        double minX = Math.max(box.minX, Math.min(eye.x, box.maxX));
        double minY = Math.max(box.minY, Math.min(eye.y, box.maxY));
        double minZ = Math.max(box.minZ, Math.min(eye.z, box.maxZ));
        Vec3 closest = new Vec3(minX, minY, minZ);

        // Pull 10% toward center so the target point sits solidly inside the uninflated
        // hitbox, guaranteeing look rays cleanly intersect the front face on the server.
        return closest.lerp(box.getCenter(), 0.10D);
    }

    private static Vec3 closestPoint(Vec3 eye, AABB box) {
        double cx = Mth.clamp(eye.x, box.minX, box.maxX);
        double cy = Mth.clamp(eye.y, box.minY, box.maxY);
        double cz = Mth.clamp(eye.z, box.minZ, box.maxZ);
        return new Vec3(cx, cy, cz);
    }
}
