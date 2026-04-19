package net.runelite.client.plugins.microbot.util.mouse;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class VirtualMouseUngatedMotionTest {

	@Test
	public void virtualMouseDoesNotBranchOnNaturalMouseFlag() throws IOException {
		List<String> hits = scanFieldReads(
				VirtualMouse.class,
				"net/runelite/client/plugins/microbot/util/antiban/Rs2AntibanSettings",
				"naturalMouse");
		assertTrue(
				"VirtualMouse.class must not gate motion on Rs2AntibanSettings.naturalMouse — " +
						"the trajectory is now unconditional so MenuOptionClicked always has a preceding mouse path (P4-a). " +
						"Found reads in: " + hits,
				hits.isEmpty());
	}

	@Test
	public void naturalMouseDefaultIsOn() throws ReflectiveOperationException {
		Class<?> settings = Class.forName(
				"net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings");
		Field f = settings.getField("naturalMouse");
		assertEquals(
				"Rs2AntibanSettings.naturalMouse default must be true — " +
						"motion is unconditional in VirtualMouse, and the post-click compensating sleeps in " +
						"Rs2Inventory / Rs2GrandExchange were unconditionalized, so there is no longer a reason to " +
						"leave this off by default. The flag now only toggles the click-point anchoring strategy and " +
						"a few hover-gate methods.",
				Boolean.TRUE,
				f.get(null));
	}

	private static List<String> scanFieldReads(Class<?> target, String ownerInternal, String fieldName) throws IOException {
		String resource = target.getSimpleName() + ".class";
		try (InputStream is = target.getResourceAsStream(resource)) {
			assertNotNull("class resource " + resource + " must be loadable for bytecode scan", is);
			ClassReader reader = new ClassReader(is.readAllBytes());
			List<String> found = new ArrayList<>();
			reader.accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public MethodVisitor visitMethod(int access, String methodName, String descriptor,
				                                 String signature, String[] exceptions) {
					return new MethodVisitor(Opcodes.ASM9) {
						@Override
						public void visitFieldInsn(int opcode, String owner, String name, String desc) {
							if (opcode == Opcodes.GETSTATIC
									&& ownerInternal.equals(owner)
									&& fieldName.equals(name)) {
								found.add(methodName + descriptor);
							}
						}
					};
				}

				@Override
				public FieldVisitor visitField(int access, String name, String descriptor,
				                               String signature, Object value) {
					return null;
				}
			}, ClassReader.SKIP_FRAMES);
			return found;
		}
	}
}
