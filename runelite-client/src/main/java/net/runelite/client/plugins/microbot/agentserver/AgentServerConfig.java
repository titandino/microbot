package net.runelite.client.plugins.microbot.agentserver;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(AgentServerConfig.GROUP)
public interface AgentServerConfig extends Config {

	String GROUP = "agentServer";
	String KEY_TOKEN = "authToken";
	String KEY_PORT = "port";
	int PORT_RANDOM_MIN = 49152;
	int PORT_RANDOM_MAX = 65535;

	@ConfigItem(
			keyName = "port",
			name = "Port",
			description = "HTTP server port for agent communication (bound to 127.0.0.1 only)",
			position = 0
	)
	@Range(min = 1024, max = 65535)
	default int port() {
		return 8081;
	}

	@ConfigItem(
			keyName = "maxResults",
			name = "Max Results",
			description = "Default max results for list/query endpoints",
			position = 1
	)
	@Range(min = 10, max = 5000)
	default int maxResults() {
		return 200;
	}

	@ConfigItem(
			keyName = KEY_TOKEN,
			name = "Auth token",
			description = "Shared secret required in the X-Agent-Token header. Auto-generated on first start. Use 'Regenerate token' to rotate.",
			secret = true,
			position = 2
	)
	default String authToken() {
		return "";
	}

	@ConfigItem(
			keyName = KEY_TOKEN,
			name = "",
			description = ""
	)
	void authToken(String token);
}
