package net.runelite.client.plugins.microbot.trent.api

import net.runelite.api.Client
import net.runelite.api.events.ChatMessage

abstract class StateMachineScript {
    var state: State = getStartState()
    var stopped = false
    abstract fun getStartState(): State

    fun loop(client: Client): Boolean {
        try {
            if (stopped)
                return false
            val next: State? = state.checkNext(client)
            if (next != null) {
                state = next
                return true
            }
            state.loop(client, this)
        } catch (exception: Throwable) {
            exception.printStackTrace()
            return true
        }
        return true
    }

    fun eventReceived(client: Client, eventObject: Any) {
        state.eventReceived(client, eventObject)
    }

    fun stop() {
        stopped = true
    }
}

abstract class State {
    abstract fun checkNext(client: Client): State?
    abstract fun loop(client: Client, script: StateMachineScript)
    open fun eventReceived(client: Client, eventObject: Any) { }
}