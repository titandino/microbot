package net.runelite.client.plugins.microbot.trent.api

import net.runelite.client.plugins.microbot.util.Global

fun sleepUntil(checkEvery: Int = 300, timeout: Int = 5000, condition: () -> Boolean) {
    Global.sleepUntilTrue(condition, checkEvery, timeout)
}