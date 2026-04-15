package net.runelite.client.plugins.microbot.trent.tempoross

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.AnimationID.LOOKING_INTO
import net.runelite.api.ChatMessageType
import net.runelite.api.Client
import net.runelite.api.ItemID
import net.runelite.api.ObjectID.*
import net.runelite.api.coords.WorldPoint
import net.runelite.api.events.AnimationChanged
import net.runelite.api.events.ChatMessage
import net.runelite.client.eventbus.Subscribe
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.percentageTextToInt
import net.runelite.client.plugins.microbot.trent.api.sleepUntil
import net.runelite.client.plugins.microbot.util.Global.sleep
import net.runelite.client.plugins.microbot.util.Global.sleepUntil
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Tempoross Solo",
    description = "Tempoross Solo",
    tags = ["fishing", "tempoross"],
    enabledByDefault = false
)
class TrentTempoross : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = TemporossScript()

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
    fun onChatMessage(message: ChatMessage) {
        script.eventReceived(client, message)
    }
    @Subscribe
    fun onAnimationChanged(event: AnimationChanged) {
        script.eventReceived(client, event)
    }
}

private fun getEnergy(): Int = percentageTextToInt(28639267)
private fun getEssence(): Int = percentageTextToInt(28639277)
private fun getStormIntensity(): Int = percentageTextToInt(28639287)

private class TemporossScript : StateMachineScript() {
    override fun getStartState(): State {
        return Root()
    }
}

private class Root : State() {
    override fun checkNext(client: Client): State? {
        if (client.localPlayer.worldLocation.regionID == 6462)
            return Ingame()
        else if (client.localPlayer.worldLocation.regionID == 6461)
            return PrepareForGame()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) { }
}

private class Ingame : State() {
    override fun checkNext(client: Client): State? {
        if (intArrayOf(12332, 12588).contains(client.localPlayer.worldLocation.regionID))
            return PrepareForGame()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        //grab 1 harpoon, 1 hammer, 1 rope, 6 buckets if not in inventory before real game starts
        //catch 8 fish and start cooking them
        //if double fishing spot spawns, immediately stop cooking and go to it
        //16 cooked fish first rotation (dodge 1 wave by tethering to post if it shows up)
        //fill ammo crate with 16 fish
        //proceed to get full inventory of cooked fish
        //if 90% storm intensity while cooking these first rotation drop all cooked fish into crate immediately before full inventory is cooked
        //otherwise just drop all fish in inventory into the ammo crate then attack tempoross
        //after tempoross gets his energy back to full, repeat making full inventories of cooked fish and attacking tempoross after filling crate until it dies
    }
}

private class PrepareForGame : State() {
    override fun checkNext(client: Client): State? {
        if (client.localPlayer.worldLocation.regionID == 12078)
            return Ingame()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        //if not in regionID 12332, climb boat ladder
        //else
        //fill any empty buckets on the tap until game starts
    }
}