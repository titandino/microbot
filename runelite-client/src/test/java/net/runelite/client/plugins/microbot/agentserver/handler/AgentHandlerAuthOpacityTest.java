package net.runelite.client.plugins.microbot.agentserver.handler;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Wire-shape guard: every pre-auth failure must be indistinguishable from a
 * 404 for an unbound path. Previously the server returned 401 with a body
 * containing an {@code X-Agent-Token} hint, which let a port-scanner identify
 * the agent server from a single unauthenticated probe without needing the
 * token. Any regression that reintroduces 401s or an auth-shaped body would
 * re-open that tell.
 */
public class AgentHandlerAuthOpacityTest {

    private HttpServer server;
    private int port;
    private final Gson gson = new Gson();

    private static final class PingHandler extends AgentHandler {
        PingHandler(Gson gson) {
            super(gson);
        }

        @Override
        public String getPath() {
            return "/ping";
        }

        @Override
        protected void handleRequest(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            sendJson(exchange, 200, java.util.Collections.singletonMap("ok", true));
        }
    }

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpContext ctx = server.createContext("/ping", new PingHandler(gson));
        assertNotNull(ctx);
        AgentHandler.setTokenSupplier(() -> "correct-token");
        server.start();
        port = server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        if (server != null) server.stop(0);
        AgentHandler.setTokenSupplier(null);
    }

    @Test
    public void missingTokenYields404NotFound() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/ping").openConnection();
        conn.setRequestMethod("GET");
        try {
            assertEquals(404, conn.getResponseCode());
            String body = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("Not Found", body);
        } finally {
            conn.disconnect();
        }
    }

    @Test
    public void wrongTokenYields404NotFound() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/ping").openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("X-Agent-Token", "wrong");
        try {
            assertEquals(404, conn.getResponseCode());
        } finally {
            conn.disconnect();
        }
    }

    @Test
    public void correctTokenPassesThrough() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/ping").openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("X-Agent-Token", "correct-token");
        try {
            assertEquals(200, conn.getResponseCode());
        } finally {
            conn.disconnect();
        }
    }

    @Test
    public void crossOriginProbeYields404() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/ping").openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Origin", "https://evil.example.com");
        try {
            assertEquals(404, conn.getResponseCode());
        } finally {
            conn.disconnect();
        }
    }

    @Test
    public void hostHeaderSpoofYields404() throws Exception {
        // Build an explicitly forged Host via raw socket — HttpURLConnection will not
        // let us override Host under all JDKs, and the JVM sometimes silently rewrites it.
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.getOutputStream().write((
                    "GET /ping HTTP/1.1\r\n" +
                            "Host: evil.example.com\r\n" +
                            "X-Agent-Token: correct-token\r\n" +
                            "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // Status line contains "404"
            String statusLine = response.split("\r\n", 2)[0];
            assertEquals("HTTP/1.1 404 Not Found", statusLine);
        }
    }

    @Test
    public void unknownPathAlsoYields404_establishingBaseline() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/definitely-not-here").openConnection();
        conn.setRequestMethod("GET");
        try {
            // The JDK HttpServer returns 404 for unbound paths with "Not found" body.
            assertEquals(404, conn.getResponseCode());
        } finally {
            conn.disconnect();
        }
    }
}
