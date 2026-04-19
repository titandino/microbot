package net.runelite.client.plugins.microbot.util.mouse;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;

import javax.inject.Inject;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.util.Global.sleep;

@Slf4j
public class VirtualMouse extends Mouse {

    private final ScheduledExecutorService scheduledExecutorService;

    @Inject
    public VirtualMouse() {
        super();
        this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    }

    public void setLastClick(Point point) {
        lastClick2 = lastClick;
        lastClick = point;
    }

	public void setLastMove(Point point) {
		lastMove = point;
		points.add(point);
		if (points.size() > MAX_POINTS) {
			points.pollFirst();
		}
	}

    private int[] scaleForDispatch(int x, int y) {
        Client c;
        try {
            c = Microbot.getClient();
        } catch (Exception ex) {
            return new int[]{x, y};
        }
        if (c == null || !c.isStretchedEnabled()) {
            return new int[]{x, y};
        }
        Dimension stretched = c.getStretchedDimensions();
        Dimension real = c.getRealDimensions();
        if (stretched == null || real == null || real.width == 0 || real.height == 0) {
            return new int[]{x, y};
        }
        return new int[]{
                (int) ((long) x * stretched.width / real.width),
                (int) ((long) y * stretched.height / real.height)
        };
    }

    private void dispatchMouse(int id, Point point, int button, int clickCount) {
        int[] s = scaleForDispatch(point.getX(), point.getY());
        Canvas canvas = getCanvas();
        MouseEvent event = new MouseEvent(canvas, id, System.currentTimeMillis(), 0,
                s[0], s[1], clickCount, false, button);
        dispatchWithoutFocusGrab(canvas, event);
    }

    private void dispatchMouseMove(int id, Point point) {
        int[] s = scaleForDispatch(point.getX(), point.getY());
        Canvas canvas = getCanvas();
        MouseEvent event = new MouseEvent(canvas, id, System.currentTimeMillis(), 0,
                s[0], s[1], 0, false);
        dispatchWithoutFocusGrab(canvas, event);
    }

