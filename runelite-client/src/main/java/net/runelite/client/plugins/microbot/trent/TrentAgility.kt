package net.runelite.client.plugins.microbot.trent

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.GameObject
import net.runelite.api.ObjectID
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject
import net.runelite.client.plugins.microbot.util.inventory.Inventory
import net.runelite.client.plugins.microbot.util.keyboard.VirtualKeyboard
import net.runelite.client.plugins.microbot.util.math.Calculations
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget
import java.awt.event.KeyEvent
import javax.inject.Inject

val ardougneCourse = intArrayOf(ObjectID.WOODEN_BEAMS, ObjectID.GAP_15609, ObjectID.PLANK_26635, ObjectID.GAP_15610, ObjectID.GAP_15611, ObjectID.STEEP_ROOF, ObjectID.GAP_15612)

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Agility",
    description = "Cuts emeralds with bankers note",
    tags = ["sorc", "garden", "thieve"],
    enabledByDefault = false
)
class TrentAgility : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false

    @OptIn(DelicateCoroutinesApi::class)
    override fun startUp() {
        if (client.getLocalPlayer() != null) {
            running = true
            GlobalScope.launch { run() }
        }
    }

    private fun run() {
        while (running) {
            if (Microbot.isMoving() || Microbot.isAnimating())
                continue
            var obstacle = Rs2GameObject.getObject(ardougneCourse)
            if (obstacle == null)
                obstacle = Rs2GameObject.getGameObjects().firstOrNull { it.id == ObjectID.GAP_15611 }
            if (obstacle != null && Rs2GameObject.interact(obstacle)) {
                Global.sleep(266, 1114)
                Global.sleepUntil({ !Rs2Player.isMoving() && !Rs2Player.isAnimating() }, 15000)
            }
        }
    }

    override fun shutDown() {
        running = false
    }
}