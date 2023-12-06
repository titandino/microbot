package net.runelite.client.plugins.microbot.trent

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.inventory.Inventory
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Master Farmer",
    description = "Thieves master farmer in draynor",
    tags = ["sorc", "garden", "thieve"],
    enabledByDefault = false
)
class MasterFarmerPlugin : Plugin() {
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
                    if (Rs2Bank.walkToBank()) {
                        Global.sleepUntil { !Microbot.isMoving() }
                        continue
                    }
                    Rs2Bank.useBank()
                    Global.sleep(1000, 2000)
                    Global.sleepUntil { !Microbot.isMoving() }
                    Rs2Bank.depositAll()
                    Rs2Bank.closeBank()
                    Global.sleep(1000, 2000)
                    continue
                }
                if (Rs2Npc.interact("Master farmer", "Pickpocket")) {
                    Global.sleep(1000, 2000)
                    Global.sleepUntil({ Inventory.count() >= 28 || Inventory.hasItemAmountStackable("coin pouch", 84) }, 60000)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    override fun shutDown() {
        running = false
    }
}