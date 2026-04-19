package net.runelite.client.plugins.microbot.agentserver.uds;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concrete {@link HttpExchange} backed by a raw socket stream pair parsed from
 * a UDS connection. Keeps handlers untouched: {@code AgentHandler.handle(...)}
 * sees the same API surface whether the transport is TCP via the JDK's
 * {@code HttpServer} or UDS via {@link UdsHttpServer}.
 *
 * Semantic choices worth flagging:
 * <ul>
 *   <li>{@code getRemoteAddress()} / {@code getLocalAddress()} return a synthetic
 *       loopback ({@code 127.0.0.1:0}). {@code AgentHandler.isLoopbackHost} is
 *       driven by the Host header, not these, so the synthetic address is a
 *       non-regression.</li>
 *   <li>{@code sendResponseHeaders(status, length)} writes the status line and
 *       headers exactly once and guarantees a {@code Connection: close}. We do
 *       not support keep-alive.</li>
 *   <li>{@code getResponseBody()} returns a length-bounded {@code OutputStream}
 *       so handlers that over-write get a clear failure, matching the JDK
 *       {@code HttpServer}'s behaviour with a fixed-length response.</li>
 * </ul>
 */
public final class UdsHttpExchange extends HttpExchange {

    private static final InetSocketAddress SYNTHETIC_LOOPBACK = new InetSocketAddress("127.0.0.1", 0);

    private final UdsHttp1Parser.ParsedRequest request;
    private final InputStream requestBody;
    private final OutputStream socketOut;
    private final Headers responseHeaders = new Headers();
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    private int responseCode = -1;
    private boolean headersSent = false;
    private OutputStream responseBody = null;

    public UdsHttpExchange(UdsHttp1Parser.ParsedRequest request, InputStream body, OutputStream socketOut) {
        this.request = request;
        this.requestBody = body;
        this.socketOut = socketOut;
    }

    @Override
    public Headers getRequestHeaders() {
        return request.headers;
    }

    @Override
    public Headers getResponseHeaders() {
        return responseHeaders;
    }

    @Override
    public URI getRequestURI() {
        return request.uri;
    }

    @Override
    public String getRequestMethod() {
        return request.method;
    }

    @Override
    public HttpContext getHttpContext() {
        return null;
    }

    @Override
    public void close() {
        try {
            if (responseBody != null) responseBody.flush();
            socketOut.flush();
        } catch (IOException ignored) {
        }
    }

    @Override
    public InputStream getRequestBody() {
        return requestBody;
    }

    @Override
    public OutputStream getResponseBody() {
        if (responseBody == null) {
            throw new IllegalStateException("sendResponseHeaders(...) must be called before getResponseBody()");
        }
        return responseBody;
    }

    @Override
    public void sendResponseHeaders(int rCode, long responseLength) throws IOException {
        if (headersSent) throw new IOException("Response headers already sent");
        headersSent = true;
        this.responseCode = rCode;

        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(rCode).append(' ').append(reasonPhrase(rCode)).append("\r\n");
        sb.append("Connection: close\r\n");

        boolean contentLengthSent = false;
        for (Map.Entry<String, java.util.List<String>> e : responseHeaders.entrySet()) {
            for (String v : e.getValue()) {
                sb.append(e.getKey()).append(": ").append(v).append("\r\n");
                if (e.getKey().equalsIgnoreCase("Content-Length")) contentLengthSent = true;
            }
        }
        if (!contentLengthSent) {
            long bodyLen = responseLength < 0 ? 0 : responseLength;
            sb.append("Content-Length: ").append(bodyLen).append("\r\n");
        }
        sb.append("\r\n");
        socketOut.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        socketOut.flush();

        long expected = responseLength < 0 ? 0 : responseLength;
        responseBody = expected == 0
                ? new NullOutputStream()
                : new BoundedOutputStream(socketOut, expected);
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return SYNTHETIC_LOOPBACK;
    }

    @Override
    public int getResponseCode() {
        return responseCode;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return SYNTHETIC_LOOPBACK;
    }

    @Override
    public String getProtocol() {
        return request.protocol;
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (value == null) attributes.remove(name);
        else attributes.put(name, value);
    }

    @Override
    public void setStreams(InputStream is, OutputStream os) {
        // No-op. Handlers in this repo don't call this; it exists only on the JDK's filter chain.
    }

    @Override
    public HttpPrincipal getPrincipal() {
        return null;
    }

    private static String reasonPhrase(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 500: return "Internal Server Error";
            default: return "Status " + code;
        }
    }

    static final class BoundedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private long remaining;

        BoundedOutputStream(OutputStream delegate, long length) {
            this.delegate = delegate;
            this.remaining = length;
        }

        @Override
        public void write(int b) throws IOException {
            if (remaining <= 0) throw new IOException("Response body exceeds declared Content-Length");
            delegate.write(b);
            remaining--;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len > remaining) {
                throw new IOException("Response body write of " + len + " bytes would exceed remaining " + remaining);
            }
            delegate.write(b, off, len);
            remaining -= len;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.flush();
        }
    }

    static final class NullOutputStream extends OutputStream {
        @Override public void write(int b) throws IOException {
            throw new IOException("Response declared with Content-Length: 0 — no body permitted");
        }
    }

    /**
     * Content-Length-bounded request body; blocks further reads once the declared
     * byte count has been delivered. Prevents a handler from accidentally draining
     * a following pipelined request (we close after one anyway, but belt-and-braces).
     */
    public static InputStream boundedRequestBody(InputStream raw, long length) {
        if (length <= 0) return new ByteArrayInputStream(new byte[0]);
        return new FilterInputStream(raw) {
            long remaining = length;

            @Override
            public int read() throws IOException {
                if (remaining <= 0) return -1;
                int b = in.read();
                if (b >= 0) remaining--;
                return b;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                if (remaining <= 0) return -1;
                int r = in.read(buf, off, (int) Math.min(len, remaining));
                if (r > 0) remaining -= r;
                return r;
            }

            @Override
            public void close() {
                // Do not close the underlying socket stream — the exchange owns it.
            }
        };
    }
}
