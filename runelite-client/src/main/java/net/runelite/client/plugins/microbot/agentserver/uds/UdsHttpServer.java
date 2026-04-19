package net.runelite.client.plugins.microbot.agentserver.uds;

import com.sun.net.httpserver.HttpHandler;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.agentserver.handler.AgentHandler;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP/1.1-over-UDS server for the agent surface. Keeps the public interface
 * parallel to the JDK {@code HttpServer}: register handlers keyed by path prefix,
 * start, stop. The socket file is placed at a caller-provided path with
 * {@code rw-------} permissions on POSIX — the parent directory permissions are
 * the real access gate; this is defense in depth.
 *
 * UDS types ({@code java.net.UnixDomainSocketAddress}, {@code StandardProtocolFamily.UNIX})
 * are Java 16+. The project compile target is Java 11, so this file reaches them
 * reflectively. On older runtimes the caller sees an {@link IOException} at
 * {@link #start()} time and can fall back to TCP.
 *
 * Concurrency model: one accept thread, work delegated to the caller-supplied
 * executor (the same pool the TCP server uses). Every connection is strictly
 * single-request — no keep-alive — matching the agent server's actual usage and
 * keeping the parser trivial.
 */
@Slf4j
public final class UdsHttpServer {

    private final Path socketPath;
    private final ExecutorService executor;
    private final Map<String, HttpHandler> contexts = new LinkedHashMap<>();
    private ServerSocketChannel server;
    private Thread acceptThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public UdsHttpServer(Path socketPath, ExecutorService executor) {
        this.socketPath = socketPath;
        this.executor = executor;
    }

    public void createContext(String path, HttpHandler handler) {
        contexts.put(path, handler);
    }

    public Path getSocketPath() {
        return socketPath;
    }

    public void start() throws IOException {
        validatePathLength(socketPath);
        lockDownParentDirectory(socketPath);
        cleanupStaleSocket();

        try {
            ProtocolFamily unix = resolveUnixProtocolFamily();
            SocketAddress address = newUnixSocketAddress(socketPath);
            server = (ServerSocketChannel) invokeServerChannelOpen(unix);
            server.bind(address);
        } catch (ReflectiveOperationException e) {
            throw new IOException("UDS not available on this JVM (requires Java 16+): " + e.getMessage(), e);
        }
        applyUserOnlyPerms(socketPath);
        running.set(true);

        acceptThread = new Thread(this::acceptLoop, "AgentServer-UDS-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running.set(false);
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException e) {
            log.debug("Could not remove UDS socket at {}: {}", socketPath, e.getMessage());
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            SocketChannel client;
            try {
                client = server.accept();
            } catch (IOException e) {
                if (running.get()) log.debug("UDS accept error: {}", e.getMessage());
                break;
            }
            executor.execute(() -> handleConnection(client));
        }
    }

    private void handleConnection(SocketChannel client) {
        try (SocketChannel c = client;
             BufferedInputStream in = new BufferedInputStream(Channels.newInputStream(c));
             OutputStream out = Channels.newOutputStream(c)) {
            UdsHttp1Parser.ParsedRequest request;
            try {
                request = UdsHttp1Parser.parse(in);
            } catch (IOException parseError) {
                writeMinimalError(out, 400, "Bad Request");
                return;
            }

            long contentLength = readContentLength(request);
            HttpHandler handler = resolveHandler(request.uri.getPath());
            if (handler == null) {
                writeMinimalError(out, 404, "Not Found");
                return;
            }

            UdsHttpExchange exchange = new UdsHttpExchange(
                    request,
                    UdsHttpExchange.boundedRequestBody(in, contentLength),
                    out);
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        } catch (IOException e) {
            if (!AgentHandler.isClientDisconnect(e)) {
                log.debug("UDS connection error: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("UDS handler exception: {}", e.getMessage());
        }
    }

    private HttpHandler resolveHandler(String path) {
        if (path == null) return null;
        HttpHandler best = null;
        int bestLen = -1;
        for (Map.Entry<String, HttpHandler> e : contexts.entrySet()) {
            String ctx = e.getKey();
            if (path.equals(ctx) || path.startsWith(ctx.endsWith("/") ? ctx : ctx + "/")) {
                if (ctx.length() > bestLen) {
                    best = e.getValue();
                    bestLen = ctx.length();
                }
            }
        }
        return best;
    }

    private static long readContentLength(UdsHttp1Parser.ParsedRequest req) {
        String cl = req.headers.getFirst("Content-Length");
        if (cl == null) return 0;
        try {
            long v = Long.parseLong(cl.trim());
            return Math.max(v, 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void writeMinimalError(OutputStream out, int code, String reason) throws IOException {
        byte[] body = reason.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: text/plain; charset=utf-8\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private void cleanupStaleSocket() throws IOException {
        if (!Files.exists(socketPath)) return;
        try {
            ProtocolFamily unix = resolveUnixProtocolFamily();
            try (SocketChannel probe = (SocketChannel) invokeSocketChannelOpen(unix)) {
                probe.connect(newUnixSocketAddress(socketPath));
                throw new IllegalStateException("Another agent server is already listening on " + socketPath);
            } catch (IOException ignored) {
                try {
                    Files.deleteIfExists(socketPath);
                } catch (IOException e) {
                    log.warn("Could not remove stale UDS socket {}: {}", socketPath, e.getMessage());
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IOException("UDS not available on this JVM (requires Java 16+): " + e.getMessage(), e);
        }
    }

    private static void applyUserOnlyPerms(Path path) {
        var views = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews();
        if (views.contains("posix")) {
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            } catch (IOException ignored) {
            }
        } else if (views.contains("acl")) {
            applyOwnerOnlyAcl(path);
        }
    }

    /**
     * Tighten the socket's parent directory to owner-only traversal. On POSIX we chmod 0700;
     * on Windows/NTFS we rewrite the ACL to a single allow-full-control entry for the current
     * user. This is the real access gate — the socket file's own permissions are secondary
     * (many filesystems ignore mode on sockets), so without the parent dir lockdown a
     * second local account could walk in and connect to the socket.
     */
    static void lockDownParentDirectory(Path socketPath) throws IOException {
        Path parent = socketPath.getParent();
        if (parent == null) return;
        Files.createDirectories(parent);

        var views = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews();
        if (views.contains("posix")) {
            try {
                Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
            } catch (IOException ignored) {
            }
        } else if (views.contains("acl")) {
            applyOwnerOnlyAcl(parent);
        }
    }

    private static void applyOwnerOnlyAcl(Path path) {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (view == null) return;
        try {
            UserPrincipal owner = view.getOwner();
            AclEntry allow = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            view.setAcl(List.of(allow));
        } catch (IOException e) {
            log.debug("Could not tighten ACL on {}: {}", path, e.getMessage());
        } catch (UnsupportedOperationException ignored) {
            // Filesystem claimed ACL support but rejected the specific entry — fall through.
        }
    }

    public static void validatePathLength(Path path) throws IOException {
        int len = path.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int cap = System.getProperty("os.name", "").toLowerCase().contains("mac") ? 104 : 108;
        if (len > cap) {
            throw new IOException("UDS socket path is too long (" + len + " bytes, limit " + cap + "): " + path);
        }
    }

    // ---- Reflection helpers for Java 16+ types while the compile target sits at Java 11. ----

    private static ProtocolFamily resolveUnixProtocolFamily() throws ClassNotFoundException, IllegalAccessException, NoSuchFieldException {
        Class<?> family = Class.forName("java.net.StandardProtocolFamily");
        @SuppressWarnings("rawtypes")
        Class rawEnum = family;
        @SuppressWarnings("unchecked")
        ProtocolFamily unix = (ProtocolFamily) Enum.valueOf(rawEnum, "UNIX");
        return unix;
    }

    private static SocketAddress newUnixSocketAddress(Path path) throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Class<?> udsAddr = Class.forName("java.net.UnixDomainSocketAddress");
        Method of = udsAddr.getMethod("of", Path.class);
        return (SocketAddress) of.invoke(null, path);
    }

    private static Object invokeServerChannelOpen(ProtocolFamily family) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        // ServerSocketChannel.open(ProtocolFamily) exists in Java 15+, but UNIX family is Java 16+.
        Method open = ServerSocketChannel.class.getMethod("open", ProtocolFamily.class);
        return open.invoke(null, family);
    }

    private static Object invokeSocketChannelOpen(ProtocolFamily family) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method open = SocketChannel.class.getMethod("open", ProtocolFamily.class);
        return open.invoke(null, family);
    }
}
