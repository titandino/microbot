package net.runelite.client.plugins.microbot.util.math;

import net.runelite.api.Point;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import okhttp3.Route;

import java.awt.*;


/**
 * Game world and projection calculations.
 */
public class Calculations {
    /**
     * Returns the angle to a given tile in degrees anti-clockwise from the
     * positive x axis (where the x-axis is from west to east).
     *
     * @param t The target tile
     * @return The angle in degrees
     */
    public static int angleToTile(Actor t) {
        int angle = (int) Math.toDegrees(Math.atan2(t.getWorldLocation().getY() - Microbot.getClient().getLocalPlayer().getWorldLocation().getY(),
                t.getWorldLocation().getX() - Microbot.getClient().getLocalPlayer().getWorldLocation().getX()));
        return angle >= 0 ? angle : 360 + angle;
    }

    public static int angleToTile(TileObject t) {
        int angle = (int) Math.toDegrees(Math.atan2(t.getWorldLocation().getY() - Microbot.getClient().getLocalPlayer().getWorldLocation().getY(),
                t.getWorldLocation().getX() - Microbot.getClient().getLocalPlayer().getWorldLocation().getX()));
        return angle >= 0 ? angle : 360 + angle;
    }

    public static int angleToTile(LocalPoint localPoint) {
        int angle = (int) Math.toDegrees(Math.atan2(localPoint.getY() - Microbot.getClient().getLocalPlayer().getWorldLocation().getY(),
                localPoint.getX() - Microbot.getClient().getLocalPlayer().getWorldLocation().getX()));
        return angle >= 0 ? angle : 360 + angle;
    }

    public static boolean pointOnScreen(Point check) {
        int x = check.getX(), y = check.getY();
        return x > Microbot.getClient().getViewportXOffset() && x < Microbot.getClient().getViewportWidth()
                && y > Microbot.getClient().getViewportYOffset() && y < Microbot.getClient().getViewportHeight();
    }

    public static Point tileToScreen(final Tile tile, final double dX, final double dY, final int height) {
        return Perspective.localToCanvas(Microbot.getClient(), new LocalPoint(tile.getLocalLocation().getX(), tile.getLocalLocation().getY()), Microbot.getClient().getPlane(), height);
    }

    public static Point tileToScreenPoint(final Point point, final double dX, final double dY, final int height) {
        return Perspective.localToCanvas(Microbot.getClient(), new LocalPoint(point.getX(), point.getY()), Microbot.getClient().getPlane(), height);
    }

    public static boolean tileOnScreen(Actor actor) {
        Point p = new Point(actor.getLocalLocation().getX(), actor.getLocalLocation().getY());
        Point tileToScreenPoint = tileToScreenPoint(p, 0.5, 0.5, 0);
        return (tileToScreenPoint != null) && pointOnScreen(tileToScreenPoint);
    }

    public static boolean tileOnScreen(TileObject tileObject) {
        Point p = new Point(tileObject.getLocalLocation().getX(), tileObject.getLocalLocation().getY());
        Point tileToScreenPoint = tileToScreenPoint(p, 0.5, 0.5, 0);
        return (tileToScreenPoint != null) && pointOnScreen(tileToScreenPoint);
    }

    public static boolean tileOnScreen(LocalPoint localPoint) {
        if (localPoint == null) return false;
        Point p = new Point((int) localPoint.getX(), (int) localPoint.getY());
        Point tileToScreenPoint = tileToScreenPoint(p, 0.5, 0.5, 0);
        return (tileToScreenPoint != null) && pointOnScreen(tileToScreenPoint);
    }

    public static Point tileToScreenHalfWay(final Tile tile, final double dX, final double dY, final int height) {
        return Perspective.localToCanvas(Microbot.getClient(), new LocalPoint((tile.getWorldLocation().getX() +
                Microbot.getClient().getLocalPlayer().getWorldLocation().getX()) / 2, (tile.getWorldLocation().getY() +
                Microbot.getClient().getLocalPlayer().getWorldLocation().getY()) / 2), Microbot.getClient().getPlane(), height);
    }

