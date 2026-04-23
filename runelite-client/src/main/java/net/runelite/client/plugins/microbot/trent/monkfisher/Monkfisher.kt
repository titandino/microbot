package net.runelite.client.plugins.microbot.trent.monkfisher

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.coords.WorldPoint
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcQueryable
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.sleepUntil
import net.runelite.client.plugins.microbot.util.Global.sleep
import net.runelite.client.plugins.microbot.util.Global.sleepUntil
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.math.Rs2Random
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Monkfisher",
    description = "Fishes monkfish",
    tags = ["fishing"],
    enabledByDefault = false
)
class Monkfisher : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = MonkfisherScript()

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

class MonkfisherScript : StateMachineScript() {
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
            val banker = Rs2NpcQueryable().withName("arnold lydspor").nearest()
            if (banker == null && Rs2Walker.walkTo(WorldPoint(2329, 3689, 0), 5)) {
                sleep(1260, 5920)
                return
            }
            if (!Rs2Bank.isOpen()) {
                if (banker != null && banker.click("bank"))
                    sleepUntil(timeout = 10000) { Rs2Bank.isOpen() }
                else
                    Rs2Walker.walkTo(WorldPoint(2329, 3689, 0), 5)
            } else if (Rs2Bank.isOpen()) {
                Rs2Bank.depositAll("raw monkfish")
                sleepUntil { !Rs2Inventory.contains("raw monkfish") }
            }
            return
        }

        val fishingSpot = Rs2NpcQueryable().withName("fishing spot").nearest()
        if (fishingSpot == null && Rs2Walker.walkTo(WorldPoint(2343, 3699, 0), 10)) {
            sleep(1260, 5920)
            return
        }
        if (fishingSpot != null && fishingSpot.click("net")) {
            val loc = WorldPoint(fishingSpot.worldLocation.x, fishingSpot.worldLocation.y, fishingSpot.worldLocation.plane)
            sleepUntil(100, 15000) { !Rs2Player.isMoving() }
            Rs2Player.waitForAnimation()
            sleepUntil(100, Rs2Random.between(60500, 125020)) {
                val spot = Rs2NpcQueryable().where { it.index == fishingSpot.index }.first()
                (spot != null && spot.worldLocation != loc)
                    || Rs2Inventory.isFull() || !Rs2Player.isAnimating()
            }
        } else
            Rs2Walker.walkTo(WorldPoint(2343, 3699, 0), 10)
    }
}