package net.captaindude.autobucketclutch;

import java.util.Arrays;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.gui.entries.DropdownBoxEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class AutoBucketClutchConfigScreen {

        private AutoBucketClutchConfigScreen() {
        }

        public static Screen create(Screen parent) {

                ConfigBuilder builder = ConfigBuilder.create()
                                .setParentScreen(parent)
                                .setTitle(Component.literal("AutoBucketClutch Settings"));

                // Persist whatever has been changed via setSaveConsumer calls
                builder.setSavingRunnable(AutoBucketClutchClient::saveConfigFromUi);

                ConfigCategory general = builder.getOrCreateCategory(Component.literal("General"));
                ConfigEntryBuilder eb = builder.entryBuilder();

                // Enabled
                general.addEntry(
                                eb.startBooleanToggle(
                                                Component.literal("Enabled"),
                                                AutoBucketClutchClient.getEnabled())
                                                .setDefaultValue(true)
                                                .setSaveConsumer(AutoBucketClutchClient::setEnabled)
                                                .build());

                // Clutch item
                ClutchItem clutchItem = AutoBucketClutchClient.getPreferredItem();

                general.addEntry(
                                eb.startDropdownMenu(
                                                Component.literal("Preferred item to use"),
                                                createClutchItemTopCell(clutchItem),
                                                createClutchItemCellCreator())
                                                .setSelections(Arrays.asList(ClutchItem.values()))
                                                .setDefaultValue(ClutchItem.ANY)
                                                .setSuggestionMode(false)
                                                .setSaveConsumer(AutoBucketClutchClient::setPreferredItem)
                                                .build());

                // Min fall distance (blocks)
                general.addEntry(
                                eb.startIntSlider(
                                                Component.literal("Min fall distance to clutch (blocks)"),
                                                (int) AutoBucketClutchClient.getMinFallDistance(),
                                                0,
                                                50)
                                                .setDefaultValue(6)
                                                .setSaveConsumer(v -> AutoBucketClutchClient
                                                                .setMinFallDistance((float) v))
                                                .build());

                // Preferred hotbar slot (1..9)
                general.addEntry(
                                eb.startIntSlider(
                                                Component.literal("Preferred hotbar slot for item"),
                                                AutoBucketClutchClient.getPreferredHotbarSlot(),
                                                1,
                                                9)
                                                .setDefaultValue(1)
                                                .setSaveConsumer(AutoBucketClutchClient::setPreferredHotbarSlot)
                                                .build());

                general.addEntry(
                                eb.startBooleanToggle(
                                                Component.literal("Disable in Creative mode"),
                                                AutoBucketClutchClient.getDisableInCreative())
                                                .setDefaultValue(false)
                                                .setSaveConsumer(AutoBucketClutchClient::setDisableInCreative)
                                                .build());

                general.addEntry(
                                eb.startBooleanToggle(
                                                Component.literal("Require Sneak to activate"),
                                                AutoBucketClutchClient.getRequireSneak())
                                                .setDefaultValue(false)
                                                .setSaveConsumer(AutoBucketClutchClient::setRequireSneak)
                                                .build());

                general.addEntry(
                                eb.startBooleanToggle(
                                                Component.literal("Auto pickup after clutch"),
                                                AutoBucketClutchClient.getAutoPickup())
                                                .setDefaultValue(true)
                                                .setSaveConsumer(AutoBucketClutchClient::setAutoPickup)
                                                .build());

                return builder.build();
        }

        /**
         * Creates the currently selected part of the clutch-item dropdown.
         *
         * The text is displayed on the left and the actual Minecraft item
         * icon is displayed on the right.
         */
        private static DropdownBoxEntry.SelectionTopCellElement<ClutchItem> createClutchItemTopCell(
                        ClutchItem selected) {

                return new DropdownBoxEntry.DefaultSelectionTopCellElement<ClutchItem>(
                                selected,
                                ClutchItem::fromDisplayName,
                                ClutchItem::getDisplayName) { 

                        @Override
                        public void render(
                                        GuiGraphics graphics,
                                        int mouseX,
                                        int mouseY,
                                        int x,
                                        int y,
                                        int width,
                                        int height,
                                        float delta) {

                                // Leave room at the right for the 16x16 item icon.
                                textFieldWidget.setX(x + 4);
                                textFieldWidget.setY(y + 6);
                                textFieldWidget.setWidth(width - 24);
                                textFieldWidget.setEditable(getParent().isEditable());
                                textFieldWidget.setTextColor(getPreferredTextColor());

                                textFieldWidget.render(
                                                graphics,
                                                mouseX,
                                                mouseY,
                                                delta);

                                ClutchItem item = getValue();

                                if (item != null) {
                                        renderItemIcon(
                                                        graphics,
                                                        item.getIconItem(),
                                                        x + width - 18,
                                                        y + 2);
                                }
                        }
                };
        }

        /**
         * Creates the individual entries displayed when the dropdown is open.
         */
        private static DropdownBoxEntry.SelectionCellCreator<ClutchItem> createClutchItemCellCreator() {

                return new DropdownBoxEntry.DefaultSelectionCellCreator<ClutchItem>(
                                ClutchItem::getDisplayName) {

                        @Override
                        public DropdownBoxEntry.SelectionCellElement<ClutchItem> create(ClutchItem selection) {

                                return new DropdownBoxEntry.DefaultSelectionCellElement<ClutchItem>(
                                                selection,
                                                ClutchItem::getDisplayName) {

                                        @Override
                                        public void render(
                                                        GuiGraphics graphics,
                                                        int mouseX,
                                                        int mouseY,
                                                        int x,
                                                        int y,
                                                        int width,
                                                        int height,
                                                        float delta) {

                                                rendering = true;

                                                this.x = x;
                                                this.y = y;
                                                this.width = width;
                                                this.height = height;

                                                boolean hovered = mouseX >= x &&
                                                                mouseX <= x + width &&
                                                                mouseY >= y &&
                                                                mouseY <= y + height;

                                                if (hovered) {
                                                        graphics.fill(
                                                                        x + 1,
                                                                        y + 1,
                                                                        x + width - 1,
                                                                        y + height - 1,
                                                                        -15132391);
                                                }

                                                boolean iconRendered = renderItemIcon(
                                                                graphics,
                                                                selection.getIconItem(),
                                                                x + 4,
                                                                y + 2);

                                                int textX = iconRendered
                                                                ? x + 28
                                                                : x + 6;

                                                graphics.drawString(
                                                                Minecraft.getInstance().font,
                                                                selection.getDisplayName().getVisualOrderText(),
                                                                textX,
                                                                y + 6,
                                                                hovered
                                                                                ? 0xffffffff
                                                                                : 0xff888888);
                                        }

                                        @Override
                                        public boolean mouseClicked(
                                                        MouseButtonEvent event,
                                                        boolean doubleClick) {

                                                boolean selected = super.mouseClicked(event, doubleClick);

                                                if (selected) {
                                                        Minecraft.getInstance()
                                                                        .schedule(() -> getEntry().setFocused(null));
                                                }

                                                return selected;
                                        }
                                };
                        }

                        @Override
                        public int getCellHeight() {
                                return 20;
                        }

                        @Override
                        public int getCellWidth() {
                                return 146;
                        }

                        @Override
                        public int getDropBoxMaxHeight() {
                                return getCellHeight() * ClutchItem.values().length;
                        }
                };
        }

        /**
         * Renders a real Minecraft item icon when ItemStacks are available.
         *
         * Minecraft 26.1 does not allow ItemStacks to be constructed before
         * registry components have been bound. This normally means a world
         * must have been loaded first.
         */
        private static boolean renderItemIcon(
                        GuiGraphics graphics,
                        Item item,
                        int x,
                        int y) {

                Minecraft minecraft = Minecraft.getInstance();

                if (minecraft.level == null) {
                        return false;
                }

                graphics.renderItem(new ItemStack(item), x, y);
                return true;
        }
}