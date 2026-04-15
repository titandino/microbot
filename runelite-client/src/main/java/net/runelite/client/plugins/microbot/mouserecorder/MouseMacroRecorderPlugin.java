package net.runelite.client.plugins.microbot.mouserecorder;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.Point;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.Desktop;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.awt.Dimension;
import java.awt.Toolkit;

@PluginDescriptor(
	name = "Mouse Macro Recorder",
	description = "Record mouse movements and clicked menu entries",
	tags = {"mouse", "macro", "recorder", "microbot"}
)
@Slf4j
public class MouseMacroRecorderPlugin extends Plugin implements MouseListener, MouseWheelListener
{
	private static final Gson GSON = new Gson();
	public static final String CONFIG_GROUP = "mouseMacroRecorder";
	private static final Path RECORDINGS_DIR = RuneLite.RUNELITE_DIR.toPath().resolve("mouse-macro-recordings");
	private static final DateTimeFormatter FILE_NAME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final int RECORD_VERSION = 1;

	@Inject
	private Client client;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private MouseMacroRecorderConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseMacroRecorderOverlay overlay;

	private final Object lock = new Object();
	private final List<MouseMacroEvent> events = new ArrayList<>();
	private long recordingStartMs;
	private long lastMoveTimeMs;
	private java.awt.Point lastMovePoint;
	private boolean recording;
	private BufferedWriter jsonlWriter;
	private Path currentRecordingPath;
	private boolean jsonlWriterFailed;
	private String sessionId;

