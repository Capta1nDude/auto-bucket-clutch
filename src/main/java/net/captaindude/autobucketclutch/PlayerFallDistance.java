package net.captaindude.autobucketclutch;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;

public final class PlayerFallDistance {

    private static final Map<PlayerEntity, FallState> FALL_STATES = new WeakHashMap<>();

    private PlayerFallDistance() {
    }

    public static float get(PlayerEntity player) {
        if (player == null) {
            return 0.0f;
        }

        FallState state = FALL_STATES.computeIfAbsent(player, p -> new FallState(p.getWorld(), p.getY()));
        World world = player.getWorld();

        if (state.world != world || shouldReset(player)) {
            state.reset(world, player.getY());
            return 0.0f;
        }

        double currentY = player.getY();
        double deltaY = currentY - state.lastY;
        state.lastY = currentY;

        if (deltaY < 0.0) {
            state.fallDistance += (float) -deltaY;
        } else if (deltaY > 0.0) {
            state.fallDistance = 0.0f;
        }

        return state.fallDistance;
    }

    public static void reset(PlayerEntity player) {
        if (player != null) {
            FALL_STATES.remove(player);
        }
    }

    private static boolean shouldReset(PlayerEntity player) {
        return player.isOnGround()
                || player.isTouchingWater()
                || player.isClimbing()
                || player.hasVehicle()
                || player.getAbilities().flying;
    }

    private static final class FallState {
        private World world;
        private double lastY;
        private float fallDistance;

        private FallState(World world, double lastY) {
            reset(world, lastY);
        }

        private void reset(World world, double lastY) {
            this.world = world;
            this.lastY = lastY;
            this.fallDistance = 0.0f;
        }
    }
}
