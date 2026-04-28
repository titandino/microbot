// Potion-dose IDs live in the legacy net.runelite.api.ItemID class (gameval ItemID hasn't
// migrated SUPER_ATTACK1..4 etc. yet). Scope the deprecation noise to this file.
@file:Suppress("DEPRECATION")

package net.runelite.client.plugins.microbot.trent.decanter

import com.google.inject.Provides
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.ItemID
import net.runelite.api.gameval.VarbitID
import net.runelite.client.config.Config
import net.runelite.client.config.ConfigGroup
import net.runelite.client.config.ConfigItem
import net.runelite.client.config.ConfigManager
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.sleepUntil
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel
import javax.inject.Inject
import kotlin.math.min

// ==========================================================================================
// Potion Decanter — bank-side, manual dose-combining decanter that works at any bank.
//
// Open/close cadence: ONCE per inventory load, NOT per click.
//   1. Open bank, deposit junk, withdraw load.
//   2. CLOSE bank.
//   3. Run the entire combine click sequence (~14-27 clicks) with bank closed —
//      `Rs2Inventory.combine` issues a "Use" menu action which OSRS rebinds to "Deposit"
//      while the bank interface is open, so combining silently no-ops if the bank is up.
//   4. Loop: re-open bank for the next load.
//
// Withdraw strategy minimises the X-quantity dialog: we prefer "withdraw all" (one click,
// no dialog) when the bank stack is <= our target, and reuse the same withdrawX amount
// across iterations so the chat-dialog cost is paid once per session at most.
// ==========================================================================================

enum class Potion(
    val displayName: String,
    val dose1: Int,
    val dose2: Int,
    val dose3: Int,
    val dose4: Int,
) {
    SUPER_ATTACK("Super attack", ItemID.SUPER_ATTACK1, ItemID.SUPER_ATTACK2, ItemID.SUPER_ATTACK3, ItemID.SUPER_ATTACK4),
    SUPER_STRENGTH("Super strength", ItemID.SUPER_STRENGTH1, ItemID.SUPER_STRENGTH2, ItemID.SUPER_STRENGTH3, ItemID.SUPER_STRENGTH4),
    SUPER_DEFENCE("Super defence", ItemID.SUPER_DEFENCE1, ItemID.SUPER_DEFENCE2, ItemID.SUPER_DEFENCE3, ItemID.SUPER_DEFENCE4),
    SUPER_COMBAT("Super combat", ItemID.SUPER_COMBAT_POTION1, ItemID.SUPER_COMBAT_POTION2, ItemID.SUPER_COMBAT_POTION3, ItemID.SUPER_COMBAT_POTION4),
    RANGING("Ranging", ItemID.RANGING_POTION1, ItemID.RANGING_POTION2, ItemID.RANGING_POTION3, ItemID.RANGING_POTION4),
    MAGIC("Magic", ItemID.MAGIC_POTION1, ItemID.MAGIC_POTION2, ItemID.MAGIC_POTION3, ItemID.MAGIC_POTION4),
    PRAYER("Prayer", ItemID.PRAYER_POTION1, ItemID.PRAYER_POTION2, ItemID.PRAYER_POTION3, ItemID.PRAYER_POTION4),
    STAMINA("Stamina", ItemID.STAMINA_POTION1, ItemID.STAMINA_POTION2, ItemID.STAMINA_POTION3, ItemID.STAMINA_POTION4),
    ANTIFIRE("Antifire", ItemID.ANTIFIRE_POTION1, ItemID.ANTIFIRE_POTION2, ItemID.ANTIFIRE_POTION3, ItemID.ANTIFIRE_POTION4),
    SUPER_ANTIFIRE("Super antifire", ItemID.SUPER_ANTIFIRE_POTION1, ItemID.SUPER_ANTIFIRE_POTION2, ItemID.SUPER_ANTIFIRE_POTION3, ItemID.SUPER_ANTIFIRE_POTION4),
    SANFEW_SERUM("Sanfew serum", ItemID.SANFEW_SERUM1, ItemID.SANFEW_SERUM2, ItemID.SANFEW_SERUM3, ItemID.SANFEW_SERUM4),
    SARADOMIN_BREW("Saradomin brew", ItemID.SARADOMIN_BREW1, ItemID.SARADOMIN_BREW2, ItemID.SARADOMIN_BREW3, ItemID.SARADOMIN_BREW4),
    ZAMORAK_BREW("Zamorak brew", ItemID.ZAMORAK_BREW1, ItemID.ZAMORAK_BREW2, ItemID.ZAMORAK_BREW3, ItemID.ZAMORAK_BREW4),
    ;

    fun nonFourDoses(): IntArray = intArrayOf(dose1, dose2, dose3)
    fun allDoses(): IntArray = intArrayOf(dose1, dose2, dose3, dose4)

    override fun toString(): String = displayName
}

