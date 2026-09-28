package net.captaindude.autobucketclutch;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.captaindude.autobucketclutch.ModMenuIntegration.ModConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;


/*

0. Register keys + load configs
1. Detect when falling
1.5. Grab bucket
2. Override aim
3. When in range, place bucket
4. Pick back up
5. Unoverride aim

*/

public class AutoBucketClutchClient implements ClientModInitializer {
    private static KeyMapping swapBucketKey;
    private static KeyMapping toggleAutoClutchKey;

    private static int SWAP_KEY = GLFW.GLFW_KEY_R;
    private static int TOGGLE_KEY = GLFW.GLFW_KEY_G;

    private static ModConfig config;
    

    @Override
    public void onInitializeClient() {
        config = ModMenuIntegration.loadConfig();
        registerKeys();

        
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            handleKeyPresses(client);

            if (!config.enabled) return;
            if (client.player == null || client.gameMode == null) return;

            ClutchHandler.tick(client, config);
        });

        ClientEntityEvents.ENTITY_LOAD.register(ClutchHandler::checkForBoats);
    }

    private void handleKeyPresses(Minecraft client) {
        if (toggleAutoClutchKey.consumeClick()) {
            config.enabled = !config.enabled;
            ModMenuIntegration.saveConfig(config);

            if (client.player != null) {
                client.player.displayClientMessage(
                        Component.literal("Automatic clutch ")
                        .append(Component.literal(config.enabled ? "ON" : "OFF")
                        .withStyle(config.enabled ? ChatFormatting.GREEN : ChatFormatting.RED, ChatFormatting.BOLD)), true);
            }
        }

        if (swapBucketKey.consumeClick()) {
            // manual helper: swap into preferred slot (or select hotbar bucket if already
            // there)
            if (client.player != null && client.gameMode != null) {
                ClutchHandler.retrieveItem(client, config);
            }
        }
    }

    private static void registerKeys() {
        final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category
                .register(Identifier.fromNamespaceAndPath("autobucketclutch", "keybinds"));

        swapBucketKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.autobucketclutch.swap_bucket",
                InputConstants.Type.KEYSYM,
                SWAP_KEY,
                KEY_CATEGORY));

        toggleAutoClutchKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.autobucketclutch.toggle",
                InputConstants.Type.KEYSYM,
                TOGGLE_KEY,
                KEY_CATEGORY)); // TODO check
    }

    // ------------------------------
    // Mod Menu getters/setters
    // ------------------------------

    public static boolean getEnabled() {
        return config.enabled;
    }

    public static void setEnabled(boolean enabled) {
        config.enabled = enabled;
        ModMenuIntegration.saveConfig(config);
    }

    public static float getMinFallDistance() {
        return config.minFallDistance;
    }

    public static void setMinFallDistance(float value) {
        config.minFallDistance = value;
        ModMenuIntegration.saveConfig(config);
    }

    public static int getPreferredHotbarSlot() {
        return config.preferredHotbarSlot;
    }

    public static void setPreferredHotbarSlot(int value) {
        config.preferredHotbarSlot = value;
        ModMenuIntegration.saveConfig(config);
    }

    public static boolean getDisableInCreative() {
        return config.disableInCreative;
    }

    public static void setDisableInCreative(boolean v) {
        config.disableInCreative = v;
        ModMenuIntegration.saveConfig(config);
    }

    public static boolean getRequireSneak() {
        return config.requireSneak;
    }

    public static void setRequireSneak(boolean v) {
        config.requireSneak = v;
        ModMenuIntegration.saveConfig(config);
    }

    public static boolean getAutoPickup() {
        return config.autoPickup;
    }

    public static void setAutoPickup(boolean v) {
        config.autoPickup = v;
        ModMenuIntegration.saveConfig(config);
    }

    public static void setPreferredItem(ClutchItem i) {
        config.preferredItem = i;
        ModMenuIntegration.saveConfig(config);
    }

    public static ClutchItem getPreferredItem() {
        return config.preferredItem;
    }

    public static void saveConfigFromUi() {
        ModMenuIntegration.saveConfig(config);
    }
}
