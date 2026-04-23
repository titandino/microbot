// The unfinished-potion constants used in HerbloreRecipe live only in the legacy
// net.runelite.api.ItemID class (gameval.ItemID hasn't migrated them yet); isolate the
// deprecation warnings that produces with a file-level suppress so the build stays clean.
@file:Suppress("DEPRECATION")

package net.runelite.client.plugins.microbot.trent.bankstander

import com.google.inject.Provides
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.gameval.ItemID
// Legacy ItemID alias — used only for constants not yet migrated into gameval (the
// unfinished-potion set). Isolating the import keeps the deprecation warnings scoped.
import net.runelite.api.ItemID as LegacyItemID
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
import net.runelite.client.plugins.microbot.util.math.Rs2Random
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget
import javax.inject.Inject

// ==========================================================================================
// Bankstander — general-purpose skilling bankstand loop.
//
// Supported activities (MVP):
//   - GEM_CUTTING        chisel on uncut gem → "Cut all" via Make-X. Default dragonstone.
//   - HERB_CLEANING      click each grimy herb (no dialogue; one click = one clean).
//   - HERBLORE_MIXING    unfinished potion + secondary → "Make All" via Make-X.
//   - FLETCHING          knife-on-logs or bowstring-on-unstrung → "Make All". Recipe picker
//                        encodes the whole chain (e.g. LOG_TO_LONGBOW_U, BOW_STRING_YEW_SB).
//   - CRAFTING_LEATHER   needle + thread + leather → "Make All" for gloves/boots/vambraces.
//
// Out of scope (intentional):
//   - No GE buying, no food, no combat, no world-hopping, no breaks, no auto-login. If the
//     bank doesn't have enough materials, we log and stop cleanly.
//   - No anti-logout nudge: the continuous interaction loop counts as activity.
//   - No travel. We assume the player is standing at a bank (bank booth/banker/chest within
//     a few tiles). If the bank isn't openable we surface the error and stop — the user can
//     move to a real bank and restart.
//
// Auto-login:  use the bundled "Mocrosoft AutoLogin" plugin (accountselector) if you want
// disconnect recovery. This script doesn't handle it.
// ==========================================================================================

// ------------------------------------------------------------------------------------------
// Enums (selected by the user in config)
// ------------------------------------------------------------------------------------------

enum class Activity { GEM_CUTTING, HERB_CLEANING, HERBLORE_MIXING, FLETCHING, CRAFTING_LEATHER }

/**
 * Cuttable gems. IDs sourced from runelite-api/gameval/ItemID.java.
 * Named plainly so they appear cleanly in the config dropdown.
 */
enum class Gem(val uncutId: Int, val cutId: Int, val displayName: String) {
    OPAL(ItemID.UNCUT_OPAL, ItemID.OPAL, "Opal"),
    JADE(ItemID.UNCUT_JADE, ItemID.JADE, "Jade"),
    RED_TOPAZ(ItemID.UNCUT_RED_TOPAZ, ItemID.RED_TOPAZ, "Red topaz"),
    SAPPHIRE(ItemID.UNCUT_SAPPHIRE, ItemID.SAPPHIRE, "Sapphire"),
    EMERALD(ItemID.UNCUT_EMERALD, ItemID.EMERALD, "Emerald"),
    RUBY(ItemID.UNCUT_RUBY, ItemID.RUBY, "Ruby"),
    DIAMOND(ItemID.UNCUT_DIAMOND, ItemID.DIAMOND, "Diamond"),
    DRAGONSTONE(ItemID.UNCUT_DRAGONSTONE, ItemID.DRAGONSTONE, "Dragonstone"),
    ONYX(ItemID.UNCUT_ONYX, ItemID.ONYX, "Onyx"),
    ZENYTE(ItemID.UNCUT_ZENYTE, ItemID.ZENYTE, "Zenyte"),
    ;

    override fun toString(): String = displayName
}

/**
 * Grimy herbs for cleaning. Cleaning is a single interact("Clean") per item — no Make-X
 * dialogue. The grimy IDs in gameval ItemID live under UNIDENTIFIED_* (the OSRS name for
 * "Grimy <herb>").
 */
