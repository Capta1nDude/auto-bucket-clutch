package net.captaindude.autobucketclutch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.lwjgl.glfw.GLFW;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class AutoBucketClutchClient implements ClientModInitializer {
    private static KeyBinding swapBucketKey;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("autobucketclutch.json");

    private static BlockPos lastWaterPos = null; // where water should be (source block)

    private static boolean isAimingOverride = false;
    private static float storedYaw = 0f;
    private static float storedPitch = 0f;

    private static final class ModConfig {
        boolean enabled = false;
    }

    private static KeyBinding toggleAutoClutchKey;

    // State for whether the auto-clutch feature is enabled or not, toggled by the
    // user with the keybind
    private static boolean isEnabled = false;

    // Simple throttles so we don't spam packets every tick
    private static int cooldownTicks = 0;
    private static boolean recentlyPlaced = false;
    private static int pickupAttemptsLeft = 0;

    // Registers keybind to R, provides key name and category
    @Override
    public void onInitializeClient() {
        loadConfig();
        // Registers the keybindings with Fabric, so they show up in controls menu and
        // can be listened for
        swapBucketKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autobucketclutch.swap_bucket",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "category.autobucketclutch"));

        toggleAutoClutchKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autobucketclutch.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.autobucketclutch"));

        // Registers the keybind to the client tick event, so it can be checked every
        // tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (toggleAutoClutchKey.wasPressed()) {
                // Toggle auto clutching
                isEnabled = !isEnabled;
                saveConfig(isEnabled);
                recentlyPlaced = false;
                cooldownTicks = 0;

                if (client.player != null) {
                    // Send a message in the action bar to indicate whether it's on or off
                    client.player.sendMessage(
                            Text.literal("Automatic clutch " + (isEnabled ? "ON" : "OFF")),
                            true // actionbar
                    );
                }
            }

            if (swapBucketKey.wasPressed()) {
                trySwapBucketIntoHand(client);
            }

            if (!isEnabled)
                return;
            if (client.player == null || client.interactionManager == null)
                return;

            if (cooldownTicks > 0) {
                cooldownTicks--;
                return;
            }
            tickAutoClutch(client);
        });

        AutoBucketClutch.LOGGER.info("AutoBucketClutchClient initialized");
    }

    private static void tickAutoClutch(MinecraftClient client) {
        var player = client.player;
        boolean isFallingFastEnough = player.getVelocity().y < -0.55;
        boolean hasMeaningfulFallDistance = player.fallDistance > 6.0f;

        // If we recently placed water, try to pick it up after landing (multi-tick + aim restore)
        if (recentlyPlaced) {
            // Start pickup mode once we touch ground
            if (player.isOnGround()) {
                tryPickupWater(client);
                pickupAttemptsLeft = 0;
                recentlyPlaced = false;
                return;
            }
        }

        
        if (!isFallingFastEnough || !hasMeaningfulFallDistance)
            return; // Meets right conditions

        AutoBucketClutch.LOGGER.info("Falling fast enough/fall distance");

        
        // Ensure we are holding a water bucket
        if (!player.getMainHandStack().isOf(Items.WATER_BUCKET)) {
            boolean swapped = trySwapBucketIntoHand(client);
            if (!swapped) return;
        }
        AutoBucketClutch.LOGGER.info("Holding water bucket");

        beginAimOverride(client);
        lookDown(client);

        AutoBucketClutch.LOGGER.info("Attempting raycast");
        // This raycast matches how the client normally determines a target from your
        HitResult hit = player.raycast(4.5d, 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK)
            return;

        // Now interact using a hit that is consistent with player rotation + reach
        AutoBucketClutch.LOGGER.info("Attempting water place");
        var itemResult = client.interactionManager.interactItem(player, Hand.MAIN_HAND);

        if (itemResult != null && itemResult.isAccepted()) {
            AutoBucketClutch.LOGGER.info("Water place accepted");
            player.swingHand(Hand.MAIN_HAND);
            recentlyPlaced = true;
            cooldownTicks = 6;

            // after hit is confirmed BLOCK
            var bhr = (net.minecraft.util.hit.BlockHitResult) hit;
            lastWaterPos = bhr.getBlockPos().offset(bhr.getSide()); // where the water source should be
        }
    }

    private static boolean tryPickupWater(MinecraftClient client) {
        var player = client.player;

        // Looks towards last water position for pickup (falls back to looking down)
        if (lastWaterPos != null) {
            lookAtBlockCenter(client, lastWaterPos);
        } else {
            player.setPitch(90.0f); // fallback
            AutoBucketClutch.LOGGER.info("No last water pos");
        }

        // Only attempt pickup if we're holding an empty bucket
        if (!player.getMainHandStack().isOf(Items.BUCKET))
            return false;

        var itemResult = client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        if (itemResult != null && itemResult.isAccepted()) {
            AutoBucketClutch.LOGGER.info("Picked up water 1");
            player.swingHand(Hand.MAIN_HAND);
            cooldownTicks = 2; // small cooldown is fine

            endAimOverride(client); // restore yaw/pitch
            recentlyPlaced = false;
            lastWaterPos = null;
            cooldownTicks = 6;
            return true;
        }

        return false;
    }

    private static void lookDown(MinecraftClient client) {
        var player = client.player;
        player.setPitch(90.0f);
    }

    private static boolean trySwapBucketIntoHand(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null)
            return false;

        int selectedHotbar = client.player.getInventory().selectedSlot; // 0..8

        int bucketSlotIndexInHandler = -1;

        for (int i = 0; i < client.player.currentScreenHandler.slots.size(); i++) {
            Slot slot = client.player.currentScreenHandler.getSlot(i);
            if (slot == null)
                continue;

            var stack = slot.getStack();
            if (!stack.isEmpty() && stack.isOf(Items.WATER_BUCKET)) {
                bucketSlotIndexInHandler = i;
                break;
            }
        }

        if (bucketSlotIndexInHandler == -1) {
            return false;
        }

        client.interactionManager.clickSlot(
                client.player.currentScreenHandler.syncId,
                bucketSlotIndexInHandler,
                selectedHotbar,
                SlotActionType.SWAP,
                client.player);

        return true;
    }

    private static void loadConfig() {
        try {
            if (!Files.exists(CONFIG_PATH)) {
                // create default file
                saveConfig(false);
                return;
            }

            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            ModConfig cfg = GSON.fromJson(json, ModConfig.class);
            if (cfg != null) {
                isEnabled = cfg.enabled;
            }
        } catch (Exception ex) {
            AutoBucketClutch.LOGGER.error("Failed to load config " + CONFIG_PATH, ex);
        }
    }

    private static void saveConfig(boolean enabled) {
        try {
            ModConfig cfg = new ModConfig();
            cfg.enabled = enabled;

            String json = GSON.toJson(cfg);

            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, json, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            AutoBucketClutch.LOGGER.error("Failed to save config " + CONFIG_PATH, ex);
        }
    }

    private static void lookAtBlockCenter(MinecraftClient client, BlockPos pos) {
        var player = client.player;
        if (player == null)
            return;

        Vec3d eye = player.getEyePos();
        Vec3d target = Vec3d.ofCenter(pos); // middle of the block

        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;

        double horiz = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));

        player.setYaw(yaw);
        player.setPitch(pitch);
    }

    private static void beginAimOverride(MinecraftClient client) {
        var player = client.player;
        if (player == null)
            return;

        if (!isAimingOverride) {
            storedYaw = player.getYaw();
            storedPitch = player.getPitch();
            isAimingOverride = true;
        }
    }

    private static void endAimOverride(MinecraftClient client) {
        var player = client.player;
        if (player == null)
            return;

        if (isAimingOverride) {
            player.setYaw(storedYaw);
            player.setPitch(storedPitch);
            isAimingOverride = false;
        }
    }

}
