package net.runelite.client.plugins.microbot.agentserver.scripting;

import com.google.common.reflect.ClassPath;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.externalplugins.PluginJarClassLoader;

import javax.swing.*;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.inject.Binder;
import com.google.inject.Injector;
import com.google.inject.Module;

/**
 * Manages dynamic script deployments: compile → classload → inject → start.
 * Each deployment maps to exactly one RuneLite plugin.
 * <p>
 * All mutating operations are synchronized to prevent races.
 */
@Slf4j
public class DynamicScriptManager {

    private static final DynamicScriptManager INSTANCE = new DynamicScriptManager();
    private final Map<String, DeployedScript> deployments = new LinkedHashMap<>();

    private DynamicScriptManager() {
    }

    public static DynamicScriptManager getInstance() {
        return INSTANCE;
    }

    /**
     * Compile source, load the plugin class, inject via Guice, and start it.
     */
    public synchronized DeployedScript deploy(String name, Path sourceDir) throws DeploymentException {
        if (deployments.containsKey(name)) {
            throw new DeploymentException("Deployment '" + name + "' already exists. Use reload to update.");
        }
        if (!Files.isDirectory(sourceDir)) {
            throw new DeploymentException("Source path is not a directory: " + sourceDir);
        }

        DeployedScript deployment = new DeployedScript(name, sourceDir);

        // 1. Compile
        Path classesDir = getBuildDir(name);
        DynamicScriptCompiler.CompilationResult result;
        try {
            result = DynamicScriptCompiler.compile(sourceDir, classesDir);
        } catch (IOException e) {
            throw new DeploymentException("Compilation I/O error: " + e.getMessage());
        }
        if (!result.isSuccess()) {
            throw new DeploymentException("Compilation failed:\n" + String.join("\n", result.getErrors()));
        }

        // 2. Load classes
        PluginJarClassLoader classLoader;
        Class<?> pluginClass;
        try {
            classLoader = new PluginJarClassLoader(classesDir.toFile(), getClass().getClassLoader());
            deployment.setClassLoader(classLoader);
            pluginClass = findPluginClass(classLoader);
        } catch (IOException e) {
            throw new DeploymentException("Failed to create classloader: " + e.getMessage());
        }

        // 3. Instantiate, inject, start
        try {
            Plugin plugin = instantiateAndStart(pluginClass);
            deployment.setPlugin(plugin);
        } catch (DeploymentException e) {
            closeQuietly(classLoader);
            throw e;
        } catch (Exception e) {
            closeQuietly(classLoader);
            throw new DeploymentException("Failed to start plugin: " + e.getMessage());
        }

        deployments.put(name, deployment);
        log.info("Deployed '{}' from {}", name, sourceDir);
        return deployment;
    }

    /**
     * Reload = undeploy + deploy from the same source directory.
     */
    public synchronized DeployedScript reload(String name) throws DeploymentException {
        DeployedScript existing = deployments.get(name);
        if (existing == null) {
            throw new DeploymentException("No deployment named '" + name + "' exists.");
        }
        Path sourceDir = existing.getSourcePath();
        doUndeploy(existing);
        deployments.remove(name);
        return deploy(name, sourceDir);
    }

    public synchronized void undeploy(String name) throws DeploymentException {
        DeployedScript existing = deployments.remove(name);
        if (existing == null) {
            throw new DeploymentException("No deployment named '" + name + "' exists.");
        }
        doUndeploy(existing);
        log.info("Undeployed '{}'", name);
    }

