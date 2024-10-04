package net.runelite.client.plugins.microbot.trent.butterflyer

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.NPC
import net.runelite.api.coords.WorldPoint
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.bankAt
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.Global.sleep
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.math.Random.random
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Butterflyer",
    description = "Butterflies stuff",
    tags = ["hunter"],
    enabledByDefault = false
)
class Butterflyer : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = ButterflyerScript()

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

class ButterflyerScript : StateMachineScript() {
    override fun getStartState(): State {
        return Root()
    }
}

val butterflies = setOf("Snowy knight", "Sapphire glacialis")
var lastInteracted: NPC? = null

private class Root : State() {
    override fun checkNext(client: Client): State? {
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        val npc = Rs2Npc.getNpcs().filter { butterflies.contains(it.name) }.findFirst()
        if (npc.isEmpty && !Rs2Walker.walkTo(WorldPoint(1436, 3241, 0), 5)) {
            Rs2Player.waitForWalking()
            return
        }
        if (npc.isEmpty) return
        if (lastInteracted != null && Rs2Player.isInteracting()) {
            val distCurr = lastInteracted?.getLocalLocation()?.distanceTo(Rs2Player.getLocalLocation()) ?: 0
            val distNew = npc.get().getLocalLocation().distanceTo(Rs2Player.getLocalLocation())
            if (lastInteracted == npc.get() || distNew >= distCurr) return
        }
        if (Rs2Npc.interact(npc.get(), "pickpocket")) {
            lastInteracted = npc.get()
            sleep(453, 722)
            if (random(0, 263) == 0)
                sleep(5332, 10692)
        }
    }
}