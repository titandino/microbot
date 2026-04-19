package net.runelite.client.plugins.microbot.util.reflection;

import org.junit.Test;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Bytecode-level guard that {@link Rs2Reflection}'s constant pool no longer
 * carries {@code org.objectweb.asm.*} references. All ASM usage must live in
 * {@link MenuActionAsmResolver}, which is loaded lazily through a plain method
 * call and therefore keeps the detection surface of the primary reflection
 * utility class clean.
 *
 * A regression that reintroduces a direct ASM import into Rs2Reflection.java
 * would fail this test before it lands.
 */
public class Rs2ReflectionAsmIsolationTest {

    @Test
    public void rs2ReflectionConstantPoolContainsNoAsmReferences() throws Exception {
        Set<String> strings = readConstantPoolStrings(Rs2Reflection.class);
        for (String s : strings) {
            assertFalse("Rs2Reflection.class must not reference ASM: found '" + s + "'",
                    s.contains("org/objectweb/asm") || s.contains("org.objectweb.asm"));
        }
    }

    @Test
    public void menuActionAsmResolverDoesContainAsm_sanityCheck() throws Exception {
        // Make sure we're not asserting vacuously — ASM references live somewhere.
        Set<String> strings = readConstantPoolStrings(MenuActionAsmResolver.class);
        assertTrue("Resolver class should still reference ASM internally",
                strings.stream().anyMatch(s -> s.contains("org/objectweb/asm") || s.contains("org.objectweb.asm")));
    }

    private static Set<String> readConstantPoolStrings(Class<?> clazz) throws Exception {
        String resource = "/" + clazz.getName().replace('.', '/') + ".class";
        Set<String> out = new HashSet<>();
        try (InputStream in = clazz.getResourceAsStream(resource);
             DataInputStream dis = new DataInputStream(in)) {
            // skip magic (4) + minor (2) + major (2)
            dis.readInt();
            dis.readUnsignedShort();
            dis.readUnsignedShort();
            int cpSize = dis.readUnsignedShort();
            for (int i = 1; i < cpSize; i++) {
                int tag = dis.readUnsignedByte();
                switch (tag) {
                    case 1: // Utf8
                        int len = dis.readUnsignedShort();
                        byte[] bytes = new byte[len];
                        dis.readFully(bytes);
                        out.add(new String(bytes, StandardCharsets.UTF_8));
                        break;
                    case 3: case 4: // Integer, Float
                        dis.readInt();
                        break;
                    case 5: case 6: // Long, Double
                        dis.readLong();
                        i++; // 8-byte entries consume two slots
                        break;
                    case 7: case 8: case 16: case 19: case 20: // Class, String, MethodType, Module, Package
                        dis.readUnsignedShort();
                        break;
                    case 9: case 10: case 11: case 12: case 17: case 18: // Field/Method/InterfaceMethod/NameAndType/Dynamic/InvokeDynamic
                        dis.readUnsignedShort();
                        dis.readUnsignedShort();
                        break;
                    case 15:
                        dis.readUnsignedByte();
                        dis.readUnsignedShort();
                        break;
                    default:
                        throw new IllegalStateException("Unknown constant pool tag: " + tag);
                }
            }
        }
        return out;
    }
}
