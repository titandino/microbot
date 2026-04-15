package net.runelite.client.plugins.microbot.agentserver;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Provides;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.agentserver.handler.*;
import net.runelite.client.ui.DrawManager;

import javax.inject.Inject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@PluginDescriptor(
		name = PluginDescriptor.Mocrosoft + "Agent Server",
		description = "HTTP server for AI agent communication - exposes widget inspection, game interaction, and state endpoints",
		tags = {"agent", "ai", "server", "automation"},
		enabledByDefault = false
)
@Slf4j
public class AgentServerPlugin extends Plugin {

	@Inject
	private AgentServerConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Client client;

	@Inject
	private DrawManager drawManager;

	private HttpServer server;
	private ExecutorService executor;
	private Thread shutdownHook;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	@Provides
	AgentServerConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(AgentServerConfig.class);
	}

	@Override
	protected void startUp() throws Exception {
		int port = config.port();
		int maxResults = config.maxResults();

		stopServer();

		String token = ensureAuthToken();
		AgentHandler.setTokenSupplier(() -> configManager.getConfiguration(AgentServerConfig.GROUP, AgentServerConfig.KEY_TOKEN));

		executor = Executors.newFixedThreadPool(4, new ThreadFactory() {
			private final AtomicInteger count = new AtomicInteger(1);

			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "AgentServer-" + count.getAndIncrement());
				t.setDaemon(true);
				return t;
			}
		});

		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		} catch (java.net.BindException e) {
			log.warn("Agent server port {} is already in use (likely another Microbot client). Skipping agent server startup for this instance.", port);
			stopServer();
			return;
		}

		server.setExecutor(executor);

		List<AgentHandler> handlers = Arrays.asList(
				new WidgetListHandler(gson, maxResults),
				new WidgetSearchHandler(gson, maxResults),
				new WidgetDescribeHandler(gson),
				new WidgetClickHandler(gson),
				new StateHandler(gson, client),
				new InventoryHandler(gson),
				new NpcHandler(gson, maxResults),
				new ObjectHandler(gson, maxResults),
				new WalkHandler(gson),
				new BankHandler(gson),
				new DialogueHandler(gson),
				new GroundItemHandler(gson, maxResults),
				new SkillsHandler(gson, client),
				new ScriptHandler(gson),
				new LoginHandler(gson, client),
				new ScreenshotHandler(gson, client, drawManager),
				new VarbitHandler(gson),
				new WidgetInvokeHandler(gson),
				new SettingsHandler(gson),
				new KeyboardHandler(gson)
		);

		for (AgentHandler handler : handlers) {
			server.createContext(handler.getPath(), handler);
		}

		shutdownHook = new Thread(() -> {
			stopServer();
			deleteTokenFile();
		}, "AgentServer-Shutdown");
		Runtime.getRuntime().addShutdownHook(shutdownHook);
		server.start();
		Path tokenFile = writeTokenFile(token);
		log.info("Agent server started on 127.0.0.1:{} with {} endpoints (auth enabled, token {})",
				port, handlers.size(), tokenFile != null ? "at " + tokenFile : "file unavailable");
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		if (!AgentServerConfig.GROUP.equals(event.getGroup()) || !AgentServerConfig.KEY_TOKEN.equals(event.getKey())) {
			return;
		}
		String value = event.getNewValue();
		if (value == null || value.isEmpty()) {
			log.warn("Agent server auth token was cleared; regenerating to keep auth enforced");
			writeTokenFile(ensureAuthToken());
			return;
		}
		writeTokenFile(value);
	}

	private String ensureAuthToken() {
		String existing = configManager.getConfiguration(AgentServerConfig.GROUP, AgentServerConfig.KEY_TOKEN);
		if (existing != null && !existing.isEmpty()) {
			return existing;
		}
		String generated = UUID.randomUUID().toString().replace("-", "");
		configManager.setConfiguration(AgentServerConfig.GROUP, AgentServerConfig.KEY_TOKEN, generated);
		return generated;
	}

	private Path writeTokenFile(String token) {
		try {
			Path dir = Paths.get(System.getProperty("user.home"), ".microbot");
			Files.createDirectories(dir);
			Path file = dir.resolve("agent-token");

			boolean posix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

			java.util.Set<java.nio.file.OpenOption> opts = new java.util.HashSet<>();
			opts.add(java.nio.file.StandardOpenOption.CREATE);
			opts.add(java.nio.file.StandardOpenOption.WRITE);
			opts.add(java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

			java.nio.file.attribute.FileAttribute<?>[] attrs = posix
					? new java.nio.file.attribute.FileAttribute<?>[]{
							PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
					: new java.nio.file.attribute.FileAttribute<?>[0];

			if (posix && Files.exists(file)) {
				try {
					Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
				} catch (IOException ignored) {
				}
			}

			try (java.nio.channels.SeekableByteChannel ch = Files.newByteChannel(file, opts, attrs)) {
				ch.write(java.nio.ByteBuffer.wrap(token.getBytes(StandardCharsets.UTF_8)));
			}

			if (posix) {
				try {
					Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
				} catch (IOException ignored) {
				}
			} else {
				try {
					file.toFile().setReadable(false, false);
					file.toFile().setWritable(false, false);
					file.toFile().setReadable(true, true);
					file.toFile().setWritable(true, true);
				} catch (SecurityException ignored) {
				}
			}
			return file;
		} catch (IOException e) {
			log.warn("Could not write agent token file: {}", e.getMessage());
			return null;
		}
	}

	@Override
	protected void shutDown() throws Exception {
		stopServer();
		deleteTokenFile();
	}

	private void deleteTokenFile() {
		try {
			Path file = Paths.get(System.getProperty("user.home"), ".microbot", "agent-token");
			Files.deleteIfExists(file);
		} catch (IOException e) {
			log.debug("Could not delete agent token file: {}", e.getMessage());
		}
	}

	private synchronized void stopServer() {
		AgentHandler.setTokenSupplier(null);
		if (server != null) {
			server.stop(0);
			server = null;
			log.info("Agent server stopped");
		}
		if (executor != null) {
			executor.shutdownNow();
			try {
				executor.awaitTermination(2, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			executor = null;
		}
		if (shutdownHook != null) {
			try {
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			} catch (IllegalStateException ignored) {
			}
			shutdownHook = null;
		}
	}

}
