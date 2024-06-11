package net.runelite.client.plugins.microbot.trent.ardystalls

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
import net.runelite.client.plugins.microbot.trent.api.sleepUntil
import net.runelite.client.plugins.microbot.trent.barbassaultwoodworker.BarbAssaultWoodworkingScript
import net.runelite.client.plugins.microbot.trent.cooking.RAW_ITEM
import net.runelite.client.plugins.microbot.util.Global.sleep
import net.runelite.client.plugins.microbot.util.Global.sleepUntil
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.math.Random
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget
import javax.inject.Inject

val THIEVING_TILE = WorldPoint(2669, 3310, 0)

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Ardougne Stall Thiever",
    description = "Steals and banks cakes in ardougne",
    tags = ["thieving", "cake", "ardougne"],
    enabledByDefault = false
)
class ArdougneStallThiever : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = ArdougneStallScript()

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

class ArdougneStallScript : StateMachineScript() {
    override fun getStartState(): State {
        return Root()
    }
}

private class Root : State() {
    override fun checkNext(client: Client): State? {
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (Rs2Combat.inCombatNotBraindamaged()) {
            Rs2Walker.walkTo(WorldPoint(2656, 3286, 0))
            return
        }
        if (Rs2Inventory.contains("chocolate slice", "bread")) {
            Rs2Inventory.dropAll(650, 820, "chocolate slice", "bread")
            return
        }
        if (Rs2Inventory.isFull()) {
            if (bankAt(10355, WorldPoint(2656, 3286, 0))) {
                Rs2Bank.depositAll()
                sleepUntil { Rs2Inventory.isEmpty() }
                Rs2Bank.closeBank()
                sleepUntil { !Rs2Bank.isOpen() }
            }
            return
        }
        if (!Rs2Player.getWorldLocation().equals(THIEVING_TILE)) {
            println("walking to thieving tile")
            if (!Rs2Walker.walkTo(THIEVING_TILE, 1))
                Rs2Walker.walkFastCanvas(THIEVING_TILE)
            return
        }
        val stall = Rs2GameObject.findObject(11730, WorldPoint(2667, 3310, 0))
        stall?.let {
            if (Rs2GameObject.interact(it, "steal-from")) {
                Rs2Player.waitForAnimation()
                sleep(600, 850)
            }
        }
    }
}