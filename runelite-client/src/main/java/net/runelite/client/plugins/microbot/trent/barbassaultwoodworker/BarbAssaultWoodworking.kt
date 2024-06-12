package net.runelite.client.plugins.microbot.trent.barbassaultwoodworker

import com.google.inject.Provides
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.client.config.*
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.wintertodt.MWintertodtConfig
import javax.inject.Inject

enum class ProcessingTask {
    Firemake,
    Fletch
}

@ConfigGroup("barbassaultwoodworker")
interface BarbAssaultWoodworkingConfig : Config {
    companion object {
        @ConfigSection(
            name = "General",
            description = "General",
            position = 0
        )
        const val GENERAL_SECTION = "general"

        @ConfigSection(
            name = "Task",
            description = "Task",
            position = 1
        )
        const val TASK_SECTION = "Task"
    }

    @ConfigItem(
        keyName = "RelightBrazier",
        name = "Relight Brazier",
        description = "If the braziers go out, relighting the brazier will reward 6x the player's Firemaking level in experience.",
        position = 1,
        section = GENERAL_SECTION
    )
    fun relightBrazier(): Boolean = true

    @ConfigItem(
        keyName = "FletchRoots",
        name = "Fletch roots into kindlings",
        description = "Bruma kindling is obtained by using a knife on a bruma root, granting Fletching experience appropriate to the player's level. The Fletching experience given is equal to 0.6 times the player's Fletching level.",
        position = 2,
        section = GENERAL_SECTION
    )
    fun fletchRoots(): Boolean = true

    @ConfigItem(
        keyName = "OpenCrates",
        name = "Open Supply Crates",
        description = "Open supply crates",
        position = 4,
        section = GENERAL_SECTION
    )
    fun openCrates(): Boolean = true

    @ConfigItem(
        keyName = "AxeInventory",
        name = "Axe In Inventory?",
        description = "Axe in inventory?",
        position = 5,
        section = GENERAL_SECTION
    )
    fun axeInInventory(): Boolean = false

    @ConfigItem(
        keyName = "Task",
        name = "Task",
        description = "Select what you would like to do with the gathered wood",
        position = 1,
        section = TASK_SECTION
    )
    fun task(): ProcessingTask = ProcessingTask.Firemake
}

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Barb Woodworking",
    description = "Woodcuts, fletches, firemakes at barbarian assault",
    tags = ["woodcutting", "fletching", "firemaking"],
    enabledByDefault = false
)
class BarbAssaultWoodworking : Plugin() {
    @Inject
    private lateinit var config: BarbAssaultWoodworkingConfig

    @Provides
    fun provideConfig(configManager: ConfigManager): BarbAssaultWoodworkingConfig {
        return configManager.getConfig(BarbAssaultWoodworkingConfig::class.java)
    }

    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = BarbAssaultWoodworkingScript()

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

class BarbAssaultWoodworkingScript : StateMachineScript() {
    override fun getStartState(): State {
        return Root()
    }
}

private class Root : State() {
    override fun checkNext(client: Client): State? {
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {

    }
}