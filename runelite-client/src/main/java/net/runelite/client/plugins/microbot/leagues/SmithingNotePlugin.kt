package net.runelite.client.plugins.microbot.leagues

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject
import net.runelite.client.plugins.microbot.util.inventory.Inventory
import net.runelite.client.plugins.microbot.util.keyboard.VirtualKeyboard
import java.awt.event.KeyEvent
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Smithing Note",
    description = "Smiths noted bars using bankers note",
    tags = ["sorc", "garden", "thieve"],
    enabledByDefault = false
)
class SmithingNotePlugin : Plugin() {
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
                if (!Inventory.hasItemAmount(2361, 1)) {
//                    Inventory.useItemSlot(0)
//                    Global.sleep(233, 451)
//                    Inventory.useItem(28767)
//                    Global.sleep(231, 511)
                    Inventory.useItem(2362)
                    Global.sleep(263, 484)
                    Inventory.useItem(28767)
                    Global.sleep(222, 300)
                    continue
                }
                Rs2GameObject.interact("Anvil");
                Global.sleep(1000, 1400)
                VirtualKeyboard.keyPress(KeyEvent.VK_SPACE)
                Global.sleepUntil({ !Inventory.hasItemAmount(2361, 1) }, 120000)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    override fun shutDown() {
        running = false
    }
}
