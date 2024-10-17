package net.runelite.client.plugins.microbot.trent.wcguildplanks

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
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "WC Guild Plank Maker",
    description = "Makes planks in the WC guild.",
    tags = ["resource processing"],
    enabledByDefault = false
)
class WCGuildPlanker : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = WCGuildPlankerScript()

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

class WCGuildPlankerScript : StateMachineScript() {
    override fun getStartState(): State {
        return Root()
    }
}

private class Root : State() {
    override fun checkNext(client: Client): State? {
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (!Rs2Inventory.contains(1521)) {
            if (bankAt(28861, WorldPoint(1592, 3475, 0))) {
                Rs2Bank.depositAll(8778)
                Global.sleepUntil { !Rs2Inventory.contains(8778) }
                Rs2Bank.withdrawAll(1521)
                Global.sleepUntil { Rs2Inventory.contains(1521) }
                Rs2Bank.closeBank()
                Global.sleepUntil { !Rs2Bank.isOpen() }
            }
            return
        } else {
            val sawmillMan = Rs2Npc.getNpc(3101)
            if (sawmillMan == null || sawmillMan.worldLocation.distanceTo(Rs2Player.getWorldLocation()) > 10) {
                Rs2Walker.walkTo(WorldPoint(1626, 3500, 0))
                return
            }
            val buyButton = Rs2Widget.getWidget(17694735)
            if (buyButton != null) {
                Rs2Widget.clickWidget(buyButton)
                Global.sleepUntil { !Rs2Inventory.contains(1521) }
            } else if (Rs2Npc.interact(sawmillMan, "Buy-plank"))
                Global.sleepUntil { !Rs2Player.isMoving() }
        }
    }
}