package net.runelite.client.plugins.microbot.trent.roguesden

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.Perspective
import net.runelite.api.coords.LocalPoint
import net.runelite.api.coords.WorldArea
import net.runelite.api.coords.WorldPoint
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.trent.api.*
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.Global.sleep
import net.runelite.client.plugins.microbot.util.Global.sleepUntilTrue
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker.walkFastCanvas
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget
import net.runelite.client.ui.overlay.Overlay
import net.runelite.client.ui.overlay.OverlayManager
import net.runelite.client.ui.overlay.OverlayPosition
import java.awt.*
import javax.inject.Inject
import kotlin.random.Random

private val AREAS = mutableMapOf<WorldArea, Color>()

private fun addArea(area: WorldArea): WorldArea {
    if (area !in AREAS)
        AREAS[area] = Color(Random.nextInt(256), Random.nextInt(256), Random.nextInt(256))
    return area
}

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Rogues Den",
    description = "Rogues den for outfit",
    tags = ["thieving"],
    enabledByDefault = false
)
class RoguesDen : Plugin() {
    @Inject
    private lateinit var client: Client

    @Inject
    private lateinit var overlayManager: OverlayManager

    @Inject
    private lateinit var overlay: RoguesDenOverlay

    private var running = false
    private val script = RoguesDenScript()

    @OptIn(DelicateCoroutinesApi::class)
    override fun startUp() {
        overlayManager.add(overlay)
        if (client.localPlayer != null) {
            Microbot.enableAutoRunOn = false
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
        overlayManager.remove(overlay)
        running = false
    }

    fun render(graphics: Graphics2D) {
        AREAS.keys.forEach areaLoop@{ area ->
            area.toWorldPointList().forEach { tile ->
                val local = LocalPoint.fromWorld(client.topLevelWorldView, tile) ?: return@forEach
                val canvasTilePoly: Polygon? = Perspective.getCanvasTilePoly(client, local, 0)
                if (canvasTilePoly != null) {
                    graphics.color = AREAS[area]
                    graphics.drawPolygon(canvasTilePoly)
                }
            }
        }
    }
}

class RoguesDenOverlay @Inject constructor(client: Client, plugin: RoguesDen) : Overlay() {
    val client: Client
    val plugin: RoguesDen

    init {
        setPosition(OverlayPosition.DYNAMIC)
        this.client = client
        this.plugin = plugin
    }