enum class Herb(val grimyId: Int, val cleanId: Int, val displayName: String) {
    GUAM(ItemID.UNIDENTIFIED_GUAM, ItemID.GUAM_LEAF, "Guam"),
    MARRENTILL(ItemID.UNIDENTIFIED_MARENTILL, ItemID.MARENTILL, "Marrentill"),
    TARROMIN(ItemID.UNIDENTIFIED_TARROMIN, ItemID.TARROMIN, "Tarromin"),
    HARRALANDER(ItemID.UNIDENTIFIED_HARRALANDER, ItemID.HARRALANDER, "Harralander"),
    RANARR(ItemID.UNIDENTIFIED_RANARR, ItemID.RANARR_WEED, "Ranarr weed"),
    TOADFLAX(ItemID.UNIDENTIFIED_TOADFLAX, ItemID.TOADFLAX, "Toadflax"),
    IRIT(ItemID.UNIDENTIFIED_IRIT, ItemID.IRIT_LEAF, "Irit leaf"),
    AVANTOE(ItemID.UNIDENTIFIED_AVANTOE, ItemID.AVANTOE, "Avantoe"),
    KWUARM(ItemID.UNIDENTIFIED_KWUARM, ItemID.KWUARM, "Kwuarm"),
    SNAPDRAGON(ItemID.UNIDENTIFIED_SNAPDRAGON, ItemID.SNAPDRAGON, "Snapdragon"),
    CADANTINE(ItemID.UNIDENTIFIED_CADANTINE, ItemID.CADANTINE, "Cadantine"),
    LANTADYME(ItemID.UNIDENTIFIED_LANTADYME, ItemID.LANTADYME, "Lantadyme"),
    DWARF_WEED(ItemID.UNIDENTIFIED_DWARF_WEED, ItemID.DWARF_WEED, "Dwarf weed"),
    TORSTOL(ItemID.UNIDENTIFIED_TORSTOL, ItemID.TORSTOL, "Torstol"),
    ;

    override fun toString(): String = displayName
}

/**
 * Herblore mixing recipes (unfinished potion + secondary ingredient → finished potion).
 * Standard MVP set — covers the common training recipes. UNF IDs come from the legacy
 * `net.runelite.api.ItemID` constants (the gameval set hasn't defined them yet).
 */
enum class HerbloreRecipe(
    val unfId: Int,
    val secondaryId: Int,
    val displayName: String,
) {
    ATTACK_POTION(LegacyItemID.GUAM_POTION_UNF, ItemID.EYE_OF_NEWT, "Attack potion (Guam + Eye of newt)"),
    STRENGTH_POTION(LegacyItemID.TARROMIN_POTION_UNF, ItemID.LIMPWURT_ROOT, "Strength potion (Tarromin + Limpwurt)"),
    ENERGY_POTION(LegacyItemID.HARRALANDER_POTION_UNF, ItemID.CHOCOLATE_DUST, "Energy potion (Harralander + Chocolate dust)"),
    DEFENCE_POTION(LegacyItemID.RANARR_POTION_UNF, ItemID.WHITE_BERRIES, "Defence potion (Ranarr + White berries)"),
    PRAYER_POTION(LegacyItemID.RANARR_POTION_UNF, ItemID.SNAPE_GRASS, "Prayer potion (Ranarr + Snape grass)"),
    SUPER_ATTACK(LegacyItemID.IRIT_POTION_UNF, ItemID.EYE_OF_NEWT, "Super attack (Irit + Eye of newt)"),
    SUPER_STRENGTH(LegacyItemID.KWUARM_POTION_UNF, ItemID.LIMPWURT_ROOT, "Super strength (Kwuarm + Limpwurt)"),
    SUPER_DEFENCE(LegacyItemID.CADANTINE_POTION_UNF, ItemID.WHITE_BERRIES, "Super defence (Cadantine + White berries)"),
    SUPER_ENERGY(LegacyItemID.AVANTOE_POTION_UNF, LegacyItemID.MORT_MYRE_FUNGUS, "Super energy (Avantoe + Mort myre fungus)"),
    SUPER_RESTORE(LegacyItemID.SNAPDRAGON_POTION_UNF, ItemID.RED_SPIDERS_EGGS, "Super restore (Snapdragon + Red spiders' eggs)"),
    ANTIFIRE(LegacyItemID.LANTADYME_POTION_UNF, ItemID.BLUE_DRAGON_SCALE, "Antifire (Lantadyme + Blue dragon scale)"),
    RANGING_POTION(LegacyItemID.DWARF_WEED_POTION_UNF, ItemID.WINE_OF_ZAMORAK, "Ranging potion (Dwarf weed + Wine of zamorak)"),
    ZAMORAK_BREW(LegacyItemID.TORSTOL_POTION_UNF, ItemID.JANGERBERRIES, "Zamorak brew (Torstol + Jangerberries)"),
    ;

    override fun toString(): String = displayName
}

/**
 * Fletching recipes. Each entry encodes the whole chain: primary tool (knife or bow string)
 * + material → result. `optionText` is the label the production widget displays for the
 * Make-X option we want to pick (matched case-insensitively by Rs2Widget.handleProcessingInterface).
 *
 * Bow stringing uses BOW_STRING as the tool (not a knife). For those, `toolId` points to
 * BOW_STRING and `materialId` is the unstrung bow — we call combine(toolId, materialId) and
 * the game opens the standard Make-X dialogue.
 */
