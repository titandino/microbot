package net.runelite.client.plugins.microbot.slayer.wildyslayer.utils;

import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.models.RS2Item;

import java.util.*;

import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.Combat.task;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;
import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

public class Loot {
    private static final Set<String> lootItems = Set.of("Larran's key", "Blighted super restore(4)", "Trouver parchment");
    private static final List<ItemStack> itemsToGrab = new ArrayList<>();

    @Subscribe
    public void onNpcLootReceived(final NpcLootReceived npcLootReceived)
    {
        final NPC npc = npcLootReceived.getNpc();
        if (!Objects.equals(npc.getName(), task().getNpcName())) return;
        final Collection<ItemStack> items = npcLootReceived.getItems();
        for (ItemStack stack : items) {
            final ItemComposition itemComposition = Microbot.getItemManager().getItemComposition(stack.getId());
            final int gePrice = Microbot.getItemManager().getItemPrice(stack.getId());
            final int haPrice = itemComposition.getHaPrice();
            if (gePrice > 25000 || haPrice > 9000 || lootItems.contains(itemComposition.getName())) itemsToGrab.add(stack);
        }
    }

    protected static void getLoot() {
        if (itemsToGrab.isEmpty()) {
            debug("No loot worth getting");
            return;
        }
        if (random(0, 5) != 0) {
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
