package net.runelite.client.plugins.microbot;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;

import javax.inject.Singleton;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Singleton
public class MicrobotVersionChecker
{
	private final AtomicBoolean newVersionAvailable = new AtomicBoolean(false);
	private final AtomicBoolean scheduled = new AtomicBoolean(false);
	private volatile ScheduledFuture<?> future;
	private final String REMOTE_VERSION_URL = "https://microbot.cloud/api/version/client";

	private final boolean disableTelemetry;

	@Inject
	public MicrobotVersionChecker(@Named("disableTelemetry") boolean disableTelemetry) {
		this.disableTelemetry = disableTelemetry;
	}

	private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, MicrobotVersionChecker.class.getSimpleName());
		t.setDaemon(true);
		t.setUncaughtExceptionHandler((th, e) -> log.warn("Version checker thread error", e));
		return t;
	});

	private void runVersionCheck()
	{
		if (newVersionAvailable.get())
		{
			return;
		}

		try
		{
			String remoteVersion = fetchRemoteVersion();
			String localVersion = RuneLiteProperties.getMicrobotVersion();
			String remote = remoteVersion == null ? null : remoteVersion.trim();
			String local = localVersion == null ? "" : localVersion.trim();
			if (remote != null && !remote.isEmpty() && Rs2UiHelper.compareVersions(local, remote) < 0)
			{
				newVersionAvailable.set(true);
				log.info("New Microbot client version available: {} (current: {})", remote, local);
			}
			else
			{
				log.debug("Microbot client is up to date: {}", local);
			}
		}
		catch (Exception e)
		{
			log.warn("Could not check Microbot client version", e);
		}
	}

	private String fetchRemoteVersion() throws Exception
	{
		var url = new URL(REMOTE_VERSION_URL);
		var conn = (HttpURLConnection) url.openConnection();
		conn.setConnectTimeout(5_000);
		conn.setReadTimeout(5_000);
		conn.setRequestMethod("GET");
		conn.setInstanceFollowRedirects(true);
		try (var reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)))
		{
			if (conn.getResponseCode() != 200)
			{
				log.debug("Version check responded with HTTP {}", conn.getResponseCode());
				return null;
			}
			String line = reader.readLine();
			return line != null ? line.trim() : null;
		}
		finally
		{
			conn.disconnect();
		}
	}

	public void checkForUpdate()
	{
		if (Microbot.isDebug()) {
			return;
		}
		if (disableTelemetry || Microbot.isTelemetryDisabled()) {
			return;
		}

		if (scheduled.compareAndSet(false, true))
		{
			future = scheduledExecutorService.schedule(this::runVersionCheck, 0, TimeUnit.SECONDS);
		}
	}

	public void shutdown()
	{
		try
		{
			if (future != null)
			{
				future.cancel(true);
			}
		}
		finally
		{
			scheduledExecutorService.shutdownNow();
			newVersionAvailable.set(false);
			scheduled.set(false);
		}
	}
}
