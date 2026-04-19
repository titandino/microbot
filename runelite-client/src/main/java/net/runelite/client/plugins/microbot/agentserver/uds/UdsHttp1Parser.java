package net.runelite.client.plugins.microbot.agentserver.uds;

import com.sun.net.httpserver.Headers;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Minimal HTTP/1.1 request parser tuned for the agent server's surface:
 * GET/POST only, Content-Length-bounded bodies, no chunked transfer,
 * no keep-alive, no trailers, no expectations. Anything else gets a
 * well-formed 400 upstream.
 *
 * Header block is capped at 8 KiB; body reads are bounded by the handler
 * (agentserver's {@code readJsonBody} enforces 16 KiB). These ceilings keep
 * a malicious client from pinning our accept thread.
 */
public final class UdsHttp1Parser {

    public static final int HEADER_BYTES_CAP = 8 * 1024;

    public static final class ParsedRequest {
        public final String method;
        public final URI uri;
        public final String protocol;
        public final Headers headers;

        ParsedRequest(String method, URI uri, String protocol, Headers headers) {
            this.method = method;
            this.uri = uri;
            this.protocol = protocol;
            this.headers = headers;
        }
    }

    private UdsHttp1Parser() {
    }

    public static ParsedRequest parse(InputStream in) throws IOException {
        byte[] header = readHeaderBlock(in);
        String text = new String(header, StandardCharsets.ISO_8859_1);
        // Split at CRLF; first line is request-line, the rest are header lines.
        int firstCrLf = text.indexOf("\r\n");
        if (firstCrLf < 0) throw new IOException("Malformed request: missing CRLF after request line");
        String requestLine = text.substring(0, firstCrLf);
        String[] parts = requestLine.split(" ", 3);
        if (parts.length != 3) throw new IOException("Malformed request line: " + requestLine);
        String method = parts[0];
        URI uri = safeURI(parts[1]);
        String protocol = parts[2];

        Headers headers = new Headers();
        int i = firstCrLf + 2;
        while (i < text.length()) {
            int nextCrLf = text.indexOf("\r\n", i);
            if (nextCrLf < 0) break;
            if (nextCrLf == i) break; // blank line — end of headers
            String line = text.substring(i, nextCrLf);
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                headers.add(name, value);
            }
            i = nextCrLf + 2;
        }

        return new ParsedRequest(method, uri, protocol, headers);
    }

    private static byte[] readHeaderBlock(InputStream in) throws IOException {
        byte[] buf = new byte[HEADER_BYTES_CAP];
        int pos = 0;
        while (pos < HEADER_BYTES_CAP) {
            int b = in.read();
            if (b < 0) {
                if (pos == 0) throw new EOFException("Empty request");
                throw new IOException("Connection closed before header terminator");
            }
            buf[pos++] = (byte) b;
            if (pos >= 4
                    && buf[pos - 4] == '\r' && buf[pos - 3] == '\n'
                    && buf[pos - 2] == '\r' && buf[pos - 1] == '\n') {
                byte[] out = new byte[pos];
                System.arraycopy(buf, 0, out, 0, pos);
                return out;
            }
        }
        throw new IOException("Header block exceeded " + HEADER_BYTES_CAP + " bytes");
    }

    private static URI safeURI(String rawTarget) throws IOException {
        try {
            // The target in an HTTP request-line is an origin-form path-with-query, not an
            // absolute URI. Prefix with a dummy scheme/authority so URI parsing accepts it,
            // then strip back to path + query so handlers see what they expect.
            URI wrapped = URI.create("http://localhost" + (rawTarget.startsWith("/") ? rawTarget : "/" + rawTarget));
            return new URI(null, null, wrapped.getPath(), wrapped.getQuery(), null);
        } catch (Exception e) {
            throw new IOException("Malformed request target: " + rawTarget, e);
        }
    }
}
