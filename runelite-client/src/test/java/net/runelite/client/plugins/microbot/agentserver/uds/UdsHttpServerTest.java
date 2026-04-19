package net.runelite.client.plugins.microbot.agentserver.uds;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import net.runelite.client.plugins.microbot.agentserver.handler.AgentHandler;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end smoke test: start a {@link UdsHttpServer}, send a handcrafted HTTP/1.1
 * request over the UNIX socket, confirm the handler fires and the wire response is
 * well-formed. Skipped on JDKs older than 16 (no UDS API).
 *
 * Opens everything reflectively so this test itself also compiles under the project's
 * Java 11 release target.
 */
public class UdsHttpServerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private UdsHttpServer server;
    private ExecutorService executor;
    private Path socketPath;

    @Before
    public void setUp() throws IOException {
        Assume.assumeTrue("UDS requires Java 16+ runtime",
                isJavaUdsAvailable());
        socketPath = tempFolder.newFolder().toPath().resolve("agent.sock");
        executor = Executors.newFixedThreadPool(2);
        server = new UdsHttpServer(socketPath, executor);
        server.createContext("/ping", new PingHandler(new Gson()));
        AgentHandler.setTokenSupplier(null);
        server.start();
    }

    @After
    public void tearDown() {
        try {
            if (server != null) server.stop();
        } finally {
            if (executor != null) executor.shutdownNow();
            AgentHandler.setTokenSupplier(null);
        }
    }

    @Test
    public void unknownPathReturns404() throws Exception {
        String response = roundTrip("GET /nowhere HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        assertTrue("expected 404, got: " + response, response.startsWith("HTTP/1.1 404"));
    }

    @Test
    public void pingEndpointRoundTrips() throws Exception {
        String response = roundTrip("GET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        assertTrue("expected 200, got: " + response, response.startsWith("HTTP/1.1 200"));
        assertTrue("body should be JSON", response.contains("\"ok\""));
        assertTrue("must close the connection", response.contains("Connection: close"));
    }

    @Test
    public void authTokenEnforced() throws Exception {
        AgentHandler.setTokenSupplier(() -> "secret");
        try {
            String response = roundTrip("GET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            assertTrue("expected 404 (opaque auth), got: " + response, response.startsWith("HTTP/1.1 404"));
        } finally {
            AgentHandler.setTokenSupplier(null);
        }
    }

    @Test
    public void authTokenAccepted() throws Exception {
        AgentHandler.setTokenSupplier(() -> "secret");
        try {
            String response = roundTrip("GET /ping HTTP/1.1\r\nHost: localhost\r\nX-Agent-Token: secret\r\nConnection: close\r\n\r\n");
            assertTrue("expected 200, got: " + response, response.startsWith("HTTP/1.1 200"));
        } finally {
            AgentHandler.setTokenSupplier(null);
        }
    }

    @Test
    public void malformedRequestYields400() throws Exception {
        String response = roundTrip("not-a-real-request\r\n\r\n");
        assertTrue("expected 400, got: " + response, response.startsWith("HTTP/1.1 400"));
    }

    @Test
    public void pathLengthValidatorRejectsOversizedPath() {
        Path absurdlyLong = tempFolder.getRoot().toPath();
        StringBuilder sb = new StringBuilder(absurdlyLong.toString());
        while (sb.length() < 200) sb.append("/padding-segment-thirty-chars--");
        Path tooLong = Path.of(sb.toString());
        try {
            UdsHttpServer.validatePathLength(tooLong);
            assertEquals("should have thrown", "but did not", "");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("too long"));
        }
    }

    // ---- helpers ----

    private String roundTrip(String request) throws Exception {
        try (SocketChannel client = openUnixChannel()) {
            client.connect(newUnixAddress(socketPath));
            client.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.ISO_8859_1)));

            ByteBuffer buf = ByteBuffer.allocate(8 * 1024);
            int read;
            StringBuilder out = new StringBuilder();
            while ((read = client.read(buf)) > 0) {
                buf.flip();
                byte[] chunk = new byte[read];
                buf.get(chunk);
                out.append(new String(chunk, StandardCharsets.ISO_8859_1));
                buf.clear();
            }
            return out.toString();
        }
    }

    private static SocketChannel openUnixChannel() throws Exception {
        Method open = SocketChannel.class.getMethod("open", ProtocolFamily.class);
        return (SocketChannel) open.invoke(null, unixProtocolFamily());
    }

    private static ProtocolFamily unixProtocolFamily() throws Exception {
        Class<?> family = Class.forName("java.net.StandardProtocolFamily");
        @SuppressWarnings({"rawtypes", "unchecked"})
        ProtocolFamily unix = (ProtocolFamily) Enum.valueOf((Class) family, "UNIX");
        return unix;
    }

    private static SocketAddress newUnixAddress(Path path) throws Exception {
        Class<?> udsAddr = Class.forName("java.net.UnixDomainSocketAddress");
        Method of = udsAddr.getMethod("of", Path.class);
        return (SocketAddress) of.invoke(null, path);
    }

    private static boolean isJavaUdsAvailable() {
        try {
            Class.forName("java.net.UnixDomainSocketAddress");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static final class PingHandler extends AgentHandler {
        PingHandler(Gson gson) {
            super(gson);
        }

        @Override
        public String getPath() {
            return "/ping";
        }

        @Override
        protected void handleRequest(HttpExchange exchange) throws IOException {
            sendJson(exchange, 200, Collections.singletonMap("ok", true));
        }
    }
}
