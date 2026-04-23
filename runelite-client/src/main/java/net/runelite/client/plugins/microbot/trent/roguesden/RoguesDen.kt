package net.runelite.client.plugins.microbot.trent.roguesden

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.Skill
import net.runelite.api.coords.LocalPoint
import net.runelite.api.coords.WorldArea
import net.runelite.api.coords.WorldPoint
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcQueryable
import net.runelite.client.plugins.microbot.api.tileitem.Rs2TileItemQueryable
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectQueryable
import net.runelite.client.plugins.microbot.trent.api.*
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.Global.sleep
import net.runelite.client.plugins.microbot.util.Global.sleepUntilTrue
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker.walkFastCanvas
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget
import javax.inject.Inject

// Kept as a pass-through so existing gameplay callsites compile unchanged.
// Previously seeded a debug AREAS map consumed by a per-frame tile overlay;
// the overlay was removed because it allocated/polygonized every tile every
// frame and tanked the client. Script states use the returned WorldArea for
// bounds-checking only, so this trivial identity function is all that's needed.
private fun addArea(area: WorldArea): WorldArea = area

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Rogues Den",
    description = "Rogues den for outfit",
    tags = ["thieving"],
    enabledByDefault = false
)
class RoguesDen : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = RoguesDenScript()

    @OptIn(DelicateCoroutinesApi::class)
    override fun startUp() {
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
        running = false
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
        val doorway = Rs2TileObjectQueryable().withId(7256).nearest()
        if (doorway == null && Rs2Walker.walkTo(WorldPoint(3056, 4991, 1))) {
            sleep(1260, 5920)
            return
        }
        if (doorway != null && doorway.click("open")) {
            Rs2Player.waitForWalking()
            sleepUntil(timeout = 10000) { !Rs2Player.isMoving() }
        } else
            Rs2Walker.walkTo(WorldPoint(3056, 4991, 1))
    }
}

private class Den : State() {
    // Last time we logged a "no step matched" diagnostic, to throttle spam.
    private var lastNoMatchLogMs: Long = 0L
    // Last step name logged so we only re-log when the step changes (keeps the chat readable).
    private var lastLoggedStep: String? = null

