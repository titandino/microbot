package net.runelite.client.plugins.microbot.trent.wguild

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.events.VarbitChanged
import net.runelite.client.eventbus.Subscribe
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.sleepUntil
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.math.Random
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Warrior's Guild",
    description = "Gathers tokens in warrior's guild using defence training",
    tags = ["combat"],
    enabledByDefault = false
)
class WGuild : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = WGuildScript()

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

    @Subscribe
    fun onVarbitChanged(varbitChanged: VarbitChanged) {
        if (varbitChanged.varbitId == 2247)
            currentVarbit = varbitChanged.value
    }
}

class WGuildScript : StateMachineScript() {
    override fun getStartState(): State {
        return Root()
    }
}

private var currentVarbit: Int = 0

private enum class Style(val varBit: Int, val projId: Int, val widgetHash: Int) {
    STAB(1, 679, 26935302),
    CRUSH(2, 680, 26935304),
    SLASH(3, 681, 26935306),
    MAGIC(0, 682, 26935308)
}

private class Root : State() {
    override fun checkNext(client: Client): State? {
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        val projectile = client.projectiles.firstOrNull { Style.entries.any { style -> it.id == style.projId } } ?: return
        val style = Style.entries.first { it.projId == projectile.id }
        if (currentVarbit != style.varBit && client.gameCycle > projectile.startCycle) {
            Global.sleep(Random.random(562, 1159))
            Rs2Widget.clickWidget(style.widgetHash)
            sleepUntil { currentVarbit == style.varBit }
        }
    }
}