package net.runelite.client.plugins.microbot.shortestpath;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.shortestpath.pathfinder.CollisionMap;
import net.runelite.client.plugins.microbot.shortestpath.pathfinder.PathSmoother;
import net.runelite.client.plugins.microbot.shortestpath.pathfinder.SplitFlagMap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins PathSmoother behaviour around transport anchors — specifically, that tiles
 * marked as transport origins/destinations are never collapsed even when
 * {@link CollisionMap#canStep} would otherwise glide past them. Regression guard
 * for the Tutorial Island rat-cage gates, whose wall collision isn't encoded in
 * the base collision data.
 */
public class PathSmootherTest {

    private static CollisionMap collisionMap;

    // Lumbridge courtyard — verified as open ground in ShortestPathCoreTest.
    private static final int LUM_X = 3220;
    private static final int LUM_Y = 3218;
    private static final int LUM_PLANE = 0;

    @BeforeClass
    public static void loadCollisionMap() {
        SplitFlagMap flags = SplitFlagMap.fromResources();
        assertNotNull("Collision map should load from resources", flags);
        collisionMap = new CollisionMap(flags);
        // Sanity: the corridor we're about to smooth through must actually be walkable.
        for (int dx = 0; dx < 5; dx++) {
            assertTrue("Lumbridge tile (" + (LUM_X + dx) + "," + LUM_Y + ") should not be blocked",
                    !collisionMap.isBlocked(LUM_X + dx, LUM_Y, LUM_PLANE));
        }
    }

    private static List<WorldPoint> straightEastCorridor(int length) {
        List<WorldPoint> path = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            path.add(new WorldPoint(LUM_X + i, LUM_Y, LUM_PLANE));
        }
        return path;
    }

    @Test
    public void smoothsOpenCorridorToEndpoints() {
        List<WorldPoint> raw = straightEastCorridor(5);

        List<WorldPoint> smoothed = PathSmoother.smooth(raw, collisionMap);

        assertEquals("Open corridor should collapse to start + end", 2, smoothed.size());
        assertEquals(raw.get(0), smoothed.get(0));
        assertEquals(raw.get(raw.size() - 1), smoothed.get(smoothed.size() - 1));
    }

    @Test
    public void preservesTransportAnchorInMiddleOfOpenCorridor() {
        List<WorldPoint> raw = straightEastCorridor(5);
        WorldPoint anchor = raw.get(2);
        Set<WorldPoint> anchors = Collections.singleton(anchor);

        List<WorldPoint> smoothed = PathSmoother.smooth(raw, collisionMap, anchors);

        assertTrue("Anchor must survive smoothing", smoothed.contains(anchor));
        assertEquals("First waypoint should still be the path start", raw.get(0), smoothed.get(0));
        assertEquals("Last waypoint should still be the path end", raw.get(raw.size() - 1),
                smoothed.get(smoothed.size() - 1));
    }

    @Test
    public void preservesOriginAndDestinationAnchorPair() {
        // Models a transport edge at indices 2 and 3 — both tiles must be retained
        // so the walker's handleTransports sees the origin and the walker re-anchors
        // from the destination.
        List<WorldPoint> raw = straightEastCorridor(6);
        WorldPoint origin = raw.get(2);
        WorldPoint destination = raw.get(3);
        Set<WorldPoint> anchors = new HashSet<>(Arrays.asList(origin, destination));

        List<WorldPoint> smoothed = PathSmoother.smooth(raw, collisionMap, anchors);

        assertTrue("Origin must survive smoothing", smoothed.contains(origin));
        assertTrue("Destination must survive smoothing", smoothed.contains(destination));
        int originIdx = smoothed.indexOf(origin);
        int destIdx = smoothed.indexOf(destination);
        assertTrue("Origin must come before destination", originIdx < destIdx);
    }

    @Test
    public void nullAnchorsBehavesLikeTwoArgOverload() {
        List<WorldPoint> raw = straightEastCorridor(5);

        List<WorldPoint> withNull = PathSmoother.smooth(raw, collisionMap, null);
        List<WorldPoint> withoutArg = PathSmoother.smooth(raw, collisionMap);

        assertEquals(withoutArg, withNull);
    }

    @Test
    public void anchorNotInPathIsIgnored() {
        List<WorldPoint> raw = straightEastCorridor(5);
        Set<WorldPoint> anchors = Collections.singleton(new WorldPoint(9999, 9999, 0));

        List<WorldPoint> smoothed = PathSmoother.smooth(raw, collisionMap, anchors);

        assertEquals("Unrelated anchor should not affect smoothing", 2, smoothed.size());
    }

    @Test
    public void shortPathReturnedUnchanged() {
        // The smoother skips work for paths shorter than 3 tiles — the only way to
        // "collapse" a 2-tile path would be to drop one endpoint, which is wrong.
        List<WorldPoint> twoTiles = straightEastCorridor(2);
        assertEquals(twoTiles, PathSmoother.smooth(twoTiles, collisionMap, Collections.emptySet()));

        List<WorldPoint> oneTile = straightEastCorridor(1);
        assertEquals(oneTile, PathSmoother.smooth(oneTile, collisionMap, Collections.emptySet()));
    }
}