enum class FletchingRecipe(
    val toolId: Int,
    val materialId: Int,
    val optionText: String,
    val displayName: String,
) {
    // === Knife-on-logs -> unstrung ===
    LOGS_TO_ARROW_SHAFT(ItemID.KNIFE, ItemID.LOGS, "Arrow shaft", "Logs -> Arrow shaft"),
    LOGS_TO_SHORTBOW_U(ItemID.KNIFE, ItemID.LOGS, "Shortbow", "Logs -> Shortbow (u)"),
    LOGS_TO_LONGBOW_U(ItemID.KNIFE, ItemID.LOGS, "Longbow", "Logs -> Longbow (u)"),
    OAK_SHORTBOW_U(ItemID.KNIFE, ItemID.OAK_LOGS, "Shortbow", "Oak logs -> Oak shortbow (u)"),
    OAK_LONGBOW_U(ItemID.KNIFE, ItemID.OAK_LOGS, "Longbow", "Oak logs -> Oak longbow (u)"),
    WILLOW_SHORTBOW_U(ItemID.KNIFE, ItemID.WILLOW_LOGS, "Shortbow", "Willow logs -> Willow shortbow (u)"),
    WILLOW_LONGBOW_U(ItemID.KNIFE, ItemID.WILLOW_LOGS, "Longbow", "Willow logs -> Willow longbow (u)"),
    MAPLE_SHORTBOW_U(ItemID.KNIFE, ItemID.MAPLE_LOGS, "Shortbow", "Maple logs -> Maple shortbow (u)"),
    MAPLE_LONGBOW_U(ItemID.KNIFE, ItemID.MAPLE_LOGS, "Longbow", "Maple logs -> Maple longbow (u)"),
    YEW_SHORTBOW_U(ItemID.KNIFE, ItemID.YEW_LOGS, "Shortbow", "Yew logs -> Yew shortbow (u)"),
    YEW_LONGBOW_U(ItemID.KNIFE, ItemID.YEW_LOGS, "Longbow", "Yew logs -> Yew longbow (u)"),
    MAGIC_SHORTBOW_U(ItemID.KNIFE, ItemID.MAGIC_LOGS, "Shortbow", "Magic logs -> Magic shortbow (u)"),
    MAGIC_LONGBOW_U(ItemID.KNIFE, ItemID.MAGIC_LOGS, "Longbow", "Magic logs -> Magic longbow (u)"),

    // === Bow stringing (unstrung -> strung via bow string) ===
    STRING_SHORTBOW(ItemID.BOW_STRING, ItemID.UNSTRUNG_SHORTBOW, "Shortbow", "String Shortbow"),
    STRING_LONGBOW(ItemID.BOW_STRING, ItemID.UNSTRUNG_LONGBOW, "Longbow", "String Longbow"),
    STRING_OAK_SHORTBOW(ItemID.BOW_STRING, ItemID.UNSTRUNG_OAK_SHORTBOW, "Oak shortbow", "String Oak shortbow"),
    STRING_OAK_LONGBOW(ItemID.BOW_STRING, ItemID.UNSTRUNG_OAK_LONGBOW, "Oak longbow", "String Oak longbow"),
    STRING_WILLOW_SHORTBOW(ItemID.BOW_STRING, ItemID.UNSTRUNG_WILLOW_SHORTBOW, "Willow shortbow", "String Willow shortbow"),
    STRING_WILLOW_LONGBOW(ItemID.BOW_STRING, ItemID.UNSTRUNG_WILLOW_LONGBOW, "Willow longbow", "String Willow longbow"),
    STRING_MAPLE_SHORTBOW(ItemID.BOW_STRING, ItemID.UNSTRUNG_MAPLE_SHORTBOW, "Maple shortbow", "String Maple shortbow"),
    STRING_MAPLE_LONGBOW(ItemID.BOW_STRING, ItemID.UNSTRUNG_MAPLE_LONGBOW, "Maple longbow", "String Maple longbow"),
    STRING_YEW_SHORTBOW(ItemID.BOW_STRING, ItemID.UNSTRUNG_YEW_SHORTBOW, "Yew shortbow", "String Yew shortbow"),
    STRING_YEW_LONGBOW(ItemID.BOW_STRING, ItemID.UNSTRUNG_YEW_LONGBOW, "Yew longbow", "String Yew longbow"),
    STRING_MAGIC_SHORTBOW(ItemID.BOW_STRING, ItemID.UNSTRUNG_MAGIC_SHORTBOW, "Magic shortbow", "String Magic shortbow"),
    STRING_MAGIC_LONGBOW(ItemID.BOW_STRING, ItemID.UNSTRUNG_MAGIC_LONGBOW, "Magic longbow", "String Magic longbow"),
    ;

    override fun toString(): String = displayName
}

/**
 * Leather crafting. Needle + thread (consumables, always withdrawn) + leather → result.
 * Thread wears out; we keep one full slot of it in the bank withdraw spec so the handler
 * tops up each cycle.
 */