// ------------------------------------------------------------------------------------------
// Config
// ------------------------------------------------------------------------------------------

@ConfigGroup(DecanterConfig.GROUP)
interface DecanterConfig : Config {
    companion object {
        const val GROUP = "trentDecanter"
    }

    @ConfigItem(
        keyName = "potion",
        name = "Potion",
        description = "Which potion family to decant into 4-doses",
        position = 0,
    )
    fun potion(): Potion = Potion.PRAYER
}

// ==========================================================================================
// Plugin + Script wiring
// ==========================================================================================

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Decanter",
    description = "Decants banked potions into 4-doses at any bank (manual combine)",
    tags = ["herblore", "decant", "bank", "potion"],
    enabledByDefault = false,
)
class Decanter : Plugin() {
    @Inject
    private lateinit var client: Client

    @Inject
    private lateinit var config: DecanterConfig

    @Provides
    fun provideConfig(configManager: ConfigManager): DecanterConfig =
        configManager.getConfig(DecanterConfig::class.java)

    private var running = false
    private var script: DecanterScript? = null

    @OptIn(DelicateCoroutinesApi::class)
    override fun startUp() {
        if (client.localPlayer != null) {
            script = DecanterScript(config)
            running = true
            GlobalScope.launch { run() }
        }
    }

    private fun run() {
        val s = script ?: return
        try {
            while (running && !s.stopped) {
                s.loop(client)
            }
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            Microbot.log("[Decanter] fatal: ${t.message}")
        }
    }

    override fun shutDown() {
        running = false
        script?.stop()
        script = null
    }
}

class DecanterScript(val config: DecanterConfig) : StateMachineScript() {
    @Volatile var fourDosesProduced: Int = 0
    @Volatile var cyclesCompleted: Int = 0
    /**
     * Counts consecutive NPEs from `Rs2Inventory.combine(int, int)` — the int-overload
     * resolves `primary`/`secondary` via `Rs2Inventory.get(id)` and then dereferences
     * `primary.getId()` inside a lambda before we can null-check. After 3 in a row,
     * we bail to Finish so the user gets their script back.
     */
    @Volatile var combineNpeCount: Int = 0

    override fun getStartState(): State = Setup(this)
}

// ==========================================================================================
// Planner
// ==========================================================================================

private data class Click(val src: Int, val dst: Int)

private data class Doses(var n1: Int, var n2: Int, var n3: Int) {
    fun copy(): Doses = Doses(n1, n2, n3)
    fun totalDoses(): Int = n1 + 2 * n2 + 3 * n3
}

private object Planner {
    /**
     * Click sequence rules (priority order, each click rule below makes one (4) where possible):
     *   1. (3)+(1) → (4)+empty   — frees a slot AND makes a (4) in one click.
     *   2. (2)+(2) → (4)+empty   — same density: 1 click, 1 (4), frees a slot.
     *   3. (3)+(2) → (4)+(1)     — 1 click, 1 (4), produces a (1) leftover.
     *   4. (1)+(1) → (2)         — collapse leftovers.
     *   5. (3)+(3) → (4)+(2)     — fallback when only (3)s remain.
     */
    fun plan(start: Doses): List<Click> {
        val d = start.copy()
        val clicks = mutableListOf<Click>()
        var safety = 256
        while (safety-- > 0) {
            if (d.n3 > 0 && d.n1 > 0) {
                clicks += Click(src = 1, dst = 3); d.n3 -= 1; d.n1 -= 1; continue
            }
            if (d.n2 >= 2) {
                clicks += Click(src = 2, dst = 2); d.n2 -= 2; continue
            }
            if (d.n3 > 0 && d.n2 > 0) {
                clicks += Click(src = 2, dst = 3); d.n3 -= 1; d.n2 -= 1; d.n1 += 1; continue
            }
            if (d.n1 >= 2 && (d.n1 >= 4 || d.n2 >= 1)) {
                clicks += Click(src = 1, dst = 1); d.n1 -= 2; d.n2 += 1; continue
            }
            if (d.n1 >= 2) {
                clicks += Click(src = 1, dst = 1); d.n1 -= 2; d.n2 += 1; continue
            }
            if (d.n3 >= 2) {
                clicks += Click(src = 3, dst = 3); d.n3 -= 2; d.n2 += 1; continue
            }
            break
        }
        return clicks
    }

