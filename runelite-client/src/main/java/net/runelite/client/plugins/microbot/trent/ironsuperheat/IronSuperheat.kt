package net.runelite.client.plugins.microbot.trent.ironsuperheat

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
import net.runelite.client.plugins.microbot.util.inventory.Rs2Item
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic
import net.runelite.client.plugins.microbot.util.math.Random
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Iron Superheater",
    description = "Mines iron and superheats it",
    tags = ["mining"],
    enabledByDefault = false
)
class IronSuperheat : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = IronSuperheatScript()

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

class IronSuperheatScript : StateMachineScript() {
    override fun getStartState(): State {
        return Root()
    }
}

private class Root : State() {
    override fun checkNext(client: Client): State? {
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        val iron = Rs2Inventory.get(440)
        if (iron != null) {
            Rs2Magic.superheat(iron, 50, 126)
            Rs2Player.waitForAnimation()
            return
        }
        if (Rs2Inventory.isFull()) {
            if (bankAt(31427, WorldPoint(3742, 3805, 0), "use")) {
                Rs2Bank.depositAll(2351)
                Global.sleepUntil { Rs2Inventory.emptySlotCount() >= 25 }
                Rs2Bank.closeBank()
                Global.sleepUntil { !Rs2Bank.isOpen() }
            }
            return
        }
        val rock = Rs2GameObject.findObject(intArrayOf(11364, 11365))
        if (rock == null) {
            Rs2Walker.walkTo(WorldPoint(3759, 3822, 0))
            return
        }
        if (Rs2GameObject.interact(rock, "mine")) {
            Rs2Player.waitForAnimation()
            sleepUntil(timeout = Random.random(3523, 6528)) { !Rs2Player.isAnimating() || Rs2GameObject.findObject(rock.id, rock.worldLocation) == null }
        }
    }
}