enum class LeatherRecipe(
    val optionText: String,
    val displayName: String,
) {
    GLOVES("Leather gloves", "Leather gloves"),
    BOOTS("Leather boots", "Leather boots"),
    VAMBRACES("Leather vambraces", "Leather vambraces"),
    ;

    override fun toString(): String = displayName
}

// ------------------------------------------------------------------------------------------
// Config
// ------------------------------------------------------------------------------------------

@ConfigGroup(BankstanderConfig.GROUP)
interface BankstanderConfig : Config {
    companion object {
        const val GROUP = "trentBankstander"
    }

    // NOTE on @ConfigSection: in Java this is a String field, which Kotlin interfaces can't
    // emit as a backing field. Rather than fight the annotation model we keep all items flat
    // and rely on `position` ordering. The script reads only the picker matching the selected
    // Activity, so the other pickers are effectively dead (visible but unused). This matches
    // the task's guidance to not fight the config framework.

    @ConfigItem(
        keyName = "activity",
        name = "Activity",
        description = "Which skilling activity to run",
        position = 0,
    )
    fun activity(): Activity = Activity.GEM_CUTTING

    @ConfigItem(
        keyName = "gem",
        name = "Gem",
        description = "Gem to cut (used when Activity = GEM_CUTTING)",
        position = 1,
    )
    fun gem(): Gem = Gem.DRAGONSTONE

    @ConfigItem(
        keyName = "herb",
        name = "Herb",
        description = "Grimy herb to clean (used when Activity = HERB_CLEANING)",
        position = 2,
    )
    fun herb(): Herb = Herb.RANARR

    @ConfigItem(
        keyName = "herbloreRecipe",
        name = "Herblore recipe",
        description = "Unfinished potion + secondary (used when Activity = HERBLORE_MIXING)",
        position = 3,
    )
    fun herbloreRecipe(): HerbloreRecipe = HerbloreRecipe.PRAYER_POTION

    @ConfigItem(
        keyName = "fletchingRecipe",
        name = "Fletching recipe",
        description = "Chain: tool + material -> result (used when Activity = FLETCHING)",
        position = 4,
    )
    fun fletchingRecipe(): FletchingRecipe = FletchingRecipe.STRING_YEW_LONGBOW

    @ConfigItem(
        keyName = "leatherRecipe",
        name = "Leather recipe",
        description = "Leather item to craft (used when Activity = CRAFTING_LEATHER)",
        position = 5,
    )
    fun leatherRecipe(): LeatherRecipe = LeatherRecipe.VAMBRACES
}

// ==========================================================================================
// Plugin + Script wiring
// ==========================================================================================

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Bankstander",
    description = "General-purpose bankstand: gems/herbs/herblore/fletching/leather",
    tags = ["bankstand", "crafting", "herblore", "fletching", "gem"],
    enabledByDefault = false,
)
class Bankstander : Plugin() {
    @Inject
    private lateinit var client: Client

    @Inject
    private lateinit var config: BankstanderConfig

    @Provides
    fun provideConfig(configManager: ConfigManager): BankstanderConfig =
        configManager.getConfig(BankstanderConfig::class.java)

    private var running = false
    private var script: BankstanderScript? = null

    @OptIn(DelicateCoroutinesApi::class)
    override fun startUp() {
        if (client.localPlayer != null) {
            script = BankstanderScript(config)
            running = true
            GlobalScope.launch { run() }
        }
    }

    private fun run() {
        val s = script ?: return
        while (running && !s.stopped) {
            s.loop(client)
        }
    }

    override fun shutDown() {
        running = false
        script?.stop()
        script = null
    }
}

class BankstanderScript(val config: BankstanderConfig) : StateMachineScript() {
    // Persistent across cycles so we can log total progress.
    @Volatile var totalProduced: Int = 0
    @Volatile var cyclesCompleted: Int = 0

    /** Resolved lazily at first use so config changes at startup are respected. */
    val handler: ActivityHandler by lazy { resolveHandler(config) }

    override fun getStartState(): State = Root()
}

// ==========================================================================================
// Activity handler interface — encapsulates per-activity logic so Bank + Process don't
// become giant when-switches.
// ==========================================================================================

interface ActivityHandler {
    /** Human-readable label for logs. */
    val label: String

    /**
     * Items that must be present before we can process a cycle. Each spec is withdrawn
     * from the bank during the Bank state; the Process state keys off their presence.
     * List order matters: first entry is treated as the "primary consumable" and used
     * to detect cycle completion (when it's gone, the cycle is done).
     */
    fun bankRequirements(): List<WithdrawSpec>

    /**
     * Items that should NOT be deposited when opening the bank (typically tools like
     * chisel, needle, knife). Everything else in the inventory gets deposited.
     */
    fun keepInInventory(): Set<Int> =
        bankRequirements().filter { it.keep }.map { it.itemId }.toSet()