    /**
     * Single fixed-X load planner. We pick ONE withdraw-quantity X that's reused across
     * every iteration so the chat-dialog cost is paid at most once per session.
     *
     * Default X=14 produces a 14+14 = 28-slot load that's optimal for the (3)+(1) priority
     * (which yields a (4) per click with zero leftovers). For other shapes (e.g. only (2)s
     * or only (3)s in the bank) the same X=14 still fits 28 slots and the planner produces
     * the right click sequence. The actual per-iteration item choice (which two of (1)/(2)/(3)
     * to withdraw) is computed in [chooseLoadIds] below.
     */
    const val FIXED_X: Int = 14

    /**
     * Choose which dose IDs to withdraw this iteration, given current bank counts.
     * Returns (idA, idB) — two item ids to withdraw FIXED_X of each, or fewer if the bank
     * is running out. Null means no actionable load is left.
     *
     * Priority pairings (bank counts only): (3)+(1) > (2)+(2) > (3)+(2) > (3)+(3) > (1)+(1).
     */
    fun chooseLoadIds(bank1: Int, bank2: Int, bank3: Int): Pair<Int, Int>? {
        // dose-num → bank-count
        val have = mapOf(1 to bank1, 2 to bank2, 3 to bank3).filterValues { it > 0 }
        if (have.isEmpty()) return null

        // Priority: yield-per-click, slot-cost considered.
        val priority = listOf(
            3 to 1, // (3)+(1)
            2 to 2, // (2)+(2)
            3 to 2, // (3)+(2)
            3 to 3, // (3)+(3)
            1 to 1, // (1)+(1)
        )
        for ((a, b) in priority) {
            if (a == b) {
                if ((have[a] ?: 0) >= 2) return a to b
            } else {
                if ((have[a] ?: 0) >= 1 && (have[b] ?: 0) >= 1) return a to b
            }
        }
        // Singleton fallback: only one dose-type with stack >= 1 — withdraw it solo for
        // the planner to chew through (e.g. lone (3)s collapse via (3)+(3) → (4)+(2)).
        val solo = have.entries.firstOrNull() ?: return null
        return solo.key to solo.key
    }
}

// ==========================================================================================
// States
// ==========================================================================================

// Tight per-click wait: dose-merge fires within one tick (~600ms). Poll fast and exit on
// the first observed inventory-count delta.
private const val CLICK_TIMEOUT_MS = 800
private const val CLICK_POLL_MS = 50
// Withdraw/deposit waits: bank ops are usually instant client-side once the bank is open.
private const val BANK_OP_TIMEOUT_MS = 1500
private const val BANK_OP_POLL_MS = 50

private class Setup(val script: DecanterScript) : State() {
    override fun checkNext(client: Client): State? = null

