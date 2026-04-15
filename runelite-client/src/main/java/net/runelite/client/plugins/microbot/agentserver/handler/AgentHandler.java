package net.runelite.client.plugins.microbot.agentserver.handler;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
public abstract class AgentHandler implements HttpHandler {

	private static final int MAX_BODY_SIZE = 16 * 1024;
	private static final String AUTH_HEADER = "X-Agent-Token";

	private static volatile Supplier<String> tokenSupplier = () -> null;

	protected final Gson gson;

	protected AgentHandler(Gson gson) {
		this.gson = gson;
	}

	public static void setTokenSupplier(Supplier<String> supplier) {
		tokenSupplier = supplier != null ? supplier : () -> null;
	}

	public abstract String getPath();

	protected abstract void handleRequest(HttpExchange exchange) throws IOException;

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (exchange.getRequestHeaders().getFirst("Origin") != null) {
			sendJson(exchange, 403, errorResponse("Cross-origin requests are not permitted"));
			return;
		}

		String host = exchange.getRequestHeaders().getFirst("Host");
		if (host == null || !isLoopbackHost(host)) {
			sendJson(exchange, 403, errorResponse("Invalid Host header"));
			return;
		}

		String expected = tokenSupplier.get();
		if (expected != null && !expected.isEmpty()) {
			String provided = exchange.getRequestHeaders().getFirst(AUTH_HEADER);
			if (provided == null || !constantTimeEquals(expected, provided)) {
				Map<String, Object> err = errorResponse("auth");
				err.put("hint", "set " + AUTH_HEADER + " header; token is in the Agent Server plugin config or ~/.microbot/agent-token");
				sendJson(exchange, 401, err);
				return;
			}
		}

		try {
			handleRequest(exchange);
		} catch (Exception e) {
			if (isClientDisconnect(e)) {
				log.debug("Client disconnected before {} response could be sent", exchange.getRequestURI());
				return;
			}
			log.error("Error handling {}", exchange.getRequestURI(), e);
			try {
				sendJson(exchange, 500, errorResponse("Internal server error: " + e.getMessage()));
			} catch (IOException ioe) {
				if (!isClientDisconnect(ioe)) {
					throw ioe;
				}
				log.debug("Client disconnected before error response could be sent for {}", exchange.getRequestURI());
			}
		}
	}

	private static boolean isLoopbackHost(String hostHeader) {
		String h = hostHeader.trim();
		if (h.startsWith("[")) {
			int end = h.indexOf(']');
			if (end < 0) return false;
			String ip = h.substring(1, end);
			return "::1".equals(ip) || "0:0:0:0:0:0:0:1".equalsIgnoreCase(ip);
		}
		int colon = h.indexOf(':');
		String name = colon >= 0 ? h.substring(0, colon) : h;
		return "127.0.0.1".equals(name) || "localhost".equalsIgnoreCase(name);
	}

	private static boolean constantTimeEquals(String a, String b) {
		byte[] ab = a.getBytes(StandardCharsets.UTF_8);
		byte[] bb = b.getBytes(StandardCharsets.UTF_8);
		if (ab.length != bb.length) return false;
		int diff = 0;
		for (int i = 0; i < ab.length; i++) {
			diff |= ab[i] ^ bb[i];
		}
		return diff == 0;
	}

	protected static boolean isClientDisconnect(Throwable t) {
		while (t != null) {
			if (t instanceof java.io.IOException) {
				String msg = t.getMessage();
				if (msg != null) {
					String lower = msg.toLowerCase();
					if (lower.contains("broken pipe")
							|| lower.contains("connection reset")
							|| lower.contains("connection closed")
							|| lower.contains("insufficient bytes written")) {
						return true;
					}
				}
			}
			t = t.getCause();
		}
		return false;
	}

	protected void requireGet(HttpExchange exchange) throws HttpMethodException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			throw new HttpMethodException("Method not allowed. Use GET.");
		}
	}

	protected void requirePost(HttpExchange exchange) throws HttpMethodException {
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			throw new HttpMethodException("Method not allowed. Use POST.");
		}
	}

	protected void sendJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
		String json = gson.toJson(body);
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(statusCode, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}

	protected Map<String, Object> errorResponse(String message) {
		Map<String, Object> err = new LinkedHashMap<>();
		err.put("error", message);
		return err;
	}

	protected Map<String, String> parseQuery(URI uri) {
		Map<String, String> params = new LinkedHashMap<>();
		String query = uri.getQuery();
		if (query == null) return params;
		for (String pair : query.split("&")) {
			String[] kv = pair.split("=", 2);
			if (kv.length == 2) {
				params.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
			} else if (kv.length == 1) {
				params.put(kv[0], "");
			}
		}
		return params;
	}

	protected Map<String, Object> readJsonBody(HttpExchange exchange) throws IOException {
		String cl = exchange.getRequestHeaders().getFirst("Content-Length");
		if (cl != null) {
			try {
				long advertised = Long.parseLong(cl.trim());
				if (advertised > MAX_BODY_SIZE) {
					throw new IllegalArgumentException("Request body too large");
				}
			} catch (NumberFormatException ignored) {
			}
		}
		byte[] bodyBytes = exchange.getRequestBody().readNBytes(MAX_BODY_SIZE + 1);
		if (bodyBytes.length > MAX_BODY_SIZE) {
			throw new IllegalArgumentException("Request body too large");
		}
		String body = new String(bodyBytes, StandardCharsets.UTF_8);
		@SuppressWarnings("unchecked")
		Map<String, Object> parsed = gson.fromJson(body, LinkedHashMap.class);
		return parsed;
	}

	protected int getIntParam(Map<String, String> params, String key, int defaultValue) {
		String val = params.get(key);
		if (val == null) return defaultValue;
		try {
			return Integer.parseInt(val);
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	protected <T> Map<String, Object> paginate(List<T> items, int offset, int limit, String key) {
		int total = items.size();
		int start = Math.min(offset, total);
		int end = Math.min(offset + limit, total);
		List<T> page = items.subList(start, end);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("count", page.size());
		result.put("total", total);
		result.put("offset", offset);
		result.put("limit", limit);
		result.put(key, page);
		return result;
	}

	protected String getSubPath(HttpExchange exchange, String basePath) {
		String full = exchange.getRequestURI().getPath();
		if (full.length() <= basePath.length()) return "";
		return full.substring(basePath.length());
	}

	public static class HttpMethodException extends Exception {
		public HttpMethodException(String message) {
			super(message);
		}
	}
}
