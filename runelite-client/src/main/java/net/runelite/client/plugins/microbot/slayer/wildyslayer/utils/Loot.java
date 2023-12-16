package net.runelite.client.plugins.microbot.slayer.wildyslayer.utils;

import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.models.RS2Item;

import java.util.*;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;
import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

public class Loot {
    public static final Set<String> lootItems = Set.of("Larran's key", "Blighted super restore(4)", "Trouver parchment");
    public static final List<ItemStack> itemsToGrab = new ArrayList<>();

    protected static void getLoot(boolean force) {
        if (itemsToGrab.isEmpty()) {
            debug("No loot worth getting");
            return;
        }
        if (Inventory.count() >= 28) {
            debug("Inventory full, can't loot");
            return;
        }
        if (!force && random(0, 5) != 0) {
            debug("There's loot, but skipping it (antiban)");
            return;
        }
        debug("Looting items!");
        while (!itemsToGrab.isEmpty()) {
            ItemStack stack = itemsToGrab.remove(0);
            if (Microbot.getClient().getLocalPlayer().getLocalLocation().distanceTo(stack.getLocation()) > 4) continue;
            Tile tile = Microbot.getClient().getScene().getTiles()[Microbot.getClient().getLocalPlayer().getWorldLocation().getPlane()][stack.getLocation().getSceneX()][stack.getLocation().getSceneY()];
            List<TileItem> itemsAtTile = tile.getGroundItems();
            for (TileItem tileItem : itemsAtTile) {
                RS2Item rs2Item = new RS2Item(Microbot.getItemManager().getItemComposition(tileItem.getId()), tile, tileItem);
                long count = Inventory.count();
                Rs2GroundItem.interact(rs2Item);
                sleepUntil(() -> Inventory.count() > count);
            }
        }
        debug("Done looting");
    }

}
