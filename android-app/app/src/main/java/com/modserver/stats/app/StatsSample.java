package com.modserver.stats.app;

import org.json.JSONObject;

final class StatsSample {
    final long capturedAtMs;
    final double tps;
    final double mspt;
    final double processCpuPercent;
    final double systemCpuPercent;
    final double memoryPercent;
    final double onlinePlayers;
    final double latencyMs;

    private StatsSample(long capturedAtMs, double tps, double mspt, double processCpuPercent,
                        double systemCpuPercent, double memoryPercent, double onlinePlayers,
                        double latencyMs) {
        this.capturedAtMs = capturedAtMs;
        this.tps = tps;
        this.mspt = mspt;
        this.processCpuPercent = processCpuPercent;
        this.systemCpuPercent = systemCpuPercent;
        this.memoryPercent = memoryPercent;
        this.onlinePlayers = onlinePlayers;
        this.latencyMs = latencyMs;
    }

    static StatsSample fromJson(JSONObject json, long latencyMs) {
        long usedBytes = json.optLong("memoryUsedBytes", 0L);
        long maxBytes = json.optLong("memoryMaxBytes", 0L);
        double memoryPercent = maxBytes > 0
                ? Math.min(100.0, usedBytes * 100.0 / maxBytes)
                : -1.0;
        long capturedAtMs = json.optLong("timestampEpochMs", System.currentTimeMillis());
        return new StatsSample(
                capturedAtMs,
                json.optDouble("estimatedTps", -1.0),
                json.optDouble("mspt", -1.0),
                json.optDouble("processCpuPercent", -1.0),
                json.optDouble("systemCpuPercent", -1.0),
                memoryPercent,
                json.optInt("onlinePlayers", 0),
                latencyMs);
    }

    double valueFor(int metric) {
        return switch (metric) {
            case StatsChartView.METRIC_TPS -> tps;
            case StatsChartView.METRIC_MSPT -> mspt;
            case StatsChartView.METRIC_PROCESS_CPU -> processCpuPercent;
            case StatsChartView.METRIC_SYSTEM_CPU -> systemCpuPercent;
            case StatsChartView.METRIC_MEMORY -> memoryPercent;
            case StatsChartView.METRIC_PLAYERS -> onlinePlayers;
            case StatsChartView.METRIC_LATENCY -> latencyMs;
            default -> -1.0;
        };
    }
}
