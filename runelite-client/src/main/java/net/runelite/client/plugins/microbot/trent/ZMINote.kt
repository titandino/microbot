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
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "ZMI Note",
    description = "Woodcuts and notes logs",
    tags = ["sorc", "garden", "thieve"],
    enabledByDefault = false
)
class ZMINote : Plugin() {
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
                if (Inventory.hasItemAmount(7936, 1)) {
                    Rs2GameObject.interact(29631)
                    Global.sleepUntil { !Inventory.hasItemAmount(7936, 1) }
                    continue
                }
                Inventory.useItem(28767)
                Global.sleepUntil { Inventory.hasItemAmount(7936, 1) }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    override fun shutDown() {
        running = false
    }
}
