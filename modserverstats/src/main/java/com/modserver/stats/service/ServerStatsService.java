package com.modserver.stats.service;

import com.modserver.stats.ModServerStats;
import com.modserver.stats.config.ServerStatsConfig;
import com.modserver.stats.http.ConsoleLogBuffer;
import com.modserver.stats.http.EmbeddedStatsApiServer;
import com.modserver.stats.http.TelemetryHttpClient;
import com.modserver.stats.model.ServerSnapshot;
import com.modserver.stats.storage.HistoryStore;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ServerStatsService {
    private final ServerStatsCollector collector = new ServerStatsCollector();
    private volatile TelemetryHttpClient httpClient;
    private final AtomicBoolean requestInFlight = new AtomicBoolean();
    private final AtomicReference<ServerSnapshot> latestSnapshot = new AtomicReference<>();
    private volatile EmbeddedStatsApiServer embeddedApi;
    private volatile ConsoleLogBuffer consoleLogBuffer;
    private volatile HistoryStore historyStore;

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        boolean historyEnabled = ServerStatsConfig.HISTORY_ENABLED.getAsBoolean();
        if (historyEnabled) {
            ConsoleLogBuffer.silenceSqliteTrace();
            try {
                historyStore = new HistoryStore(
                        FMLPaths.CONFIGDIR.get().resolve("modserverstats").resolve("history"),
                        ServerStatsConfig.HISTORY_RETENTION_DAYS.getAsInt(),
                        ServerStatsConfig.HISTORY_MAX_SAMPLES_PER_REQUEST.getAsInt());
            } catch (IOException | RuntimeException error) {
                ModServerStats.LOGGER.error("History storage could not start: {}", error.getMessage());
            }
        }
        if (!ServerStatsConfig.API_ENABLED.getAsBoolean()) return;
        ConsoleLogBuffer logBuffer = null;
        if (ServerStatsConfig.CONSOLE_ENABLED.getAsBoolean()) {
            try {
                logBuffer = ConsoleLogBuffer.install();
            } catch (RuntimeException error) {
                ModServerStats.LOGGER.error("Console log capture could not start: {}", error.getMessage());
            }
        }
        try {
            EmbeddedStatsApiServer api = new EmbeddedStatsApiServer(
                    event.getServer(), latestSnapshot, historyStore,
                    FMLPaths.CONFIGDIR.get().resolve("modserverstats").resolve("updates"),
                    ServerStatsConfig.CONSOLE_ENABLED.getAsBoolean(), logBuffer);
            api.start(ServerStatsConfig.API_BIND_ADDRESS.get(), ServerStatsConfig.API_PORT.getAsInt());
            embeddedApi = api;
            consoleLogBuffer = logBuffer;
        } catch (IOException | RuntimeException error) {
            if (logBuffer != null) logBuffer.close();
            ModServerStats.LOGGER.error("Embedded Android API could not start on port {}: {}",
                    ServerStatsConfig.API_PORT.getAsInt(), error.getMessage());
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        EmbeddedStatsApiServer api = embeddedApi;
        embeddedApi = null;
        if (api != null) api.close();
        ConsoleLogBuffer logBuffer = consoleLogBuffer;
        consoleLogBuffer = null;
        if (logBuffer != null) logBuffer.close();
        HistoryStore store = historyStore;
        historyStore = null;
        if (store != null) store.close();
        latestSnapshot.set(null);
        httpClient = null;
        requestInFlight.set(false);
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        boolean apiEnabled = ServerStatsConfig.API_ENABLED.getAsBoolean();
        boolean telemetryEnabled = ServerStatsConfig.ENABLED.getAsBoolean();
        boolean historyEnabled = ServerStatsConfig.HISTORY_ENABLED.getAsBoolean();
        if (!apiEnabled && !telemetryEnabled && !historyEnabled) return;

        int tickCount = server.getTickCount();
        boolean snapshotDue = apiEnabled && tickCount % 20 == 0;
        boolean historyDue = historyEnabled
                && tickCount % (ServerStatsConfig.HISTORY_INTERVAL_SECONDS.getAsInt() * 20) == 0;
        boolean uploadDue = telemetryEnabled
                && tickCount % (ServerStatsConfig.INTERVAL_SECONDS.getAsInt() * 20) == 0;
        boolean upload = uploadDue && requestInFlight.compareAndSet(false, true);
        if (!snapshotDue && !historyDue && !upload) return;

        ServerSnapshot snapshot = collector.collect(server);
        if (apiEnabled) latestSnapshot.set(snapshot);
        if (historyDue) {
            HistoryStore store = historyStore;
            if (store != null) store.append(snapshot);
        }
        if (!upload) return;

        try {
            TelemetryHttpClient client = httpClient;
            if (client == null) {
                client = new TelemetryHttpClient();
                httpClient = client;
            }
            client.send(snapshot, ServerStatsConfig.ENDPOINT.get(),
                    ServerStatsConfig.API_TOKEN.get(), ServerStatsConfig.REQUEST_TIMEOUT_SECONDS.get())
                    .whenComplete((ignored, error) -> {
                        requestInFlight.set(false);
                        if (error != null) ModServerStats.LOGGER.warn("Telemetry upload failed: {}", error.getMessage());
                    });
        } catch (RuntimeException error) {
            requestInFlight.set(false);
            ModServerStats.LOGGER.warn("Telemetry upload could not be created: {}", error.getMessage());
        }
    }
}
