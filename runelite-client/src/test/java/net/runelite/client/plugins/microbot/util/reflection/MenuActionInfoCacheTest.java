package net.runelite.client.plugins.microbot.util.reflection;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Round-trips the menu-action cache and pins the invariants the runtime relies on:
 * missing fields → null (safe miss), class gone → bust, descriptor drift → bust,
 * supported garbage kinds deserialize correctly. Drift in any of those lets the
 * cache silently hand a bogus {@code Method} to {@code Rs2Reflection}, which then
 * invokes the wrong vanilla entry point and breaks every click in the client.
 */
public class MenuActionInfoCacheTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Path cachePath;

    @Before
    public void setUp() throws IOException {
        cachePath = tempFolder.newFile("cache.properties").toPath();
        Files.deleteIfExists(cachePath);
    }

    @After
    public void tearDown() throws IOException {
        Files.deleteIfExists(cachePath);
    }

    /**
     * Stand-in used for the round-trip — any JDK class with a resolvable method works.
     */
    @SuppressWarnings("unused")
    public static void markerMethod() {
    }

    private static Method lookupMarker() throws NoSuchMethodException {
        return MenuActionInfoCacheTest.class.getDeclaredMethod("markerMethod");
    }

    @Test
    public void roundTripPreservesOwnerMethodAndGarbage() throws Exception {
        MenuActionAsmResolver.Resolution original = new MenuActionAsmResolver.Resolution(lookupMarker(), 42);
        MenuActionInfoCache.store(original, cachePath);

        MenuActionAsmResolver.Resolution loaded = MenuActionInfoCache.load(cachePath);
        assertNotNull(loaded);
        assertEquals(MenuActionInfoCacheTest.class, loaded.method.getDeclaringClass());
        assertEquals("markerMethod", loaded.method.getName());
        assertEquals(Integer.valueOf(42), loaded.garbageValue);
    }

    @Test
    public void missingFileYieldsNull() {
        assertNull(MenuActionInfoCache.load(cachePath));
    }

    @Test
    public void malformedFileYieldsNull() throws IOException {
        Files.writeString(cachePath, "menuAction.ownerClass=only-one-key");
        assertNull(MenuActionInfoCache.load(cachePath));
    }

    @Test
    public void bogusOwnerClassBustsCache() throws IOException {
        Properties props = new Properties();
        props.setProperty(MenuActionInfoCache.KEY_OWNER, "net.nonexistent.Nope");
        props.setProperty(MenuActionInfoCache.KEY_METHOD, "markerMethod");
        props.setProperty(MenuActionInfoCache.KEY_DESCRIPTOR, "(IIIIIILjava/lang/String;Ljava/lang/String;III)V");
        props.setProperty(MenuActionInfoCache.KEY_GARBAGE_KIND, "Integer");
        props.setProperty(MenuActionInfoCache.KEY_GARBAGE_VALUE, "1");
        try (var out = Files.newOutputStream(cachePath)) {
            props.store(out, null);
        }
        assertNull(MenuActionInfoCache.load(cachePath));
    }

    @Test
    public void methodGoneBustsCache() throws IOException {
        Properties props = new Properties();
        props.setProperty(MenuActionInfoCache.KEY_OWNER, MenuActionInfoCacheTest.class.getName());
        props.setProperty(MenuActionInfoCache.KEY_METHOD, "methodThatDoesNotExist");
        props.setProperty(MenuActionInfoCache.KEY_DESCRIPTOR, "(IIIIIILjava/lang/String;Ljava/lang/String;III)V");
        props.setProperty(MenuActionInfoCache.KEY_GARBAGE_KIND, "Integer");
        props.setProperty(MenuActionInfoCache.KEY_GARBAGE_VALUE, "0");
        try (var out = Files.newOutputStream(cachePath)) {
            props.store(out, null);
        }
        assertNull(MenuActionInfoCache.load(cachePath));
    }

    @Test
    public void descriptorDriftBustsCache() throws IOException {
        Properties props = new Properties();
        props.setProperty(MenuActionInfoCache.KEY_OWNER, MenuActionInfoCacheTest.class.getName());
        props.setProperty(MenuActionInfoCache.KEY_METHOD, "markerMethod");
        props.setProperty(MenuActionInfoCache.KEY_DESCRIPTOR, "(V)");
        props.setProperty(MenuActionInfoCache.KEY_GARBAGE_KIND, "Integer");
        props.setProperty(MenuActionInfoCache.KEY_GARBAGE_VALUE, "0");
        try (var out = Files.newOutputStream(cachePath)) {
            props.store(out, null);
        }
        assertNull(MenuActionInfoCache.load(cachePath));
    }

    @Test
    public void supportsAllIntegralGarbageKinds() throws Exception {
        List<Object> kinds = List.of((byte) 5, (short) 11, 17, 23L);
        for (Object v : kinds) {
            Files.deleteIfExists(cachePath);
            MenuActionInfoCache.store(new MenuActionAsmResolver.Resolution(lookupMarker(), v), cachePath);
            MenuActionAsmResolver.Resolution loaded = MenuActionInfoCache.load(cachePath);
            assertNotNull("kind " + v.getClass().getSimpleName(), loaded);
            assertEquals(v.toString(), loaded.garbageValue.toString());
            assertEquals(v.getClass(), loaded.garbageValue.getClass());
        }
    }

    @Test
    public void invalidateDeletesFile() throws Exception {
        MenuActionInfoCache.store(new MenuActionAsmResolver.Resolution(lookupMarker(), 1), cachePath);
        assertTrue(Files.exists(cachePath));
        MenuActionInfoCache.invalidate(cachePath);
        assertTrue(!Files.exists(cachePath));
    }

    @Test
    public void resolvePropsDirectlyResolvesAValidSet() {
        Properties props = new Properties();
        props.setProperty(MenuActionInfoCache.KEY_OWNER, MenuActionInfoCacheTest.class.getName());
        props.setProperty(MenuActionInfoCache.KEY_METHOD, "markerMethod");
        props.setProperty(MenuActionInfoCache.KEY_DESCRIPTOR, "(IIIIIILjava/lang/String;Ljava/lang/String;III)V");
        props.setProperty(MenuActionInfoCache.KEY_GARBAGE_KIND, "Integer");
        props.setProperty(MenuActionInfoCache.KEY_GARBAGE_VALUE, "7");
        MenuActionAsmResolver.Resolution r = MenuActionInfoCache.resolveProps(props, "test");
        assertNotNull(r);
        assertEquals("markerMethod", r.method.getName());
        assertEquals(Integer.valueOf(7), r.garbageValue);
    }

    @Test
    public void bundledLoaderResolvesAgainstTheShippedClientJar() {
        // The repo ships a build-time generated menu-action-info.properties and the
        // injected-client jar is on the test runtime classpath, so loadBundled() must
        // resolve to a real Method (owner class = obfuscated Client implementation).
        // A regression that stops shipping the resource, or a drift between the shipped
        // resource and the current injected-client, would flip this to null.
        MenuActionAsmResolver.Resolution r = MenuActionInfoCache.loadBundled();
        assertNotNull("bundled resource should resolve; did you forget to run :client:seedMenuActionInfo?", r);
        assertNotNull(r.method);
        assertNotNull(r.garbageValue);
        assertEquals("(IIIIIILjava/lang/String;Ljava/lang/String;III)V",
                org.objectweb.asm.Type.getMethodDescriptor(r.method));
    }
}
