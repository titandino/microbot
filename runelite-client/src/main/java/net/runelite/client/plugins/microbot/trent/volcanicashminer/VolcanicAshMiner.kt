package net.runelite.client.plugins.microbot.trent.volcanicashminer

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
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.math.Random
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Volcanic Ash Miner",
    description = "Gathers volcanic ash and banks near volcanic mine",
    tags = ["mining"],
    enabledByDefault = false
)
class VolcanicAshMiner : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = VolcanicAshMinerScript()

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

class VolcanicAshMinerScript : StateMachineScript() {
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
            if (bankAt(30989, WorldPoint(3820, 3809, 0), "use")) {
                Rs2Bank.depositAll()
                Global.sleepUntil { Rs2Inventory.isEmpty() }
                Rs2Bank.closeBank()
                Global.sleepUntil { !Rs2Bank.isOpen() }
            }
            return
        }
        val rock = Rs2GameObject.findObjectById(30985)
        rock?.let {
            if (Rs2GameObject.interact(it, "mine")) {
                Rs2Player.waitForAnimation()
                sleepUntil(timeout = Random.random(78592, 221592)) { !Rs2Player.isAnimating() || Rs2GameObject.findObject(rock.id, rock.worldLocation) == null }
            }
        }
    }
}