package net.runelite.client.plugins.microbot.globval.enums;

import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import org.junit.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * Validates that every {@link InterfaceTab} has a correct and unique
 * {@code varcIntIndex}, and that the {@link Rs2Tab} reverse-lookup map
 * stays in sync with the enum.  If a game update shifts the indices,
 * these tests will catch the drift at CI time.
 */
public class InterfaceTabTest {

    /** All real tabs (everything except the sentinel). */
    private static final Set<InterfaceTab> REAL_TABS =
            EnumSet.complementOf(EnumSet.of(InterfaceTab.NOTHING_SELECTED));

    // ------------------------------------------------------------------
    //  varcIntIndex uniqueness & range
    // ------------------------------------------------------------------

    @Test
    public void allRealTabsHaveNonNegativeVarcIntIndex() {
        for (InterfaceTab tab : REAL_TABS) {
            assertTrue(tab.name() + " has negative varcIntIndex " + tab.getVarcIntIndex(),
                    tab.getVarcIntIndex() >= 0);
        }
    }

    @Test
    public void noTwoTabsShareTheSameVarcIntIndex() {
        Map<Integer, InterfaceTab> seen = new HashMap<>();
        for (InterfaceTab tab : REAL_TABS) {
            InterfaceTab existing = seen.put(tab.getVarcIntIndex(), tab);
            assertNull("varcIntIndex " + tab.getVarcIntIndex()
                            + " is claimed by both " + existing + " and " + tab,
                    existing);
        }
    }

    @Test
    public void nothingSelectedHasSentinelIndex() {
        assertEquals(-1, InterfaceTab.NOTHING_SELECTED.getVarcIntIndex());
    }

    // ------------------------------------------------------------------
    //  Completeness: every index in the expected range is covered
    // ------------------------------------------------------------------

    @Test
    public void indicesFormContiguousRangeFromZeroToMax() {
        int maxIndex = REAL_TABS.stream()
                .mapToInt(InterfaceTab::getVarcIntIndex)
                .max()
                .orElse(-1);

        Set<Integer> actual = REAL_TABS.stream()
                .map(InterfaceTab::getVarcIntIndex)
                .collect(Collectors.toSet());

        for (int i = 0; i <= maxIndex; i++) {
            assertTrue("No InterfaceTab maps to varcIntIndex " + i, actual.contains(i));
        }
    }

    // ------------------------------------------------------------------
    //  Rs2Tab.INDEX_TO_TAB consistency
    // ------------------------------------------------------------------

    @Test
    public void rs2TabMapCoversAllRealTabs() {
        Map<Integer, InterfaceTab> map = Rs2Tab.INDEX_TO_TAB;
        for (InterfaceTab tab : REAL_TABS) {
            assertSame(tab.name() + " missing from Rs2Tab.INDEX_TO_TAB",
                    tab, map.get(tab.getVarcIntIndex()));
        }
    }

    @Test
    public void rs2TabMapDoesNotContainSentinel() {
        assertFalse("INDEX_TO_TAB should not contain NOTHING_SELECTED",
                Rs2Tab.INDEX_TO_TAB.containsValue(InterfaceTab.NOTHING_SELECTED));
    }

    @Test
    public void rs2TabMapSizeMatchesRealTabCount() {
        assertEquals("INDEX_TO_TAB size should equal real tab count",
                REAL_TABS.size(), Rs2Tab.INDEX_TO_TAB.size());
    }

    // ------------------------------------------------------------------
    //  Spot-check known values (catches silent renumbering)
    // ------------------------------------------------------------------

    @Test
    public void spotCheckKnownVarcIntIndices() {
        assertEquals("COMBAT",    0,  InterfaceTab.COMBAT.getVarcIntIndex());
        assertEquals("SKILLS",    1,  InterfaceTab.SKILLS.getVarcIntIndex());
        assertEquals("QUESTS",    2,  InterfaceTab.QUESTS.getVarcIntIndex());
        assertEquals("INVENTORY", 3,  InterfaceTab.INVENTORY.getVarcIntIndex());
        assertEquals("EQUIPMENT", 4,  InterfaceTab.EQUIPMENT.getVarcIntIndex());
        assertEquals("PRAYER",    5,  InterfaceTab.PRAYER.getVarcIntIndex());
        assertEquals("MAGIC",     6,  InterfaceTab.MAGIC.getVarcIntIndex());
        assertEquals("CHAT",      7,  InterfaceTab.CHAT.getVarcIntIndex());
        assertEquals("ACC_MAN",   8,  InterfaceTab.ACC_MAN.getVarcIntIndex());
        assertEquals("FRIENDS",   9,  InterfaceTab.FRIENDS.getVarcIntIndex());
        assertEquals("LOGOUT",    10, InterfaceTab.LOGOUT.getVarcIntIndex());
        assertEquals("SETTINGS",  11, InterfaceTab.SETTINGS.getVarcIntIndex());
        assertEquals("EMOTES",    12, InterfaceTab.EMOTES.getVarcIntIndex());
        assertEquals("MUSIC",     13, InterfaceTab.MUSIC.getVarcIntIndex());
    }
}
