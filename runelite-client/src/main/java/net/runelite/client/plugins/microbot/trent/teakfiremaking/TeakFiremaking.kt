package net.runelite.client.plugins.microbot.trent.teakfiremaking

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.ItemID
import net.runelite.api.ObjectID
import net.runelite.api.coords.WorldPoint
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.sleepUntil
import net.runelite.client.plugins.microbot.util.Global.sleepUntil
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.math.Random
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Teak Firemaking",
    description = "Chops and firemakes in castle wars area",
    tags = ["woodcutting", "firemaking"],
    enabledByDefault = false
)
class TeakFiremaking : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = TeakFiremakingScript()

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

class TeakFiremakingScript : StateMachineScript() {
    override fun getStartState(): State {
        return Woodcut()
    }
}

private val START_TILES = arrayOf(WorldPoint(2335, 3049,0), WorldPoint(2334, 3048, 0), WorldPoint(2335, 3047, 0), WorldPoint(2335, 3046, 0), WorldPoint(2337, 3045, 0))

private class Woodcut : State() {
    override fun checkNext(client: Client): State? {
        if (Rs2Inventory.isFull() || (Rs2GameObject.findObject(9036, WorldPoint(2335, 3048, 0)) == null && Rs2Inventory.count(ItemID.TEAK_LOGS) >= 10))
            return Firemake()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        val tree = Rs2GameObject.findObject(9036, WorldPoint(2335, 3048, 0))
        tree?.let {
            if (Rs2GameObject.interact(it, "chop down")) {
                Rs2Player.waitForAnimation()
                sleepUntil(timeout = Random.random(52592, 78592)) { Rs2GameObject.findObject(9036, WorldPoint(2335, 3048, 0)) == null }
            }
        }
    }
}

private class Firemake : State() {
    override fun checkNext(client: Client): State? {
        if (!Rs2Inventory.contains(ItemID.TEAK_LOGS))
            return Woodcut()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (Rs2GameObject.findObject(ObjectID.FIRE_26185, client.getLocalPlayer().getWorldLocation()) != null) {
            val tile = START_TILES.find { Rs2GameObject.findObject(ObjectID.FIRE_26185, it) == null }
            if (tile != null) {
                Rs2Walker.walkFastCanvas(START_TILES.random())
                Rs2Player.waitForWalking()
            }
            return
        }
        Rs2Inventory.use(ItemID.TINDERBOX)
        Rs2Inventory.use(ItemID.TEAK_LOGS)
        Rs2Player.waitForWalking()
    }
}