    /** Run one processing step. Returns true if a meaningful action was dispatched. */
    fun process(script: BankstanderScript): Boolean

    /** True when the cycle is complete (primary consumable depleted). */
    fun isCycleDone(): Boolean {
        val primary = bankRequirements().firstOrNull { !it.keep } ?: return true
        return Rs2Inventory.count(primary.itemId) == 0
    }

    /**
     * How many "produced" items came out of the last cycle. Called after cycle completion
     * and before deposit. Uses the primary consumable's starting amount as the count.
     */
    fun cycleCount(): Int = 27
}

/**
 * Withdrawal strategy. Three flavors matching the three real cases:
 *   - [One]        single "keep" tool/item (chisel, knife, needle): uses withdrawOne(id).
 *   - [All]        bulk consumable that fills the rest of inventory: uses withdrawAll(id).
 *                  Preferred over withdrawX(id, 27) because it's one click and no typing.
 *   - [Exactly]    paired-ratio cycles only (e.g. herblore 14+14). Uses withdrawX(id, n).
 *                  Reserve for cases where `All` would overflow another required item.
 */
sealed interface WithdrawAmount {
    object One : WithdrawAmount
    object All : WithdrawAmount
    data class Exactly(val n: Int) : WithdrawAmount
}

/**
 * Declarative withdraw request. `keep = true` means "don't deposit this when banking"
 * (tools, gem bag, etc.).
 */
data class WithdrawSpec(
    val itemId: Int,
    val amount: WithdrawAmount,
    val keep: Boolean = false,
    val name: String = "#$itemId",
)

// ------------------------------------------------------------------------------------------
// Handler implementations
// ------------------------------------------------------------------------------------------

private fun resolveHandler(config: BankstanderConfig): ActivityHandler = when (config.activity()) {
    Activity.GEM_CUTTING -> GemCuttingHandler(config.gem())
    Activity.HERB_CLEANING -> HerbCleaningHandler(config.herb())
    Activity.HERBLORE_MIXING -> HerbloreMixingHandler(config.herbloreRecipe())
    Activity.FLETCHING -> FletchingHandler(config.fletchingRecipe())
    Activity.CRAFTING_LEATHER -> LeatherCraftingHandler(config.leatherRecipe())
}

// ---- Gem cutting ----

private class GemCuttingHandler(val gem: Gem) : ActivityHandler {
    override val label = "gem cutting ${gem.displayName.lowercase()}"

    // Chisel kept in inventory; gems fill the rest of the slots via withdrawAll (single
    // menu click, no number-entry dialog). Order matters: chisel (one/keep) declared before
    // the fill-everything item so the inventory has room left over when we get to gems.
    override fun bankRequirements(): List<WithdrawSpec> = listOf(
        WithdrawSpec(ItemID.CHISEL, WithdrawAmount.One, keep = true, name = "Chisel"),
        WithdrawSpec(gem.uncutId, WithdrawAmount.All, name = "Uncut ${gem.displayName}"),
    )

    override fun process(script: BankstanderScript): Boolean {
        // Use chisel on gem → production widget opens → pick "Cut all" via the recipe label.
        // The production widget shows each gem name as an option; picking the chisel action
        // on an uncut gem in Make-X-mode produces the cut gem.
        if (!Rs2Inventory.hasItem(gem.uncutId)) return false
        if (!Rs2Inventory.hasItem(ItemID.CHISEL)) return false

        if (!Rs2Inventory.combine(ItemID.CHISEL, gem.uncutId)) return false

        sleepUntil(timeout = 4000) { Rs2Widget.isProductionWidgetOpen() }
        if (!Rs2Widget.isProductionWidgetOpen()) return false

        // "Cut all" is implicit via the option label — SKILLMULTI defaults to "All" quantity
        // after first interaction. We pick the gem's cut-name option and wait for depletion.
        val ok = Rs2Widget.handleProcessingInterface(gem.displayName)
        if (!ok) return false

        sleepUntil(checkEvery = 600, timeout = 90_000) { !Rs2Inventory.hasItem(gem.uncutId) }
        return true
    }
}

// ---- Herb cleaning ----

private class HerbCleaningHandler(val herb: Herb) : ActivityHandler {
    override val label = "cleaning ${herb.displayName.lowercase()}"

    override fun bankRequirements(): List<WithdrawSpec> = listOf(
        WithdrawSpec(herb.grimyId, WithdrawAmount.All, name = "Grimy ${herb.displayName}"),
    )

