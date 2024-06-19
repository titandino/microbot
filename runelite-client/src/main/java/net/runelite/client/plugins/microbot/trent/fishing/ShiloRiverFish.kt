package net.runelite.client.plugins.microbot.trent.fishing

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.coords.WorldPoint
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.sleepUntil
import net.runelite.client.plugins.microbot.util.Global.sleep
import net.runelite.client.plugins.microbot.util.Global.sleepUntil
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.math.Random
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Shilo River Fish",
    description = "Fishes trout and salmon at shilo village",
    tags = ["fishing", "shilo", "salmon"],
    enabledByDefault = false
)
class ShiloRiverFish : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = ShiloRiverFishScript()

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

class ShiloRiverFishScript : StateMachineScript() {
    override fun getStartState(): State {
        return Root()
    }
}

private class Root : State() {
    override fun checkNext(client: Client): State? {
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (Rs2Inventory.isFull()) {
            val banker = Rs2Npc.getNpc("banker")
            if (banker == null && Rs2Walker.walkTo(WorldPoint(2852, 2955, 0), 10)) {
                sleep(1260, 5920)
                return
            }
            if (!Rs2Bank.isOpen()) {
                if (Rs2Npc.interact(banker, "bank"))
                    sleepUntil(timeout = 10000) { Rs2Bank.isOpen() }
                else
                    Rs2Walker.walkTo(WorldPoint(2852, 2955, 0), 10)
            } else if (Rs2Bank.isOpen()) {
                Rs2Bank.depositAll("raw trout")
                Rs2Bank.depositAll("raw salmon")
                sleepUntil { !Rs2Inventory.contains("raw salmon") && !Rs2Inventory.contains("raw trout") }
            }
            return
        }

        val fishingSpot = Rs2Npc.getNpc("rod fishing spot")
        if (fishingSpot == null && Rs2Walker.walkTo(WorldPoint(2846, 2970, 0), 10)) {
            sleep(1260, 5920)
            return
        }
        if (Rs2Npc.interact(fishingSpot, "lure")) {
            val loc = WorldPoint(fishingSpot.worldLocation.x, fishingSpot.worldLocation.y, fishingSpot.worldLocation.plane)
            sleepUntil(100, 15000) { !Rs2Player.isWalking() }
            Rs2Player.waitForAnimation()
            sleepUntil(100, Random.random(60500, 125020)) { (Rs2Npc.getNpcByIndex(fishingSpot.index) != null && !Rs2Npc.getNpcByIndex(fishingSpot.index).worldLocation.equals(loc)) || Rs2Inventory.isFull() || !Rs2Player.isAnimating() }
        } else
            Rs2Walker.walkTo(WorldPoint(2846, 2970, 0), 10)
    }
}