    public synchronized List<Map<String, Object>> listDeployments() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DeployedScript d : deployments.values()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", d.getName());
            info.put("sourcePath", d.getSourcePath().toString());
            info.put("deployedAt", d.getDeployedAt().toString());
            if (d.getPlugin() != null) {
                PluginDescriptor desc = d.getPlugin().getClass().getAnnotation(PluginDescriptor.class);
                info.put("plugin", desc != null ? desc.name() : d.getPlugin().getClass().getSimpleName());
                info.put("active", Microbot.getPluginManager().isActive(d.getPlugin()));
            }
            result.add(info);
        }
        return result;
    }

    public synchronized DeployedScript getDeployment(String name) {
        return deployments.get(name);
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private void doUndeploy(DeployedScript deployment) {
        if (deployment.getPlugin() != null) {
            stopPluginSafe(deployment.getPlugin());
            Microbot.getPluginManager().remove(deployment.getPlugin());
        }
        closeQuietly(deployment.getClassLoader());
    }

    /**
     * Scans the classloader for exactly one @PluginDescriptor class.
     */
    private Class<?> findPluginClass(URLClassLoader classLoader) throws DeploymentException, IOException {
        Class<?> found = null;
        for (ClassPath.ClassInfo ci : ClassPath.from(classLoader).getAllClasses()) {
            try {
                Class<?> clazz = classLoader.loadClass(ci.getName());
                if (clazz.isAnnotationPresent(PluginDescriptor.class) && Plugin.class.isAssignableFrom(clazz)) {
                    if (found != null) {
                        throw new DeploymentException(
                            "Multiple @PluginDescriptor classes found. Only one plugin per deployment is supported.");
                    }
                    found = clazz;
                }
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                log.trace("Skipping class {}: {}", ci.getName(), e.getMessage());
            }
        }
        if (found == null) {
            throw new DeploymentException("No @PluginDescriptor class found in compiled output.");
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private Plugin instantiateAndStart(Class<?> clazz) throws PluginInstantiationException, DeploymentException {
        Class<Plugin> pluginClass = (Class<Plugin>) clazz;

        PluginDescriptor desc = pluginClass.getAnnotation(PluginDescriptor.class);
        String className = pluginClass.getName();
        String descName = desc != null ? desc.name() : null;
        for (Plugin existing : Microbot.getPluginManager().getPlugins()) {
            Class<?> existingClass = existing.getClass();
            if (existingClass.getName().equals(className)) {
                throw new DeploymentException("Plugin class '" + className
                        + "' is already loaded natively (e.g. via pluginsToDebug or the core plugin registry) "
                        + "and cannot be hot-swapped by the dynamic loader. Remove the native registration first.");
            }
            if (descName != null) {
                PluginDescriptor existingDesc = existingClass.getAnnotation(PluginDescriptor.class);
                if (existingDesc != null && descName.equals(existingDesc.name())) {
                    throw new DeploymentException("A plugin named '" + descName
                            + "' is already registered (class " + existingClass.getName()
                            + "). Rename the @PluginDescriptor.name or unregister the existing plugin first.");
                }
            }
        }

        Plugin plugin;
        try {
            plugin = pluginClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new PluginInstantiationException("Cannot instantiate " + pluginClass.getSimpleName() + ": " + e.getMessage());
        }

        // Guice child injector — same pattern as PluginManager.instantiate()
        Injector parentInjector = Microbot.getInjector();
        Module pluginModule = (Binder binder) -> {
            binder.bind(pluginClass).toInstance(plugin);
            binder.install(plugin);
        };
        Injector pluginInjector = parentInjector.createChildInjector(pluginModule);
        plugin.setInjector(pluginInjector);

        // Register and start on EDT
        PluginManager pm = Microbot.getPluginManager();
        pm.add(plugin);

        AtomicBoolean success = new AtomicBoolean(false);
        AtomicReference<Exception> error = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    pm.setPluginEnabled(plugin, true);
                    success.set(pm.startPlugin(plugin));
                } catch (Exception e) {
                    error.set(e);
                }
            });
        } catch (Exception e) {
            pm.remove(plugin);
            throw new PluginInstantiationException("EDT dispatch failed: " + e.getMessage());
        }

        if (error.get() != null) {
            pm.remove(plugin);
            throw new PluginInstantiationException(error.get());
        }
        if (!success.get()) {
            pm.remove(plugin);
            throw new PluginInstantiationException("startPlugin returned false for " + pluginClass.getSimpleName());
        }

        return plugin;
    }

    private void stopPluginSafe(Plugin plugin) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    PluginManager pm = Microbot.getPluginManager();
                    pm.setPluginEnabled(plugin, false);
                    pm.stopPlugin(plugin);
                } catch (Exception e) {
                    log.warn("Error stopping plugin {}: {}", plugin.getClass().getSimpleName(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("EDT error stopping plugin {}: {}", plugin.getClass().getSimpleName(), e.getMessage());
        }
    }

    private Path getBuildDir(String name) {
        return Path.of(System.getProperty("user.home"), ".runelite", "dynamic-scripts", name, "classes");
    }

    private void closeQuietly(URLClassLoader cl) {
        if (cl == null) return;
        try {
            cl.close();
        } catch (IOException e) {
            log.warn("Error closing classloader: {}", e.getMessage());
        }
    }

    public static class DeploymentException extends Exception {
        public DeploymentException(String message) {
            super(message);
        }
    }
}
