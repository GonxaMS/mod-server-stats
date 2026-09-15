package com.modserver.stats.service;

import com.modserver.stats.config.ServerStatsConfig;
import com.modserver.stats.model.PlayerSnapshot;
import com.modserver.stats.model.ServerSnapshot;
import net.minecraft.server.MinecraftServer;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;

public final class ServerStatsCollector {
    private final com.sun.management.OperatingSystemMXBean operatingSystem =
            ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);

    public ServerSnapshot collect(MinecraftServer server) {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
        double tps = Math.min(20.0, mspt <= 0.0 ? 20.0 : 1000.0 / mspt);
        double processCpuPercent = toPercent(operatingSystem.getProcessCpuLoad());
        double systemCpuPercent = toPercent(operatingSystem.getCpuLoad());
        ArrayList<PlayerSnapshot> players = new ArrayList<>();
        server.getPlayerList().getPlayers().forEach(player ->
                players.add(new PlayerSnapshot(player.getName().getString(), player.getUUID().toString())));
        return new ServerSnapshot(ServerStatsConfig.SERVER_ID.get(), System.currentTimeMillis(),
                server.getServerVersion(), players.size(), server.getMaxPlayers(), players,
                used, runtime.maxMemory(), server.getTickCount() / 20L, mspt, tps,
                processCpuPercent, systemCpuPercent);
    }

    private static double toPercent(double load) {
        if (!Double.isFinite(load) || load < 0.0) {
            return -1.0;
        }
        return Math.min(100.0, load * 100.0);
    }
}
