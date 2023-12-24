package net.runelite.client.plugins.microbot.slayer.wildyslayer.utils;

import net.runelite.api.ItemComposition;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.models.RS2Item;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.WildyWalk.distTo;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;
import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

public class Loot {
    public static final Set<String> lootItems = Set.of("Larran's key", "Blighted super restore(4)", "Trouver parchment", "Ranarr seed", "Loop half of key", "Tooth half of key", "Looting bag", "Dragon bones", "Green dragonhide");
    public static final List<Pair<String, ItemStack>> itemsToGrab = new ArrayList<>();

    protected static void getLoot(boolean force) {
        if (itemsToGrab.isEmpty()) {
            debug("No loot worth getting");
            return;
        }

        if (Inventory.hasClosedLootingBag()) {
            debug("Opening Looting bag..");
            Inventory.useItemSafe("Looting bag"); // assumes your Looting bag is left-click Open
            debug("Opened Looting bag..");
        }

        if (Inventory.count() >= 28) {
            if (Inventory.contains("Vial")) {
                debug("Dropping vials to make inventory space");
                Inventory.dropAll("Vial");
            } else if (Inventory.contains("Monkfish")) {
                debug("Dropping monks to make inventory space");
                Inventory.dropAll("Monkfish");
            } else {
                debug("Inventory full of valuables, can't loot");
                return;
            }
        }
        if (!force && random(0, 3) != 0) {
            debug("There's loot, but skipping it (antiban)");
            return;
        }
        debug("Looting items!");
        while (!itemsToGrab.isEmpty()) {
            Pair<String, ItemStack> stringItemStackPair = itemsToGrab.remove(0);
            String name = stringItemStackPair.getLeft();
            ItemStack stack = stringItemStackPair.getRight();
            debug("Looting stack " + stack.getLocation());
            Tile tile = Microbot.getClient().getScene().getTiles()[Microbot.getClient().getLocalPlayer().getWorldLocation().getPlane()][stack.getLocation().getSceneX()][stack.getLocation().getSceneY()];
            List<TileItem> itemsAtTile = tile.getGroundItems();
            debug("Stack not too far, getting the " + itemsAtTile.size() + " items...");
            for (TileItem tileItem : itemsAtTile) {
                debug("Started loop for tileItem " + tileItem);
                ItemComposition itemComposition = Microbot.getClientThread().runOnClientThread(() -> Microbot.getItemManager().getItemComposition(tileItem.getId()));
                if (!itemComposition.getName().equals(name)) {
                    debug("Skipping item " + itemComposition.getName() + " because it wasn't actually the item with any value");
                    continue;
                }
                RS2Item rs2Item = Microbot.getClientThread().runOnClientThread(() -> new RS2Item(itemComposition, tile, tileItem));
                debug("Getting item " + rs2Item.getItem().getName());
                if (distTo(rs2Item.getTile().getWorldLocation()) > 4) {
                    debug(rs2Item.getItem().getName() + " is more than 4 tiles away, skipping it");
                    continue;
                }
                long count = Inventory.count();
                Rs2GroundItem.interact(rs2Item);
                sleepUntil(() -> Inventory.count() > count);
                if (count != Inventory.count()) {
                    debug("Successfully looted " + rs2Item.getItem().getName());
                } else {
                    debug("Failed to loot " + rs2Item.getItem().getName());
                }
            }
        }
        debug("Done looting");
    }

}
