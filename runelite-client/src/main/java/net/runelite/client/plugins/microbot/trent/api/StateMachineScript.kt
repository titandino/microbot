package net.runelite.client.plugins.microbot.trent.api

import net.runelite.api.Client

abstract class StateMachineScript {
    var state: State = getStartState()
    abstract fun getStartState(): State

    fun loop(client: Client) {
        val next: State? = state.checkNext(client)
        if (next != null) {
            state = next
            return
        }
        state.loop(client, this)
    }
}

abstract class State {
    abstract fun checkNext(client: Client): State?
    abstract fun loop(client: Client, script: StateMachineScript)
}