    public static boolean tileOnScreen(Tile t) {
        Point point = tileToScreen(t, 0.5, 0.5, 0);
        return (point != null) && pointOnScreen(point);
    }

    public static boolean tileOnScreenHalfWay(Tile t) {
        Point point = tileToScreenHalfWay(t, 0.5, 0.5, 0);
        return (point != null) && pointOnScreen(point);
    }

    public static Tile getTileOnScreen(Tile tile) {
        try {
            if (tileOnScreen(tile)) {
                return tile;
            } else {
                if (tileOnScreenHalfWay(tile)) {
                    return tile;
                } else {
                    return getTileOnScreen(tile);
                }
            }
        } catch (StackOverflowError soe) {
            return null;
        }
    }

    public static boolean canReach(RouteStrategy strategy) {
        return pathLengthTo(strategy) != -1;
    }

    public static boolean tileOnMap(WorldPoint w) {
        return tileToMinimap(w) != null;
    }

    public static Point tileToMinimap(WorldPoint w) {
        return worldToMinimap(w.getX(), w.getY());
    }

    public static Point worldToMinimap(double x, double y) {
        LocalPoint test = LocalPoint.fromWorld(Microbot.getClient(), (int) x, (int) y);
        if (test != null) {
            return Microbot.getClientThread().runOnClientThread(() -> Perspective.localToMinimap(Microbot.getClient(), test, 2500 * (int) Microbot.getClient().getMinimapZoom()));
        }
        return null;
    }

    public static Point worldToCanvas(double x, double y) {
        LocalPoint test = LocalPoint.fromWorld(Microbot.getClient(), (int) x, (int) y);
        if (test != null) {
            return Microbot.getClientThread().runOnClientThread(() -> Perspective.localToCanvas(Microbot.getClient(), test, Microbot.getClient().getPlane()));
        }
        return null;
    }


    /**
     * Returns the length of the path generated to a given RSTile.
     *
     * @param strategy <code>true</code> if reaching any tile adjacent to the destination
     *                 should be accepted.
     * @return <code>true</code> if reaching any tile adjacent to the destination
     * should be accepted.
     */

    public static int pathLengthTo(RouteStrategy strategy) {
        WorldPoint curPos = Microbot.getClient().getLocalPlayer().getWorldLocation();
        return pathLengthBetween(curPos, strategy);
    }

    public static int pathLengthBetween(WorldPoint start, RouteStrategy strategy) {
        return findRouteDistance(start.getX() - Microbot.getClient().getBaseX(),start.getY() - Microbot.getClient().getBaseY(), strategy, false); // if it's an object, accept any adjacent tile
    }

    private static final int DIR_NORTH = 0x1;
    private static final int DIR_EAST = 0x2;
    private static final int DIR_SOUTH = 0x4;
    private static final int DIR_WEST = 0x8;
    private static final int QUEUE_SIZE = 4095;