    override fun loop(client: Client, script: StateMachineScript) {
        val potion = this.script.config.potion()
        val bankOpenEntry = Rs2Bank.isOpen()
        val qtyType = Microbot.getVarbitValue(VarbitID.BANK_QUANTITY_TYPE)
        Microbot.log("[Decanter] Setup entry: bankOpen=$bankOpenEntry qtyType=$qtyType potion=${potion.displayName} ids=[${potion.dose1},${potion.dose2},${potion.dose3},${potion.dose4}]")

        if (!Rs2Bank.isOpen() && !Rs2Bank.openBank()) {
            Microbot.log("[Decanter] Setup: openBank() returned false; waiting up to 5s")
            sleepUntil(timeout = 5000) { Rs2Bank.isOpen() }
            return
        }
        sleepUntil(timeout = 4000) { Rs2Bank.isOpen() }
        if (!Rs2Bank.isOpen()) {
            Microbot.log("[Decanter] Setup: bank still not open after waits; bailing this iteration")
            return
        }

        val b1 = Rs2Bank.count(potion.dose1)
        val b2 = Rs2Bank.count(potion.dose2)
        val b3 = Rs2Bank.count(potion.dose3)
        val invHasNonFour = potion.nonFourDoses().any { Rs2Inventory.hasItem(it) }
        Microbot.log("[Decanter] Setup counts: b1=$b1 b2=$b2 b3=$b3 invHasNonFour=$invHasNonFour")
        if (b1 == 0 && b2 == 0 && b3 == 0 && !invHasNonFour) {
            Microbot.log("[Decanter] Setup → Finish (no non-4-dose ${potion.displayName} anywhere). cycles=${this.script.cyclesCompleted} fours=${this.script.fourDosesProduced}")
            this.script.state = Finish(this.script)
            return
        }
        Microbot.log("[Decanter] Setup → Decant")
        this.script.state = Decant(this.script)
    }
}

private class Decant(val script: DecanterScript) : State() {
    override fun checkNext(client: Client): State? = null

