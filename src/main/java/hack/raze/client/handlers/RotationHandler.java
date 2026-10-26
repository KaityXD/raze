package hack.raze.client.handlers;

import hack.raze.client.auth.MathProt;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Yes this is important.
 * Likely not finished.
 *
 * Entity rotation vectors and related hooks.
 *
 * @see MixinEntity#getRotationVec(float) Entity raycasting and targeting
 * @see MixinEntity#getRotationVector() Item interactions
 * @see MixinEntity#getHeadRotationVector() Head-based interactions
 * @see MixinEntity#updateVelocity() Movement input to velocity conversion (MoveFix)
 *
 * @see MixinPlayer#causeExtraKnockback() Attack knockback direction
 * @see MixinPlayer#doSweepAttack() Sweep attack direction
 * @see MixinPlayer#redirectSwimmingLookVector() Swimming travel look vector
 *
 * @see MixinLivingEntity#jump() Sprint jump direction
 * @see MixinLivingEntity#calcGlidingVelocity() Elytra flying pitch and direction
 *
 * @see MixinFireworkRocketEntity#tick() Firework rocket boost direction when elytra flying
 *
 * @see MultiPlayerGameMode#interactItem() Sends PlayerInteractItemC2SPacket with silent yaw/pitch
 * @see MixinItem#raycast() Block interaction range and item raycasting (buckets, pearls, etc.)
 * 
 * @see hack.raze.client.event.impl.EventMovementInput Movement input correction
 */
public class RotationHandler {
    @Getter
    @Setter
    private static float serverYaw = 0f;

    @Getter
    @Setter
    private static float serverPitch = 0f;

    @Getter
    private static float prevServerYaw = 0f;

    @Getter
    private static float prevServerPitch = 0f;

    @Getter
    @Setter
    private static float serverDeltaYaw = 0f;

    @Getter
    @Setter
    private static boolean hasSilentRotation = false;

    // View-swap state, set by a feature that replaces the player's actual rotation with
    // the server rotation for part of the tick (e.g. StrafeFix during aiStep). Used by
    // MixinLocalPlayer to shield vanilla view-driven state (yBob/xBob, which drives the
    // first-person hand wobble in ItemInHandRenderer) from the silent rotation.
    @Getter
    @Setter
    private static boolean hasViewSwap = false;
    @Getter
    @Setter
    private static float viewSwapOrigYaw = 0f;
    @Getter
    @Setter
    private static float viewSwapOrigPitch = 0f;

    // Third-person preview rotations, advanced once per tick like vanilla.
    // The body uses the vanilla tickHeadTurn simulation and is interpolated at
    // render time with Mth.rotLerp, instead of being eased per frame.
    @Getter
    private static float renderYaw;
    @Getter
    private static float prevRenderYaw;
    @Getter
    private static float renderPitch;
    @Getter
    private static float prevRenderPitch;
    @Getter
    private static float renderBodyYaw;
    @Getter
    private static float prevRenderBodyYaw;
    private static boolean renderInitialized;
    private static boolean renderBodyYawActive;

    // Credit to Nami Client for helping me figure this out (the weird AimModulo360
    // flag)

    /**
     * Update server rotations (called every tick to keep them in sync).
     * This prevents the AimModulo360 flag by ensuring continuity.
     */
    public static void updateServerRotations(float yaw, float pitch) {
        // Store previous values for interpolation
        prevServerYaw = serverYaw;
        prevServerPitch = serverPitch;
        // Normalize yaw to maintain continuity relative to previous serverYaw
        serverYaw = serverYaw + Mth.wrapDegrees(yaw - serverYaw);
        float enforcedPitch = MathProt.getEnforcedPitch(pitch);
        serverPitch = Mth.clamp(enforcedPitch, -90f, 90f);
        serverDeltaYaw = 0f;
    }

    /**
     * Set server rotations with delta tracking for silent aim.
     * Calculates the yaw delta from the last server yaw to prevent flags.
     */
    public static void setServerRotationsWithDelta(float yaw, float pitch) {
        // Store previous values for interpolation
        prevServerYaw = serverYaw;
        prevServerPitch = serverPitch;
        // Normalize yaw to maintain continuity relative to previous serverYaw
        float wrappedDelta = Mth.wrapDegrees(yaw - serverYaw);
        serverDeltaYaw = wrappedDelta;
        serverYaw = yaw;
        float enforcedPitch = MathProt.getEnforcedPitch(pitch);
        serverPitch = Mth.clamp(enforcedPitch, -90f, 90f);
    }

