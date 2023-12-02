package net.runelite.client.plugins.microbot.leagues

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.GameObject
import net.runelite.api.Skill
import net.runelite.api.WallObject
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.mining.uppermotherload.UpperMotherloadScript
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject
import net.runelite.client.plugins.microbot.util.inventory.Inventory
import net.runelite.client.plugins.microbot.util.math.Random
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc
import net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Fish Note",
    description = "Fishes and notes fish",
    tags = ["sorc", "garden", "thieve"],
    enabledByDefault = false
)
class FishNote : Plugin() {
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
                val fishSpot = Rs2Npc.getNpc(6825)
                if (fishSpot != null && Rs2Npc.interact(fishSpot, "Bait")) {
                    Global.sleep(2500, 6500)
                    Global.sleepUntil({ Inventory.isFull() || client.localPlayer.animation == -1 || Rs2Npc.getNpcs().firstOrNull { it.worldLocation == fishSpot.getWorldLocation() && fishSpot.id == it.id } == null }, 60000)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    override fun shutDown() {
        running = false
    }
}
