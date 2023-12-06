package net.runelite.client.plugins.microbot.trent

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.Skill
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject
import net.runelite.client.plugins.microbot.util.inventory.Inventory
import net.runelite.client.plugins.microbot.util.math.Random
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Mine Note",
    description = "Mines and notes bars",
    tags = ["sorc", "garden", "thieve"],
    enabledByDefault = false
)
class MineNotePlugin : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false

    @OptIn(DelicateCoroutinesApi::class)
    override fun startUp() {
        if (client.getLocalPlayer() != null) {
            running = true;
            GlobalScope.launch { run() }
        }
    }

    private fun run() {
        while (running) {
            try {
                if (Inventory.isFull()) {
                    Inventory.useItemSlot(Random.random(0, 6))
                    Global.sleep(500, 1000)
                    Inventory.useItem(28767)
                    Global.sleep(500, 1000)
                    continue
                }
                if (!Microbot.hasLevel(85, Skill.MINING) || !mineRock("Runite rocks"))
                    if (!Microbot.hasLevel(70, Skill.MINING) || !mineRock("Adamantite rocks"))
                        if (!Microbot.hasLevel(55, Skill.MINING) || !mineRock("Mithril rocks"))
                            mineRock("Iron rocks")
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    private fun mineRock(rockName: String?): Boolean {
        val rock = Rs2GameObject.findObject(rockName) ?: return false
        if (rock.worldLocation.distanceTo(client.localPlayer.worldLocation) <= 14 && Rs2GameObject.interact(rock, "Mine")) {
            Global.sleepUntil { Rs2GameObject.getGameObjects().firstOrNull { it.worldLocation == rock.getWorldLocation() && rock.id == it.id } == null }
            return true
        }
        return false
    }

    override fun shutDown() {
        running = false
    }
}
