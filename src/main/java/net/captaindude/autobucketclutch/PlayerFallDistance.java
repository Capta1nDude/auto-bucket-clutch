package net.captaindude.autobucketclutch;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public final class PlayerFallDistance {

    private static final Map<Player, FallState> FALL_STATES = new WeakHashMap<>();

    private PlayerFallDistance() {
    }

    public static float get(Player player) {
        if (player == null) {
            return 0.0f;
        }

        FallState state = FALL_STATES.computeIfAbsent(player, p -> new FallState(p.level(), p.getY()));
        Level world = player.level();

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

    public static void reset(Player player) {
        if (player != null) {
            FALL_STATES.remove(player);
        }
    }

    private static boolean shouldReset(Player player) {
        return player.onGround()
                || player.isInWater()
                || player.onClimbable()
                || player.isPassenger()
                || player.getAbilities().flying;
    }

    private static final class FallState {
        private Level world;
        private double lastY;
        private float fallDistance;

        private FallState(Level world, double lastY) {
            reset(world, lastY);
        }

        private void reset(Level world, double lastY) {
            this.world = world;
            this.lastY = lastY;
            this.fallDistance = 0.0f;
        }
    }
}
