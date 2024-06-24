package net.runelite.client.plugins.microbot.trent.ironsuperheat

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.ItemID
import net.runelite.api.coords.WorldPoint
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.bankAt
import net.runelite.client.plugins.microbot.trent.api.sleepUntil
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.Global.sleep
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic
import net.runelite.client.plugins.microbot.util.math.Random
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import javax.inject.Inject

private enum class Metal(vararg val materials: Pair<Int, Int>) {
    IRON(ItemID.IRON_ORE to 1),
    STEEL(ItemID.IRON_ORE to 1, ItemID.COAL to 2),
}

private val metalToMake = Metal.STEEL

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
        if (superheat()) return
        if (bank()) return
        mine()
    }

    fun superheat(): Boolean {
        metalToMake.materials.forEach { ore ->
            if (!Rs2Inventory.hasItemAmount(ore.first, ore.second))
                return false
        }
        val targetOre = Rs2Inventory.get(metalToMake.materials[0].first)
        if (targetOre != null) {
            Rs2Magic.superheat(targetOre, 15, 63)
            Rs2Player.waitForAnimation()
            return true
        }
        return false
    }

    fun bank(): Boolean {
        if (Rs2Inventory.isFull()) {
            if (bankAt(31427, WorldPoint(3742, 3805, 0), "use")) {
                Rs2Bank.depositAll()
                Rs2Bank.withdrawAll("nature rune")
                Global.sleepUntil { Rs2Inventory.emptySlotCount() >= 10 }
                Rs2Bank.closeBank()
                Global.sleepUntil { !Rs2Bank.isOpen() }
            }
            return true
        }
        return false
    }

    fun mine() {
        when (metalToMake) {
            Metal.IRON -> mineRock(11364, 11365)
            Metal.STEEL -> {
                if (!Rs2Inventory.contains(ItemID.IRON_ORE))
                    mineRock(11364, 11365)
                else
                    mineRock(11366, 11367)
            }
        }
    }

    fun mineRock(vararg rockIds: Int) {
        val rock = Rs2GameObject.findObject(rockIds)
        if (rock == null) {
            if (Rs2Walker.walkTo(WorldPoint(3759, 3822, 0)))
                sleep(2500, 5520)
            return
        }
        if (Rs2GameObject.interact(rock, "mine")) {
            Rs2Player.waitForAnimation(Random.random(15338, 22932))
            sleepUntil(timeout = Random.random(6632, 12662)) { !Rs2Player.isAnimating() || Rs2GameObject.findObject(rock.id, rock.worldLocation) == null }
        }
    }
}