    override fun process(script: BankstanderScript): Boolean {
        // Each grimy herb has a "Clean" action. No Make-X dialogue. One click cleans one
        // herb (instant); we loop through the inventory until no grimy of this herb remains.
        if (!Rs2Inventory.hasItem(herb.grimyId)) return false

        // The interact(id, "Clean") call cleans one herb. Repeat tightly; server caps how
        // fast this can register but a small jitter between clicks is plenty.
        var any = false
        while (Rs2Inventory.hasItem(herb.grimyId)) {
            if (!Rs2Inventory.interact(herb.grimyId, "Clean")) break
            any = true
            Global.sleep(Rs2Random.between(40, 120))
        }
        // Give the client a beat to process the last click.
        sleepUntil(checkEvery = 200, timeout = 2000) { !Rs2Inventory.hasItem(herb.grimyId) }
        return any
    }

    override fun cycleCount(): Int = 28
}

// ---- Herblore mixing ----

private class HerbloreMixingHandler(val recipe: HerbloreRecipe) : ActivityHandler {
    override val label = "mixing ${recipe.displayName.lowercase()}"

    // Paired ratio: 14 unf + 14 secondary (Make-X does 14 combinations). The unf side MUST
    // be Exactly(14) — otherwise withdrawAll would pull 28 and leave zero room for the
    // secondary. The secondary can be `All` because after 14 unfs are in inventory there
    // are exactly 14 slots left, so withdrawAll fills them with no number-entry dialog.
    // Declaration order matters: unf (exact) first to consume half the slots before the
    // fill-everything secondary runs.
    override fun bankRequirements(): List<WithdrawSpec> = listOf(
        WithdrawSpec(recipe.unfId, WithdrawAmount.Exactly(14), name = "Unf potion"),
        WithdrawSpec(recipe.secondaryId, WithdrawAmount.All, name = "Secondary"),
    )

    override fun process(script: BankstanderScript): Boolean {
        if (!Rs2Inventory.hasItem(recipe.unfId) || !Rs2Inventory.hasItem(recipe.secondaryId)) return false

        if (!Rs2Inventory.combine(recipe.unfId, recipe.secondaryId)) return false

        sleepUntil(timeout = 4000) { Rs2Widget.isProductionWidgetOpen() }
        if (!Rs2Widget.isProductionWidgetOpen()) return false

        // Make-X "All" gets registered on the first pass; subsequent cycles just need
        // spacebar-equivalent, which handleProcessingInterface does via keyboard shortcut.
        val ok = Rs2Widget.handleProcessingInterface("Potion") ||
            Rs2Widget.handleProcessingInterface(recipe.displayName.substringBefore(" ("))
        if (!ok) return false

        sleepUntil(checkEvery = 600, timeout = 60_000) {
            !Rs2Inventory.hasItem(recipe.unfId) || !Rs2Inventory.hasItem(recipe.secondaryId)
        }
        return true
    }

    override fun cycleCount(): Int = 14
}

// ---- Fletching ----

private class FletchingHandler(val recipe: FletchingRecipe) : ActivityHandler {
    override val label = "fletching ${recipe.displayName.lowercase()}"

    override fun bankRequirements(): List<WithdrawSpec> {
        // Knife path: knife is a reusable tool (keep), logs fill the rest via withdrawAll.
        // Bow string path: paired ratio — unstrung bows are the limiting side, so we pin
        // them at Exactly(14), then withdrawAll the bow strings to fill the remaining 14
        // slots. No Withdraw-X dialog for either consumable.
        //
        // Declaration order matters for both branches: Exactly/One items first so they
        // occupy slots before the `All` item tries to fill the inventory.
        return when (recipe.toolId) {
            ItemID.KNIFE, ItemID.FLETCHING_KNIFE -> listOf(
                WithdrawSpec(ItemID.KNIFE, WithdrawAmount.One, keep = true, name = "Knife"),
                WithdrawSpec(recipe.materialId, WithdrawAmount.All, name = "Material"),
            )

            ItemID.BOW_STRING -> listOf(
                WithdrawSpec(recipe.materialId, WithdrawAmount.Exactly(14), name = "Unstrung"),
                WithdrawSpec(ItemID.BOW_STRING, WithdrawAmount.All, name = "Bow string"),
            )

            else -> listOf(
                WithdrawSpec(recipe.toolId, WithdrawAmount.Exactly(14), name = "Tool"),
                WithdrawSpec(recipe.materialId, WithdrawAmount.All, name = "Material"),
            )
        }
    }

    override fun process(script: BankstanderScript): Boolean {
        if (!Rs2Inventory.hasItem(recipe.toolId) || !Rs2Inventory.hasItem(recipe.materialId)) return false

        if (!Rs2Inventory.combine(recipe.toolId, recipe.materialId)) return false

        sleepUntil(timeout = 4000) { Rs2Widget.isProductionWidgetOpen() }
        if (!Rs2Widget.isProductionWidgetOpen()) return false

        val ok = Rs2Widget.handleProcessingInterface(recipe.optionText)
        if (!ok) return false

        // Material drives completion. For stringing, unstrung bows are the limiting
        // consumable; for knife fletching, logs are. Either way materialId hits 0 first.
        sleepUntil(checkEvery = 600, timeout = 90_000) { !Rs2Inventory.hasItem(recipe.materialId) }
        return true
    }