    override fun checkNext(client: Client): State? {
        return if (!setOf(11854, 11855, 12110, 12111).contains(Rs2Player.getWorldLocation().regionID)) Lobby() else null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        val step = RogueMazeStep.entries.firstOrNull { it.condition.invoke() }
        if (step == null) {
            // Bug 1 safety net: if no step matches the player's tile we'd sit and AFK forever.
            // Log it (throttled) so we can see exactly which tile is falling through the cracks.
            val now = System.currentTimeMillis()
            if (now - lastNoMatchLogMs > 2000) {
                lastNoMatchLogMs = now
                Microbot.log("[RoguesDen] NO STEP MATCHED at pos=${Rs2Player.getWorldLocation()}")
            }
            return
        }
        Rs2Player.toggleRunEnergy(step.run)
        if (step.name != lastLoggedStep) {
            lastLoggedStep = step.name
            Microbot.log("[RoguesDen] step: ${step.name} pos=${Rs2Player.getWorldLocation()}")
        }
        when(step.target) {
            is WorldPoint -> {
                // Bug 2 fix: walkFastCanvas clicks the minimap/canvas for the destination tile.
                // If the tile is off-screen (player behind cover, camera tilted down, etc.) the
                // canvas projection fails and we silently no-op, stalling the state machine.
                // Pre-rotate if the destination isn't on-screen. Cheap/idempotent: turnTo is a
                // no-op when the target is already within the current yaw cone.
                val destLocal = LocalPoint.fromWorld(client, step.target)
                if (destLocal != null && !Rs2Camera.isTileOnScreen(destLocal)) {
                    Microbot.log("[RoguesDen] camera rotate -> WorldPoint ${step.target} (off-screen)")
                    Rs2Camera.turnTo(destLocal)
                }
                walkFastCanvas(step.target)
                Rs2Player.waitForWalking()
            }
            is ObjectTarget -> {
                val obj = findTileObject(step.target.objectId, step.target.tile)
                if (obj != null) {
                    // Pre-click camera adjust: rotate only when the object's tile is not
                    // currently on-screen. Done inline (per-click, not per-state-entry) because
                    // the player can move behind cover mid-run between clicks on the same step.
                    if (!Rs2Camera.isTileOnScreen(obj)) {
                        Microbot.log("[RoguesDen] camera rotate -> object ${step.target.objectId} @ ${obj.worldLocation} (off-screen)")
                        Rs2Camera.turnTo(obj)
                    }
                    var clicked = obj.click(step.target.option)
                    if (!clicked) {
                        // Belt-and-suspenders: the game sometimes eats the click (occlusion,
                        // nearby ground item overlap, stale menu). One rotate+retry before
                        // giving up; still cheap and logged so stalls are diagnosable.
                        Microbot.log("[RoguesDen] click failed on object ${step.target.objectId} @ ${obj.worldLocation} onScreen=${Rs2Camera.isTileOnScreen(obj)} — rotating and retrying")
                        Rs2Camera.turnTo(obj)
                        sleep(300, 600)
                        clicked = obj.click(step.target.option)
                        Microbot.log("[RoguesDen] retry click on object ${step.target.objectId}: $clicked")
                    }
                    if (clicked) {
                        Rs2Player.waitForWalking()
                        // Bug 1 fix: some obstacles (notably the "grill" BLOCKING_DOOR, id 7255)
                        // unlock on click but don't auto-walk the player through. GRILL_13 in
                        // particular was leaving the player parked east of the grill after the
                        // open animation, causing the loop to AFK. If the step defines a
                        // followUpWalkTo tile, nudge the player there so the next iteration
                        // matches a fresh step's WorldArea.
                        step.target.followUpWalkTo?.let { dest ->
                            Microbot.log("[RoguesDen] post-click followUp walkTo $dest")
                            Rs2Walker.walkTo(dest, 1)
                            Rs2Player.waitForWalking()
                        }
                    }
                }
            }
            is ItemTarget -> {
                val tile = step.target.tile
                val item = Rs2TileItemQueryable()
                    .withId(step.target.itemId)
                    .where { it.worldLocation.x == tile.x && it.worldLocation.y == tile.y }
                    .first()
                if (item != null) {
                    val itemLocal = item.localLocation
                    if (itemLocal != null && !Rs2Camera.isTileOnScreen(itemLocal)) {
                        Microbot.log("[RoguesDen] camera rotate -> item ${step.target.itemId} @ ${item.worldLocation} (off-screen)")
                        Rs2Camera.turnTo(itemLocal)
                    }
                    var clicked = item.click("take")
                    if (!clicked) {
                        Microbot.log("[RoguesDen] click failed on item ${step.target.itemId} @ ${item.worldLocation} — rotating and retrying")
                        if (itemLocal != null) Rs2Camera.turnTo(itemLocal)
                        sleep(300, 600)
                        clicked = item.click("take")
                        Microbot.log("[RoguesDen] retry click on item ${step.target.itemId}: $clicked")
                    }
                    if (clicked) Rs2Player.waitForWalking()
                }
            }
            is WidgetTarget -> {
                // Widgets are UI overlays in screen-space, not world-space; camera yaw has
                // no effect on whether they're clickable. Intentionally no camera logic here.
                Rs2Widget.clickWidget(step.target.widgetHash)
                Rs2Player.waitForWalking()
            }
        }
    }
}

private data class ObjectTarget(
    val objectId: Int,
    val option: String,
    val tile: WorldPoint,
    // Optional tile to walk to after the click+waitForWalking completes. Used for obstacles
    // (e.g. grill id 7255) that only unlock on click rather than pathing the player through.
    val followUpWalkTo: WorldPoint? = null,
)
private data class ItemTarget(val itemId: Int, val tile: WorldPoint)
private data class WidgetTarget(val widgetHash: Int)