    override fun loop(client: Client, script: StateMachineScript) {
        val potion = this.script.config.potion()
        val bankOpenEntry = Rs2Bank.isOpen()
        val qtyType = Microbot.getVarbitValue(VarbitID.BANK_QUANTITY_TYPE)
        Microbot.log("[Decanter] Decant entry: bankOpen=$bankOpenEntry qtyType=$qtyType potion=${potion.displayName} ids=[${potion.dose1},${potion.dose2},${potion.dose3},${potion.dose4}]")

        // Reset stale NPE counter at the start of each fresh iteration so a single bad
        // earlier batch doesn't bail the whole session.
        this.script.combineNpeCount = 0

        // Open bank if not already open (typical at start-of-iteration after a prior
        // close-before-combine cycle, or recovery from random event / login screen).
        if (!Rs2Bank.isOpen()) {
            Microbot.log("[Decanter] Decant: bank closed; opening for deposit+withdraw")
            if (!Rs2Bank.openBank()) {
                sleepUntil(timeout = 3000) { Rs2Bank.isOpen() }
                return
            }
            sleepUntil(timeout = 4000) { Rs2Bank.isOpen() }
            if (!Rs2Bank.isOpen()) {
                Microbot.log("[Decanter] Decant: bank failed to open; bail")
                return
            }
        }

        // Step 1: deposit anything that isn't a non-4 dose of the target potion. This
        // clears 4-doses produced last iteration plus empty vials in one sweep.
        val keep = potion.nonFourDoses().toTypedArray()
        val preDepInv = inventorySnapshot(potion)
        Microbot.log("[Decanter] Decant pre-deposit: keep=${keep.toList()} inv=$preDepInv")
        val depRet = Rs2Bank.depositAllExcept(*keep)
        // Defensive: wait for ANY non-keep item count to drop. If nothing was depositable
        // (i.e. inventory only has keep items already), this just times out — that's fine.
        val keepSet = keep.toSet()
        val depositables = preDepInv.entries.filter { it.key !in keepSet && it.value > 0 }
        if (depositables.isNotEmpty()) {
            sleepUntil(checkEvery = 50, timeout = 600) {
                depositables.any { (id, before) -> Rs2Inventory.count(id) < before }
            }
        }
        val postDepInv = inventorySnapshot(potion)
        Microbot.log("[Decanter] Decant post-deposit: depositReturn=$depRet inv=$postDepInv")

        // Step 2: read bank state and decide today's withdraw shape.
        val b1 = Rs2Bank.count(potion.dose1)
        val b2 = Rs2Bank.count(potion.dose2)
        val b3 = Rs2Bank.count(potion.dose3)
        val i1 = Rs2Inventory.count(potion.dose1)
        val i2 = Rs2Inventory.count(potion.dose2)
        val i3 = Rs2Inventory.count(potion.dose3)
        Microbot.log("[Decanter] Decant counts: bank b1=$b1 b2=$b2 b3=$b3 / inv i1=$i1 i2=$i2 i3=$i3")

        if (b1 + b2 + b3 + i1 + i2 + i3 == 0) {
            Microbot.log("[Decanter] Decant → Finish (no remaining doses). cycles=${this.script.cyclesCompleted} fours=${this.script.fourDosesProduced}")
            this.script.state = Finish(this.script)
            return
        }

        // Step 3: if inventory empty, withdraw a fresh load using fixed-X strategy.
        if (i1 + i2 + i3 == 0) {
            val pair = Planner.chooseLoadIds(b1, b2, b3)
            if (pair == null) {
                Microbot.log("[Decanter] Decant → Finish (no actionable bank pair).")
                this.script.state = Finish(this.script)
                return
            }
            val (doseA, doseB) = pair
            val idA = doseId(potion, doseA)!!
            val idB = doseId(potion, doseB)!!
            val bankA = Rs2Bank.count(idA)
            val bankB = if (idA == idB) bankA else Rs2Bank.count(idB)
            Microbot.log("[Decanter] Decant chosen pair: doseA=$doseA(id=$idA bank=$bankA) doseB=$doseB(id=$idB bank=$bankB)")

            if (idA == idB) {
                // Single-id load (e.g. only (3)s left). withdrawAll always — no dialog.
                val ret = Rs2Bank.withdrawAll(idA)
                Microbot.log("[Decanter] Decant withdrawAll(id=$idA) → ret=$ret bankStack=$bankA")
                if (!ret) {
                    Microbot.log("[Decanter] Decant: withdraw call returned false; sleep+skip")
                    Global.sleep(200)
                    return
                }
                sleepUntil(checkEvery = BANK_OP_POLL_MS, timeout = BANK_OP_TIMEOUT_MS) {
                    Rs2Inventory.count(idA) >= min(bankA, 28)
                }
            } else {
                // Two-id load. For each id, "withdraw all" if bank stack <= FIXED_X (one
                // click, no dialog); otherwise withdrawX(FIXED_X) — the dialog is paid
                // once per session because X stays constant across iterations.
                val rA = withdrawTargeted(idA, bankA, Planner.FIXED_X)
                val rB = withdrawTargeted(idB, bankB, Planner.FIXED_X)
                if (!rA || !rB) {
                    Microbot.log("[Decanter] Decant: withdraw call returned false (rA=$rA rB=$rB); sleep+skip")
                    Global.sleep(200)
                    return
                }
                sleepUntil(checkEvery = BANK_OP_POLL_MS, timeout = BANK_OP_TIMEOUT_MS) {
                    Rs2Inventory.count(idA) > 0 || Rs2Inventory.count(idB) > 0
                }
            }
            val postWithdraw = inventorySnapshot(potion)
            Microbot.log("[Decanter] Decant post-withdraw inv=$postWithdraw")
        }

        // Step 3.5: CLOSE the bank before combining. While the bank interface is open
        // in OSRS, an inventory item's primary action becomes "Deposit" rather than
        // "Use", so `Rs2Inventory.combine` (which issues use→use) silently no-ops.
        // We open/close once per LOAD (not per click) — combine sequence runs while
        // bank is closed, then next iteration re-opens for the next deposit+withdraw.
        if (Rs2Bank.isOpen()) {
            Microbot.log("[Decanter] Decant: closing bank before combine sequence")
            Rs2Bank.closeBank()
            sleepUntil(checkEvery = 50, timeout = 2000) { !Rs2Bank.isOpen() }
            if (Rs2Bank.isOpen()) {
                Microbot.log("[Decanter] Decant: bank failed to close; skipping combine this iteration")
                return
            }
        }

        // Step 4: plan + execute clicks with bank CLOSED. Combine "use→use" only fires
        // correctly when the inventory's primary action is "Use" (no bank interface up).
        val plan = Planner.plan(Doses(
            n1 = Rs2Inventory.count(potion.dose1),
            n2 = Rs2Inventory.count(potion.dose2),
            n3 = Rs2Inventory.count(potion.dose3),
        ))
        Microbot.log("[Decanter] Decant plan: ${plan.size} clicks")
        if (plan.isEmpty()) {
            this.script.cyclesCompleted += 1
            return
        }
        val startFours = Rs2Inventory.count(potion.dose4)
        var aborted = false
        // Stagnation watchdog: if 3 consecutive clicks produce no inventory change at
        // all, the combine path is broken (bank reopened, cache wedged, etc.) — abort
        // and let Setup re-validate state.
        var consecutiveNoChange = 0
        var clicksDone = 0
        for (click in plan) {
            val srcId = doseId(potion, click.src) ?: continue
            val dstId = doseId(potion, click.dst) ?: continue

            // Pre-validate BOTH ids on every click — cache may be stale even when
            // a previous click "succeeded". hasItem returns false if the item is
            // genuinely gone OR if the cache forgot about it; either way, we can't
            // safely call combine.
            if (!Rs2Inventory.hasItem(srcId) || !Rs2Inventory.hasItem(dstId)) {
                Microbot.log("[Decanter] combine: stale plan, abort and replan (src=$srcId hasSrc=${Rs2Inventory.hasItem(srcId)} dst=$dstId hasDst=${Rs2Inventory.hasItem(dstId)})")
                aborted = true
                break
            }

            val before4 = Rs2Inventory.count(potion.dose4)
            val before1 = Rs2Inventory.count(potion.dose1)
            val before2 = Rs2Inventory.count(potion.dose2)
            val before3 = Rs2Inventory.count(potion.dose3)

            // Use the model-overload combine(Rs2ItemModel, Rs2ItemModel) — Rs2Inventory.java:161.
            // The int-overload at line 137 calls `combine(get(primaryItemId), get(secondaryItemId))`,
            // which can pass null into the model-overload. The model-overload then builds a
            // lambda `item -> item.getId() == primary.getId()` (line 163) that NPEs the moment
            // it's evaluated. We sidestep that by fetching the models ourselves and null-checking
            // BEFORE handing them in — but the model-overload still re-fetches `primary` via
            // `get(...)` internally (line 163), so we ALSO wrap the call in try/catch in case the
            // cache flips null between our hasItem check and the internal lookup.
            val srcModel: Rs2ItemModel? = try { Rs2Inventory.get(srcId) } catch (_: Throwable) { null }
            val dstModel: Rs2ItemModel? = try { Rs2Inventory.get(dstId) } catch (_: Throwable) { null }
            if (srcModel == null || dstModel == null) {
                Microbot.log("[Decanter] combine: model-fetch returned null (srcModel=${srcModel != null} dstModel=${dstModel != null}) — abort and replan")
                aborted = true
                break
            }

            var ok = false
            var attempt = 0
            while (attempt < 2) {
                attempt++
                try {
                    ok = Rs2Inventory.combine(srcModel, dstModel)
                    this.script.combineNpeCount = 0
                    break
                } catch (npe: NullPointerException) {
                    this.script.combineNpeCount += 1
                    Microbot.log("[Decanter] combine NPE (attempt=$attempt npeCount=${this.script.combineNpeCount}): ${npe.message}")
                    if (this.script.combineNpeCount >= 3) {
                        Microbot.log("[Decanter] repeated combine NPEs — bailing to Finish")
                        this.script.state = Finish(this.script)
                        return
                    }
                    Global.sleep(100)
                    if (!Rs2Inventory.hasItem(srcId) || !Rs2Inventory.hasItem(dstId)) {
                        Microbot.log("[Decanter] combine: stale plan, abort and replan (post-NPE)")
                        aborted = true
                        break
                    }
                    // fall through to retry once
                } catch (t: Throwable) {
                    Microbot.log("[Decanter] combine threw ${t.javaClass.simpleName}: ${t.message} — abort and replan")
                    aborted = true
                    break
                }
            }
            if (aborted) break
            if (!ok) {
                Global.sleep(80)
                val unchanged = Rs2Inventory.count(potion.dose4) == before4 &&
                    Rs2Inventory.count(potion.dose1) == before1 &&
                    Rs2Inventory.count(potion.dose2) == before2 &&
                    Rs2Inventory.count(potion.dose3) == before3
                Microbot.log("[Decanter] Decant combine($srcId,$dstId) returned false; unchanged=$unchanged invSnap=${inventorySnapshot(potion)} — re-plan")
                if (unchanged) {
                    aborted = true
                    break
                }
            }
            sleepUntil(checkEvery = CLICK_POLL_MS, timeout = CLICK_TIMEOUT_MS) {
                Rs2Inventory.count(potion.dose4) != before4 ||
                Rs2Inventory.count(potion.dose1) != before1 ||
                Rs2Inventory.count(potion.dose2) != before2 ||
                Rs2Inventory.count(potion.dose3) != before3
            }
            // Stagnation watchdog: snapshot post-click inventory and compare to before.
            val changed = Rs2Inventory.count(potion.dose4) != before4 ||
                Rs2Inventory.count(potion.dose1) != before1 ||
                Rs2Inventory.count(potion.dose2) != before2 ||
                Rs2Inventory.count(potion.dose3) != before3
            clicksDone += 1
            if (changed) {
                consecutiveNoChange = 0
            } else {
                consecutiveNoChange += 1
                if (clicksDone % 3 == 0 || consecutiveNoChange >= 3) {
                    Microbot.log("[Decanter] combine progress: clicksDone=$clicksDone consecutiveNoChange=$consecutiveNoChange invSnap=${inventorySnapshot(potion)}")
                }
                if (consecutiveNoChange >= 3) {
                    Microbot.log("[Decanter] combine produced no inventory change in 3 clicks — aborting")
                    aborted = true
                    break
                }
            }
            Global.sleep(30, 80)
        }
        if (aborted) {
            // Force a re-validation pass next iteration: Setup re-reads bank+inventory
            // counts and routes back into Decant with fresh state. This breaks the
            // wedged "withdraws but never decants" loop.
            this.script.state = Setup(this.script)
        }
        val endFours = Rs2Inventory.count(potion.dose4)
        val gained = (endFours - startFours).coerceAtLeast(0)
        if (gained > 0) {
            this.script.fourDosesProduced += gained
        }
        this.script.cyclesCompleted += 1
        if (this.script.cyclesCompleted % 5 == 1) {
            Microbot.log("[Decanter] cycle=${this.script.cyclesCompleted} +${gained} → total fours=${this.script.fourDosesProduced}")
        }
    }

