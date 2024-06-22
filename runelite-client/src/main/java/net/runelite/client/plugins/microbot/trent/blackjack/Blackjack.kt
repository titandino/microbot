package net.runelite.client.plugins.microbot.trent.blackjack

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.NPC
import net.runelite.api.Skill
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.sleepUntil
import net.runelite.client.plugins.microbot.util.Global.sleep
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.math.Random
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Blackjacker",
    description = "Blackjacks configurable NPCs",
    tags = ["thieving"],
    enabledByDefault = false
)
class Blackjacker : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = BlackjacketScript()

    @OptIn(DelicateCoroutinesApi::class)
    override fun startUp() {
        if (client.localPlayer != null) {
            running = true
            PREVIOUS_HP = client.getBoostedSkillLevel(Skill.HITPOINTS)
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

class BlackjacketScript : StateMachineScript() {
    override fun getStartState(): State {
        return Root()
    }
}

private var WINE = 1993

private var HIT_REACT_TIME = 110
private var PICKPOCKET_MIN_DELAY = 200
private var PICKPOCKET_MAX_DELAY = 365

private var FIRST_HIT = false
private var KNOCKOUT = false
private var KNOCKOUT_PASSED = false
private var PLAYER_HIT = 0
private var PREVIOUS_HP = 0
private var HITSPLAT_HP = 0
private var KNOCKOUT_XP_DROP = 0
private var XP_DROP = 0
private var BLACKJACK_CYCLE = 0

private var HIT_REACT_START_TIME = 0L
private var XP_DROP_START_TIME = 0L
private var START_TIME = 0L
private var END_TIME = 0L
private var PREVIOUS_ACTION_TIME = 0L
private var STATE = "BLACKJACK"

private class Root : State() {
    override fun checkNext(client: Client): State? {
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (!Microbot.isLoggedIn()) {
            sleep(1000)
            return
        }
        val npc = Rs2Npc.getNpc(737) ?: return

        START_TIME = System.currentTimeMillis()

        if (STATE == "BLACKJACK") {
            if (KNOCKOUT && client.localPlayer.animation != 401 && !KNOCKOUT_PASSED) {
                HIT_REACT_START_TIME = System.currentTimeMillis()
                sleepUntil(10, HIT_REACT_TIME - 10) { client.localPlayer.animation == 401 }
                if (client.localPlayer.animation == 401) {
                    KNOCKOUT_PASSED = true
                }
            }
        }
        handlePlayerHit(client, npc)
        if (client.getBoostedSkillLevel(Skill.HITPOINTS) <= 11 || !Rs2Inventory.hasItem(WINE)) {
            if (Rs2Inventory.hasItem(WINE)) {
                sleep(120, 240)
                Rs2Inventory.interact(WINE, "drink")
                sleep(120, 240)
            } else
                sleep(58285, 5000000)
        }
        when (STATE) {
            "BLACKJACK" -> {
                if (BLACKJACK_CYCLE == 0) {
                    PREVIOUS_HP = client.getBoostedSkillLevel(Skill.HITPOINTS)
                    XP_DROP_START_TIME = System.currentTimeMillis()
                    KNOCKOUT_XP_DROP = client.getSkillExperience(Skill.THIEVING)
                    if (System.currentTimeMillis() > (PREVIOUS_ACTION_TIME + Random.random(500, 700)) || !KNOCKOUT) {
                        Rs2Npc.interact(npc, "Knock-Out")
                    }
                    PREVIOUS_ACTION_TIME = System.currentTimeMillis()
                    KNOCKOUT = true
                    END_TIME = System.currentTimeMillis()
                    ++BLACKJACK_CYCLE
                    return
                }
                if (BLACKJACK_CYCLE <= 2) {
                    if (KNOCKOUT && !FIRST_HIT) {
                        if (npc.getAnimation() != 838)
                            sleepUntil(timeout = 600) { npc.getAnimation() == 838 }
                    }
                    
                    XP_DROP = client.getSkillExperience(Skill.THIEVING)
                    XP_DROP_START_TIME = System.currentTimeMillis()
                    // 360ms is good.370ms starts to miss.350ms decent. 350~365
                    if ((PREVIOUS_ACTION_TIME + 1140 + PICKPOCKET_MIN_DELAY) > System.currentTimeMillis()) {
                        sleep(((PREVIOUS_ACTION_TIME + 840 + Random.random(PICKPOCKET_MIN_DELAY, PICKPOCKET_MAX_DELAY)) - System.currentTimeMillis()).toInt())
                    }
                    if (npc.getAnimation() == 838) {
                        Rs2Npc.interact(npc, "Pickpocket")
                        KNOCKOUT = false
                        sleepUntil(timeout = 1000) { XP_DROP < client.getSkillExperience(Skill.THIEVING) }
                    } else {
                        sleep(90, 140)
                        BLACKJACK_CYCLE = 0
                        return
                    }
                    PREVIOUS_ACTION_TIME = System.currentTimeMillis()
                    END_TIME = System.currentTimeMillis()
                    ++BLACKJACK_CYCLE
                    return
                }
                if (npc.getAnimation() == 838)
                    sleepUntil(timeout = 800) { npc.getAnimation() != 838 }
                sleep(120, 180)
                BLACKJACK_CYCLE = 0
            }
        }
        END_TIME = System.currentTimeMillis()
    }
}

fun handlePlayerHit(client: Client, npc: NPC) {
    if (PLAYER_HIT >= 1) {
        var j = 0
        val i = Random.random(2, 3)
        var c = 120
        if (PLAYER_HIT == 1 && FIRST_HIT) {
            if ((HIT_REACT_START_TIME + HIT_REACT_TIME) > System.currentTimeMillis()) {
                sleep(60, ((HIT_REACT_START_TIME + HIT_REACT_TIME) - System.currentTimeMillis()).toInt())
            }
            while (j < i) {
                Rs2Npc.interact(npc, "Pickpocket")
                sleep(c, (c * 1.3).toInt())
                c = (c * 1.4).toInt()
                ++j
            }
            KNOCKOUT = false
            FIRST_HIT = false
            BLACKJACK_CYCLE = 0
        }
        val hasStars = client.localPlayer.hasSpotAnim(245)
        if (!hasStars) {
            if (PLAYER_HIT <= 1 || client.getSkillExperience(Skill.THIEVING) > HITSPLAT_HP) {
                PLAYER_HIT = 0
            } else {
                PLAYER_HIT = 0
                STATE = "RUN_AWAY"
                KNOCKOUT = false
                BLACKJACK_CYCLE = 0
            }
            PREVIOUS_HP = client.getBoostedSkillLevel(Skill.HITPOINTS)
        }
    }
}