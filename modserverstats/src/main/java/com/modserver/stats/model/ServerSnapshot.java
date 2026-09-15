package com.modserver.stats.model;

import java.util.List;

public record ServerSnapshot(
        String serverId,
        long timestampEpochMs,
        String minecraftVersion,
        int onlinePlayers,
        int maxPlayers,
        List<PlayerSnapshot> players,
        long memoryUsedBytes,
        long memoryMaxBytes,
        long uptimeSeconds,
        double mspt,
        double estimatedTps,
        double processCpuPercent,
        double systemCpuPercent) {}
