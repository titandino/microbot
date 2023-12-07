package net.runelite.client.plugins.microbot.util.math;

import net.runelite.api.*;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.client.plugins.microbot.Microbot;

public class ObjectStrategy extends RouteStrategy {

    private int x;
    private int y;
    private int routeType;
    private ObjectType type;
    private int rotation;
    private int sizeX;
    private int sizeY;
    private int accessBlockFlag;

    public ObjectStrategy(TileObject object, ObjectDefinition def) {
        x = object.getWorldLocation().getX() - Microbot.getClient().getBaseX();
        y = object.getWorldLocation().getY() - Microbot.getClient().getBaseY();
        routeType = getType(object);
        type = def.getObjectTypes() == null ? ObjectType.SCENERY_INTERACT : ObjectType.forId(def.getObjectTypes()[0]);
        rotation = getRotation(object);
        sizeX = rotation == 0 || rotation == 2 ? def.getSizeX() : def.getSizeY();
        sizeY = rotation == 0 || rotation == 2 ? def.getSizeY() : def.getSizeX();
        accessBlockFlag = def.getBlockingMask();
        if (rotation != 0)
            accessBlockFlag = ((accessBlockFlag << rotation) & 0xF) + (accessBlockFlag >> (4 - rotation));
    }

    @Override
    public boolean canExit(int currentX, int currentY, int sizeXY, int[][] clip) {
        switch (routeType) {
            case 0:
                return RouteStrategy.checkWallInteract(clip, currentX, currentY, sizeXY, x, y, type, rotation);
            case 1:
                return RouteStrategy.checkWallDecorationInteract(clip, currentX, currentY, sizeXY, x, y, type, rotation);
            case 2:
                return RouteStrategy.checkFilledRectangularInteract(clip, currentX, currentY, sizeXY, sizeXY, x, y, sizeX, sizeY, accessBlockFlag);
            case 3:
                return currentX == x && currentY == y;
        }
        return false;
    }

    @Override
    public int getApproxDestinationX() {
        return x;
    }

    @Override
    public int getApproxDestinationY() {
        return y;
    }

    @Override
    public int getApproxDestinationSizeX() {
        return sizeX;
    }

    @Override
    public int getApproxDestinationSizeY() {
        return sizeY;
    }

    private int getType(TileObject object) {
        if (object instanceof WallObject)
            return 0;
        if (object instanceof DecorativeObject)
            return 1;
        if (object instanceof GameObject || object instanceof GroundObject)
            return 2;
        return 3;
    }

    private int getRotation(TileObject object) {
        if (object instanceof WallObject)
            return ((WallObject) object).getConfig() >>> 6 & 3;
        if (object instanceof GameObject)
            return ((GameObject) object).getOrientation();
        return 0;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ObjectStrategy))
            return false;
        ObjectStrategy strategy = (ObjectStrategy) other;
        return x == strategy.x && y == strategy.y && routeType == strategy.routeType && type == strategy.type && rotation == strategy.rotation && sizeX == strategy.sizeX && sizeY == strategy.sizeY && accessBlockFlag == strategy.accessBlockFlag;
    }

}