    override fun render(graphics: Graphics2D): Dimension? {
        plugin.render(graphics)
        return null
    }
}

class RoguesDenScript : StateMachineScript() {
    override fun getStartState(): State {
        return Lobby()
    }
}

private class Lobby : State() {
    override fun checkNext(client: Client): State? {
        return if (setOf(11854, 11855, 12110, 12111).contains(Rs2Player.getWorldLocation().regionID)) Den() else null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (client.energy < 5000) return

        if (!Rs2Inventory.isEmpty()) {
            if (bankAt(26707, WorldPoint(3039, 4969, 1))) {
                Rs2Bank.depositAll()
                Global.sleepUntil { Rs2Inventory.isEmpty() }
                Rs2Bank.closeBank()
                Global.sleepUntil { !Rs2Bank.isOpen() }
            }
            return
        }
        val doorway = Rs2GameObject.findDoor(7256)
        if (doorway == null && Rs2Walker.walkTo(WorldPoint(3056, 4991, 1))) {
            sleep(1260, 5920)
            return
        }
        if (Rs2GameObject.interact(doorway, "open")) {
            Rs2Player.waitForWalking()
            sleepUntil(timeout = 10000) { !Rs2Player.isWalking() }
        } else
            Rs2Walker.walkTo(WorldPoint(3056, 4991, 1))
    }
}

private class Den : State() {
    override fun checkNext(client: Client): State? {
        return if (!setOf(11854, 11855, 12110, 12111).contains(Rs2Player.getWorldLocation().regionID)) Lobby() else null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        val step = RogueMazeStep.entries.firstOrNull { it.condition.invoke() } ?: return
        Rs2Player.toggleRunEnergy(step.run)
        println(step)
        when(step.target) {
            is WorldPoint -> {
                walkFastCanvas(step.target)
                Rs2Player.waitForWalking()
            }
            is ObjectTarget -> {
                val obj = findTileObject(step.target.objectId, step.target.tile)
                if (obj != null && Rs2GameObject.interact(obj, step.target.option))
                    Rs2Player.waitForWalking()
            }
            is ItemTarget -> {
                val item = Rs2GroundItem.getAllAt(step.target.tile.x, step.target.tile.y)?.firstOrNull { it.item.id == step.target.itemId }
                if (item != null && Rs2GroundItem.interact(item, "take"))
                    Rs2Player.waitForWalking()
            }
            is WidgetTarget -> {
                Rs2Widget.clickWidget(step.target.widgetHash)
                Rs2Player.waitForWalking()
            }
        }
    }
}

private data class ObjectTarget(val objectId: Int, val option: String, val tile: WorldPoint)
private data class ItemTarget(val itemId: Int, val tile: WorldPoint)
private data class WidgetTarget(val widgetHash: Int)

private enum class RogueMazeStep(val target: Any, val run: Boolean = false, val condition: () -> Boolean) {
    BARS_1(ObjectTarget(7251, "Enter", WorldPoint(3049, 4997, 1)), condition = { addArea(WorldArea(3050, 4992, 8, 8, 1)).contains(Rs2Player.getWorldLocation()) }),
    STAND_1(WorldPoint(3039, 4999, 1), condition = { addArea(WorldArea(3040, 4995, 9, 6, 1)).contains(Rs2Player.getWorldLocation()) }),
    RUN_1(WorldPoint(3029, 5003, 1), run = true, condition = { addArea(WorldArea(3030, 4994, 10, 11, 1)).contains(Rs2Player.getWorldLocation()) }),
    GRILL_1(ObjectTarget(7255, "Open", WorldPoint(3024, 5001, 1)), condition = { addArea(WorldArea(3024, 4998, 6, 6, 1)).contains(Rs2Player.getWorldLocation()) }),
    RUN_2(WorldPoint(3039, 4999, 1), run = true, condition = { addArea(WorldArea(3030, 4994, 11, 11, 1)).contains(Rs2Player.getWorldLocation()) }),
    RUN_3(WorldPoint(3011, 5005, 1), run = true, condition = { addArea(WorldArea(3012, 5001, 12, 5, 1)).contains(Rs2Player.getWorldLocation()) }),
    RUN_4(WorldPoint(3004, 5003, 1), run = true, condition = { addArea(WorldArea(3005, 5002, 7, 4, 1)).contains(Rs2Player.getWorldLocation()) }),
    LEDGE_1(ObjectTarget(7240, "Climb", WorldPoint(2993, 5004, 1)), condition = { addArea(WorldArea(2994, 4996, 11, 9, 1)).contains(Rs2Player.getWorldLocation()) }),
    STAND_2(WorldPoint(2969, 5017, 1), condition = { addArea(WorldArea(2970, 4996, 19, 24, 1)).contains(Rs2Player.getWorldLocation()) }),
    LEDGE_2(ObjectTarget(7239, "Climb", WorldPoint(2958, 5031, 1)), condition = { addArea(WorldArea(2950, 5016, 18, 15, 1)).contains(Rs2Player.getWorldLocation()) }),
    STAND_3(WorldPoint(2962, 5050, 1), condition = { addArea(WorldArea(2955, 5035, 12, 15, 1)).contains(Rs2Player.getWorldLocation()) }),
    RUN_5(WorldPoint(2963, 5056, 1), run = true, condition = { addArea(WorldArea(2962, 5050, 3, 6, 1)).contains(Rs2Player.getWorldLocation()) }),
    PASSAGEWAY_1(ObjectTarget(7219, "Enter", WorldPoint(2957, 5069, 1)), condition = { addArea(WorldArea(2955, 5056, 12, 13, 1)).contains(Rs2Player.getWorldLocation()) }),
    TRAVERSE_NEXT(WorldPoint(2957, 5082, 1), condition = { addArea(WorldArea(2952, 5072, 8, 10, 1)).contains(Rs2Player.getWorldLocation()) }),
    PASSAGEWAY_2(ObjectTarget(7219, "Enter", WorldPoint(2955, 5095, 1)), condition = { addArea(WorldArea(2952, 5082, 8, 13, 1)).contains(Rs2Player.getWorldLocation()) }),
    STAND_4(WorldPoint(2963, 5105, 1), condition = { addArea(WorldArea(2950, 5098, 13, 13, 1)).contains(Rs2Player.getWorldLocation()) }),
    PASSAGEWAY_3(ObjectTarget(7219, "Enter", WorldPoint(2972, 5097, 1)), condition = { addArea(WorldArea(2963, 5098, 12, 8, 1)).contains(Rs2Player.getWorldLocation()) }),
    GRILL_2(ObjectTarget(7255, "Open", WorldPoint(2972, 5094, 1)), condition = { Rs2Player.getWorldLocation().equals(WorldPoint(2972, 5094, 1)) }),
    STAND_5(WorldPoint(2976, 5087, 1), condition = { addArea(WorldArea(2967, 5088, 9, 6, 1)).contains(Rs2Player.getWorldLocation()) }),
    LEDGE_3(ObjectTarget(7240, "Climb", WorldPoint(2983, 5087, 1)), condition = { addArea(WorldArea(2976, 5084, 7, 4, 1)).contains(Rs2Player.getWorldLocation()) }),
    WALL_1(ObjectTarget(7249, "Search", WorldPoint(2993, 5087, 1)), condition = { addArea(WorldArea(2990, 5086, 3, 3, 1)).contains(Rs2Player.getWorldLocation()) }),
    RUN_6(WorldPoint(2997, 5088, 1), run = true, condition = { Rs2Player.getWorldLocation().equals(WorldPoint(2993, 5088, 1)) }),
    RUN_7(WorldPoint(3006, 5088, 1), run = true, condition = { Rs2Player.getWorldLocation().equals(WorldPoint(2997, 5088, 1)) }),
    TILE(ItemTarget(5568, WorldPoint(3018, 5080, 1)), condition = { addArea(WorldArea(3005, 5079, 19, 13, 1)).contains(Rs2Player.getWorldLocation()) && !Rs2Inventory.contains(5568) }),
    TILE_DOOR(ObjectTarget(7234, "Open", WorldPoint(3023, 5082, 1)), condition = { Rs2Inventory.contains(5568) && Rs2Widget.getWidget(45088773) == null }),
    TILE_PUZZLE(WidgetTarget(45088773), condition = { Rs2Widget.getWidget(45088773) != null }),
    GRILL_3(ObjectTarget(7255, "Open", WorldPoint(3030, 5079, 1)), condition = { addArea(WorldArea(3024, 5078, 7, 6, 1)).contains(Rs2Player.getWorldLocation()) }),
    GRILL_4(ObjectTarget(7255, "Open", WorldPoint(3032, 5078, 1)), condition = { addArea(WorldArea(3031, 5079, 3, 2, 1)).contains(Rs2Player.getWorldLocation()) }),
    GRILL_5(ObjectTarget(7255, "Open", WorldPoint(3036, 5076, 1)), condition = { addArea(WorldArea(3032, 5075, 5, 3, 1)).contains(Rs2Player.getWorldLocation()) }),
    GRILL_6(ObjectTarget(7255, "Open", WorldPoint(3039, 5079, 1)), condition = { addArea(WorldArea(3037, 5075, 3, 5, 1)).contains(Rs2Player.getWorldLocation()) }),
    GRILL_7(ObjectTarget(7255, "Open", WorldPoint(3042, 5076, 1)), condition = { addArea(WorldArea(3040, 5075, 3, 5, 1)).contains(Rs2Player.getWorldLocation()) }),
    GRILL_8(ObjectTarget(7255, "Open", WorldPoint(3044, 5069, 1)), condition = { addArea(WorldArea(3043, 5069, 2, 9, 1)).contains(Rs2Player.getWorldLocation()) }),
    GRILL_9(ObjectTarget(7255, "Open", WorldPoint(3041, 5068, 1)), condition = { addArea(WorldArea(3040, 5067, 5, 2, 1)).contains(Rs2Player.getWorldLocation()) }),
    GRILL_10(ObjectTarget(7255, "Open", WorldPoint(3040, 5070, 1)), condition = { addArea(WorldArea(3040, 5069, 3, 3, 1)).contains(Rs2Player.getWorldLocation()) }),
    GRILL_11(ObjectTarget(7255, "Open", WorldPoint(3038, 5069, 1)), condition = { addArea(WorldArea(3037, 5069, 3, 3, 1)).contains(Rs2Player.getWorldLocation()) }),
    STAND_6(WorldPoint(3038, 5049, 1), condition = { addArea(WorldArea(3037, 5050, 3, 19, 1)).contains(Rs2Player.getWorldLocation()) }),
    STAND_7(WorldPoint(3028, 5034, 1), condition = { addArea(WorldArea(3029, 5031, 16, 19, 1)).contains(Rs2Player.getWorldLocation()) }),
    RUN_8(WorldPoint(3024, 5034, 1), run = true, condition = { Rs2Player.getWorldLocation().equals(WorldPoint(3028, 5034, 1)) }),
    GRILL_12(ObjectTarget(7255, "Open", WorldPoint(3015, 5033, 1)), condition = { addArea(WorldArea(3015, 5030, 10, 7, 1)).contains(Rs2Player.getWorldLocation()) }),
    GRILL_13(ObjectTarget(7255, "Open", WorldPoint(3010, 5033, 1)), run = true, condition = { addArea(WorldArea(3010, 5032, 5, 4, 1)).contains(Rs2Player.getWorldLocation()) }),
    RUN_9(WorldPoint(3000, 5034, 1), run = true, condition = { addArea(WorldArea(3001, 5032, 9, 4, 1)).contains(Rs2Player.getWorldLocation()) }),
    STAND_8(WorldPoint(2992, 5045, 1), condition = { addArea(WorldArea(2990, 5032, 11, 13, 1)).contains(Rs2Player.getWorldLocation()) }),
    RUN_10(WorldPoint(2992, 5053, 1), run = true, condition = { addArea(WorldArea(2991, 5045, 3, 8, 1)).contains(Rs2Player.getWorldLocation()) }),
    STAND_9(WorldPoint(2992, 5067, 1), condition = { addArea(WorldArea(2990, 5053, 5, 14, 1)).contains(Rs2Player.getWorldLocation()) }),
    RUN_11(WorldPoint(2992, 5075, 1), run = true, condition = { addArea(WorldArea(2988, 5067, 9, 8, 1)).contains(Rs2Player.getWorldLocation()) }),
    STAND_10(WorldPoint(3004, 5067, 1), condition = { addArea(WorldArea(2990, 5075, 12, 6, 1)).contains(Rs2Player.getWorldLocation()) }),
    FLASH_POWDER(ItemTarget(5559, WorldPoint(3009, 5063, 1)), run = true, condition = { addArea(WorldArea(2998, 5063, 15, 13, 1)).contains(Rs2Player.getWorldLocation()) && !Rs2Inventory.hasItem(5559) && (Rs2Npc.getNpc(23423) == null || Rs2Npc.getNpc(23423).worldLocation.x > 3015) }),
    FLASH_GUARD(WidgetTarget(-1), run = true, condition = { Rs2Inventory.hasItemAmount(5559, 5, true) && Rs2Inventory.use(5559) && Rs2Npc.interact(3191) && sleepUntilTrue({ Rs2Inventory.get(5559)?.quantity in 1..4 }, 100, 2000) }),
    RUN_PAST_GUARD(WorldPoint(3028, 5056, 1), run = true, condition = { Rs2Inventory.get(5559)?.quantity in 1..4 && addArea(WorldArea(3009, 5057, 22, 15, 1)).contains(Rs2Player.getWorldLocation()) }),
    STAND_11(WorldPoint(3028, 5047, 1), condition = { addArea(WorldArea(3026, 5048, 6, 9, 1)).contains(Rs2Player.getWorldLocation()) }),
    CRACK_SAFE(ObjectTarget(7237, "Crack", WorldPoint(3018, 5047, 1)), condition = { addArea(WorldArea(3010, 5042, 22, 7, 1)).contains(Rs2Player.getWorldLocation()) }),
}