    public static int findRouteDistance(int srcX, int srcY, RouteStrategy strategy, boolean findAlternative) {
        int[][] directions = new int[128][128];
        int[][] distances = new int[128][128];
        int[] bufferX = new int[4096];
        int[] bufferY = new int[4096];
        int[][] clip = Microbot.getClient().getCollisionMaps()[Microbot.getClient().getPlane()].getFlags();
        int exitX, exitY;

        for (int x = 0; x < 128; x++) {
            for (int y = 0; y < 128; y++) {
                directions[x][y] = 0;
                distances[x][y] = 99999999;
            }
        }
        boolean found = false;
        {
            int currentX = srcX;
            int currentY = srcY;
            int graphBaseX = srcX - (64);
            int graphBaseY = srcY - (64);
            directions[(64)][(64)] = 99;
            distances[(64)][(64)] = 0;
            int read = 0;
            int write = 1;
            bufferX[0] = srcX;
            bufferY[0] = srcY;
            while (read != write) {
                currentX = bufferX[read];
                currentY = bufferY[read];
                read = read + 1 & QUEUE_SIZE;
                int currentGraphX = currentX - graphBaseX;
                int currentGraphY = currentY - graphBaseY;
                int clipX = currentX;
                int clipY = currentY;
                if (strategy.canExit(currentX, currentY, 1, clip)) {
                    exitX = currentX;
                    exitY = currentY;
                    found = true;
                }
                int nextDistance = distances[currentGraphX][currentGraphY] + 1;
                if (currentGraphX > 0 && directions[currentGraphX - 1][currentGraphY] == 0 &&
                        !ClipFlag.flagged(clip[clipX - 1][clipY], ClipFlag.PFBW_GROUND_DECO, ClipFlag.PFBW_FLOOR, ClipFlag.PF_E, ClipFlag.PF_FULL)) {
                    bufferX[write] = currentX - 1;
                    bufferY[write] = currentY;
                    write = write + 1 & QUEUE_SIZE;
                    directions[currentGraphX - 1][currentGraphY] = DIR_EAST;
                    distances[currentGraphX - 1][currentGraphY] = nextDistance;
                }
                if (currentGraphX < 127 && directions[currentGraphX + 1][currentGraphY] == 0 &&
                        !ClipFlag.flagged(clip[clipX + 1][clipY], ClipFlag.PFBW_GROUND_DECO, ClipFlag.PFBW_FLOOR, ClipFlag.PF_W, ClipFlag.PF_FULL)) {
                    bufferX[write] = currentX + 1;
                    bufferY[write] = currentY;
                    write = write + 1 & QUEUE_SIZE;
                    directions[currentGraphX + 1][currentGraphY] = DIR_WEST;
                    distances[currentGraphX + 1][currentGraphY] = nextDistance;
                }
                if (currentGraphY > 0 && directions[currentGraphX][currentGraphY - 1] == 0 &&
                        !ClipFlag.flagged(clip[clipX][clipY - 1], ClipFlag.PFBW_GROUND_DECO, ClipFlag.PFBW_FLOOR, ClipFlag.PF_N, ClipFlag.PF_FULL)) {
                    bufferX[write] = currentX;
                    bufferY[write] = currentY - 1;
                    write = write + 1 & QUEUE_SIZE;
                    directions[currentGraphX][currentGraphY - 1] = DIR_NORTH;
                    distances[currentGraphX][currentGraphY - 1] = nextDistance;
                }
                if (currentGraphY < 127 && directions[currentGraphX][currentGraphY + 1] == 0 &&
                        !ClipFlag.flagged(clip[clipX][clipY + 1], ClipFlag.PFBW_GROUND_DECO, ClipFlag.PFBW_FLOOR, ClipFlag.PF_S, ClipFlag.PF_FULL)) {
                    bufferX[write] = currentX;
                    bufferY[write] = currentY + 1;
                    write = write + 1 & QUEUE_SIZE;
                    directions[currentGraphX][currentGraphY + 1] = DIR_SOUTH;
                    distances[currentGraphX][currentGraphY + 1] = nextDistance;
                }
                if (currentGraphX > 0 && currentGraphY > 0 && directions[currentGraphX - 1][currentGraphY - 1] == 0 &&
                        !ClipFlag.flagged(clip[clipX - 1][clipY - 1], ClipFlag.PFBW_GROUND_DECO, ClipFlag.PFBW_FLOOR, ClipFlag.PF_N, ClipFlag.PF_NE, ClipFlag.PF_E, ClipFlag.PF_FULL) &&
                        !ClipFlag.flagged(clip[clipX - 1][clipY], ClipFlag.PFBW_GROUND_DECO, ClipFlag.PFBW_FLOOR, ClipFlag.PF_E, ClipFlag.PF_FULL) &&
                        !ClipFlag.flagged(clip[clipX][clipY - 1], ClipFlag.PFBW_GROUND_DECO, ClipFlag.PFBW_FLOOR, ClipFlag.PF_N, ClipFlag.PF_FULL)) {
                    bufferX[write] = currentX - 1;
                    bufferY[write] = currentY - 1;
                    write = write + 1 & QUEUE_SIZE;
                    directions[currentGraphX - 1][currentGraphY - 1] = 3;
                    distances[currentGraphX - 1][currentGraphY - 1] = nextDistance;
                }
                if (currentGraphX < 127 && currentGraphY > 0 && directions[currentGraphX + 1][currentGraphY - 1] == 0 &&
                        !ClipFlag.flagged(clip[clipX + 1][clipY - 1], ClipFlag.PFBW_GROUND_DECO, ClipFlag.PFBW_FLOOR, ClipFlag.PF_NW, ClipFlag.PF_N, ClipFlag.PF_W, ClipFlag.PF_FULL) &&
                        !ClipFlag.flagged(clip[clipX + 1][clipY], ClipFlag.PFBW_GROUND_DECO, ClipFlag.PFBW_FLOOR, ClipFlag.PF_W, ClipFlag.PF_FULL) &&
                        !ClipFlag.flagged(clip[clipX][clipY - 1], ClipFlag.PFBW_GROUND_DECO, ClipFlag.PFBW_FLOOR, ClipFlag.PF_N, ClipFlag.PF_FULL)) {
                    bufferX[write] = currentX + 1;
                    bufferY[write] = currentY - 1;
                    write = write + 1 & QUEUE_SIZE;
                    directions[currentGraphX + 1][currentGraphY - 1] = 9;
                    distances[currentGraphX + 1][currentGraphY - 1] = nextDistance;
                }
                if (currentGraphX > 0 && currentGraphY < 127 && directions[currentGraphX - 1][currentGraphY + 1] == 0 &&
                        !ClipFlag.flagged(clip[clipX - 1][clipY + 1], ClipFlag.PFBW_GROUND_DECO, ClipFlag.PFBW_FLOOR, ClipFlag.PF_E, ClipFlag.PF_SE, ClipFlag.PF_S, ClipFlag.PF_FULL) &&
                        !ClipFlag.flagged(clip[clipX - 1][clipY], ClipFlag.PFBW_GROUND_DECO, ClipFlag.PFBW_FLOOR, ClipFlag.PF_E, ClipFlag.PF_FULL) &&
                        !ClipFlag.flagged(clip[clipX][clipY + 1], ClipFlag.PFBW_GROUND_DECO, ClipFlag.PFBW_FLOOR, ClipFlag.PF_S, ClipFlag.PF_FULL)) {
                    bufferX[write] = currentX - 1;
                    bufferY[write] = currentY + 1;
                    write = write + 1 & QUEUE_SIZE;
                    directions[currentGraphX - 1][currentGraphY + 1] = 6;
                    distances[currentGraphX - 1][currentGraphY + 1] = nextDistance;
                }
                if (currentGraphX < 127 && currentGraphY < 127 && directions[currentGraphX + 1][currentGraphY + 1] == 0 &&
                        !ClipFlag.flagged(clip[clipX + 1][clipY + 1], ClipFlag.PFBW_GROUND_DECO, ClipFlag.PFBW_FLOOR, ClipFlag.PF_S, ClipFlag.PF_SW, ClipFlag.PF_W, ClipFlag.PF_FULL) &&
                        !ClipFlag.flagged(clip[clipX + 1][clipY], ClipFlag.PFBW_GROUND_DECO, ClipFlag.PFBW_FLOOR, ClipFlag.PF_W, ClipFlag.PF_FULL) &&
                        !ClipFlag.flagged(clip[clipX][clipY + 1], ClipFlag.PFBW_GROUND_DECO, ClipFlag.PFBW_FLOOR, ClipFlag.PF_S, ClipFlag.PF_FULL)) {
                    bufferX[write] = currentX + 1;
                    bufferY[write] = currentY + 1;
                    write = write + 1 & QUEUE_SIZE;
                    directions[currentGraphX + 1][currentGraphY + 1] = 12;
                    distances[currentGraphX + 1][currentGraphY + 1] = nextDistance;
                }
            }
            exitX = currentX;
            exitY = currentY;
        }
        int graphBaseX = srcX - 64;
        int graphBaseY = srcY - 64;
        int endX = exitX;
        int endY = exitY;
        if (!found) {
            if (findAlternative) {
                int lowestCost = Integer.MAX_VALUE;
                int lowestDistance = Integer.MAX_VALUE;
                int alternativeRouteRange = 10;
                int approxDestX = strategy.getApproxDestinationX();
                int approxDestY = strategy.getApproxDestinationY();
                int approxDestinationSizeX = strategy.getApproxDestinationSizeX();
                int approxDestinationSizeY = strategy.getApproxDestinationSizeY();
                for (int checkX = approxDestX - alternativeRouteRange; checkX <= approxDestX + alternativeRouteRange; checkX++) {
                    for (int checkY = approxDestY - alternativeRouteRange; checkY <= approxDestY + alternativeRouteRange; checkY++) {
                        int graphX = checkX - graphBaseX;
                        int graphY = checkY - graphBaseY;
                        if (graphX >= 0 && graphY >= 0 && graphX < 128 && graphY < 128 && (distances[graphX][graphY] < 100)) {
                            int deltaX = 0;
                            if (checkX < approxDestX)
                                deltaX = approxDestX - checkX;
                            else if (checkX > approxDestinationSizeX + approxDestX - 1)
                                deltaX = checkX - (approxDestX + approxDestinationSizeX - 1);
                            int deltaY = 0;
                            if (checkY < approxDestY)
                                deltaY = approxDestY - checkY;
                            else if (checkY > approxDestinationSizeY + approxDestY - 1)
                                deltaY = checkY - (approxDestY + approxDestinationSizeY - 1);
                            int cost = deltaX * deltaX + deltaY * deltaY;
                            if (cost < lowestCost || (cost == lowestCost && (distances[graphX][graphY]) < lowestDistance)) {
                                lowestCost = cost;
                                lowestDistance = (distances[graphX][graphY]);
                                endX = checkX;
                                endY = checkY;
                            }
                        }
                    }
                }
                if (lowestCost == Integer.MAX_VALUE)
                    return -1;
            } else {
                if (srcX == endX && srcY == endY)
                    return 0;
                return -1;
            }
        }
        if (srcX == endX && srcY == endY)
            return 0;
        int steps = 0;
        int realSteps = 0;
        bufferX[steps] = endX;
        bufferY[steps++] = endY;
        int lastwritten;
        int direction = (lastwritten = directions[endX - graphBaseX][endY - graphBaseY]);
        while (srcX != endX || endY != srcY) {
            if (lastwritten != direction) {
                lastwritten = direction;
                bufferX[steps] = endX;
                bufferY[steps++] = endY;
            }
            if ((direction & 0x2) != 0)
                endX++;
            else if ((direction & 0x8) != 0)
                endX--;
            if ((direction & 0x1) != 0)
                endY++;
            else if ((direction & 0x4) != 0)
                endY--;
            realSteps++;
            direction = directions[endX - graphBaseX][endY - graphBaseY];
        }
        return realSteps;
    }

    public static void renderValidMovement() {
        Microbot.getClientThread().runOnClientThread(() -> {

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    canWalk(dx, dy);
                }
            }
            return true;
        });
    }

    public static boolean canWalk(int dx, int dy) {
        return Microbot.getClientThread().runOnClientThread(() -> {
            WorldArea area = Microbot.getClient().getLocalPlayer().getWorldArea();
            if (area == null) {
                return false;
            }
            boolean canWalk = area.canTravelInDirection(Microbot.getClient(), dx, dy);
            if (!canWalk) return false;
            LocalPoint lp = Microbot.getClient().getLocalPlayer().getLocalLocation();
            if (lp == null) {
                return false;
            }

            lp = new LocalPoint(
                    lp.getX() + dx * Perspective.LOCAL_TILE_SIZE + dx * Perspective.LOCAL_TILE_SIZE * (area.getWidth() - 1) / 2,
                    lp.getY() + dy * Perspective.LOCAL_TILE_SIZE + dy * Perspective.LOCAL_TILE_SIZE * (area.getHeight() - 1) / 2);

            Polygon poly = Perspective.getCanvasTilePoly(Microbot.getClient(), lp);
            if (poly == null) {
                return false;
            }
            return true;
        });
    }
}
