package net.runelite.client.plugins.microbot.agentserver.handler;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigProfile;
import net.runelite.client.config.ProfileManager;
import net.runelite.client.plugins.microbot.Microbot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent server endpoint for listing and switching Microbot login profiles.
 * <p>
 * Endpoints:
 * <ul>
 *   <li>{@code GET /profiles} — list all non-internal profiles with active indicator</li>
 *   <li>{@code POST /profiles} — switch the active profile by name</li>
 * </ul>
 * <p>
 * Passwords and bank PINs are never exposed in responses.
 */
@Slf4j
public class ProfileHandler extends AgentHandler {

	private static final String PATH = "/profiles";

	public ProfileHandler(Gson gson) {
		super(gson);
	}

	@Override
	public String getPath() {
		return PATH;
	}

	@Override
	protected void handleRequest(HttpExchange exchange) throws IOException {
		String method = exchange.getRequestMethod().toUpperCase();

		if ("GET".equals(method)) {
			handleList(exchange);
		} else if ("POST".equals(method)) {
			handleSwitch(exchange);
		} else {
			sendJson(exchange, 405, errorResponse("Use GET or POST"));
		}
	}

	private void handleList(HttpExchange exchange) throws IOException {
		ConfigManager configManager = Microbot.getConfigManager();
		ProfileManager profileManager = Microbot.getProfileManager();

		if (configManager == null || profileManager == null) {
			sendJson(exchange, 503, errorResponse("Profile system not initialized"));
			return;
		}

		long activeId = configManager.getProfile() != null ? configManager.getProfile().getId() : -1;

		List<Map<String, Object>> profileList = new ArrayList<>();
		try (ProfileManager.Lock lock = profileManager.lock()) {
			for (ConfigProfile p : lock.getProfiles()) {
				if (p.isInternal()) {
					continue;
				}
				profileList.add(profileToMap(p, p.getId() == activeId));
			}
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("count", profileList.size());

		ConfigProfile active = configManager.getProfile();
		result.put("activeProfile", active != null && !active.isInternal() ? active.getName() : null);
		result.put("profiles", profileList);

		sendJson(exchange, 200, result);
	}

	private void handleSwitch(HttpExchange exchange) throws IOException {
		ConfigManager configManager = Microbot.getConfigManager();
		ProfileManager profileManager = Microbot.getProfileManager();

		if (configManager == null || profileManager == null) {
			sendJson(exchange, 503, errorResponse("Profile system not initialized"));
			return;
		}

		Map<String, Object> body = readJsonBody(exchange);
		if (body == null || !body.containsKey("name")) {
			sendJson(exchange, 400, errorResponse("Missing required field: name"));
			return;
		}

		Object nameObj = body.get("name");
		if (!(nameObj instanceof String) || ((String) nameObj).isBlank()) {
			sendJson(exchange, 400, errorResponse("Field 'name' must be a non-empty string"));
			return;
		}

		String requestedName = (String) nameObj;
		ConfigProfile targetProfile = null;

		// Phase 1: find profile under lock, persist active flag
		try (ProfileManager.Lock lock = profileManager.lock()) {
			List<ConfigProfile> profiles = lock.getProfiles();

			// Exact case-insensitive match first
			for (ConfigProfile p : profiles) {
				if (!p.isInternal() && p.getName().equalsIgnoreCase(requestedName)) {
					targetProfile = p;
					break;
				}
			}

			if (targetProfile == null) {
				// No partial matching — profile names are sensitive identifiers
				Map<String, Object> error = new LinkedHashMap<>();
				error.put("error", "Profile not found: " + requestedName);
				List<String> available = new ArrayList<>();
				for (ConfigProfile p : profiles) {
					if (!p.isInternal()) {
						available.add(p.getName());
					}
				}
				error.put("available", available);
				sendJson(exchange, 404, error);
				return;
			}

			// Already active check (by ID, not the persisted flag)
			if (configManager.getProfile() != null
					&& configManager.getProfile().getId() == targetProfile.getId()) {
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("success", true);
				result.put("message", "Profile '" + targetProfile.getName() + "' is already active");
				result.put("profile", profileToMap(targetProfile, true));
				sendJson(exchange, 200, result);
				return;
			}

			// Persist active flag (mirrors MicrobotProfilePanel protocol)
			profiles.forEach(p -> p.setActive(false));
			targetProfile.setActive(true);
			lock.dirty();
		}

		// Phase 2: switch config (synchronous — ensures next login uses this profile)
		try {
			configManager.switchProfile(targetProfile);
		} catch (Exception e) {
			log.error("Failed to switch profile to '{}'", targetProfile.getName(), e);
			sendJson(exchange, 500, errorResponse("Profile switch failed: " + e.getMessage()));
			return;
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("success", true);
		result.put("message", "Switched to profile '" + targetProfile.getName() + "'");
		result.put("profile", profileToMap(targetProfile, true));
		sendJson(exchange, 200, result);
	}

	/**
	 * Build a safe map representation of a profile (never includes password or bank PIN).
	 */
	private static Map<String, Object> profileToMap(ConfigProfile profile, boolean isActive) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("name", profile.getName());
		map.put("isMember", profile.isMember());
		map.put("selectedWorld", describeWorldSelection(profile.getSelectedWorld()));
		map.put("active", isActive);
		return map;
	}

	private static Object describeWorldSelection(Integer selectedWorld) {
		if (selectedWorld == null) {
			return "auto";
		}
		if (selectedWorld == -1) {
			return "random-members";
		}
		if (selectedWorld == -2) {
			return "random-f2p";
		}
		return selectedWorld;
	}
}
