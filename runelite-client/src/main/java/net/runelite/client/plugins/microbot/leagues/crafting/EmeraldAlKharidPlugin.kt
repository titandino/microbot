package net.runelite.client.plugins.microbot.leagues.crafting

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.inventory.Inventory
import net.runelite.client.plugins.microbot.util.keyboard.VirtualKeyboard
import java.awt.event.KeyEvent
import java.util.function.BooleanSupplier
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Cut Emeralds Note",
    description = "Cuts emeralds with bankers note",
    tags = ["sorc", "garden", "thieve"],
    enabledByDefault = false
)
class EmeraldAlKharidPlugin : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false

    @OptIn(DelicateCoroutinesApi::class)
    override fun startUp() {
        if (client.getLocalPlayer() != null) {
            running = true
            GlobalScope.launch { run() }
        }
    }

    private fun run() {
        while (running) {
            if (!Inventory.hasItem(1621)) {
                Inventory.useItem(1605)
                Inventory.useItem(28767)
                Inventory.useItem(1622)
                Inventory.useItem(28767)
                Global.sleep(500, 1000)
            }
            Inventory.useItem("chisel")
            Inventory.useItem(1621)
            Global.sleep(600)
            VirtualKeyboard.keyPress(KeyEvent.VK_SPACE)
            Global.sleep(4000)
            Global.sleepUntil({ !Microbot.isGainingExp || !Inventory.hasItem(1621) }, 30000)
        }
    }

    override fun shutDown() {
        running = false
    }
}