	@Provides
	MouseMacroRecorderConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(MouseMacroRecorderConfig.class);
	}

	@Override
	protected void startUp()
	{
		mouseManager.registerMouseListener(this);
		mouseManager.registerMouseWheelListener(this);
		overlayManager.add(overlay);
		if (config.recordingEnabled())
		{
			startRecording();
		}
	}

	@Override
	protected void shutDown()
	{
		mouseManager.unregisterMouseListener(this);
		mouseManager.unregisterMouseWheelListener(this);
		overlayManager.remove(overlay);
		stopRecording();
	}

	public void startRecording()
	{
		synchronized (lock)
		{
			closeJsonlWriterLocked();
			events.clear();
			recordingStartMs = System.currentTimeMillis();
			lastMoveTimeMs = 0;
			lastMovePoint = null;
			jsonlWriterFailed = false;
			sessionId = UUID.randomUUID().toString();
			openJsonlWriterLocked(recordingStartMs);
			recording = true;
		}
	}

	public void stopRecording()
	{
		synchronized (lock)
		{
			recording = false;
			closeJsonlWriterLocked();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!MouseMacroRecorderConfig.CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}

		String key = event.getKey();
		if ("recordingEnabled".equals(key))
		{
			if (config.recordingEnabled())
			{
				startRecording();
			}
			else
			{
				stopRecording();
			}
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!recording)
		{
			return;
		}

		if (!recordEvent())
		{
			return;
		}

		Point mousePosition = client.getMouseCanvasPosition();
		recordEvent(MouseMacroEventType.UP, mousePosition.getX(), mousePosition.getY(), MouseButton.LEFT, modifiersFromClient(), null, null, new MenuEntrySnapshot(event.getMenuEntry()));
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent mouseEvent)
	{
		recordMouseMove(mouseEvent);
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent mouseEvent)
	{
		recordMouseMove(mouseEvent);
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent mouseEvent)
	{
		recordMouseButton(mouseEvent, MouseMacroEventType.DOWN);
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent mouseEvent)
	{
		recordMouseButton(mouseEvent, MouseMacroEventType.UP);
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent mouseWheelEvent)
	{
		recordMouseScroll(mouseWheelEvent);
		return mouseWheelEvent;
	}

	private void recordMouseMove(MouseEvent mouseEvent)
	{
		if (!recording || !config.recordMouseMovement())
		{
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastMoveTimeMs < config.movementSampleIntervalMs())
		{
			return;
		}

		int minDistance = config.movementMinDistance();
		java.awt.Point point = mouseEvent.getPoint();
		if (lastMovePoint != null && minDistance > 0)
		{
			int dx = point.x - lastMovePoint.x;
			int dy = point.y - lastMovePoint.y;
			if (dx * dx + dy * dy < minDistance * minDistance)
			{
				return;
			}
		}

		if (!recordEvent())
		{
			return;
		}

		recordEvent(MouseMacroEventType.MOVE, point.x, point.y, buttonFromModifiers(mouseEvent.getModifiersEx()), modifiersFromMouseEvent(mouseEvent), null, null, null);
		lastMoveTimeMs = now;
		lastMovePoint = point;
	}

	private void recordMouseButton(MouseEvent mouseEvent, MouseMacroEventType type)
	{
		if (!recordEvent())
		{
			return;
		}

		MouseButton button = buttonFromMouseEvent(mouseEvent);
		MouseModifiers modifiers = modifiersFromMouseEvent(mouseEvent);
		recordEvent(type, mouseEvent.getX(), mouseEvent.getY(), button, modifiers, null, null, null);
	}

	private void recordMouseScroll(MouseWheelEvent mouseWheelEvent)
	{
		if (!recordEvent())
		{
			return;
		}

		MouseModifiers modifiers = modifiersFromMouseEvent(mouseWheelEvent);
		int deltaY = (int) Math.round(mouseWheelEvent.getPreciseWheelRotation() * mouseWheelEvent.getScrollAmount());
		recordEvent(MouseMacroEventType.SCROLL, mouseWheelEvent.getX(), mouseWheelEvent.getY(), null, modifiers,
			null, deltaY, null);
	}

	private boolean recordEvent()
	{
        return recording;
    }

	private void recordEvent(
		MouseMacroEventType type,
		int x,
		int y,
		MouseButton button,
		MouseModifiers modifiers,
		Integer scrollDeltaX,
		Integer scrollDeltaY,
		MenuEntrySnapshot menuEntry)
	{
		long now = System.currentTimeMillis();
		MouseMacroEvent event = new MouseMacroEvent(type, now - recordingStartMs, x, y, button, modifiers, scrollDeltaX, scrollDeltaY, menuEntry);
		synchronized (lock)
		{
			events.add(event);
			writeJsonlEventLocked(event);
		}
	}

	private void openJsonlWriterLocked(long startedAtEpochMs)
	{
		try
		{
			Files.createDirectories(RECORDINGS_DIR);
			String filename = "mouse-macro-recording-" + FILE_NAME_FORMATTER.format(
				Instant.ofEpochMilli(startedAtEpochMs).atZone(ZoneId.systemDefault())
			) + ".jsonl";
			currentRecordingPath = RECORDINGS_DIR.resolve(filename);
			jsonlWriter = Files.newBufferedWriter(currentRecordingPath, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			writeMetadataLineLocked(startedAtEpochMs);
		}
		catch (IOException e)
		{
			jsonlWriterFailed = true;
			jsonlWriter = null;
			currentRecordingPath = null;
			log.warn("Failed to open mouse macro JSONL writer", e);
		}
	}

	private void writeMetadataLineLocked(long startedAtEpochMs)
	{
		if (jsonlWriter == null)
		{
			return;
		}

		try
		{
			jsonlWriter.write(GSON.toJson(buildStartRecord(startedAtEpochMs)));
			jsonlWriter.newLine();
			jsonlWriter.flush();
		}
		catch (IOException e)
		{
			jsonlWriterFailed = true;
			log.warn("Failed to write mouse macro recording metadata to JSONL", e);
			closeJsonlWriterLocked();
		}
	}

	private void writeJsonlEventLocked(MouseMacroEvent event)
	{
		if (jsonlWriter == null || jsonlWriterFailed)
		{
			return;
		}

		try
		{
			jsonlWriter.write(GSON.toJson(new JsonlEventRecord(sessionId, recordingStartMs, event)));
			jsonlWriter.newLine();
			jsonlWriter.flush();
		}
		catch (IOException e)
		{
			jsonlWriterFailed = true;
			log.warn("Failed to write mouse macro event to JSONL {}", currentRecordingPath, e);
			closeJsonlWriterLocked();
		}
	}

	int getRecordedEventCount()
	{
		synchronized (lock)
		{
			return events.size();
		}
	}

	boolean isRecording()
	{
		return recording;
	}

	public static void openRecordingsFolderStatic()
	{
		try
		{
			Files.createDirectories(RECORDINGS_DIR);
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
			{
				Desktop.getDesktop().open(RECORDINGS_DIR.toFile());
			}
			else
			{
				log.warn("Desktop OPEN action not supported; cannot open recordings folder");
			}
		}
		catch (IOException e)
		{
			log.warn("Failed to open mouse macro recordings folder {}", RECORDINGS_DIR, e);
		}
	}

	private JsonlStartRecord buildStartRecord(long startedAtEpochMs)
	{
		Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
		return new JsonlStartRecord(
			RECORD_VERSION,
			sessionId,
			startedAtEpochMs,
			screen.width,
			screen.height,
			client.getCanvasWidth(),
			client.getCanvasHeight(),
			client.getScale()
		);
	}

	private MouseButton buttonFromMouseEvent(MouseEvent event)
	{
		switch (event.getButton())
		{
			case MouseEvent.BUTTON1:
				return MouseButton.LEFT;
			case MouseEvent.BUTTON2:
				return MouseButton.MIDDLE;
			case MouseEvent.BUTTON3:
				return MouseButton.RIGHT;
			default:
				return null;
		}
	}

	private MouseButton buttonFromModifiers(int modifiersEx)
	{
		if ((modifiersEx & InputEvent.BUTTON1_DOWN_MASK) != 0)
		{
			return MouseButton.LEFT;
		}
		if ((modifiersEx & InputEvent.BUTTON2_DOWN_MASK) != 0)
		{
			return MouseButton.MIDDLE;
		}
		if ((modifiersEx & InputEvent.BUTTON3_DOWN_MASK) != 0)
		{
			return MouseButton.RIGHT;
		}
		return null;
	}

	private MouseModifiers modifiersFromMouseEvent(MouseEvent mouseEvent)
	{
		return new MouseModifiers(mouseEvent.isShiftDown(), mouseEvent.isControlDown(), mouseEvent.isAltDown());
	}

	private MouseModifiers modifiersFromClient()
	{
		return new MouseModifiers(
			client.isKeyPressed(KeyCode.KC_SHIFT),
			client.isKeyPressed(KeyCode.KC_CONTROL),
			client.isKeyPressed(KeyCode.KC_ALT)
		);
	}

	private void closeJsonlWriterLocked()
	{
		if (jsonlWriter == null)
		{
			return;
		}

		try
		{
			jsonlWriter.close();
		}
		catch (IOException e)
		{
			log.warn("Failed to close mouse macro JSONL writer", e);
		}
		finally
		{
			jsonlWriter = null;
			currentRecordingPath = null;
		}
	}

	private static class JsonlStartRecord
	{
		private final String recordType = "start";
		private final int recordVersion;
		private final String sessionId;
		private final long startedAtEpochMs;
		private final int screenWidth;
		private final int screenHeight;
		private final int canvasWidth;
		private final int canvasHeight;
		private final int clientScale;

		private JsonlStartRecord(
			int recordVersion,
			String sessionId,
			long startedAtEpochMs,
			int screenWidth,
			int screenHeight,
			int canvasWidth,
			int canvasHeight,
			int clientScale)
		{
			this.recordVersion = recordVersion;
			this.sessionId = sessionId;
			this.startedAtEpochMs = startedAtEpochMs;
			this.screenWidth = screenWidth;
			this.screenHeight = screenHeight;
			this.canvasWidth = canvasWidth;
			this.canvasHeight = canvasHeight;
			this.clientScale = clientScale;
		}
	}

	private static class JsonlEventRecord
	{
		private final String recordType = "event";
		private final String sessionId;
		private final long startedAtEpochMs;
		private final MouseMacroEvent event;

		private JsonlEventRecord(String sessionId, long startedAtEpochMs, MouseMacroEvent event)
		{
			this.sessionId = sessionId;
			this.startedAtEpochMs = startedAtEpochMs;
			this.event = event;
		}
	}
}
