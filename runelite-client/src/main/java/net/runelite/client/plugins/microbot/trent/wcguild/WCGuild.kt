package net.runelite.client.plugins.microbot.trent.wcguild

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
    name = PluginDescriptor.Trent + "WC Guild",
    description = "Chops and banks logs in WC guild",
    tags = ["woodcutting"],
    enabledByDefault = false
)
class WCGuild : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = WCGuildScript()

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

class WCGuildScript : StateMachineScript() {
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
            if (bankAt(28861, WorldPoint(1592, 3475, 0))) {
                Rs2Bank.depositAll()
                Global.sleepUntil { Rs2Inventory.isEmpty() }
                Rs2Bank.closeBank()
                Global.sleepUntil { !Rs2Bank.isOpen() }
            }
            return
        }
        val tree = Rs2GameObject.get("Yew tree", true)
        tree?.let {
            if (Rs2GameObject.interact(it, "chop down")) {
                Rs2Player.waitForAnimation()
                sleepUntil(timeout = Random.random(78592, 221592)) { !Rs2Player.isAnimating() || Rs2GameObject.findObject(tree.id, tree.worldLocation) == null }
            }
        }
    }
}