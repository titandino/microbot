package net.runelite.client.plugins.microbot.leagues

import net.runelite.client.plugins.microbot.util.math.Random

fun attempt(attempts: Int, action: () -> Boolean): Boolean {
    for (i in 1..attempts) {
        if (action())
            return true
    }
    return false
}

fun repeatUntil(action: () -> Boolean): Boolean {
    while(true) {
        if (action())
            return true
    }
    return false
}

fun repeatForTime(minTime: Int, maxTime: Int, action: () -> Unit) {
    val endTime = System.currentTimeMillis() + Random.random(minTime, maxTime)
    while (System.currentTimeMillis() < endTime)
        action()
}