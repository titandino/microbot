package net.runelite.client.plugins.microbot.trent.soul_wars

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.coords.WorldArea
import net.runelite.api.coords.WorldPoint
import net.runelite.api.kit.KitType
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Soul Wars",
    description = "Soul wars (not good)",
    tags = ["sorc", "garden", "thieve"],
    enabledByDefault = false
)
class SoulWars : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script: SWScript = SWScript()

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

class SWScript : StateMachineScript() {
    override fun getStartState(): State {
        return WhereAmI()
    }
}

class WhereAmI : State() {
    override fun checkNext(client: Client): State? {
        if (client.localPlayer.worldLocation.regionID == 8748)
            return WaitInLobby()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {

    }
}

val LOBBY_WAITING = WorldArea(2190, 2838, 10, 10, 0)

class WaitInLobby : State() {
    override fun checkNext(client: Client): State? {
        if (client.localPlayer.worldLocation.regionID == 8748)
            return null
        return GameLoop()
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (!client.localPlayer.worldLocation.isInArea2D(LOBBY_WAITING)) {
            Rs2GameObject.interact(41199)
            Global.sleepUntil { client.localPlayer.worldLocation.isInArea2D(LOBBY_WAITING) }
        }
    }
}

val BLUE_RESPAWN = WorldArea(2136, 2900, 8, 11, 0)
val WEST_RESPAWN = WorldArea(2161, 2897, 3, 3, 0)
val EAST_RESPAWN = WorldArea(2252, 2924, 3, 3, 0)
val RED_RESPAWN = WorldArea(2270, 2914, 9, 11, 0)

class GameLoop : State() {
    override fun checkNext(client: Client): State? {
        if (client.localPlayer.worldLocation.regionID == 8748)
            return WaitInLobby()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if ((Rs2Inventory.hasItem(25203) || Rs2Inventory.hasItem(25204) || Rs2Inventory.hasItem(25205) || Rs2Inventory.hasItem(25206)) && Microbot.getVarbitValue(9794) < 250) {
            if (!Rs2Inventory.interact(25206))
                if (!Rs2Inventory.interact(25205))
                    if (!Rs2Inventory.interact(25204))
                        Rs2Inventory.interact(25203)
            return
        }
        if (WorldPoint.fromLocalInstance(client, client.getLocalPlayer().getLocalLocation()).isInArea2D(BLUE_RESPAWN, WEST_RESPAWN, EAST_RESPAWN, RED_RESPAWN)) {
            if (!Rs2Inventory.hasItem(25203)) {
                Rs2GameObject.interact("Potion of power table", "Take-10")
                Global.sleepUntil { Rs2Inventory.hasItem(25203) }
            }
            Rs2GameObject.interact(intArrayOf(40457, 40455, 40453, 40454), "Pass")
            Global.sleepUntil { !WorldPoint.fromLocalInstance(client, client.getLocalPlayer().getLocalLocation()).isInArea2D(BLUE_RESPAWN, WEST_RESPAWN, EAST_RESPAWN, RED_RESPAWN) }
            return
        }
        val enemies = client.players
            .filter { it.team != 0 && it.playerComposition.getEquipmentId(KitType.CAPE) != client.localPlayer.playerComposition.getEquipmentId(KitType.CAPE) }
            .sortedBy { it.getLocalLocation().distanceTo(client.localPlayer.localLocation) }

        if (enemies.isNotEmpty()) {
            //Rs2Player.interact(enemies.first(), "attack") TODO
            Global.sleep(1500, 2500)
            Global.sleepUntil({ !Rs2Player.isInteracting() }, 25000)
            return
        }
        var tiles = WorldPoint.toLocalInstance(client, WorldPoint(2286, 2931, 0))
        if (tiles.isEmpty())
            tiles = WorldPoint.toLocalInstance(client, WorldPoint(2126, 2891, 0))
        if (tiles.isNotEmpty() && !WorldPoint.fromLocalInstance(client, client.getLocalPlayer().getLocalLocation()).isInArea2D(WorldArea(WorldPoint(2286, 2931, 0), 3, 3), WorldArea(WorldPoint(2126, 2891, 0), 3, 3))) {
            Rs2Walker.walkTo(tiles.first())
            Global.sleep(2500, 3500)
            Global.sleepUntil({ !Rs2Player.isInteracting() }, 25000)
        }
    }
}