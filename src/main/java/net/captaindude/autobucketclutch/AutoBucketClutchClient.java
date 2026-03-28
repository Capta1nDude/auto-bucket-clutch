package net.captaindude.autobucketclutch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.lwjgl.glfw.GLFW;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class AutoBucketClutchClient implements ClientModInitializer {

    private static KeyMapping swapBucketKey;
    private static KeyMapping toggleAutoClutchKey;

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

        final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category
                .register(Identifier.fromNamespaceAndPath("autobucketclutch", "keybinds"));

        swapBucketKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.autobucketclutch.swap_bucket",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                KEY_CATEGORY));

        toggleAutoClutchKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.autobucketclutch.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                KEY_CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            if (toggleAutoClutchKey.consumeClick()) {
                isEnabled = !isEnabled;
                saveConfig(isEnabled);

                // reset state
                recentlyPlaced = false;
                lastWaterPos = null;
                cooldownTicks = 0;
                endAimOverride(client);

                if (client.player != null) {
                    client.player.sendOverlayMessage(
                            Component.literal("Automatic clutch " + (isEnabled ? "ON" : "OFF")));
                }
            }

            if (swapBucketKey.consumeClick()) {
                // manual helper: swap into preferred slot (or select hotbar bucket if already
                // there)
                if (client.player != null && client.gameMode != null) {
                    ensureWaterBucketReady(client);
                }
            }

            if (!isEnabled)
                return;
            if (client.player == null || client.gameMode == null)
                return;

            if (cooldownTicks > 0) {
                cooldownTicks--;
                return;
            }

            tickAutoClutch(client);
        });
    }

    private static void tickAutoClutch(Minecraft client) {
        var player = client.player;

        // Auto-disable in creative
        if (disableInCreative && player.getAbilities().instabuild) {
            if (isAimingOverride)
                endAimOverride(client);
            return;
        }

        // Require sneak to activate
        if (requireSneak && !player.isShiftKeyDown()) {
            if (isAimingOverride)
                endAimOverride(client);
            return;
        }

        // 1) If we recently placed, do pickup mode after landing for a few ticks
        if (recentlyPlaced) {
            if (!autoPickup) {
                if (player.onGround()) {
                    endAimOverride(client);
                    recentlyPlaced = false;
                    lastWaterPos = null;
                }
                return;
            }

            if (player.onGround()) {
                tryPickupWater(client);
                recentlyPlaced = false;
                return;
            }

            return;
        }

        // 2) Normal clutch logic
        boolean isFallingFastEnough = player.getDeltaMovement().y < -0.55;
        double distanceToGround = distanceToGround(client, 32.0);
        boolean groundDetected = distanceToGround != Double.MAX_VALUE;
        boolean isWithinClutchRange = groundDetected && distanceToGround <= minFallDistance;

        AutoBucketClutch.LOGGER.info("Distance to ground: " + distanceToGround);
        AutoBucketClutch.LOGGER.info("Velocity: " + player.getDeltaMovement());
        AutoBucketClutch.LOGGER.info("Is falling fast enough: " + isFallingFastEnough);
        AutoBucketClutch.LOGGER.info("Ground detected: " + groundDetected);
        AutoBucketClutch.LOGGER.info("Is within clutch range: " + isWithinClutchRange);

        if (!isFallingFastEnough || !isWithinClutchRange) {
            // if we were overriding aim but didn't end up placing, restore to avoid "stuck"
            // camera
            AutoBucketClutch.LOGGER.info("Not falling fast enough or not within clutch range");
            if (isAimingOverride) {
                AutoBucketClutch.LOGGER.info("Ending aim override");
                endAimOverride(client);
            }
            return;
        }

        AutoBucketClutch.LOGGER.info("Falling fast enough and within clutch range");

        // Ensure we have a water bucket ready (hotbar-first, otherwise swap into
        // preferred slot)
        if (!ensureWaterBucketReady(client)) {
            return;
        }

        AutoBucketClutch.LOGGER.info("Water bucket ready");

        // Store view once, then look down for the clutch attempt
        beginAimOverride(client);
        lookDown(client);

        AutoBucketClutch.LOGGER.info("Looking down");

        // Vanilla-style raycast to confirm we are in range of a block to place against
        HitResult hit = player.pick(4.5d, 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        AutoBucketClutch.LOGGER.info("Hit a block");

        // Place water (use item)
        var itemResult = client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        if (itemResult != null && itemResult.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            recentlyPlaced = true;
            cooldownTicks = 6;

            var bhr = (net.minecraft.world.phys.BlockHitResult) hit;
            lastWaterPos = bhr.getBlockPos().relative(bhr.getDirection());
        }

        AutoBucketClutch.LOGGER.info("Placed water");
    }

    // ------------------------------
    // Water bucket selection logic
    // ------------------------------

    private static void tryPickupWater(Minecraft client) {
        var player = client.player;

        if (lastWaterPos != null) {
            lookAtBlockCenter(client, lastWaterPos);
        } else {
            player.setXRot(90f);
        }

        if (!player.getMainHandItem().is(Items.BUCKET))
            return;

        var result = client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        if (result != null && result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            endAimOverride(client);
            lastWaterPos = null;
            cooldownTicks = 6;
        }
    }

    private static boolean ensureWaterBucketReady(Minecraft client) {
        var player = client.player;

        // Already holding
        if (player.getMainHandItem().is(Items.WATER_BUCKET)) {
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

    private static int findWaterBucketInHotbar(Minecraft client) {
        var inv = client.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(Items.WATER_BUCKET))
                return i;
        }
        return -1;
    }

    private static void selectHotbarSlot(Minecraft client, int slot0to8) {
        client.player.getInventory().setSelectedSlot(slot0to8);
    }

    private static boolean swapWaterBucketIntoSlot(Minecraft client, int hotbarSlot0to8) {
        if (client.player == null || client.gameMode == null)
            return false;

        int bucketSlotIndexInHandler = -1;

        for (int i = 0; i < client.player.containerMenu.slots.size(); i++) {
            Slot slot = client.player.containerMenu.getSlot(i);
            if (slot == null)
                continue;

            var stack = slot.getItem();
            if (!stack.isEmpty() && stack.is(Items.WATER_BUCKET)) {
                bucketSlotIndexInHandler = i;
                break;
            }
        }

        if (bucketSlotIndexInHandler == -1)
            return false;

        // Swap bucket to hand
        client.gameMode.handleContainerInput(
            client.player.containerMenu.containerId,
            bucketSlotIndexInHandler,
            hotbarSlot0to8,
            ContainerInput.SWAP,
            client.player
        );

        return true;
    }

    // ------------------------------
    // Aim helpers
    // ------------------------------

    private static void lookDown(Minecraft client) {
        client.player.setXRot(90.0f);
    }

    private static void lookAtBlockCenter(Minecraft client, BlockPos pos) {
        var player = client.player;
        if (player == null)
            return;

        Vec3 eye = player.getEyePosition();
        Vec3 target = Vec3.atCenterOf(pos);

        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;

        double horiz = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));

        player.setYRot(yaw);
        player.setXRot(pitch);
    }

    private static void beginAimOverride(Minecraft client) {
        var player = client.player;
        if (player == null)
            return;

        if (!isAimingOverride) {
            storedYaw = player.getYRot();
            storedPitch = player.getXRot();
            isAimingOverride = true;
        }
    }

    private static void endAimOverride(Minecraft client) {
        var player = client.player;
        if (player == null)
            return;

        if (isAimingOverride) {
            player.setYRot(storedYaw);
            player.setXRot(storedPitch);
            isAimingOverride = false;
        }
    }
        
    private static double distanceToGround(Minecraft client, double maxCheckDistance) {
        var player = client.player;
        if (player == null) {
            return Double.MAX_VALUE;
        }

        Vec3 start = new Vec3(player.getX(), player.getY(), player.getZ());
        Vec3 end = start.subtract(0.0, maxCheckDistance, 0.0);

        var hit = player.level().clip(new net.minecraft.world.level.ClipContext(
                start,
                end,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                player));

        if (!(hit instanceof net.minecraft.world.phys.BlockHitResult blockHit)) {
            return Double.MAX_VALUE;
        }

        return start.y - blockHit.getBlockPos().getY() - 1.0;
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