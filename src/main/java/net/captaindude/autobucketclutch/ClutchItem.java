package net.captaindude.autobucketclutch;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public enum ClutchItem {

    ANY("Any", Items.BARRIER),
    WATER_BUCKET("Water Bucket", Items.WATER_BUCKET),
    BOAT("Boat", Items.OAK_BOAT),
    COBWEB("Cobweb", Items.COBWEB),
    SLIME_BLOCK("Slime Block", Items.SLIME_BLOCK),
    HAY_BALE("Hay Bale", Items.HAY_BLOCK);

    private final String displayName;
    private final Item iconItem;

    ClutchItem(String displayName, Item iconItem) {
        this.displayName = displayName;
        this.iconItem = iconItem;
    }

    public Component getDisplayName() {
        return Component.literal(displayName);
    }

    public Item getIconItem() {
        return iconItem;
    }

    public Item[] getValidItems() {
        return switch (this) {
            case BOAT -> BOAT_VARIANTS;
            default -> new Item[] { this.getIconItem() };
        };
    }

    public static ClutchItem fromDisplayName(String name) {
        for (ClutchItem item : values()) {
            if (item.displayName.equals(name)) {
                return item;
            }
        }

        return null;
    }

    public boolean shouldBePickedUp() {
        switch (this) {
            case WATER_BUCKET: return true;
            default: return false;
        }
    }
    
    public boolean usesItemInteraction() {
        switch (this) {
            case WATER_BUCKET, BOAT: return true;
            default: return false;
        }
    }

    Item[] BOAT_VARIANTS = {
            Items.OAK_BOAT,
            Items.OAK_CHEST_BOAT,

            Items.SPRUCE_BOAT,
            Items.SPRUCE_CHEST_BOAT,

            Items.BIRCH_BOAT,
            Items.BIRCH_CHEST_BOAT,

            Items.JUNGLE_BOAT,
            Items.JUNGLE_CHEST_BOAT,

            Items.ACACIA_BOAT,
            Items.ACACIA_CHEST_BOAT,

            Items.DARK_OAK_BOAT,
            Items.DARK_OAK_CHEST_BOAT,

            Items.MANGROVE_BOAT,
            Items.MANGROVE_CHEST_BOAT,

            Items.CHERRY_BOAT,
            Items.CHERRY_CHEST_BOAT,

            Items.PALE_OAK_BOAT,
            Items.PALE_OAK_CHEST_BOAT,

            Items.BAMBOO_RAFT,
            Items.BAMBOO_CHEST_RAFT
    };
}