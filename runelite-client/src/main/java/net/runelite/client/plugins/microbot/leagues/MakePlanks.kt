package net.runelite.client.plugins.microbot.leagues

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.Skill
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject
import net.runelite.client.plugins.microbot.util.inventory.Inventory
import net.runelite.client.plugins.microbot.util.keyboard.VirtualKeyboard
import net.runelite.client.plugins.microbot.util.math.Random
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc
import java.awt.event.KeyEvent
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Make Planks",
    description = "Woodcuts and notes logs",
    tags = ["sorc", "garden", "thieve"],
    enabledByDefault = false
)
class MakePlanks : Plugin() {
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
                if (Inventory.hasItem(6333)) {
                    Rs2Npc.interact("Sawmill operator", "Buy-plank")
                    Global.sleep(600, 800)
                    VirtualKeyboard.keyPress(KeyEvent.VK_SPACE)
                    Global.sleep(1200, 2500)
                    continue
                }
                if (Inventory.hasItem(8780)) {
                    Inventory.useItem(8780)
                    Global.sleep(150, 310)
                    Inventory.useItem(28767)
                    Global.sleep(1200, 2500)
                    continue
                }
                Inventory.useItem(6334)
                Global.sleep(150, 310)
                Inventory.useItem(28767)
                Global.sleep(1200, 2500)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    override fun shutDown() {
        running = false
    }
}