    private void dispatchWheel(Point point, int wheelRotation, int unitsToScroll) {
        int[] s = scaleForDispatch(point.getX(), point.getY());
        Canvas canvas = getCanvas();
        MouseWheelEvent event = new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), 0, s[0], s[1], 0, false, 0, unitsToScroll, wheelRotation);
        dispatchWithoutFocusGrab(canvas, event);
    }

    // Jagex's MOUSE_PRESSED listener calls canvas.requestFocus() when the event source is the Canvas,
    // which yanks OS keyboard focus away from whatever app the user is typing in. Flip focusable off
    // for the duration of the synthetic dispatch so requestFocus is a no-op; mouse delivery itself is
    // unaffected by focusable state.
    private void dispatchWithoutFocusGrab(Canvas canvas, AWTEvent event) {
        boolean wasFocusable = canvas.isFocusable();
        if (wasFocusable) canvas.setFocusable(false);
        BotEventGuard.begin();
        try {
            canvas.dispatchEvent(event);
        } finally {
            BotEventGuard.end();
            if (wasFocusable) canvas.setFocusable(true);
        }
    }

    private void handleClick(Point point, boolean rightClick) {
        entered(point);
        exited(point);
        moved(point);
        pressed(point, rightClick ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1);
        released(point, rightClick ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1);
        clicked(point, rightClick ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1);
        setLastClick(point);
    }
    public Mouse click(Point point, boolean rightClick) {
        if (point == null) return this;

        Runnable clickAction = () -> {
            if (point.getX() > 1 && point.getY() > 1 && Microbot.naturalMouse != null) {
                Microbot.naturalMouse.moveTo(point.getX(), point.getY());
            }
            handleClick(point, rightClick);
        };

        if (Microbot.getClient().isClientThread()) {
            scheduledExecutorService.schedule(clickAction, 0, TimeUnit.MILLISECONDS);
        } else {
            clickAction.run();
        }

        return this;
    }


    public Mouse click(Point point, boolean rightClick, NewMenuEntry entry) {
        if (point == null) return this;

        Runnable clickAction = () -> {
            Point newPoint = point;
            if (point.getX() > 1 && point.getY() > 1 && Microbot.naturalMouse != null) {
                Microbot.naturalMouse.moveTo(point.getX(), point.getY());

                if (Rs2UiHelper.hasActor(entry)) {
                    Rectangle rectangle = Rs2UiHelper.getActorClickbox(entry.getActor());
                    if (!Rs2UiHelper.isMouseWithinRectangle(rectangle)) {
                        newPoint = Rs2UiHelper.getClickingPoint(rectangle, true);
                        Microbot.naturalMouse.moveTo(newPoint.getX(), newPoint.getY());
                    }
                }

                if (Rs2UiHelper.isGameObject(entry)) {
                    Rectangle rectangle = Rs2UiHelper.getObjectClickbox(entry.getGameObject());
                    if (!Rs2UiHelper.isMouseWithinRectangle(rectangle)) {
                        newPoint = Rs2UiHelper.getClickingPoint(rectangle, true);
                        Microbot.naturalMouse.moveTo(newPoint.getX(), newPoint.getY());

                    }
                }
            }

            Microbot.targetMenu = entry;
            handleClick(newPoint, rightClick);
        };

        if (Microbot.getClient().isClientThread()) {
            scheduledExecutorService.schedule(clickAction, 0, TimeUnit.MILLISECONDS);
        } else {
            clickAction.run();
        }

        return this;
    }


    public Mouse click(int x, int y) {
        return click(new Point(x, y), false);
    }

    public Mouse click(double x, double y) {
        return click(new Point((int) x, (int) y), false);
    }

    public Mouse click(Rectangle rectangle) {
        return click(Rs2UiHelper.getClickingPoint(rectangle, true), false);
    }

    @Override
    public Mouse click(int x, int y, boolean rightClick) {
        return click(new Point(x, y), rightClick);
    }

    @Override
    public Mouse click(Point point) {
        return click(point, false);
    }

    @Override
    public Mouse click(Point point, NewMenuEntry entry) {
        return click(point, false, entry);
    }

    @Override
    public Mouse click() {
        return click(Microbot.getClient().getMouseCanvasPosition());
    }

    public Mouse move(Point point) {
        setLastMove(point);
        dispatchMouseMove(MouseEvent.MOUSE_MOVED, point);
        return this;
    }

    public Mouse move(Rectangle rect) {
        Point pt = new Point((int) rect.getCenterX(), (int) rect.getCenterY());
        setLastMove(pt);
        dispatchMouseMove(MouseEvent.MOUSE_MOVED, pt);
        return this;
    }

    public Mouse move(Polygon polygon) {
        Point point = new Point((int) polygon.getBounds().getCenterX(), (int) polygon.getBounds().getCenterY());
        setLastMove(point);
        dispatchMouseMove(MouseEvent.MOUSE_MOVED, point);
        return this;
    }

    public Mouse scrollDown(Point point) {
        move(point);
        scheduledExecutorService.schedule(
                () -> dispatchWheel(point, 2, 10),
                Rs2Random.logNormalBounded(40, 100), TimeUnit.MILLISECONDS);
        return this;
    }

    public Mouse scrollUp(Point point) {
        move(point);
        scheduledExecutorService.schedule(
                () -> dispatchWheel(point, -2, -10),
                Rs2Random.logNormalBounded(40, 100), TimeUnit.MILLISECONDS);
        return this;
    }

    @Override
    public java.awt.Point getMousePosition() {
        Point point = lastMove;
        return new java.awt.Point(point.getX(), point.getY());
    }

    @Override
    public Mouse move(int x, int y) {
        return move(new Point(x, y));
    }

    @Override
    public Mouse move(double x, double y) {
        return move(new Point((int) x, (int) y));
    }

    private synchronized void pressed(Point point, int button) {
        dispatchMouse(MouseEvent.MOUSE_PRESSED, point, button, 1);
    }

    private synchronized void released(Point point, int button) {
        dispatchMouse(MouseEvent.MOUSE_RELEASED, point, button, 1);
    }

    private synchronized void clicked(Point point, int button) {
        dispatchMouse(MouseEvent.MOUSE_CLICKED, point, button, 1);
    }

    private synchronized void exited(Point point) {
        dispatchMouseMove(MouseEvent.MOUSE_EXITED, point);
    }

    private synchronized void entered(Point point) {
        dispatchMouseMove(MouseEvent.MOUSE_ENTERED, point);
    }

    private synchronized void moved(Point point) {
        dispatchMouseMove(MouseEvent.MOUSE_MOVED, point);
    }

    public void shutdown() {
        scheduledExecutorService.shutdownNow();
    }

    public Mouse drag(Point startPoint, Point endPoint) {
        if (startPoint == null || endPoint == null) return this;

        if (startPoint.getX() > 1 && startPoint.getY() > 1 && Microbot.naturalMouse != null)
            Microbot.naturalMouse.moveTo(startPoint.getX(), startPoint.getY());
        else
            move(startPoint);
        sleep(Rs2Random.logNormalBounded(50, 80));
        pressed(startPoint, MouseEvent.BUTTON1);
        sleep(Rs2Random.logNormalBounded(80, 120));
        if (endPoint.getX() > 1 && endPoint.getY() > 1 && Microbot.naturalMouse != null)
            Microbot.naturalMouse.moveTo(endPoint.getX(), endPoint.getY());
        else
            move(endPoint);
        sleep(Rs2Random.logNormalBounded(80, 120));
        released(endPoint, MouseEvent.BUTTON1);

        return this;
    }
}
