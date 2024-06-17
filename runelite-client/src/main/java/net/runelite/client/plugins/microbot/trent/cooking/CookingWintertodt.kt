package net.runelite.client.plugins.microbot.trent.cooking

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.sleepUntil
import net.runelite.client.plugins.microbot.util.Global.sleepUntil
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.math.Random
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget
import javax.inject.Inject

const val RAW_ITEM = "raw anchovies"

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Cook Wintertodt",
    description = "Cooks food at wintertodt",
    tags = ["cooking"],
    enabledByDefault = false
)
class CookingWintertodt : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = CookingWintertodtScript()

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

class CookingWintertodtScript : StateMachineScript() {
    override fun getStartState(): State {
        return Root()
    }
}

private class Root : State() {
    override fun checkNext(client: Client): State? {
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (!Rs2Inventory.contains(RAW_ITEM)) {
            if (!Rs2Bank.isOpen()) {
                if (Rs2GameObject.interact(29321, "bank"))
                    sleepUntil(timeout = 10000) { Rs2Bank.isOpen() }
            } else {
                Rs2Bank.depositAll()
                Rs2Bank.withdrawAll(RAW_ITEM)
                sleepUntil { Rs2Inventory.contains(RAW_ITEM) }
                Rs2Bank.closeBank()
                sleepUntil { !Rs2Bank.isOpen() }
            }
            return
        }
        if (Rs2Inventory.useUnNotedItemOnObject(RAW_ITEM, 29300)) {
            sleepUntil { Rs2Widget.getWidget(17694734) != null }
            if (Rs2Widget.clickWidget(17694734))
                sleepUntil(timeout = Random.random(85232, 96739)) { !Rs2Inventory.contains(RAW_ITEM) }
        }
    }
}