    /**
     * Advance the third-person preview once per tick, from the local player's tick.
     * The head eases toward the sent rotation, and the body runs the same simulation
     * as vanilla {@code LivingEntity.tickHeadTurn}: keep the current body yaw while
     * idle, face the travel direction while moving, snap to the aim while swinging,
     * and clamp how far the head may lead the body.
     */
    public static void updateRenderRotations(LocalPlayer player) {
        if (!renderInitialized) {
            renderYaw = player.getYRot();
            prevRenderYaw = player.yRotO;
            renderPitch = player.getXRot();
            prevRenderPitch = player.xRotO;
            renderInitialized = true;
        } else {
            prevRenderYaw = renderYaw;
            prevRenderPitch = renderPitch;
            renderYaw += Mth.wrapDegrees(serverYaw - renderYaw) * 0.4F;
            renderPitch += (serverPitch - renderPitch) * 0.4F;
        }

        updateRenderBodyYaw(player, serverYaw);
    }

    public static void updateRenderBodyYaw(LocalPlayer player, float aimYaw) {
        if (!renderBodyYawActive) {
            renderBodyYaw = player.yBodyRot;
            prevRenderBodyYaw = player.yBodyRotO;
            renderBodyYawActive = true;
        } else {
            prevRenderBodyYaw = renderBodyYaw;
        }

        double dx = player.getX() - player.xo;
        double dz = player.getZ() - player.zo;
        float targetBodyYaw = renderBodyYaw;

        if (dx * dx + dz * dz > 0.0025D) {
            float moveYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
            float viewDiff = Math.abs(Mth.wrapDegrees(aimYaw - moveYaw));
            targetBodyYaw = (viewDiff > 95.0F && viewDiff < 265.0F) ? moveYaw - 180.0F : moveYaw;
        }
        if (player.attackAnim > 0.0F) {
            targetBodyYaw = aimYaw;
        }

        renderBodyYaw += Mth.wrapDegrees(targetBodyYaw - renderBodyYaw) * 0.3F;

        float headDelta = Mth.wrapDegrees(aimYaw - renderBodyYaw);
        headDelta = Mth.clamp(headDelta, -75.0F, 75.0F);
        renderBodyYaw = aimYaw - headDelta;
        if (headDelta * headDelta > 2500.0F) {
            renderBodyYaw += headDelta * 0.2F;
        }

        while (renderBodyYaw - prevRenderBodyYaw < -180.0F) prevRenderBodyYaw -= 360.0F;
        while (renderBodyYaw - prevRenderBodyYaw >= 180.0F) prevRenderBodyYaw += 360.0F;
    }

    // Per-frame third-person head smoothing. Advanced while rendering (so it runs at
    // frame rate, not tick rate) using a frame-rate independent exponential factor.
    // A handful of float ops per frame -- no measurable FPS cost.
    private static final float FRAME_SMOOTHING = 30.0F;
    private static final long FRAME_STALE_NANOS = 250_000_000L;
    private static float frameYaw;
    private static float framePitch;
    private static long frameNanos;
    private static boolean frameInit;

    public static void updateFrameRenderRotations() {
        long now = System.nanoTime();
        if (!frameInit || now - frameNanos > FRAME_STALE_NANOS) {
            frameYaw = serverYaw;
            framePitch = serverPitch;
            frameNanos = now;
            frameInit = true;
            return;
        }
        float dt = (now - frameNanos) / 1.0E9F;
        frameNanos = now;
        if (dt <= 0.0F) return;
        if (dt > 0.1F) dt = 0.1F;
        float alpha = 1.0F - (float) Math.exp(-FRAME_SMOOTHING * dt);
        frameYaw += Mth.wrapDegrees(serverYaw - frameYaw) * alpha;
        framePitch += (serverPitch - framePitch) * alpha;
    }

    public static float getFrameRenderYaw() {
        return frameYaw;
    }

    public static float getFrameRenderPitch() {
        return framePitch;
    }

    public static void clearRenderBodyYaw() {
        renderBodyYawActive = false;
        renderBodyYaw = 0.0F;
        prevRenderBodyYaw = 0.0F;
        renderInitialized = false;
    }

    public static void reset() {
        serverDeltaYaw = 0f;
        hasSilentRotation = false;
        renderInitialized = false;
        frameInit = false;
        clearRenderBodyYaw();
    }

    public static void syncToPlayer(float playerYaw, float playerPitch) {
        // Store previous values for interpolation
        prevServerYaw = serverYaw;
        prevServerPitch = serverPitch;
        // Normalize yaw to maintain continuity relative to previous serverYaw
        serverYaw = serverYaw + Mth.wrapDegrees(playerYaw - serverYaw);
        float enforcedPitch = MathProt.getEnforcedPitch(playerPitch);
        serverPitch = Mth.clamp(enforcedPitch, -90f, 90f);
        serverDeltaYaw = 0f;
        hasSilentRotation = false;
        renderInitialized = false;
        clearRenderBodyYaw();
    }

    public static Vec3 getRotationVector(float pitch, float yaw) {
        float pitchRad = pitch * Mth.DEG_TO_RAD;
        float yawRad = -yaw * Mth.DEG_TO_RAD;
        float cosPitch = Mth.cos(pitchRad);
        float sinPitch = Mth.sin(pitchRad);
        float cosYaw = Mth.cos(yawRad);
        float sinYaw = Mth.sin(yawRad);
        return new Vec3(sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
    }
}