    override fun cycleCount(): Int = if (recipe.toolId == ItemID.BOW_STRING) 14 else 27
}

// ---- Leather crafting ----

private class LeatherCraftingHandler(val recipe: LeatherRecipe) : ActivityHandler {
    override val label = "crafting ${recipe.displayName.lowercase()}"

    // Declaration order: needle (keep) + thread (single stack) BEFORE leather (fill-rest).
    // Thread withdraws as `One` — a thread "item" is a stack of many, one inventory slot.
    // That's enough for many cycles; it wears down naturally and the bank check catches
    // depletion on the next banking visit. Leather uses `All` to fill whatever's left.
    override fun bankRequirements(): List<WithdrawSpec> = listOf(
        WithdrawSpec(ItemID.NEEDLE, WithdrawAmount.One, keep = true, name = "Needle"),
        WithdrawSpec(ItemID.THREAD, WithdrawAmount.One, name = "Thread"),
        WithdrawSpec(ItemID.LEATHER, WithdrawAmount.All, name = "Leather"),
    )

    override fun process(script: BankstanderScript): Boolean {
        if (!Rs2Inventory.hasItem(ItemID.NEEDLE) || !Rs2Inventory.hasItem(ItemID.LEATHER)) return false

        if (!Rs2Inventory.combine(ItemID.NEEDLE, ItemID.LEATHER)) return false

        sleepUntil(timeout = 4000) { Rs2Widget.isProductionWidgetOpen() }
        if (!Rs2Widget.isProductionWidgetOpen()) return false

        val ok = Rs2Widget.handleProcessingInterface(recipe.optionText)
        if (!ok) return false

        sleepUntil(checkEvery = 600, timeout = 90_000) { !Rs2Inventory.hasItem(ItemID.LEATHER) }
        return true
    }

    override fun cycleCount(): Int = 22
}

// ==========================================================================================
// States
// ==========================================================================================

/**
 * Root: trivial dispatcher. If nothing to process, go Bank; otherwise Process.
 * Kept so stop-signals have a stable home.
 */
private class Root : State() {
    @Volatile private var logged = false

    override fun checkNext(client: Client): State? {
        if (Microbot.pauseAllScripts.get()) return null
        return Bank()
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (!logged) {
            Microbot.log("[Bankstander] state: Root")
            logged = true
        }
        Global.sleep(Rs2Random.between(600, 1200))
    }
}

/**
 * Bank: open bank → deposit non-kept items → withdraw requirements → close → Process.
 * If the bank can't be opened (player not at a bank), we log a clear message and stop.
 */
private class Bank : State() {
    @Volatile private var logged = false

    override fun checkNext(client: Client): State? {
        if (Microbot.pauseAllScripts.get()) return null
        val s = ownScript ?: return null

        // If we already have all requirements and a closed bank, hand off to Process.
        if (!Rs2Bank.isOpen() && hasAllRequirements(s.handler)) return Process()
        return null
    }

    @Volatile private var ownScript: BankstanderScript? = null