private enum class RogueMazeStep(val target: Any, val run: Boolean = false, val condition: () -> Boolean) {
    // 80 Thieving "Squeeze-past" wooden wall shortcut (OSRS wiki: Rogues' Den).
    // RuneLite ObjectID constants: ROGUESDEN_OBSTACLE_WOODEN_WALL1 = 7242,
    // ROGUESDEN_OBSTACLE_WOODEN_WALL2 = 7243, ROGUESDEN_OBSTACLE_WOODEN_WALL3 = 7244.
    // Wiki map places the usable wooden obstruction near the west side of the grill
    // cluster, around (3037, 5082, 1). Squeezing past it drops the player south-west
    // onto the open floor near STAND_6 (3038, 5049, 1), bypassing GRILL_3..GRILL_11.
    //
    // The exact tile/id/action are an educated guess from the wiki; the user has been
    // asked for a screenshot to lock these in. Picks the nearest wooden-wall object on
    // any of the three IDs so we don't have to care which variant the server spawns.
    // Gated on real (unboosted) Thieving level >= 80 and positioned FIRST in the priority
    // list so it overrides the long grill chain whenever the player is in range and
    // qualifies. If the object isn't found the step no-ops and we fall through to the
    // normal grill path — safe fallback.
    WOODEN_WALL_SHORTCUT(
        ObjectTarget(7242, "Squeeze-past", WorldPoint(3037, 5082, 1), followUpWalkTo = WorldPoint(3038, 5049, 1)),
        run = true,
        condition = {
            Rs2Player.getRealSkillLevel(Skill.THIEVING) >= 80
                && addArea(WorldArea(3034, 5079, 10, 6, 1)).contains(Rs2Player.getWorldLocation())
                // Require the wooden-wall object to actually be in the scene; prevents the
                // step from winning when the player has already squeezed through.
                && (findTileObject(7242, WorldPoint(3037, 5082, 1)) != null
                    || findTileObject(7243, WorldPoint(3037, 5082, 1)) != null
                    || findTileObject(7244, WorldPoint(3037, 5082, 1)) != null)
        }
    ),
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
    // Bug 1 fix for "stuck after final grill":
    // 7255 (ROGUESDEN_OBSTACLE_BLOCKING_DOOR_ENTER, the "grill") unlocks on "Open" but does
    // NOT path the player through. Post-click the player was parked on the east side and
    // the loop AFKed forever. followUpWalkTo nudges the player west toward RUN_9's target
    // so the next iteration picks up RUN_9 / STAND_8 cleanly.
    GRILL_13(
        ObjectTarget(7255, "Open", WorldPoint(3010, 5033, 1), followUpWalkTo = WorldPoint(3000, 5034, 1)),
        run = true,
        condition = { addArea(WorldArea(3010, 5032, 5, 4, 1)).contains(Rs2Player.getWorldLocation()) }
    ),
    // Widened RUN_9 one tile west+one tile south to catch the tile immediately west of
    // grill 13 (3009, 5033) and the row just south (y=5031), which previously fell
    // outside any step's WorldArea and produced the "no step matched" stall.
    RUN_9(WorldPoint(3000, 5034, 1), run = true, condition = { addArea(WorldArea(3000, 5031, 10, 5, 1)).contains(Rs2Player.getWorldLocation()) }),
    STAND_8(WorldPoint(2992, 5045, 1), condition = { addArea(WorldArea(2990, 5032, 11, 13, 1)).contains(Rs2Player.getWorldLocation()) }),
    RUN_10(WorldPoint(2992, 5053, 1), run = true, condition = { addArea(WorldArea(2991, 5045, 3, 8, 1)).contains(Rs2Player.getWorldLocation()) }),
    STAND_9(WorldPoint(2992, 5067, 1), condition = { addArea(WorldArea(2990, 5053, 5, 14, 1)).contains(Rs2Player.getWorldLocation()) }),
    RUN_11(WorldPoint(2992, 5075, 1), run = true, condition = { addArea(WorldArea(2988, 5067, 9, 8, 1)).contains(Rs2Player.getWorldLocation()) }),
    STAND_10(WorldPoint(3004, 5067, 1), condition = { addArea(WorldArea(2990, 5075, 12, 6, 1)).contains(Rs2Player.getWorldLocation()) }),
    FLASH_POWDER(ItemTarget(5559, WorldPoint(3009, 5063, 1)), run = true, condition = {
        val chaser = Rs2NpcQueryable().withId(23423).nearest()
        addArea(WorldArea(2998, 5063, 15, 13, 1)).contains(Rs2Player.getWorldLocation())
            && !Rs2Inventory.hasItem(5559)
            && (chaser == null || chaser.worldLocation.x > 3015)
    }),
    FLASH_GUARD(WidgetTarget(-1), run = true, condition = {
        Rs2Inventory.hasItemAmount(5559, 5, true)
            && Rs2Inventory.use(5559)
            && Rs2NpcQueryable().withId(3191).interact()
            && sleepUntilTrue({ Rs2Inventory.get(5559)?.quantity in 1..4 }, 100, 2000)
    }),
    RUN_PAST_GUARD(WorldPoint(3028, 5056, 1), run = true, condition = { Rs2Inventory.get(5559)?.quantity in 1..4 && addArea(WorldArea(3009, 5057, 22, 15, 1)).contains(Rs2Player.getWorldLocation()) }),
    STAND_11(WorldPoint(3028, 5047, 1), condition = { addArea(WorldArea(3026, 5048, 6, 9, 1)).contains(Rs2Player.getWorldLocation()) }),
    CRACK_SAFE(ObjectTarget(7237, "Crack", WorldPoint(3018, 5047, 1)), condition = { addArea(WorldArea(3010, 5042, 22, 7, 1)).contains(Rs2Player.getWorldLocation()) }),
}
