package net.runelite.client.plugins.microbot.trent.teakfiremaking

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.coords.WorldPoint
import net.runelite.api.gameval.ItemID
import net.runelite.api.gameval.ObjectID
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectQueryable
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.sleepUntil
import net.runelite.client.plugins.microbot.util.Global.sleepUntil
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.math.Rs2Random
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

private val TEAK_TILE = WorldPoint(2335, 3048, 0)

private fun findTreeAt(id: Int, tile: WorldPoint): Rs2TileObjectModel? =
    Rs2TileObjectQueryable()
        .withId(id)
        .where { it.worldLocation == tile }
        .first()

private class Woodcut : State() {
    override fun checkNext(client: Client): State? {
        if (Rs2Inventory.isFull() || (findTreeAt(9036, TEAK_TILE) == null && Rs2Inventory.count(ItemID.TEAK_LOGS) >= 10))
            return Firemake()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        val tree = findTreeAt(9036, TEAK_TILE)
        tree?.let {
            if (it.click("chop down")) {
                Rs2Player.waitForAnimation()
                sleepUntil(timeout = Rs2Random.between(52592, 78592)) { findTreeAt(9036, TEAK_TILE) == null }
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
        if (findTreeAt(ObjectID.FIRE, client.localPlayer.worldLocation) != null) {
            val tile = START_TILES.find { findTreeAt(ObjectID.FIRE, it) == null }
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