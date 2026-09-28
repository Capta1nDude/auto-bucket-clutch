package net.captaindude.autobucketclutch;

import net.captaindude.autobucketclutch.ModMenuIntegration.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class ClutchHandler {
    private static int cooldownTicks = 0;
    private static boolean recentlyPlaced = false;
    private static BlockPos placementPos = null;
    private static boolean enterBoatSoon;

    // Aim override
    private static boolean isAimingOverride = false;
    private static float storedYaw = 0f;
    private static float storedPitch = 0f;

    private static Item selectedItem = null;
    private static ClutchItem selectedClutchItem = null;

    public static void tick(Minecraft client, ModConfig config) {
        var player = client.player;
        
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        if (recentlyPlaced && selectedClutchItem.shouldBePickedUp()) tryPickupWater(client, config); // Should never be triggered before a clutch item is selected
        
        if (config.disableInCreative && player.getAbilities().instabuild) {endAimOverride(client); return;}

        if (config.requireSneak && !player.isShiftKeyDown()) {endAimOverride(client); return;}

        selectedClutchItem = getSelectedClutchItem(client, config, config.preferredItem);
        selectedItem = getSelectedItem(client, config, selectedClutchItem);

        if (selectedItem == null) {endAimOverride(client); return;}

        if (isValidFalling(client, config)) beginAimOverride(client); else return;

        if (inPlacementRange(client)) performClutch(client, config); else return;
    }



    private static ClutchItem getSelectedClutchItem(Minecraft client, ModConfig config, ClutchItem preferredItem) {
        if (preferredItem == ClutchItem.ANY) {
            Item i;
            i = getSelectedItem(client, config, ClutchItem.WATER_BUCKET);
            if (i != null && client.player.level().dimension() != Level.NETHER) return ClutchItem.WATER_BUCKET;
            i = getSelectedItem(client, config, ClutchItem.BOAT);
            if (i != null) return ClutchItem.BOAT;
            i = getSelectedItem(client, config, ClutchItem.COBWEB);
            if (i != null) return ClutchItem.COBWEB;
            i = getSelectedItem(client, config, ClutchItem.SLIME_BLOCK);
            if (i != null) return ClutchItem.SLIME_BLOCK;
            i = getSelectedItem(client, config, ClutchItem.HAY_BALE);
            if (i != null) return ClutchItem.HAY_BALE;
            else return null;
        }

        return config.preferredItem;
    }

    private static Item getSelectedItem(Minecraft client, ModConfig config, ClutchItem preferredItem) {
        if (preferredItem == null) return null;
        var validItems = preferredItem.getValidItems();
        var inventory = client.player.getInventory();

        // Hotbar first
        for (int i = 0; i < 9; i++) {
            var stack = inventory.getItem(i);

            for (Item validItem : validItems) {
                if (stack.is(validItem)) {
                    return validItem;
                }
            }
        }

        // Then rest of inventory
        for (int i = 9; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);

            for (Item validItem : validItems) {
                if (stack.is(validItem)) {
                    return validItem;
                }
            }
        }

        return null;
    }



    /* WATER PICKUP */
    private static void tryPickupWater(Minecraft client, ModConfig config) {
        var player = client.player;

        if (!player.onGround()) return;
        if (!config.autoPickup) return;

        if (placementPos != null) {
            lookAtBlockCenter(client, placementPos);
        } else {
            player.setXRot(90f);
        }

        if (!player.getMainHandItem().is(Items.BUCKET)) return;

        var result = client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        if (result != null && result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            endAimOverride(client);
            recentlyPlaced = false;
            placementPos = null;
            cooldownTicks = 6;
        }
    }



    /* PLACEMENT */
    private static void performClutch(Minecraft client, ModConfig config) {
        var player = client.player;

        retrieveItem(client, config);

        // Vanilla-style raycast to confirm we are in range of a block to place against
        HitResult hit = player.pick(4.5d, 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        
        // Place item
        var bhr = (BlockHitResult) hit;

        if (selectedClutchItem.usesItemInteraction()) {
            var result = client.gameMode.useItem(player, InteractionHand.MAIN_HAND);

            if (selectedClutchItem == ClutchItem.BOAT) enterBoatSoon = true;

            if (result != null && result.consumesAction() && selectedClutchItem.shouldBePickedUp()) recentlyPlaced = true;
        } else {
            client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, bhr);
        }

        player.swing(InteractionHand.MAIN_HAND);
        cooldownTicks = 6;
        placementPos = bhr.getBlockPos().relative(bhr.getDirection());
    }

    public static void checkForBoats(Entity entity, ClientLevel level) {
        if (!enterBoatSoon) return;
        if (!(entity instanceof Boat boat)) return;

        Minecraft client = Minecraft.getInstance();
        var player = client.player;

        if (player == null || client.gameMode == null) return;
        if (player.distanceToSqr(boat) > 16.0) return;

        EntityHitResult entityHit = new EntityHitResult(boat);

        Vec3 hitLocation = entityHit.getLocation().subtract(boat.position());

        // Send without sneaking
        player.connection.send(
            new ServerboundInteractPacket(
                    boat.getId(),
                    InteractionHand.MAIN_HAND,
                    hitLocation,
                    false
            )
        );

        player.swing(InteractionHand.MAIN_HAND);
        enterBoatSoon = false;
    }



    /* AIM HELPERS */
    private static void beginAimOverride(Minecraft client) {
        var player = client.player;
        if (player == null)
            return;

        if (!isAimingOverride) {
            storedYaw = player.getYRot();
            storedPitch = player.getXRot();
            isAimingOverride = true;
        }

        player.setXRot(90.0f);
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



    /* FALLING DETECTION */
    private static boolean isValidFalling(Minecraft client, ModConfig config) {
        var player = client.player;

        boolean isFallingFastEnough = player.getDeltaMovement().y < -0.55;

        double distanceToGround = distanceToGround(client, 32.0);
        boolean groundDetected = distanceToGround < 6.0f;
        
        boolean fallingFromValidHeight = PlayerFallDistanceProvider.get(player) + distanceToGround >= config.minFallDistance;

        return isFallingFastEnough && groundDetected && fallingFromValidHeight;
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

    private static boolean inPlacementRange(Minecraft client) {
        double distanceToGround = distanceToGround(client, 32.0);
        boolean groundDetected = distanceToGround != Double.MAX_VALUE;

        var player = client.player;

        double fallSpeed = Math.max(0.0, -player.getDeltaMovement().y);

        return groundDetected && distanceToGround <= 6.0f + fallSpeed;
    }

    

    /* ITEM RETRIEVAL */
    public static boolean retrieveItem(Minecraft client, ModConfig config) {
        Item item = selectedItem;

        var player = client.player;

        // Already holding
        if (player.getMainHandItem().is(item)) {
            return true;
        }

        // If bucket is already in hotbar, just select it
        int hotbarSlot = findItemInHotbar(client, item);
        if (hotbarSlot != -1) {
            selectHotbarSlot(client, hotbarSlot);
            return true;
        }

        // Otherwise swap one into preferred slot
        int preferred0to8 = config.preferredHotbarSlot - 1;
        boolean swapped = swapItemIntoSlot(client, preferred0to8, item);
        if (swapped) {
            selectHotbarSlot(client, preferred0to8);
        }
        return swapped;
    }

    private static int findItemInHotbar(Minecraft client, Item item) {
        var inv = client.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(item))
                return i;
        }
        return -1;
    }

    private static void selectHotbarSlot(Minecraft client, int slot0to8) {
        client.player.getInventory().setSelectedSlot(slot0to8);
    }

    private static boolean swapItemIntoSlot(Minecraft client, int hotbarSlot0to8, Item item) {
        if (client.player == null || client.gameMode == null)
            return false;

        int bucketSlotIndexInHandler = -1;

        for (int i = 0; i < client.player.containerMenu.slots.size(); i++) {
            Slot slot = client.player.containerMenu.getSlot(i);
            if (slot == null)
                continue;

            var stack = slot.getItem();
            if (!stack.isEmpty() && stack.is(item)) {
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

}
