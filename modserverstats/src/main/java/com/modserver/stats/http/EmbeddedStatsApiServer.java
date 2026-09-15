package com.modserver.stats.http;

import com.modserver.stats.ModServerStats;
import com.modserver.stats.model.ServerSnapshot;
import com.modserver.stats.storage.HistoryStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.rcon.RconConsoleSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public final class EmbeddedStatsApiServer implements AutoCloseable {
    private final MinecraftServer server;
    private final AtomicReference<ServerSnapshot> latestSnapshot;
    private final HistoryStore historyStore;
    private final boolean consoleEnabled;
    private final byte[] basicCredentialsBytes;
    private final byte[] authTokenBytes;
    private final ConsoleLogBuffer consoleLogBuffer;
    private ServerSocket serverSocket;
    private ExecutorService requestExecutor;
    private Thread acceptThread;

    public EmbeddedStatsApiServer(MinecraftServer server, AtomicReference<ServerSnapshot> latestSnapshot,
                                  HistoryStore historyStore, boolean consoleEnabled,
                                  String username, String password, String authToken,
                                  ConsoleLogBuffer consoleLogBuffer) {
        this.server = server;
        this.latestSnapshot = latestSnapshot;
        this.historyStore = historyStore;
        this.consoleEnabled = consoleEnabled;
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
        this.consoleLogBuffer = consoleLogBuffer;
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
            if ("/api/server/command".equals(path)) {
                if (!consoleEnabled) {
                    respond(socket, 404, "Not Found", "{\"error\":\"console_disabled\"}");
                    return;
                }
                String command = queryValue(requestTarget, "command");
                if (command == null || command.isBlank()) {
                    respond(socket, 400, "Bad Request", "{\"error\":\"command_required\"}");
                    return;
                }
                command = command.trim();
                if (command.startsWith("/")) command = command.substring(1).trim();
                if (command.isBlank()) {
                    respond(socket, 400, "Bad Request", "{\"error\":\"command_required\"}");
                    return;
                }
                if (command.length() > 2048) {
                    respond(socket, 413, "Payload Too Large", "{\"error\":\"command_too_long\"}");
                    return;
                }
                try {
                    String output = executeCommand(command);
                    respond(socket, 200, "OK", "{\"ok\":true,\"command\":"
                            + jsonString(command) + ",\"output\":" + jsonString(output) + "}");
                } catch (TimeoutException error) {
                    respond(socket, 504, "Gateway Timeout", "{\"error\":\"command_timeout\"}");
                } catch (Exception error) {
                    respond(socket, 500, "Internal Server Error", "{\"error\":\"command_failed\",\"message\":"
                            + jsonString(error.getMessage()) + "}");
                }
                return;
            }
            if ("/api/server/console".equals(path)) {
                if (consoleLogBuffer == null) {
                    respond(socket, 404, "Not Found", "{\"error\":\"console_disabled\"}");
                    return;
                }
                respondConsole(socket, requestTarget);
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

    private String executeCommand(String command) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                RconConsoleSource console = new RconConsoleSource(server);
                console.prepareForCommand();
                server.getCommands().performPrefixedCommand(
                        console.createCommandSourceStack(), command);
                String output = console.getCommandResponse();
                result.complete(output == null ? "" : output);
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    private void respondConsole(Socket socket, String requestTarget) throws IOException {
        long after = Math.max(0L, queryLong(requestTarget, "after", 0L));
        int limit = queryInt(requestTarget, "limit", 80);
        ConsoleLogBuffer.Snapshot snapshot = consoleLogBuffer.readAfter(after, limit);
        StringBuilder body = new StringBuilder(256);
        body.append("{\"cursor\":").append(snapshot.cursor())
                .append(",\"truncated\":").append(snapshot.truncated())
                .append(",\"lines\":[");
        for (int index = 0; index < snapshot.lines().size(); index++) {
            if (index > 0) body.append(',');
            ConsoleLogBuffer.ConsoleLine line = snapshot.lines().get(index);
            body.append("{\"sequence\":").append(line.sequence())
                    .append(",\"text\":").append(jsonString(line.text())).append('}');
        }
        body.append("]}");
        respond(socket, 200, "OK", body.toString());
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

    private static String queryValue(String requestTarget, String name) {
        int queryStart = requestTarget.indexOf('?');
        if (queryStart < 0 || queryStart == requestTarget.length() - 1) return null;
        String[] values = requestTarget.substring(queryStart + 1).split("&");
        for (String value : values) {
            String[] pair = value.split("=", 2);
            if (pair.length != 2 || !name.equals(pair[0])) continue;
            try {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        StringBuilder json = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> json.append("\\\\");
                case '"' -> json.append("\\\"");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append("\\u");
                        String hex = Integer.toHexString(character);
                        for (int padding = hex.length(); padding < 4; padding++) json.append('0');
                        json.append(hex);
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        return json.append('"').toString();
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
