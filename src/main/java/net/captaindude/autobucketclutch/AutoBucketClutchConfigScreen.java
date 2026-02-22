package net.captaindude.autobucketclutch;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public final class AutoBucketClutchConfigScreen {

    private AutoBucketClutchConfigScreen() {
    }

    public static Screen create(Screen parent) {

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.literal("AutoBucketClutch Settings"));

        // Persist whatever has been changed via setSaveConsumer calls
        builder.setSavingRunnable(AutoBucketClutchClient::saveConfigFromUi);

        ConfigCategory general = builder.getOrCreateCategory(Text.literal("General"));
        ConfigEntryBuilder eb = builder.entryBuilder();

        // Enabled default
        general.addEntry(
                eb.startBooleanToggle(Text.literal("Enabled"), AutoBucketClutchClient.getEnabled())
                        .setDefaultValue(false)
                        .setSaveConsumer(AutoBucketClutchClient::setEnabled)
                        .build());

        // Min fall distance (blocks)
        general.addEntry(
                eb.startIntSlider(Text.literal("Min fall distance to clutch (blocks)"),
                        (int) AutoBucketClutchClient.getMinFallDistance(),
                        0, 50)
                        .setDefaultValue(6)
                        .setSaveConsumer(v -> AutoBucketClutchClient.setMinFallDistance((float) v))
                        .build());

        // Preferred hotbar slot (1..9)
        general.addEntry(
                eb.startIntSlider(Text.literal("Preferred hotbar slot for water bucket"),
                        AutoBucketClutchClient.getPreferredHotbarSlot(),
                        1, 9)
                        .setDefaultValue(1)
                        .setSaveConsumer(AutoBucketClutchClient::setPreferredHotbarSlot)
                        .build());

        general.addEntry(
                eb.startBooleanToggle(Text.literal("Disable in Creative mode"),
                        AutoBucketClutchClient.getDisableInCreative())
                        .setDefaultValue(true)
                        .setSaveConsumer(AutoBucketClutchClient::setDisableInCreative)
                        .build());

        general.addEntry(
                eb.startBooleanToggle(Text.literal("Require Sneak to activate"),
                        AutoBucketClutchClient.getRequireSneak())
                        .setDefaultValue(false)
                        .setSaveConsumer(AutoBucketClutchClient::setRequireSneak)
                        .build());

        general.addEntry(
                eb.startBooleanToggle(Text.literal("Auto pickup after clutch"), AutoBucketClutchClient.getAutoPickup())
                        .setDefaultValue(true)
                        .setSaveConsumer(AutoBucketClutchClient::setAutoPickup)
                        .build());

        return builder.build();
    }
}