    /**
     * Withdraw `desired` of `itemId` using the cheapest call shape:
     *   - bankStack <= desired → withdrawAll (one click, no dialog).
     *   - bankStack >  desired → withdrawX(desired) (first call dialogs once; subsequent
     *     calls reuse the cached X value).
     */
    private fun withdrawTargeted(itemId: Int, bankStack: Int, desired: Int): Boolean {
        if (bankStack <= 0) {
            Microbot.log("[Decanter] withdrawTargeted: itemId=$itemId bankStack=0 → no-op (true)")
            return true
        }
        return if (bankStack <= desired) {
            val ret = Rs2Bank.withdrawAll(itemId)
            Microbot.log("[Decanter] withdrawTargeted: itemId=$itemId bankStack=$bankStack desired=$desired → withdrawAll ret=$ret")
            ret
        } else {
            val ret = Rs2Bank.withdrawX(itemId, desired)
            Microbot.log("[Decanter] withdrawTargeted: itemId=$itemId bankStack=$bankStack desired=$desired → withdrawX($desired) ret=$ret")
            ret
        }
    }

    private fun doseId(p: Potion, dose: Int): Int? = when (dose) {
        1 -> p.dose1
        2 -> p.dose2
        3 -> p.dose3
        4 -> p.dose4
        else -> null
    }
}

private fun inventorySnapshot(potion: Potion): Map<Int, Int> = mapOf(
    potion.dose1 to Rs2Inventory.count(potion.dose1),
    potion.dose2 to Rs2Inventory.count(potion.dose2),
    potion.dose3 to Rs2Inventory.count(potion.dose3),
    potion.dose4 to Rs2Inventory.count(potion.dose4),
    ItemID.VIAL to Rs2Inventory.count(ItemID.VIAL),
)

private class Finish(val script: DecanterScript) : State() {
    override fun checkNext(client: Client): State? = null

    override fun loop(client: Client, script: StateMachineScript) {
        if (Rs2Bank.isOpen()) Rs2Bank.closeBank()
        Microbot.log("[Decanter] stopped. cycles=${this.script.cyclesCompleted} fours produced=${this.script.fourDosesProduced}")
        this.script.stop()
    }
}
