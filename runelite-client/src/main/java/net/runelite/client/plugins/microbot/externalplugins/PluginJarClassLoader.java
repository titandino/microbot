package net.runelite.client.plugins.microbot.externalplugins;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.ReflectUtil;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.net.URLClassLoader;

@Slf4j
public class PluginJarClassLoader extends URLClassLoader implements ReflectUtil.PrivateLookupableClassLoader {

    @Getter
    @Setter
    private MethodHandles.Lookup lookup;

    private final ClassLoader parent;

    public PluginJarClassLoader(File jarFile, ClassLoader parent) throws IOException {
        super(new URL[]{jarFile.toURI().toURL()}, null);
        this.parent = parent;
        ReflectUtil.installLookupHelper(this);
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        try {
            return super.loadClass(name);
        } catch (ClassNotFoundException ex) {
            return parent.loadClass(name);
        }
    }

    @Override
    public Class<?> defineClass0(String name, byte[] b, int off, int len) throws ClassFormatError {
        return super.defineClass(name, b, off, len);
    }
}
