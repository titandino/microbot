package net.runelite.client.plugins.microbot.trent.autoprayflick

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.Prayer
import net.runelite.api.events.GameTick
import net.runelite.api.events.VarbitChanged
import net.runelite.api.widgets.ComponentID.MINIMAP_QUICK_PRAYER_ORB
import net.runelite.client.eventbus.Subscribe
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.util.Global.sleep
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget
import java.security.SecureRandom
import javax.inject.Inject


@PluginDescriptor(
    name = PluginDescriptor.Trent + "Auto Pray Flick",
    description = "Perfectly flicks prayers",
    tags = ["prayer", "combat"],
    enabledByDefault = false
)
class AutoPrayFlick : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private var firstFlick = true

    override fun startUp() {
        if (client.localPlayer != null) {
            running = true
        }
    }

    override fun shutDown() {
        running = false
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Subscribe
    fun onGameTick(gameTick: GameTick) {
        Rs2Widget.getWidget(MINIMAP_QUICK_PRAYER_ORB) ?: return
        if (!Prayer.entries.any { client.isPrayerActive(it) } && !firstFlick) {
            GlobalScope.launch {
                sleep(randomDelay(1, 9))
                Rs2Widget.clickWidget(MINIMAP_QUICK_PRAYER_ORB)
            }
            return
        }
        GlobalScope.launch {
            sleep(randomDelay(1, 9))
            Rs2Widget.clickWidget(MINIMAP_QUICK_PRAYER_ORB)
            sleep(randomDelay(93, 117))
            Rs2Widget.clickWidget(MINIMAP_QUICK_PRAYER_ORB)
        }
        if (firstFlick)
            firstFlick = false
    }
}

private fun randomDelay(min: Int, max: Int): Int {
    val rand = SecureRandom()
    var n: Int = rand.nextInt(max) + 1
    if (n < min) {
        n += min
    }
    return n
}