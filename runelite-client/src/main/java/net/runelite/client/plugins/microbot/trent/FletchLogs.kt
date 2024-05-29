package net.runelite.client.plugins.microbot.trent

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.ItemID
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.inventory.Inventory
import net.runelite.client.plugins.microbot.util.keyboard.VirtualKeyboard
import java.awt.event.KeyEvent
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Fletcher",
    description = "Makes all potions possible in bank",
    tags = ["sorc", "garden", "thieve"],
    enabledByDefault = false
)
class FletchLogs : Plugin() {
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
                Rs2Bank.useBank()
                Global.sleep(600, 1000)
                Global.sleepUntil { Rs2Bank.isOpen() }
                Rs2Bank.depositAll(58)
                attempt(10) {
                    Rs2Bank.depositAll(58)
                    Global.sleep(600, 1000)
                    return@attempt !Inventory.hasItemAmount(58, 1)
                }
                Global.sleep(600, 1000)
                attempt(10) {
                    Rs2Bank.withdrawAll(1519)
                    Global.sleep(600, 1000)
                    return@attempt Inventory.hasItemAmount(1519, 1)
                }
                Rs2Bank.closeBank();
                Global.sleep(1000, 2000)
                Inventory.useItem(946)
                Global.sleep(300, 600)
                Inventory.useItem(1519)
                Global.sleep(600, 1000)
                VirtualKeyboard.keyPress(KeyEvent.VK_SPACE)
                Global.sleepUntil({ !Inventory.hasItemAmount(1519, 1) }, 40000)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }


    override fun shutDown() {
        running = false
    }
}
