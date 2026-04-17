package net.runelite.client.plugins.microbot.agentserver.scripting;

import lombok.Getter;
import net.runelite.client.plugins.Plugin;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Tracks a single dynamic script deployment: one source directory → one plugin.
 */
@Getter
public class DeployedScript {

    private final String name;
    private final Path sourcePath;
    private final Instant deployedAt;
    private URLClassLoader classLoader;
    private Plugin plugin;

    public DeployedScript(String name, Path sourcePath) {
        this.name = name;
        this.sourcePath = sourcePath;
        this.deployedAt = Instant.now();
    }

    public void setClassLoader(URLClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public void setPlugin(Plugin plugin) {
        this.plugin = plugin;
    }
}
