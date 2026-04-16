package net.runelite.client.plugins.microbot.agentserver.handler;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.statemachine.StateSnapshot;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent server endpoint for inspecting the runtime state of any
 * {@link StateMachineScript} that is currently registered.
 * <p>
 * Endpoints:
 * <ul>
 *   <li>{@code GET /debug/snapshot?script=MyScript} — snapshot of a single script</li>
 *   <li>{@code GET /debug/snapshot} — list all registered state machine scripts with their current state</li>
 * </ul>
 */
@Slf4j
public class StateMachineDebugHandler extends AgentHandler {

    private static final String PATH = "/debug/snapshot";

    public StateMachineDebugHandler(Gson gson) {
        super(gson);
    }

    @Override
    public String getPath() {
        return PATH;
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
        String scriptName = params.get("script");

        if (scriptName == null || scriptName.isEmpty()) {
            handleListAll(exchange);
        } else {
            handleSingle(exchange, scriptName);
        }
    }

    private void handleListAll(HttpExchange exchange) throws IOException {
        Map<String, StateMachineScript<?>> registry = StateMachineScript.getRegistry();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", registry.size());
        result.put("scripts", registry.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("name", entry.getKey());
                    StateSnapshot<?> snap = entry.getValue().getSnapshot();
                    if (snap != null) {
                        info.put("currentState", snap.currentState() != null ? snap.currentState().name() : null);
                        info.put("loopCount", snap.loopCount());
                        info.put("transitionCount", snap.transitionCount());
                        if (snap.stateEnteredAt() != null) {
                            info.put("msInCurrentState",
                                    System.currentTimeMillis() - snap.stateEnteredAt().toEpochMilli());
                        }
                    } else {
                        info.put("currentState", "NOT_INITIALIZED");
                    }
                    return info;
                })
                .collect(Collectors.toList()));

        sendJson(exchange, 200, result);
    }

    private void handleSingle(HttpExchange exchange, String scriptName) throws IOException {
        Map<String, StateMachineScript<?>> registry = StateMachineScript.getRegistry();

        // Try exact match first, then case-insensitive partial match
        StateMachineScript<?> script = registry.get(scriptName);
        if (script == null) {
            for (Map.Entry<String, StateMachineScript<?>> entry : registry.entrySet()) {
                if (entry.getKey().toLowerCase().contains(scriptName.toLowerCase())) {
                    script = entry.getValue();
                    break;
                }
            }
        }

        if (script == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Script not found: " + scriptName);
            error.put("available", registry.keySet());
            sendJson(exchange, 404, error);
            return;
        }

        StateSnapshot<?> snap = script.getSnapshot();
        if (snap == null) {
            sendJson(exchange, 200, Map.of(
                    "script", scriptName,
                    "state", "NOT_INITIALIZED",
                    "hint", "Script is registered but step() has not been called yet"
            ));
            return;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("script", scriptName);
        result.putAll(snap.toMap());
        sendJson(exchange, 200, result);
    }
}
