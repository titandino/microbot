package net.runelite.client.plugins.microbot.slayer.wildyslayer;

import com.google.inject.Provides;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.slayer.wildyslayer.parallel.WildySlayerStatusUpdater;
import net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.Gear;
import net.runelite.client.plugins.slayer.SlayerPlugin;
import org.apache.commons.lang3.tuple.Pair;

import javax.inject.Inject;
import java.awt.*;
import java.util.Collection;
import java.util.Objects;

import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.Combat.task;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.Loot.itemsToGrab;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.Loot.lootItems;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

@PluginDescriptor(
        name = PluginDescriptor.RedBracket + "Wildy Slayer",
        description = "Trains slayer in the wilderness",
        tags = {"slayer", "wilderness"},
        enabledByDefault = false
)

@PluginDependency(SlayerPlugin.class)
public class WildySlayerPlugin extends Plugin {
    @Inject
    public WildySlayerConfig config;
    @Inject
    public WildySlayerScript wildySlayerScript;
    @Inject
    public WildySlayerStatusUpdater wildySlayerStatusUpdater;

    @Provides
    WildySlayerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(WildySlayerConfig.class);
    }

    public static boolean wildySlayerRunning = false;

    public long startTime;
    public long lastMeaningfulActonTime;

    @Override
    protected void startUp() throws AWTException {
        Gear.gearFails = 0;
        startTime = System.currentTimeMillis();
        lastMeaningfulActonTime = System.currentTimeMillis();
        wildySlayerRunning = true;
        Instance = this;
        wildySlayerScript.run();
        wildySlayerStatusUpdater.run();
    }

    protected void shutDown() {
        Instance = null;
        wildySlayerRunning = false;
        wildySlayerScript.shutdown();
        wildySlayerStatusUpdater.shutdown();
    }

    public static WildySlayerPlugin Instance;

    @Subscribe
    public void onNpcLootReceived(final NpcLootReceived npcLootReceived)
    {
        System.out.println("Received loot " + npcLootReceived);
        final NPC npc = npcLootReceived.getNpc();
        if (task() == null) {
            debug("Got loot but have no task!");
            return;
        }
        if (!Objects.equals(npc.getName().toLowerCase(), task().getNpcName().toLowerCase())) {
            debug("Got loot from " + npc.getName() + ", which is not " + task().getNpcName());
            return;
        }
        final Collection<ItemStack> items = npcLootReceived.getItems();
        for (ItemStack stack : items) {
            final ItemComposition itemComposition = Microbot.getItemManager().getItemComposition(stack.getId());
            final int gePrice = Microbot.getItemManager().getItemPrice(stack.getId());
            final int haPrice = itemComposition.getHaPrice();
            if (gePrice > 25000 || haPrice > 9000 || lootItems.contains(itemComposition.getName())){
                debug("Added " + itemComposition.getName() + " to items to loot");
                itemsToGrab.add(Pair.of(itemComposition.getName(), stack));
            }
            else debug("Skipped " + itemComposition.getName() + "; ge " + gePrice + "/ha : " + haPrice);
        }
    }

}
