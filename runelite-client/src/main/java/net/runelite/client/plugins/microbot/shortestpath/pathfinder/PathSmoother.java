package net.runelite.client.plugins.microbot.shortestpath.pathfinder;

import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Post-BFS line-of-sight path smoothing.
 *
 * <p>The raw BFS path emits one tile per step. Long straight corridors become
 * dozens of adjacent tiles even though a single minimap click from the start
 * of the corridor to the end would traverse the whole stretch. This smoother
 * greedily collapses those runs: for each anchor tile, it advances as far
 * along the path as a line-of-sight walk allows, keeping only the farthest
 * reachable waypoint.
 *
 * <p>Correctness rests on two invariants:
 * <ul>
 *   <li>{@link CollisionMap#canStep} rejects any step across a wall or
 *       closed-door edge — doors/stairs/ladders/teleports in the BFS path
 *       are reached via a transport edge, so their origin→destination
 *       step fails LOS and is always preserved.</li>
 *   <li>Non-adjacent consecutive path tiles (teleports, boats, POH portals)
 *       fail the cheap adjacency check before we even attempt LOS, so those
 *       boundaries are also preserved.</li>
 * </ul>
 *
 * <p>Segments are capped at {@link #MAX_SEGMENT_CHEBYSHEV} tiles so that
 * {@code Rs2Walker.isNearPath()} (which tests tiles within ~{@code
 * recalculateDistance} of the player) continues to intersect the path no
 * matter where along a long corridor the player currently is.
 */
public final class PathSmoother {
    static final int MAX_SEGMENT_CHEBYSHEV = 10;

    private PathSmoother() {}

    public static List<WorldPoint> smooth(List<WorldPoint> path, CollisionMap map) {
        if (path == null || path.size() < 3 || map == null) {
            return path;
        }

        final int n = path.size();
        List<WorldPoint> result = new ArrayList<>(n);
        result.add(path.get(0));

        int i = 0;
        while (i < n - 1) {
            int j = i + 1;
            while (j + 1 < n
                    && isChebyshevAdjacentSamePlane(path.get(j), path.get(j + 1))
                    && chebyshev(path.get(i), path.get(j + 1)) <= MAX_SEGMENT_CHEBYSHEV
                    && lineOfSight(path.get(i), path.get(j + 1), map)) {
                j++;
            }
            result.add(path.get(j));
            i = j;
        }

        return result;
    }

    private static boolean isChebyshevAdjacentSamePlane(WorldPoint a, WorldPoint b) {
        if (a.getPlane() != b.getPlane()) return false;
        return Math.abs(a.getX() - b.getX()) <= 1 && Math.abs(a.getY() - b.getY()) <= 1;
    }

    private static int chebyshev(WorldPoint a, WorldPoint b) {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    private static boolean lineOfSight(WorldPoint from, WorldPoint to, CollisionMap map) {
        if (from.getPlane() != to.getPlane()) return false;
        final int z = from.getPlane();
        int x = from.getX();
        int y = from.getY();
        final int tx = to.getX();
        final int ty = to.getY();
        while (x != tx || y != ty) {
            int dx = Integer.signum(tx - x);
            int dy = Integer.signum(ty - y);
            if (!map.canStep(x, y, z, dx, dy)) {
                return false;
            }
            x += dx;
            y += dy;
        }
        return true;
    }
}
