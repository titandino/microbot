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

val butterflies = setOf("Snowy knight", "Sapphire glacialis", "Sunlight Moth", "Moonlight moth")
var lastInteracted: Rs2NpcModel? = null

private class Root : State() {
    override fun checkNext(client: Client): State? {
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        val npc = Rs2NpcQueryable()
            .where { butterflies.contains(it.name) }
            .where { Rs2Walker.canReach(it.worldLocation, -2, -2) }
            .first() ?: return
        if (lastInteracted != null && Rs2Player.isInteracting()) {
            val distCurr = lastInteracted?.getLocalLocation()?.distanceTo(Rs2Player.getLocalLocation()) ?: 0
            val distNew = npc.getLocalLocation().distanceTo(Rs2Player.getLocalLocation())
            if (lastInteracted == npc || distNew >= distCurr) return
        }
        if (npc.click("pickpocket")) {
            lastInteracted = npc
            sleep(453, 722)
            if (random(0, 263) == 0)
                sleep(5332, 10692)
        }
    }
}
