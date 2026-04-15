package net.runelite.client.plugins.microbot.util.tabs;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

@Slf4j
public class Rs2Tab {
    private static final int TAB_SWITCH_SCRIPT = 915;
    private static volatile InterfaceTab cachedTab = InterfaceTab.NOTHING_SELECTED;

    public static void onVarClientIntChanged(VarClientIntChanged event) {
        if (event.getIndex() != VarClientID.TOPLEVEL_PANEL) return;
        int value = Microbot.getClient().getVarcIntValue(VarClientID.TOPLEVEL_PANEL);
        switch (value) {
            case 0:
                cachedTab = InterfaceTab.COMBAT;
                break;
            case 1:
                cachedTab = InterfaceTab.SKILLS;
                break;
            case 2:
                cachedTab = InterfaceTab.QUESTS;
                break;
            case 3:
                cachedTab = InterfaceTab.INVENTORY;
                break;
            case 4:
                cachedTab = InterfaceTab.EQUIPMENT;
                break;
            case 5:
                cachedTab = InterfaceTab.PRAYER;
                break;
            case 6:
                cachedTab = InterfaceTab.MAGIC;
                break;
            case 7:
                cachedTab = InterfaceTab.CHAT;
                break;
            case 8:
                cachedTab = InterfaceTab.ACC_MAN;
                break;
            case 9:
                cachedTab = InterfaceTab.FRIENDS;
                break;
            case 10:
                cachedTab = InterfaceTab.LOGOUT;
                break;
            case 11:
                cachedTab = InterfaceTab.SETTINGS;
                break;
            case 12:
                cachedTab = InterfaceTab.EMOTES;
                break;
            case 13:
                cachedTab = InterfaceTab.MUSIC;
                break;
            default:
                cachedTab = InterfaceTab.NOTHING_SELECTED;
                break;
        }
    }

    public static InterfaceTab getCurrentTab() {
        return cachedTab;
    }

    public static boolean isCurrentTab(InterfaceTab tab) {
        return getCurrentTab() == tab;
    }

    public static boolean switchTo(InterfaceTab tab) {
        if (isCurrentTab(tab)) return true;

        if (tab == InterfaceTab.NOTHING_SELECTED && Microbot.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 0)
            return false;

        int varcIntIndex = tab.getVarcIntIndex();
        if (varcIntIndex != -1) {
            Microbot.getClientThread().runOnClientThreadOptional(() -> {
                Microbot.getClient().runScript(TAB_SWITCH_SCRIPT, varcIntIndex);
                return true;
            });
        } else {
            int hotkey = tab.getHotkey();
            if (hotkey == -1) {
                log.warn("Tab {} does not have a hotkey assigned, cannot switch to it.", tab.getName());
                return false;
            }
            Rs2Keyboard.keyPress(hotkey);
        }

        return sleepUntil(() -> isCurrentTab(tab));
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToInventoryTab() {
        return switchTo(InterfaceTab.INVENTORY);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToCombatOptionsTab() {
        return switchTo(InterfaceTab.COMBAT);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToSkillsTab() {
        return switchTo(InterfaceTab.SKILLS);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToQuestTab() {
        return switchTo(InterfaceTab.QUESTS);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToEquipmentTab() {
        return switchTo(InterfaceTab.EQUIPMENT);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToPrayerTab() {
        return switchTo(InterfaceTab.PRAYER);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToMagicTab() {
        return switchTo(InterfaceTab.MAGIC);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToGroupingTab() {
        return switchTo(InterfaceTab.CHAT);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToFriendsTab() {
        return switchTo(InterfaceTab.FRIENDS);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToAccountManagementTab() {
        return switchTo(InterfaceTab.ACC_MAN);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToSettingsTab() {
        return switchTo(InterfaceTab.SETTINGS);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToEmotesTab() {
        return switchTo(InterfaceTab.EMOTES);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToMusicTab() {
        return switchTo(InterfaceTab.MUSIC);
    }

    @Deprecated(since = "Use switchTo")
    public static boolean switchToLogout() {
        return switchTo(InterfaceTab.LOGOUT);
    }

	public static Widget getSpellBookTab()
	{
		if (Microbot.getClient().isResized())
		{
			if (Microbot.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1)
			{
				return Rs2Widget.getWidget(InterfaceID.ToplevelPreEoc.STONE6);
			}
			return Rs2Widget.getWidget(InterfaceID.ToplevelOsrsStretch.STONE6);
		}
		return Rs2Widget.getWidget(InterfaceID.Toplevel.STONE6);
	}
}