    override fun loop(client: Client, script: StateMachineScript) {
        val s = script as? BankstanderScript ?: return
        ownScript = s
        val handler = s.handler

        if (!logged) {
            Microbot.log("[Bankstander] state: Bank (${handler.label})")
            logged = true
        }

        if (!Rs2Bank.isOpen()) {
            if (!Rs2Bank.openBank()) {
                Microbot.log("[Bankstander] Bank not reachable — stand at a bank and restart.")
                s.stop()
                return
            }
            sleepUntil(timeout = 5000) { Rs2Bank.isOpen() }
            if (!Rs2Bank.isOpen()) {
                Microbot.log("[Bankstander] Failed to open bank.")
                s.stop()
                return
            }
        }

        // Deposit everything not in the keep set (preserves chisel/needle/knife etc.).
        val keep = handler.keepInInventory()
        if (keep.isEmpty()) {
            Rs2Bank.depositAll()
        } else {
            Rs2Bank.depositAllExcept(*keep.toTypedArray())
        }
        sleepUntil(timeout = 3000) {
            Rs2Inventory.count { !keep.contains(it.id) } == 0
        }

        // Withdraw each requirement. Process in declaration order so that `Exactly` and
        // `One` specs (including keep-tools) claim their slots before any `All` spec runs
        // its fill-inventory pass. Idempotent: if the item is already satisfied we skip.
        for (spec in handler.bankRequirements()) {
            // Already-satisfied check. For `keep` items of any amount, presence is enough.
            // For `All`, presence of any quantity means "nothing to do this cycle" — we
            // already filled up. For `Exactly(n)`, need at least n in the inventory.
            val invCount = Rs2Inventory.count(spec.itemId)
            val alreadySatisfied = when {
                spec.keep -> Rs2Inventory.hasItem(spec.itemId)
                spec.amount is WithdrawAmount.Exactly -> invCount >= spec.amount.n
                spec.amount is WithdrawAmount.One -> invCount >= 1
                // WithdrawAmount.All: if we've got any of it, don't re-fire. Otherwise
                // withdrawing "all" twice is still fine but we'd waste a click.
                spec.amount is WithdrawAmount.All -> invCount >= 1
                else -> false
            }
            if (alreadySatisfied) continue

            // Bank-has-it check, amount-aware:
            //   One         → bank needs at least 1
            //   Exactly(n)  → bank needs at least n
            //   All         → bank needs at least 1 (fill-whatever-there-is is acceptable)
            val bankMinimum = when (val a = spec.amount) {
                WithdrawAmount.One -> 1
                WithdrawAmount.All -> 1
                is WithdrawAmount.Exactly -> a.n
            }
            if (!Rs2Bank.hasBankItem(spec.itemId, bankMinimum)) {
                Microbot.log("[Bankstander] Bank missing ${spec.name} (id=${spec.itemId}) — stopping.")
                s.stop()
                return
            }

            // Dispatch to the right bank call. withdrawX is the ONLY Exactly path; One and
            // All use dedicated single-click APIs that never type a number in.
            when (val a = spec.amount) {
                WithdrawAmount.One -> Rs2Bank.withdrawOne(spec.itemId)
                WithdrawAmount.All -> Rs2Bank.withdrawAll(spec.itemId)
                is WithdrawAmount.Exactly -> Rs2Bank.withdrawX(spec.itemId, a.n)
            }

            // Wait for the item(s) to show up in the inventory. Thresholds match amount.
            val expect = when (val a = spec.amount) {
                WithdrawAmount.One -> 1
                WithdrawAmount.All -> 1 // "at least one", since All fills variable capacity
                is WithdrawAmount.Exactly -> a.n
            }
            sleepUntil(checkEvery = 200, timeout = 2000) { Rs2Inventory.count(spec.itemId) >= expect }
        }

        if (!hasAllRequirements(handler)) {
            Microbot.log("[Bankstander] Could not satisfy all requirements — stopping.")
            s.stop()
            return
        }

        Rs2Bank.closeBank()
        sleepUntil(timeout = 2000) { !Rs2Bank.isOpen() }
        // Small humanizer before processing starts.
        Global.sleep(Rs2Random.between(200, 500))
    }
}

/**
 * Process: run the handler until isCycleDone(), then hand back to Bank.
 */
private class Process : State() {
    @Volatile private var logged = false
    @Volatile private var ownScript: BankstanderScript? = null

    override fun checkNext(client: Client): State? {
        if (Microbot.pauseAllScripts.get()) return null
        val s = ownScript ?: return null
        if (s.handler.isCycleDone()) {
            s.cyclesCompleted += 1
            s.totalProduced += s.handler.cycleCount()
            Microbot.log(
                "[Bankstander] cycle: ${s.handler.label} +${s.handler.cycleCount()} " +
                    "(total: ${s.totalProduced}, cycles: ${s.cyclesCompleted})",
            )
            return Bank()
        }
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        val s = script as? BankstanderScript ?: return
        ownScript = s
        val handler = s.handler

        if (!logged) {
            Microbot.log("[Bankstander] state: Process (${handler.label})")
            logged = true
        }

        if (Rs2Player.isAnimating() || Rs2Widget.isProductionWidgetOpen()) {
            // Let the current animation / production wave finish before re-triggering.
            sleepUntil(checkEvery = 600, timeout = 20_000) {
                handler.isCycleDone() ||
                    (!Rs2Player.isAnimating() && !Rs2Widget.isProductionWidgetOpen())
            }
            return
        }

        handler.process(s)
        // Small humanizer after firing the Make-X dispatch.
        Global.sleep(Rs2Random.between(100, 260))
    }
}

// ==========================================================================================
// Helpers
// ==========================================================================================

/**
 * True iff every requirement is present at a level adequate to start a cycle.
 * - `One` / `All`     → need at least 1 in inventory.
 * - `Exactly(n)`      → need at least n (the paired-ratio amount).
 * - `keep` just means the deposit skipped it; the amount rule still applies.
 */
private fun hasAllRequirements(handler: ActivityHandler): Boolean {
    for (spec in handler.bankRequirements()) {
        val minimum = when (val a = spec.amount) {
            WithdrawAmount.One, WithdrawAmount.All -> 1
            is WithdrawAmount.Exactly -> a.n
        }
        if (Rs2Inventory.count(spec.itemId) < minimum) return false
    }
    return true
}
