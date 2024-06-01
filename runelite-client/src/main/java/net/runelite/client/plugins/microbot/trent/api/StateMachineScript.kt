package net.runelite.client.plugins.microbot.trent.api

import net.runelite.api.Client
import net.runelite.api.events.ChatMessage

abstract class StateMachineScript {
    var state: State = getStartState()
    abstract fun getStartState(): State

    fun loop(client: Client) {
        try {
            val next: State? = state.checkNext(client)
            if (next != null) {
                state = next
                return
            }
            state.loop(client, this)
        } catch (exception: Throwable) {
            exception.printStackTrace()
        }
    }

    fun eventReceived(client: Client, eventObject: Any) {
        state.eventReceived(client, eventObject)
    }
}

abstract class State {
    abstract fun checkNext(client: Client): State?
    abstract fun loop(client: Client, script: StateMachineScript)
    open fun eventReceived(client: Client, eventObject: Any) { }
}