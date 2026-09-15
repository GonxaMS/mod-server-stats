package com.modserver.stats.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ServerStatsConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Enable periodic telemetry uploads")
            .define("telemetry.enabled", false);
    public static final ModConfigSpec.ConfigValue<String> ENDPOINT = BUILDER
            .comment("HTTP or HTTPS endpoint receiving the JSON payload")
            .define("telemetry.endpoint", "http://127.0.0.1:8080/api/server/stats");
    public static final ModConfigSpec.ConfigValue<String> API_TOKEN = BUILDER
            .comment("Optional bearer token sent in the Authorization header")
            .define("telemetry.apiToken", "");
    public static final ModConfigSpec.ConfigValue<String> SERVER_ID = BUILDER
            .comment("Stable identifier for this Minecraft server")
            .define("telemetry.serverId", "survival-1");
    public static final ModConfigSpec.IntValue INTERVAL_SECONDS = BUILDER
            .comment("Upload interval in seconds")
            .defineInRange("telemetry.intervalSeconds", 10, 1, 3600);
    public static final ModConfigSpec.IntValue REQUEST_TIMEOUT_SECONDS = BUILDER
            .comment("HTTP request timeout in seconds")
            .defineInRange("telemetry.requestTimeoutSeconds", 5, 1, 120);

    public static final ModConfigSpec.BooleanValue API_ENABLED = BUILDER
            .comment("Expose the latest server snapshot through the embedded Android API")
            .define("api.enabled", false);
    public static final ModConfigSpec.IntValue API_PORT = BUILDER
            .comment("TCP port used by the embedded Android API")
            .defineInRange("api.port", 8080, 1024, 65535);
    public static final ModConfigSpec.ConfigValue<String> API_BIND_ADDRESS = BUILDER
            .comment("Address to bind the embedded API to; use 0.0.0.0 for remote Android clients")
            .define("api.bindAddress", "0.0.0.0");
    public static final ModConfigSpec.ConfigValue<String> API_USERNAME = BUILDER
            .comment("Username for the Android API account")
            .define("api.username", "admin");
    public static final ModConfigSpec.ConfigValue<String> API_PASSWORD = BUILDER
            .comment("Password for the Android API account; use at least 12 characters")
            .define("api.password", "");
    public static final ModConfigSpec.ConfigValue<String> API_AUTH_TOKEN = BUILDER
            .comment("Legacy bearer token accepted for migration; prefer api.username and api.password")
            .define("api.authToken", "");
    public static final ModConfigSpec.BooleanValue CONSOLE_ENABLED = BUILDER
            .comment("Expose the remote command console through the embedded Android API")
            .define("api.consoleEnabled", false);

    public static final ModConfigSpec.BooleanValue HISTORY_ENABLED = BUILDER
            .comment("Store server snapshots on disk for historical Android charts")
            .define("history.enabled", true);
    public static final ModConfigSpec.IntValue HISTORY_INTERVAL_SECONDS = BUILDER
            .comment("Seconds between saved history samples")
            .defineInRange("history.intervalSeconds", 30, 5, 3600);
    public static final ModConfigSpec.IntValue HISTORY_RETENTION_DAYS = BUILDER
            .comment("Number of daily history files to retain")
            .defineInRange("history.retentionDays", 30, 1, 365);
    public static final ModConfigSpec.IntValue HISTORY_MAX_SAMPLES_PER_REQUEST = BUILDER
            .comment("Maximum history samples returned by one API request")
            .defineInRange("history.maxSamplesPerRequest", 720, 10, 10000);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ServerStatsConfig() {}
}
