package com.modserver.stats.http;

import com.modserver.stats.ModServerStats;
import com.modserver.stats.model.ServerSnapshot;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class TelemetryHttpClient {
    private final HttpClient client = HttpClient.newHttpClient();

    public CompletableFuture<Void> send(ServerSnapshot snapshot, String endpoint, String token, int timeoutSeconds) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ServerSnapshotJson.toJson(snapshot)));
        if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
        return client.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding())
                .thenAccept(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("HTTP status " + response.statusCode());
                    }
                    ModServerStats.LOGGER.debug("Telemetry sent with HTTP status {}", response.statusCode());
                });
    }

}
