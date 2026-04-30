package net.runelite.client.plugins.microbot.trent.combat

import com.google.inject.Provides
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.Varbits
import net.runelite.client.config.Config
import net.runelite.client.config.ConfigGroup
import net.runelite.client.config.ConfigItem
import net.runelite.client.config.ConfigManager
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcQueryable
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.sleepUntil
import net.runelite.client.plugins.microbot.util.Global.sleep
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import javax.inject.Inject

@ConfigGroup("trentcombat")
interface CombatConfig : Config {
    @ConfigItem(
        keyName = "monsterName",
        name = "Monster name",
        description = "Case-insensitive substring matched against NPC names",
        position = 0
    )
    fun monsterName(): String = ""

    @ConfigItem(
        keyName = "maxRange",
        name = "Max range",
        description = "Tile radius from the player to consider candidates",
        position = 1
    )
    fun maxRange(): Int = 10
}

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Combat",
    description = "Attacks the configured monster by name; LOS- and engagement-aware",
    tags = ["combat", "pvm"],
    enabledByDefault = false
)
class Combat : Plugin() {
    @Inject
    private lateinit var config: CombatConfig

    @Provides
    fun provideConfig(configManager: ConfigManager): CombatConfig =
        configManager.getConfig(CombatConfig::class.java)

    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = CombatScript { config }

    @OptIn(DelicateCoroutinesApi::class)
    override fun startUp() {
        if (client.localPlayer != null) {
            running = true
            GlobalScope.launch { run() }
        }
    }

    private fun run() {
        while (running) {
            try {
                script.loop(client)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                running = false
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    override fun shutDown() {
        running = false
    }
}

class CombatScript(private val configProvider: () -> CombatConfig) : StateMachineScript() {
    override fun getStartState(): State = Root(configProvider)
}

private class Root(private val configProvider: () -> CombatConfig) : State() {
    override fun checkNext(client: Client): State? = null

    override fun loop(client: Client, script: StateMachineScript) {
        val cfg = configProvider()
        val name = cfg.monsterName().trim()
        if (name.isEmpty()) {
            sleep(400, 700)
            return
        }

        if (Rs2Player.isInteracting()) {
            sleep(200, 350)
            return
        }

        val localPlayer = client.localPlayer ?: run { sleep(200, 400); return }
        val playerLoc = Rs2Player.getWorldLocation() ?: run { sleep(200, 400); return }
        val inMulti = client.getVarbitValue(Varbits.MULTICOMBAT_AREA) == 1
        val maxRange = cfg.maxRange().coerceAtLeast(1)

        val target: Rs2NpcModel? = Rs2NpcQueryable()
            .withNameContains(name)
            .within(maxRange)
            .where { npc ->
                if (npc.isDead) return@where false
                if (!npc.hasLineOfSight()) return@where false
                if (inMulti) return@where true
                // skip if getInteracting() != localPlayer because single-combat blocks attacks on engaged NPCs
                val interacting = npc.interacting
                if (interacting != null && interacting !== localPlayer) return@where false
                // HP bar visible (ratio in [0, scale-1]) implies recent damage from someone — likely taken
                if (npc.healthRatio in 0..(npc.healthScale - 1)) return@where false
                true
            }
            .toList()
            .minByOrNull { it.worldLocation.distanceTo(playerLoc) }

        if (target == null) {
            sleep(300, 600)
            return
        }

        if (target.click("Attack")) {
            sleepUntil(checkEvery = 100, timeout = 2_000) { Rs2Player.isInteracting() }
        } else {
            sleep(200, 400)
        }
    }
}
