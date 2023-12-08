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
    name = PluginDescriptor.Trent + "Teak Tables",
    description = "Woodcuts and notes logs",
    tags = ["sorc", "garden", "thieve"],
    enabledByDefault = false
)
class TeakTables : Plugin() {
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
                if (Inventory.hasItemAmount(8780, 8)) {
                    var builtTable = Rs2GameObject.findObjectById(13145)
                    if (builtTable != null) {
                        Rs2GameObject.interact(builtTable, "Remove")
                        Global.sleep(600, 850)
                        Rs2Widget.clickWidget("Yes")
                        Global.sleepUntil { Rs2GameObject.findObjectById(13145) == null }
                        continue
                    }
                    var tableSpace = Rs2GameObject.findObjectById(15277)
                    if (tableSpace != null) {
                        Rs2GameObject.interact(tableSpace, "Build")
                        //Global.sleepUntil { Rs2Widget.getWidget(30015495) != null }
                        Global.sleep(600, 850)
                        Rs2Widget.clickWidget(30015495)
                        //Global.sleepUntil { Rs2GameObject.findObjectById(15277) == null }
                        Global.sleep(1200, 2200)
                    }
                    continue
                }
                Inventory.useItem(28767)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    override fun shutDown() {
        running = false
    }
}
