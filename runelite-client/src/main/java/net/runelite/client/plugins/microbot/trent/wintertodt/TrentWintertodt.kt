package net.runelite.client.plugins.microbot.trent.wintertodt

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

private lateinit var axe: String

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Wintertodt",
    description = "Wintertodt",
    tags = ["firemaking", "wintertodt", "winter"],
    enabledByDefault = false
)
class TrentWintertodt : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = WintertodtScript()

    @OptIn(DelicateCoroutinesApi::class)
    override fun startUp() {
        if (client.localPlayer != null) {
            running = true
            GlobalScope.launch { run() }
        }
    }

    private fun run() {
        axe = Rs2Inventory.get("axe").name ?: return println("No axe found in inventory.")
        println("Found $axe in inventory. Time to get going chief.")
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

fun isWintertodtAlive(): Boolean = Rs2Widget.hasWidget("Wintertodt's Energy")

fun getWintertodtHealth(): Int {
    val healthBar = Rs2Widget.getWidget(25952276) ?: return -1
    if (isWintertodtAlive())
        return healthBar.text.split("\\D+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[1].toInt()
    return 1
}

class WintertodtScript : StateMachineScript() {
    override fun getStartState(): State {
        return Root()
    }
}

class Root : State() {
    override fun checkNext(client: Client): State? {
        if (client.localPlayer.worldLocation.regionID == 6462)
            return Ingame()
        else if (client.localPlayer.worldLocation.regionID == 6461)
            return PrepareForGame()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) { }
}

class Ingame : State() {
    var task = "chop"
    private var lastAction: Long = 0
    private var interrupted = true
    private var fletching = false

    override fun checkNext(client: Client): State? {
        if (client.localPlayer.worldLocation.regionID == 6461)
            return PrepareForGame()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (Rs2Inventory.contains("supply crate")) {
            val door = Rs2GameObject.findObject(29322, WorldPoint(1630, 3965, 0))
            if (door == null && Rs2Walker.walkTo(WorldPoint(1630, 3965, 0))) {
                sleep(1260, 5920)
                return
            }
            if (Rs2GameObject.interact(door, "enter"))
                sleepUntil(timeout = 10000) { client.localPlayer.worldLocation.regionID == 6461 }
            return
        }
        if (Rs2Player.eatAt(43)) {
            sleep(346, 642)
            return
        }
        //Dodge them damages lmao
        var dangerLoc = client.projectiles.find { it.id == 501 && WorldPoint.fromLocalInstance(client, it.target).distanceTo(Rs2Player.getWorldLocation()) <= 1 }?.target
        if (dangerLoc != null)
            dangerLoc = client.graphicsObjects.find { it.id == 502 && WorldPoint.fromLocalInstance(client, it.location).distanceTo(Rs2Player.getWorldLocation()) <= 1 }?.location
        if (dangerLoc != null) {
            dodgeDangerAtPoint(WorldPoint.fromLocalInstance(client, dangerLoc))
            Rs2Player.waitForWalking(8000)
            return
        }
        if (!isWintertodtAlive() || getWintertodtHealth() <= 0) {
            if (Rs2GameObject.findObjectByIdAndDistance(BRAZIER_29312, 2) == null && Rs2Walker.walkTo(WorldPoint(1621, 3998, 0)))
                Rs2Player.waitForWalking()
            return
        }
        val unlitBrazier = Rs2GameObject.findObjectByIdAndDistance(BRAZIER_29312, 5)
        if (unlitBrazier != null && Rs2GameObject.interact(unlitBrazier, "light")) {
            Rs2Player.waitForAnimation()
            sleep(1500, 2500)
            return
        }
        val brokenBrazier = Rs2GameObject.findObjectByIdAndDistance(BRAZIER_29313, 5)
        if (brokenBrazier != null && Rs2GameObject.interact(brokenBrazier, "fix")) {
            Rs2Player.waitForAnimation()
            sleep(1500, 2500)
            return
        }
        val litBrazier = Rs2GameObject.findObjectByIdAndDistance(BURNING_BRAZIER_29314, 10)
        when(task) {
            "chop" -> {
                if (getWintertodtHealth() <= 15 && (Rs2Inventory.hasItemAmount(ItemID.BRUMA_ROOT, 3) || Rs2Inventory.hasItemAmount(ItemID.BRUMA_KINDLING, 3))) {
                    task = "burn"
                    interrupted = true
                }
                if (Rs2Inventory.isFull()) {
                    task = "fletch"
                    interrupted = true
                }
            }
            "fletch" -> {
                if (!Rs2Inventory.contains(ItemID.BRUMA_ROOT)) {
                    task = "burn"
                    interrupted = true
                }
            }
            "burn" -> {
                if (!Rs2Inventory.contains(ItemID.BRUMA_ROOT) && !Rs2Inventory.contains(ItemID.BRUMA_KINDLING)) {
                    task = "chop"
                    interrupted = true
                }
            }
        }
        if (!interrupted && (System.currentTimeMillis() - lastAction) < 5000)
            return
        when(task) {
            "chop" -> {
                if (moveTo(1619, 3989))
                    return
                if (Rs2GameObject.interact(BRUMA_ROOTS, "chop")) {
                    interrupted = false
                    lastAction = System.currentTimeMillis()
                    Rs2Player.waitForAnimation(2500)
                }
            }
            "fletch" -> {
                fletching = false
                if (Rs2Inventory.use(ItemID.KNIFE) && Rs2Inventory.use(ItemID.BRUMA_ROOT)) {
                    interrupted = false
                    lastAction = System.currentTimeMillis()
                    sleepUntil(timeout = 4000) { fletching }
                }
            }
            "burn" -> {
                if (moveTo(1619, 3998))
                    return
                if (litBrazier != null && Rs2GameObject.interact(litBrazier, "feed")) {
                    interrupted = false
                    lastAction = System.currentTimeMillis()
                    Rs2Player.waitForAnimation(2500)
                }
            }
        }
    }

    internal enum class InterruptType {
        COLD, SNOWFALL, BRAZIER, INVENTORY_FULL, OUT_OF_ROOTS, FIXED_BRAZIER, LIT_BRAZIER, BRAZIER_WENT_OUT
    }

    override fun eventReceived(client: Client, eventObject: Any) {
        val animChange = eventObject as? AnimationChanged
        if (animChange != null) {
            if (animChange.actor == client.localPlayer && animChange.actor.animation == LOOKING_INTO)
                lastAction = System.currentTimeMillis()
            return
        }
        val message = eventObject as? ChatMessage ?: return
        if (message.type != ChatMessageType.GAMEMESSAGE && message.type != ChatMessageType.SPAM)
            return
        val text = message.messageNode.value

        if (text.startsWith("You carefully fletch the root")) {
            lastAction = System.currentTimeMillis()
            fletching = true
            return
        }

        if (text.startsWith("You get a bruma root")) {
            lastAction = System.currentTimeMillis()
            return
        }

        val interruptType = when {
            text.startsWith("The cold of") -> InterruptType.COLD
            text.startsWith("The freezing cold attack") -> InterruptType.SNOWFALL
            text.startsWith("The brazier is broken and shrapnel") -> InterruptType.BRAZIER
            text.startsWith("You have run out of bruma roots") -> InterruptType.OUT_OF_ROOTS
            text.startsWith("Your inventory is too full") -> InterruptType.INVENTORY_FULL
            text.startsWith("You fix the brazier") -> InterruptType.FIXED_BRAZIER
            text.startsWith("You light the brazier") -> InterruptType.LIT_BRAZIER
            text.startsWith("The brazier has gone out.") -> InterruptType.BRAZIER_WENT_OUT
            else -> null
        } ?: return

        val needsResume = when (interruptType) {
            InterruptType.COLD, InterruptType.BRAZIER, InterruptType.SNOWFALL -> task != "chop"
            InterruptType.INVENTORY_FULL, InterruptType.OUT_OF_ROOTS, InterruptType.BRAZIER_WENT_OUT -> true
            else -> false
        }
        if (needsResume)
            interrupted = true
    }
}

fun moveTo(x: Int, y: Int): Boolean {
    if (Rs2Player.getWorldLocation().x == x && Rs2Player.getWorldLocation().y == y)
        return false
    Rs2Walker.walkFastCanvas(WorldPoint(x, y, Rs2Player.getWorldLocation().plane))
    sleepUntil { Rs2Player.getWorldLocation().x == x && Rs2Player.getWorldLocation().y == y }
    return true
}

fun dodgeDangerAtPoint(dodgeLocation: WorldPoint) {
    val currentLocation = Rs2Player.getWorldLocation()

    val dx = dodgeLocation.x - currentLocation.x
    val dy = dodgeLocation.y - currentLocation.y

    val primaryDirection = when {
        dx > 0 -> 1 to 0   // Move East
        dx < 0 -> -1 to 0  // Move West
        dy > 0 -> 0 to 1   // Move North
        else -> 0 to -1    // Move South
    }

    val directions = listOf(
        primaryDirection,   // Primary direction
        0 to -1,            // South
        0 to 1,             // North
        -1 to 0,            // West
        1 to 0              // East
    ).distinct()           // Remove duplicates

    val reachablePoints = directions.mapNotNull { (dx, dy) ->
        val point = WorldPoint(currentLocation.x + dx, currentLocation.y + dy, currentLocation.plane)
        if (Rs2Walker.canReach(point)) point else null
    }

    reachablePoints.randomOrNull()?.let { Rs2Walker.walkFastCanvas(it) }
}


class PrepareForGame : State() {
    override fun checkNext(client: Client): State? {
        if (client.localPlayer.worldLocation.regionID == 6462)
            return Ingame()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        val itemsToTake = mutableListOf(axe, "knife", "hammer")
        if (!Rs2Equipment.hasEquipped(ItemID.BRUMA_TORCH))
            itemsToTake.add("tinderbox")
        if (Rs2Player.eatAt(85)) {
            sleep(346, 642)
            return
        }
        if (Rs2Inventory.contains("supply crate") || !Rs2Inventory.containsAll(*itemsToTake.toTypedArray()) || !Rs2Inventory.hasItemAmount("salmon", 10, false, true)) {
            val chest = Rs2GameObject.findObject(29321, WorldPoint(1641, 3944, 0))
            if (chest == null && Rs2Walker.walkTo(WorldPoint(1641, 3944, 0))) {
                sleep(1260, 5920)
                return
            }
            if (!Rs2Bank.isOpen() && Rs2GameObject.interact(chest, "bank"))
                sleepUntil(timeout = 10000) { Rs2Bank.isOpen() }
            else if (Rs2Bank.isOpen()) {
                Rs2Bank.depositAll()
                itemsToTake.forEach { Rs2Bank.withdrawOne(it, true) }
                Rs2Bank.withdrawX("salmon", 10, true)
                sleepUntil { !Rs2Inventory.contains("supply crate") && Rs2Inventory.containsAll(*itemsToTake.toTypedArray()) && Rs2Inventory.hasItemAmount("salmon", 10, false, true) }
            }
        } else {
            val door = Rs2GameObject.findObject(29322, WorldPoint(1630, 3965, 0))
            if (door == null && Rs2Walker.walkTo(WorldPoint(1630, 3965, 0))) {
                sleep(1260, 5920)
                return
            }
            if (Rs2GameObject.interact(door, "enter"))
                sleepUntil(timeout = 10000) { client.localPlayer.worldLocation.regionID == 6462 }
        }
    }
}