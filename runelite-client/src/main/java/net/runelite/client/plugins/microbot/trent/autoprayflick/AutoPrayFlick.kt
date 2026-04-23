package net.runelite.client.plugins.microbot.trent.autoprayflick

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.Prayer
import net.runelite.api.events.GameTick
import net.runelite.api.gameval.InterfaceID.Orbs.PRAYERBUTTON
import net.runelite.client.eventbus.Subscribe
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
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
        Rs2Widget.getWidget(PRAYERBUTTON) ?: return
        if (!Prayer.entries.any { Microbot.getVarbitValue(it.varbit) == 1 } && !firstFlick) {
            GlobalScope.launch {
                sleep(randomDelay(1, 9))
                Rs2Widget.clickWidget(PRAYERBUTTON)
            }
            return
        }
        GlobalScope.launch {
            sleep(randomDelay(1, 9))
            Rs2Widget.clickWidget(PRAYERBUTTON)
            sleep(randomDelay(93, 117))
            Rs2Widget.clickWidget(PRAYERBUTTON)
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