package net.runelite.client.plugins.microbot.leagues

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.inventory.Inventory
import net.runelite.client.plugins.microbot.util.keyboard.VirtualKeyboard
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget
import java.awt.event.KeyEvent
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
                Rs2Npc.interact(2874, "Trade")
                Global.sleepUntil({ Rs2Widget.findWidget("Gem Trader.", null) != null }, 30000)
                repeatUntil {
                    Rs2Widget.clickWidgetFast(Rs2Widget.getWidget(19726336), 5, 5)
                    return@repeatUntil !Inventory.hasItem(1605)
                }
                repeatForTime(4461, 6151) {
                    Rs2Widget.clickWidgetFast(Rs2Widget.getWidget(19660816), 2,2)
                    Global.sleep(25, 85)
                }
                VirtualKeyboard.keyPress(KeyEvent.VK_ESCAPE)
                Global.sleep(900)
            }
            Inventory.useItem("chisel")
            Global.sleep(300, 600)
            Inventory.useItem(1621)
            Global.sleep(800)
            VirtualKeyboard.keyPress(KeyEvent.VK_SPACE)
            Global.sleep(4000)
            Global.sleepUntil({ !Inventory.hasItem(1621) }, 30000)
        }
    }

    override fun shutDown() {
        running = false
    }
}
