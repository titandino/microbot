package net.runelite.client.plugins.microbot.agentserver.handler;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import net.runelite.client.plugins.microbot.Microbot;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class VarpHandler extends AgentHandler {

	public VarpHandler(Gson gson) {
		super(gson);
	}

	@Override
	public String getPath() {
		return "/varp";
	}

	@Override
	protected void handleRequest(HttpExchange exchange) throws IOException {
		try {
			requireGet(exchange);
		} catch (HttpMethodException e) {
			sendJson(exchange, 405, errorResponse(e.getMessage()));
			return;
		}

		Map<String, String> params = parseQuery(exchange.getRequestURI());
		String idStr = params.get("id");
		if (idStr == null || idStr.isEmpty()) {
			sendJson(exchange, 400, errorResponse("Missing required parameter: id"));
			return;
		}

		int varpId;
		try {
			varpId = Integer.parseInt(idStr);
		} catch (NumberFormatException e) {
			sendJson(exchange, 400, errorResponse("Invalid varp id: " + idStr));
			return;
		}

		int value = Microbot.getVarbitPlayerValue(varpId);

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("varpId", varpId);
		response.put("value", value);
		sendJson(exchange, 200, response);
	}
}
