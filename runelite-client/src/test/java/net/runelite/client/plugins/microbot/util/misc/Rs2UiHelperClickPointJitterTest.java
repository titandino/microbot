package net.runelite.client.plugins.microbot.util.misc;

import net.runelite.api.Point;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.awt.Rectangle;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class Rs2UiHelperClickPointJitterTest {

	@Test
	public void randomPointExJittersEvenWhenAnchorIsInsideRect() {
		Rectangle rect = new Rectangle(100, 100, 32, 32);
		Point anchor = new Point(115, 115);
		double force = 0.78;

		Set<Long> seen = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			Point p = Rs2Random.randomPointEx(anchor, rect, force);
			assertTrue("point " + p + " must stay inside target rect " + rect,
					p.getX() >= rect.x && p.getX() <= rect.x + rect.width
							&& p.getY() >= rect.y && p.getY() <= rect.y + rect.height);
			seen.add(((long) p.getX() << 32) | (p.getY() & 0xFFFFFFFFL));
		}
		assertTrue(
				"randomPointEx must produce varied output across repeated calls with identical in-rect anchors — " +
						"if variance collapses, back-to-back clicks on the same NPC/object/widget would click the exact same pixel. " +
						"Distinct points seen: " + seen.size(),
				seen.size() > 50);
	}

	@Test
	public void getClickingPointHasNoMousePositionEarlyReturn() throws IOException {
		AtomicInteger mousePositionReads = new AtomicInteger();
		AtomicInteger isMouseWithinRectangleCalls = new AtomicInteger();

		try (InputStream is = Rs2UiHelper.class.getResourceAsStream("Rs2UiHelper.class")) {
			assertNotNull("Rs2UiHelper.class must be loadable for bytecode scan", is);
			new ClassReader(is.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor,
				                                 String signature, String[] exceptions) {
					if (!"getClickingPoint".equals(name)) return null;
					return new MethodVisitor(Opcodes.ASM9) {
						@Override
						public void visitMethodInsn(int opcode, String owner, String mName, String desc, boolean itf) {
							if ("isMouseWithinRectangle".equals(mName)) isMouseWithinRectangleCalls.incrementAndGet();
							if ("getMousePosition".equals(mName)) mousePositionReads.incrementAndGet();
						}
					};
				}
			}, ClassReader.SKIP_FRAMES);
		}

		assertTrue(
				"getClickingPoint must not contain the early-return `if (isMouseWithinRectangle(rect)) return mousePos` path — " +
						"that path skipped randomisation and produced pixel-identical back-to-back clicks. " +
						"isMouseWithinRectangle calls inside getClickingPoint: " + isMouseWithinRectangleCalls.get(),
				isMouseWithinRectangleCalls.get() == 0);
		assertTrue(
				"getClickingPoint should still read mouse position exactly once (to use as the randomPointEx anchor). Reads: "
						+ mousePositionReads.get(),
				mousePositionReads.get() == 1);
	}
}
