package net.runelite.client.plugins.microbot.util.mouse;

import net.runelite.api.Client;
import net.runelite.client.plugins.stretchedmode.TranslateMouseListener;
import net.runelite.client.plugins.stretchedmode.TranslateMouseWheelListener;
import org.junit.After;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MouseSourceTest {

	@After
	public void clearGuardState() {
		while (BotEventGuard.isSynthetic()) {
			BotEventGuard.end();
		}
	}

	@Test
	public void guardReportsSyntheticOnlyWhileActive() {
		assertFalse(BotEventGuard.isSynthetic());
		BotEventGuard.begin();
		try {
			assertTrue(BotEventGuard.isSynthetic());
		} finally {
			BotEventGuard.end();
		}
		assertFalse(BotEventGuard.isSynthetic());
	}

	@Test
	public void guardSupportsNestedDispatch() {
		BotEventGuard.begin();
		BotEventGuard.begin();
		try {
			assertTrue(BotEventGuard.isSynthetic());
		} finally {
			BotEventGuard.end();
		}
		assertTrue("outer frame still active", BotEventGuard.isSynthetic());
		BotEventGuard.end();
		assertFalse(BotEventGuard.isSynthetic());
	}

	@Test
	public void translatorScalesAllEventsUniformly() {
		Client client = mock(Client.class);
		when(client.getStretchedDimensions()).thenReturn(new Dimension(1600, 1200));
		when(client.getRealDimensions()).thenReturn(new Dimension(800, 600));
		TranslateMouseListener listener = new TranslateMouseListener(client);

		MouseEvent real = new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 0L, 0, 400, 600, 1, false, MouseEvent.BUTTON1);
		MouseEvent scaled = listener.mousePressed(real);

		assertEquals("real user event must be scaled (stretched→game coords)", 200, scaled.getX());
		assertEquals(300, scaled.getY());
	}

	@Test
	public void wheelTranslatorScalesAllEventsUniformly() {
		Client client = mock(Client.class);
		when(client.getStretchedDimensions()).thenReturn(new Dimension(1600, 1200));
		when(client.getRealDimensions()).thenReturn(new Dimension(800, 600));
		TranslateMouseWheelListener listener = new TranslateMouseWheelListener(client);

		MouseWheelEvent event = new MouseWheelEvent(new Canvas(), MouseEvent.MOUSE_WHEEL, 0L, 0, 400, 600, 0, false,
				MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1);
		MouseWheelEvent scaled = listener.mouseWheelMoved(event);

		assertEquals(200, scaled.getX());
		assertEquals(300, scaled.getY());
	}

	@Test
	public void virtualMouseClassHasNoMicrobotLiteralInConstantPool() throws IOException {
		List<String> hits = scanStringConstants(VirtualMouse.class);
		assertTrue("VirtualMouse.class must not LDC the literal \"Microbot\" — that was the watermark we removed. Hits: " + hits,
				hits.isEmpty());
	}

	@Test
	public void mouseClassHasNoMicrobotLiteralInConstantPool() throws IOException {
		List<String> hits = scanStringConstants(Mouse.class);
		assertTrue("Mouse.class must not LDC the literal \"Microbot\" — the sentinel watermark is retired. Hits: " + hits,
				hits.isEmpty());
	}

	@Test
	public void noOldVirtualSourceSymbolRemains() {
		try {
			Mouse.class.getField("VIRTUAL_SOURCE");
			org.junit.Assert.fail("Mouse.VIRTUAL_SOURCE was retired; its presence indicates a revert");
		} catch (NoSuchFieldException expected) {
		}
	}

	private static List<String> scanStringConstants(Class<?> target) throws IOException {
		String resource = target.getSimpleName() + ".class";
		try (InputStream is = target.getResourceAsStream(resource)) {
			assertNotNull("class resource " + resource + " must be loadable for bytecode scan", is);
			ClassReader reader = new ClassReader(is.readAllBytes());
			List<String> found = new ArrayList<>();
			reader.accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
					return new MethodVisitor(Opcodes.ASM9) {
						@Override
						public void visitLdcInsn(Object value) {
							if (value instanceof String && "Microbot".equals(value)) {
								found.add(name + descriptor);
							}
						}
					};
				}
			}, ClassReader.SKIP_FRAMES);
			return found;
		}
	}
}
