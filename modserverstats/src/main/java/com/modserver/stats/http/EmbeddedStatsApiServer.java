package com.modserver.stats.http;

import com.modserver.stats.ModServerStats;
import com.modserver.stats.model.ServerSnapshot;
import com.modserver.stats.storage.HistoryStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public final class EmbeddedStatsApiServer implements AutoCloseable {
    private final AtomicReference<ServerSnapshot> latestSnapshot;
    private final HistoryStore historyStore;
    private final byte[] basicCredentialsBytes;
    private final byte[] authTokenBytes;
    private ServerSocket serverSocket;
    private ExecutorService requestExecutor;
    private Thread acceptThread;

    public EmbeddedStatsApiServer(AtomicReference<ServerSnapshot> latestSnapshot,
                                  HistoryStore historyStore, String username, String password,
                                  String authToken) {
        this.latestSnapshot = latestSnapshot;
        this.historyStore = historyStore;
        String normalizedUsername = username == null ? "" : username.trim();
        String normalizedPassword = password == null ? "" : password.trim();
        String basicCredentials = normalizedUsername.isEmpty() || normalizedPassword.isEmpty()
                ? ""
                : normalizedUsername + ":" + normalizedPassword;
        this.basicCredentialsBytes = basicCredentials.isEmpty()
                ? new byte[0]
                : Base64.getEncoder().encodeToString(basicCredentials.getBytes(StandardCharsets.UTF_8))
                        .getBytes(StandardCharsets.UTF_8);
        this.authTokenBytes = (authToken == null ? "" : authToken.trim())
                .getBytes(StandardCharsets.UTF_8);
    }

    public synchronized void start(String bindAddress, int port) throws IOException {
        if (serverSocket != null) return;
        InetAddress address = bindAddress == null || bindAddress.isBlank()
                ? null : InetAddress.getByName(bindAddress);
        serverSocket = address == null
                ? new ServerSocket(port, 50)
                : new ServerSocket(port, 50, address);
        requestExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "modserverstats-api-client");
            thread.setDaemon(true);
            return thread;
        });
        acceptThread = new Thread(this::acceptLoop, "modserverstats-api");
        acceptThread.setDaemon(true);
        acceptThread.start();
        ModServerStats.LOGGER.info("Embedded Android API listening on {}:{}",
                bindAddress == null || bindAddress.isBlank() ? "0.0.0.0" : bindAddress, port);
    }

    private void acceptLoop() {
        while (serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                requestExecutor.execute(() -> handle(client));
            } catch (IOException error) {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    ModServerStats.LOGGER.warn("Embedded Android API stopped accepting connections: {}",
                            error.getMessage());
                }
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.length() > 8192) return;

            String[] requestParts = requestLine.split(" ", 3);
            if (requestParts.length < 2) {
                respond(socket, 400, "Bad Request", "{\"error\":\"bad_request\"}");
                return;
            }

            String line;
            String authorization = null;
            int headerCount = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (++headerCount > 50 || line.length() > 8192) {
                    respond(socket, 400, "Bad Request", "{\"error\":\"too_many_headers\"}");
                    return;
                }
                int separator = line.indexOf(':');
                if (separator > 0
                        && "authorization".equalsIgnoreCase(line.substring(0, separator).trim())) {
                    authorization = line.substring(separator + 1).trim();
                }
            }

            String requestTarget = requestParts[1];
            String path = requestTarget;
            int queryStart = path.indexOf('?');
            if (queryStart >= 0) path = path.substring(0, queryStart);
            if (!"GET".equalsIgnoreCase(requestParts[0])) {
                respond(socket, 405, "Method Not Allowed", "{\"error\":\"method_not_allowed\"}");
                return;
            }
            if (!isAuthorized(authorization)) {
                respond(socket, 401, "Unauthorized", "{\"error\":\"unauthorized\"}");
                return;
            }
            if ("/health".equals(path)) {
                respond(socket, 200, "OK", "{\"status\":\"ok\"}");
                return;
            }
            if ("/api/server/history".equals(path)) {
                if (historyStore == null) {
                    respond(socket, 404, "Not Found", "{\"error\":\"history_disabled\"}");
                    return;
                }
                int limit = queryInt(requestTarget, "limit", 360);
                long from = queryLong(requestTarget, "fromEpochMs", -1L);
                long until = queryLong(requestTarget, "toEpochMs", -1L);
                String history = from >= 0L && until >= 0L
                        ? historyStore.readRange(from, until, limit)
                        : historyStore.readRecent(queryInt(requestTarget, "minutes", 60), limit);
                respond(socket, 200, "OK", history);
                return;
            }
            if (!"/api/server/stats".equals(path)) {
                respond(socket, 404, "Not Found", "{\"error\":\"not_found\"}");
                return;
            }

            ServerSnapshot snapshot = latestSnapshot.get();
            if (snapshot == null) {
                respond(socket, 503, "Service Unavailable", "{\"error\":\"snapshot_not_ready\"}");
                return;
            }
            respond(socket, 200, "OK", ServerSnapshotJson.toJson(snapshot));
        } catch (IOException ignored) {
            // The client may close the connection before reading the response.
        } catch (RuntimeException error) {
            ModServerStats.LOGGER.debug("Embedded Android API request failed: {}", error.getMessage());
        }
    }

    private boolean isAuthorized(String authorization) {
        if (authorization == null) return false;
        int separator = authorization.indexOf(' ');
        if (separator <= 0) {
            return false;
        }
        String scheme = authorization.substring(0, separator);
        String suppliedCredentials = authorization.substring(separator + 1).trim();
        if (suppliedCredentials.isEmpty()) return false;
        if ("Basic".equalsIgnoreCase(scheme) && basicCredentialsBytes.length > 0) {
            return MessageDigest.isEqual(basicCredentialsBytes,
                    suppliedCredentials.getBytes(StandardCharsets.UTF_8));
        }
        if ("Bearer".equalsIgnoreCase(scheme) && authTokenBytes.length > 0) {
            return MessageDigest.isEqual(authTokenBytes,
                    suppliedCredentials.getBytes(StandardCharsets.UTF_8));
        }
        return false;
    }

    private static int queryInt(String requestTarget, String name, int fallback) {
        int queryStart = requestTarget.indexOf('?');
        if (queryStart < 0 || queryStart == requestTarget.length() - 1) return fallback;
        String[] values = requestTarget.substring(queryStart + 1).split("&");
        for (String value : values) {
            String[] pair = value.split("=", 2);
            if (pair.length != 2 || !name.equals(pair[0])) continue;
            try {
                return Integer.parseInt(pair[1]);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static long queryLong(String requestTarget, String name, long fallback) {
        int queryStart = requestTarget.indexOf('?');
        if (queryStart < 0 || queryStart == requestTarget.length() - 1) return fallback;
        String[] values = requestTarget.substring(queryStart + 1).split("&");
        for (String value : values) {
            String[] pair = value.split("=", 2);
            if (pair.length != 2 || !name.equals(pair[0])) continue;
            try {
                return Long.parseLong(pair[1]);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static void respond(Socket socket, int status, String reason, String body) throws IOException {
        respond(socket, status, reason, body, "");
    }

    private static void respond(Socket socket, int status, String reason, String body, String extraHeaders)
            throws IOException {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        respondBytes(socket, status, reason, content, "application/json; charset=utf-8", extraHeaders);
    }

    private static void respondBytes(Socket socket, int status, String reason, byte[] content,
                                     String contentType) throws IOException {
        respondBytes(socket, status, reason, content, contentType, "");
    }

    private static void respondBytes(Socket socket, int status, String reason, byte[] content,
                                     String contentType, String extraHeaders) throws IOException {
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + content.length + "\r\n"
                + "Connection: close\r\n"
                + extraHeaders + "\r\n";
        OutputStream output = socket.getOutputStream();
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(content);
        output.flush();
    }

    @Override
    public synchronized void close() {
        if (serverSocket == null) return;
        try {
            serverSocket.close();
        } catch (IOException error) {
            ModServerStats.LOGGER.debug("Embedded Android API close failed: {}", error.getMessage());
        }
        if (requestExecutor != null) requestExecutor.shutdownNow();
        serverSocket = null;
        requestExecutor = null;
        acceptThread = null;
    }
}
