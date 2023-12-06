package net.runelite.client.plugins.microbot.trent

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject
import net.runelite.client.plugins.microbot.util.inventory.Inventory
import net.runelite.client.plugins.microbot.util.math.Random
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Woodcut Note",
    description = "Woodcuts and notes logs",
    tags = ["sorc", "garden", "thieve"],
    enabledByDefault = false
)
class WoodcutNote : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false

    @OptIn(DelicateCoroutinesApi::class)
    override fun startUp() {
        if (client.getLocalPlayer() != null) {
            running = true;
            GlobalScope.launch { run() }
        }
    }

    private fun run() {
        while (running) {
            try {
                if (Inventory.isFull()) {
                    Inventory.useItemSlot(Random.random(0, 6))
                    Global.sleep(500, 1000)
                    Inventory.useItem(28767)
                    Global.sleep(500, 1000)
                    continue
                }
                val oak = Rs2GameObject.findObject("Teak tree");
                if (oak != null && oak.worldLocation.distanceTo(client.localPlayer.worldLocation) <= 14 && Rs2GameObject.interact(oak, "Chop down"))
                    Global.sleepUntil({ Inventory.isFull() || Rs2GameObject.getGameObjects().firstOrNull { it.worldLocation == oak.getWorldLocation() && oak.id == it.id } == null }, 30000)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    override fun shutDown() {
        running = false
    }
}
