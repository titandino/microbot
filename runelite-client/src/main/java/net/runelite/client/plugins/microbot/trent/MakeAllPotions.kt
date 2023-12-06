package net.runelite.client.plugins.microbot.trent

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.ItemID
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.inventory.Inventory
import net.runelite.client.plugins.microbot.util.keyboard.VirtualKeyboard
import java.awt.event.KeyEvent
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Potion Processor",
    description = "Makes all potions possible in bank",
    tags = ["sorc", "garden", "thieve"],
    enabledByDefault = false
)
class MakeAllPotions : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false

    private var currentHerb: Herb? = null
    private var currentPot: Potion? = null

    enum class Herb(val grimyId: Int, val cleanId: Int) {
        GUAM(ItemID.GRIMY_GUAM_LEAF, ItemID.GUAM_LEAF),
        MARRENTILL(ItemID.GRIMY_MARRENTILL, ItemID.MARRENTILL),
        TARROMIN(ItemID.GRIMY_TARROMIN, ItemID.TARROMIN),
        HARRALANDER(ItemID.GRIMY_HARRALANDER, ItemID.HARRALANDER),
        RANARR(ItemID.GRIMY_RANARR_WEED, ItemID.RANARR_WEED),
        SNAPDRAGON(ItemID.GRIMY_SNAPDRAGON, ItemID.SNAPDRAGON),
        TOADFLAX(ItemID.GRIMY_TOADFLAX, ItemID.TOADFLAX),
        AVANTOE(ItemID.GRIMY_AVANTOE, ItemID.AVANTOE),
        IRIT(ItemID.GRIMY_IRIT_LEAF, ItemID.IRIT_LEAF),
        KWUARM(ItemID.GRIMY_KWUARM, ItemID.KWUARM),
        CADANTINE(ItemID.GRIMY_CADANTINE, ItemID.CADANTINE),
        LANTADYME(ItemID.GRIMY_LANTADYME, ItemID.LANTADYME),
        DWARF_WEED(ItemID.GRIMY_DWARF_WEED, ItemID.DWARF_WEED),
        TORSTOL(ItemID.GRIMY_TORSTOL, ItemID.TORSTOL)
    }

    enum class Potion(val primary: Int, val secondary: Int) {
        RANARR_UNF(ItemID.VIAL_OF_WATER, ItemID.RANARR_WEED),
        IRIT_UNF(ItemID.VIAL_OF_WATER, ItemID.IRIT_LEAF),
        KWUARM_UNF(ItemID.VIAL_OF_WATER, ItemID.KWUARM),
        CADANTINE_UNF(ItemID.VIAL_OF_WATER, ItemID.CADANTINE),
        LANTADYME_UNF(ItemID.VIAL_OF_WATER, ItemID.LANTADYME),
        PRAYER(ItemID.RANARR_POTION_UNF, ItemID.SNAPE_GRASS),
        SUPER_ATTACK(ItemID.IRIT_POTION_UNF, ItemID.EYE_OF_NEWT),
        SUPER_STRENGTH(ItemID.KWUARM_POTION_UNF, ItemID.LIMPWURT_ROOT),
        SUPER_DEFENSE(ItemID.CADANTINE_POTION_UNF, ItemID.WHITE_BERRIES),
        MAGIC(ItemID.LANTADYME_POTION_UNF, ItemID.POTATO_CACTUS)
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun startUp() {
        if (client.getLocalPlayer() != null) {
            running = true;
            GlobalScope.launch { run() }
        }
    }

    private fun run() {
        while (running) {
            try {
                when {
                    bankContainsGrimys() -> cleanGrimys()
                    currentPot == null -> choosePotion()
                    else -> makePotion()
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    private fun bankContainsGrimys() = Herb.values().any { Rs2Bank.hasItem(it.grimyId) }

    private fun cleanGrimys() {
        currentHerb = currentHerb ?: Herb.values().firstOrNull { Rs2Bank.hasItem(it.grimyId) }
        if (currentHerb == null || !Rs2Bank.hasItem(currentHerb!!.grimyId)) {
            currentHerb = null
            return
        }
        Rs2Bank.useBank()
        Global.sleep(600, 1000)
        Global.sleepUntil { !Microbot.isMoving() }
        Rs2Bank.depositAll()
        Global.sleep(600, 1000)
        attempt(10) {
            Rs2Bank.withdrawAll(currentHerb!!.grimyId)
            Global.sleep(600, 1000)
            return@attempt Inventory.hasItemAmount(currentHerb!!.grimyId, 1)
        }
        Global.sleep(600, 1000)
        Rs2Bank.closeBank();
        Global.sleep(1000, 2000)
        repeat(28) { Inventory.useItemSlot(it) }
    }

    private fun choosePotion() {
        currentPot = Potion.values().firstOrNull { Rs2Bank.hasItem(it.primary) && Rs2Bank.hasItem(it.secondary) }
    }

    private fun makePotion() {
        currentPot?.let { pot ->
            if (!Rs2Bank.hasItem(pot.primary) || !Rs2Bank.hasItem(pot.secondary)) {
                currentPot = null
                return
            }
            Rs2Bank.useBank()
            Global.sleep(600, 1000)
            Global.sleepUntil { Rs2Bank.isOpen() }
            Rs2Bank.depositAll()
            Global.sleep(600, 1000)
            attempt(10) {
                Rs2Bank.withdrawX(pot.primary, 14)
                Global.sleep(600, 1000)
                return@attempt Inventory.hasItemAmount(pot.primary, 1)
            }
            attempt(10) {
                Rs2Bank.withdrawX(pot.secondary, 14)
                Global.sleep(600, 1000)
                return@attempt Inventory.hasItemAmount(pot.secondary, 1)
            }
            Rs2Bank.closeBank();
            Global.sleep(1000, 2000)
            Inventory.useItem(pot.primary)
            Global.sleep(300, 600)
            Inventory.useItem(pot.secondary)
            Global.sleep(600, 1000)
            VirtualKeyboard.keyPress(KeyEvent.VK_SPACE)
            Global.sleepUntil({ !Inventory.hasItemAmount(pot.secondary, 1) }, 40000)
        }
    }

    override fun shutDown() {
        running = false
    }
}
