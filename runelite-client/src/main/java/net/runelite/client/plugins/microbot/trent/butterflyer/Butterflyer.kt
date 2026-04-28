package net.runelite.client.plugins.microbot.trent.butterflyer

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcQueryable
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.util.Global.sleep
import net.runelite.client.plugins.microbot.util.math.Rs2Random.between as random
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.reachable.Rs2Reachable
import net.runelite.client.plugins.microbot.shortestpath.WorldPointUtil
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

val butterflies = setOf("Ruby harvest", "Black warlock", "Snowy knight", "Sapphire glacialis", "Sunlight Moth", "Moonlight moth")
var lastInteracted: Rs2NpcModel? = null

private class Root : State() {
    override fun checkNext(client: Client): State? {
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        val playerLoc = Rs2Player.getLocalLocation() ?: return
        val playerWp = Rs2Player.getWorldLocation() ?: return
        val candidates = Rs2NpcQueryable()
            .where { butterflies.contains(it.name) }
            .toList()
            .filter { (it.worldLocation?.distanceTo(playerWp) ?: Int.MAX_VALUE) <= 12 }
            .sortedBy { it.worldLocation?.distanceTo(playerWp) ?: Int.MAX_VALUE }

        val reachable = Rs2Reachable.getReachableTiles(playerWp)
        val npc = candidates.firstOrNull { c ->
            val wl = c.worldLocation ?: return@firstOrNull false
            reachable.contains(WorldPointUtil.packWorldPoint(wl.x, wl.y, wl.plane))
        } ?: return

        if (lastInteracted != null && Rs2Player.isInteracting()) {
            val distCurr = lastInteracted?.localLocation?.distanceTo(playerLoc) ?: 0
            val distNew = npc.localLocation.distanceTo(playerLoc)
            if (lastInteracted?.index == npc.index || distNew >= distCurr) return
        }
        if (npc.click("catch")) {
            lastInteracted = npc
            sleep(453, 722)
            if (random(0, 263) == 0)
                sleep(5332, 10692)
        }
    }
}
