package net.captaindude.autobucketclutch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;

public class ModMenuIntegration implements ModMenuApi {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("autobucketclutch.json");

    public static final class ModConfig {
        boolean enabled = true;
        float minFallDistance = 6.0f;
        int preferredHotbarSlot = 1;

        boolean disableInCreative = false;
        boolean requireSneak = false;
        boolean autoPickup = true;

        ClutchItem preferredItem = ClutchItem.WATER_BUCKET;
    }

    public static ModConfig loadConfig() {
        try {
            ModConfig localConfig = new ModConfig();

            if (!Files.exists(CONFIG_PATH)) {
                saveConfig(localConfig);
                return localConfig;
            }

            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            ModConfig storedConfig = GSON.fromJson(json, ModConfig.class);
            if (storedConfig != null) {
                localConfig.enabled = storedConfig.enabled;
                localConfig.minFallDistance = storedConfig.minFallDistance;

                localConfig.preferredHotbarSlot = storedConfig.preferredHotbarSlot;

                localConfig.disableInCreative = storedConfig.disableInCreative;
                localConfig.requireSneak = storedConfig.requireSneak;
                localConfig.autoPickup = storedConfig.autoPickup;

                localConfig.preferredItem = storedConfig.preferredItem;
            }

            return localConfig;
        } catch (Exception ex) {
            AutoBucketClutch.LOGGER.error("Failed to load config " + CONFIG_PATH, ex);
            return null;
        }
    }

    public static void saveConfig(ModConfig localConfig) {
        try {
            ModConfig storedConfig = new ModConfig();
            storedConfig.enabled = localConfig.enabled;
            storedConfig.minFallDistance = localConfig.minFallDistance;

            storedConfig.preferredHotbarSlot = localConfig.preferredHotbarSlot;

            storedConfig.disableInCreative = localConfig.disableInCreative;
            storedConfig.requireSneak = localConfig.requireSneak;
            storedConfig.autoPickup = localConfig.autoPickup;

            storedConfig.preferredItem = localConfig.preferredItem;

            String json = GSON.toJson(storedConfig);

            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, json, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            AutoBucketClutch.LOGGER.error("Failed to save config " + CONFIG_PATH, ex);
        }
    }

    @Override
    public ConfigScreenFactory<Screen> getModConfigScreenFactory() {
        return parent -> AutoBucketClutchConfigScreen.create(parent);
    }   
}