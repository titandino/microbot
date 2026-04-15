package net.runelite.client.plugins.microbot.trent.api

import net.runelite.api.TileObject
import net.runelite.api.coords.WorldPoint
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.Global.sleep
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget

fun sleepUntil(checkEvery: Int = 300, timeout: Int = 5000, condition: () -> Boolean) {
    Global.sleepUntilTrue(condition, checkEvery, timeout)
}

fun moveTo(x: Int, y: Int): Boolean {
    if (Rs2Player.getWorldLocation().x == x && Rs2Player.getWorldLocation().y == y)
        return false
    Rs2Walker.walkFastCanvas(WorldPoint(x, y, Rs2Player.getWorldLocation().plane))
    Global.sleepUntil { Rs2Player.getWorldLocation().x == x && Rs2Player.getWorldLocation().y == y }
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

fun percentageTextToInt(widgetId: Int): Int {
    val widget = Rs2Widget.getWidget(widgetId) ?: return -1
    return try { widget.text.split("\\D+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[1].toInt() } catch(e: Throwable) { -1 }
}

fun bankAt(objectId: Int, tile: WorldPoint, option: String = "bank"): Boolean {
    val chest = Rs2GameObject.findObject(objectId, tile)
    if ((chest == null || chest.worldLocation.distanceTo(Rs2Player.getWorldLocation()) > 14) && Rs2Walker.walkTo(tile, 10)) {
        sleep(1260, 5920)
        return false
    }
    Rs2Walker.setTarget(null)
    if (!Rs2Bank.isOpen()) {
        if (Rs2GameObject.interact(chest, option))
            sleepUntil(timeout = 10000) { Rs2Bank.isOpen() }
        else
            Rs2Walker.walkTo(tile)
        return false
    } else
        return true
}

fun findTileObject(id: Int, tile: WorldPoint): TileObject? {
    return Rs2GameObject.getAll().firstOrNull { it.id == id && it.worldLocation == tile }
}