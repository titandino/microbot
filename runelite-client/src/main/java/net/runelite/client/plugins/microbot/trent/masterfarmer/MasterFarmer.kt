package net.runelite.client.plugins.microbot.trent.masterfarmer

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.coords.WorldPoint
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.bankAt
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.Global.sleep
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.math.Random.random
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Master Farmer",
    description = "Thieves master farmer in Draynor",
    tags = ["thieving"],
    enabledByDefault = false
)
class MasterFarmer : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = MasterFarmerScript()

    @OptIn(DelicateCoroutinesApi::class)
    override fun startUp() {
        if (client.localPlayer != null) {
            running = true
            GlobalScope.launch { run() }
        }
    }

    private fun run() {
        while (running) {
            script.loop(client)
        }
    }

    override fun shutDown() {
        running = false
    }
}

class MasterFarmerScript : StateMachineScript() {
    override fun getStartState(): State {
        return Root()
    }
}

private val THIEVING_TILE = WorldPoint(3080, 3250, 0)

private class Root : State() {
    override fun checkNext(client: Client): State? {
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (Rs2Player.eatAt(70)) {
            Rs2Player.waitForAnimation()
            return
        }
        if (Rs2Inventory.isFull() || !Rs2Inventory.contains("salmon")) {
            if (bankAt(10355, WorldPoint(3091, 3245, 0))) {
                Rs2Bank.depositAll()
                Rs2Bank.withdrawX("salmon", 10)
                Global.sleepUntil { Rs2Inventory.hasItemAmount("salmon", 10) }
                Rs2Bank.closeBank()
                Global.sleepUntil { !Rs2Bank.isOpen() }
            }
            return
        }
        val farmer = Rs2Npc.getNpc(5730)
        if (farmer == null && !Rs2Walker.walkTo(THIEVING_TILE, 1)) {
            Rs2Walker.walkFastCanvas(THIEVING_TILE)
            return
        }
        if (Rs2Npc.interact(farmer, "pickpocket")) {
            sleep(453, 722)
            if (random(0, 263) == 0)
                sleep(5332, 10692)
        }
    }
}