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
    private static KeyBinding toggleAutoClutchKey;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("autobucketclutch.json");

    // Aim override
    private static boolean isAimingOverride = false;
    private static float storedYaw = 0f;
    private static float storedPitch = 0f;

    // Configurable
    private static float minFallDistance = 6.0f; // default
    private static int preferredHotbarSlot = 1; // 1..9 (UI-friendly)

    private static boolean disableInCreative = true; // default
    private static boolean requireSneak = false; // default
    private static boolean autoPickup = true; // default

    // Runtime state
    private static boolean isEnabled = false;
    private static int cooldownTicks = 0;
    private static boolean recentlyPlaced = false;

    private static BlockPos lastWaterPos = null; // where the water source should be

    private static final class ModConfig {
        boolean enabled = false;
        float minFallDistance = 6.0f;
        int preferredHotbarSlot = 1;

        boolean disableInCreative = true;
        boolean requireSneak = false;
        boolean autoPickup = true;
    }

    @Override
    public void onInitializeClient() {
        loadConfig();

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

        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            if (toggleAutoClutchKey.wasPressed()) {
                isEnabled = !isEnabled;
                saveConfig(isEnabled);

                // reset state
                recentlyPlaced = false;
                lastWaterPos = null;
                cooldownTicks = 0;
                endAimOverride(client);

                if (client.player != null) {
                    client.player.sendMessage(
                            Text.literal("Automatic clutch " + (isEnabled ? "ON" : "OFF")),
                            true);
                }
            }

            if (swapBucketKey.wasPressed()) {
                // manual helper: swap into preferred slot (or select hotbar bucket if already
                // there)
                if (client.player != null && client.interactionManager != null) {
                    ensureWaterBucketReady(client);
                }
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

        // Auto-disable in creative
        if (disableInCreative && player.getAbilities().creativeMode) {
            if (isAimingOverride)
                endAimOverride(client);
            return;
        }

        // Require sneak to activate
        if (requireSneak && !player.isSneaking()) {
            if (isAimingOverride)
                endAimOverride(client);
            return;
        }

        // 1) If we recently placed, do pickup mode after landing for a few ticks
        if (recentlyPlaced) {
            if (!autoPickup) {
                if (player.isOnGround()) {
                    endAimOverride(client);
                    recentlyPlaced = false;
                    lastWaterPos = null;
                }
                return;
            }

            if (player.isOnGround()) {
                tryPickupWater(client);
                recentlyPlaced = false;
                return;
            }

            return;
        }

        // 2) Normal clutch logic
        boolean isFallingFastEnough = player.getVelocity().y < -0.55;
        boolean hasMeaningfulFallDistance = player.fallDistance > minFallDistance;

        if (!isFallingFastEnough || !hasMeaningfulFallDistance) {
            // if we were overriding aim but didn't end up placing, restore to avoid "stuck"
            // camera
            if (isAimingOverride) {
                endAimOverride(client);
            }
            return;
        }

        // Ensure we have a water bucket ready (hotbar-first, otherwise swap into
        // preferred slot)
        if (!ensureWaterBucketReady(client)) {
            return;
        }

        // Store view once, then look down for the clutch attempt
        beginAimOverride(client);
        lookDown(client);

        // Vanilla-style raycast to confirm we are in range of a block to place against
        HitResult hit = player.raycast(4.5d, 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        // Place water (use item)
        var itemResult = client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        if (itemResult != null && itemResult.isAccepted()) {
            player.swingHand(Hand.MAIN_HAND);
            recentlyPlaced = true;
            cooldownTicks = 6;

            var bhr = (net.minecraft.util.hit.BlockHitResult) hit;
            lastWaterPos = bhr.getBlockPos().offset(bhr.getSide());
        }
    }

    // ------------------------------
    // Water bucket selection logic
    // ------------------------------

    private static void tryPickupWater(MinecraftClient client) {
        var player = client.player;

        if (lastWaterPos != null) {
            lookAtBlockCenter(client, lastWaterPos);
        } else {
            player.setPitch(90f);
        }

        if (!player.getMainHandStack().isOf(Items.BUCKET))
            return;

        var result = client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        if (result != null && result.isAccepted()) {
            player.swingHand(Hand.MAIN_HAND);
            endAimOverride(client);
            lastWaterPos = null;
            cooldownTicks = 6;
        }
    }

    private static boolean ensureWaterBucketReady(MinecraftClient client) {
        var player = client.player;

        // Already holding
        if (player.getMainHandStack().isOf(Items.WATER_BUCKET)) {
            return true;
        }

        // If bucket is already in hotbar, just select it
        int hotbarSlot = findWaterBucketInHotbar(client);
        if (hotbarSlot != -1) {
            selectHotbarSlot(client, hotbarSlot);
            return true;
        }

        // Otherwise swap one into preferred slot
        int preferred0to8 = clampHotbarSlot(preferredHotbarSlot) - 1;
        boolean swapped = swapWaterBucketIntoSlot(client, preferred0to8);
        if (swapped) {
            selectHotbarSlot(client, preferred0to8);
        }
        return swapped;
    }

    private static int findWaterBucketInHotbar(MinecraftClient client) {
        var inv = client.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.WATER_BUCKET))
                return i;
        }
        return -1;
    }

    private static void selectHotbarSlot(MinecraftClient client, int slot0to8) {
        client.player.getInventory().selectedSlot = slot0to8;
    }

    private static boolean swapWaterBucketIntoSlot(MinecraftClient client, int hotbarSlot0to8) {
        if (client.player == null || client.interactionManager == null)
            return false;

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

        if (bucketSlotIndexInHandler == -1)
            return false;

        client.interactionManager.clickSlot(
                client.player.currentScreenHandler.syncId,
                bucketSlotIndexInHandler,
                hotbarSlot0to8,
                SlotActionType.SWAP,
                client.player);

        return true;
    }

    // ------------------------------
    // Aim helpers
    // ------------------------------

    private static void lookDown(MinecraftClient client) {
        client.player.setPitch(90.0f);
    }

    private static void lookAtBlockCenter(MinecraftClient client, BlockPos pos) {
        var player = client.player;
        if (player == null)
            return;

        Vec3d eye = player.getEyePos();
        Vec3d target = Vec3d.ofCenter(pos);

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

    // ------------------------------
    // Config
    // ------------------------------

    private static int clampHotbarSlot(int slot1to9) {
        if (slot1to9 < 1)
            return 1;
        if (slot1to9 > 9)
            return 9;
        return slot1to9;
    }

    private static void loadConfig() {
        try {
            if (!Files.exists(CONFIG_PATH)) {
                saveConfig(false);
                return;
            }

            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            ModConfig cfg = GSON.fromJson(json, ModConfig.class);
            if (cfg != null) {
                isEnabled = cfg.enabled;
                minFallDistance = cfg.minFallDistance;

                preferredHotbarSlot = clampHotbarSlot(cfg.preferredHotbarSlot);

                disableInCreative = cfg.disableInCreative;
                requireSneak = cfg.requireSneak;
                autoPickup = cfg.autoPickup;
            }
        } catch (Exception ex) {
            AutoBucketClutch.LOGGER.error("Failed to load config " + CONFIG_PATH, ex);
        }
    }

    private static void saveConfig(boolean enabled) {
        try {
            ModConfig cfg = new ModConfig();
            cfg.enabled = enabled;
            cfg.minFallDistance = minFallDistance;

            cfg.preferredHotbarSlot = clampHotbarSlot(preferredHotbarSlot);

            cfg.disableInCreative = disableInCreative;
            cfg.requireSneak = requireSneak;
            cfg.autoPickup = autoPickup;

            String json = GSON.toJson(cfg);

            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, json, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            AutoBucketClutch.LOGGER.error("Failed to save config " + CONFIG_PATH, ex);
        }
    }

    // ------------------------------
    // Mod Menu getters/setters
    // ------------------------------

    public static boolean getEnabled() {
        return isEnabled;
    }

    public static void setEnabled(boolean enabled) {
        isEnabled = enabled;
        saveConfig(isEnabled);
    }

    public static float getMinFallDistance() {
        return minFallDistance;
    }

    public static void setMinFallDistance(float value) {
        minFallDistance = value;
        saveConfig(isEnabled);
    }

    public static int getPreferredHotbarSlot() {
        return preferredHotbarSlot;
    }

    public static void setPreferredHotbarSlot(int value) {
        preferredHotbarSlot = clampHotbarSlot(value);
        saveConfig(isEnabled);
    }

    public static boolean getDisableInCreative() {
        return disableInCreative;
    }

    public static void setDisableInCreative(boolean v) {
        disableInCreative = v;
        saveConfig(isEnabled);
    }

    public static boolean getRequireSneak() {
        return requireSneak;
    }

    public static void setRequireSneak(boolean v) {
        requireSneak = v;
        saveConfig(isEnabled);
    }

    public static boolean getAutoPickup() {
        return autoPickup;
    }

    public static void setAutoPickup(boolean v) {
        autoPickup = v;
        saveConfig(isEnabled);
    }

    public static void saveConfigFromUi() {
        saveConfig(isEnabled);
    }
}