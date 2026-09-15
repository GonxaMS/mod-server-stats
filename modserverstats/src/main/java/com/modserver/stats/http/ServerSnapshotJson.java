package com.modserver.stats.http;

import com.modserver.stats.model.PlayerSnapshot;
import com.modserver.stats.model.ServerSnapshot;

public final class ServerSnapshotJson {
    private ServerSnapshotJson() {}

    public static String toJson(ServerSnapshot snapshot) {
        StringBuilder json = new StringBuilder(512);
        json.append('{')
                .append("\"serverId\":").append(string(snapshot.serverId()))
                .append(",\"timestampEpochMs\":").append(snapshot.timestampEpochMs())
                .append(",\"minecraftVersion\":").append(string(snapshot.minecraftVersion()))
                .append(",\"onlinePlayers\":").append(snapshot.onlinePlayers())
                .append(",\"maxPlayers\":").append(snapshot.maxPlayers())
                .append(",\"players\":[");
        for (int i = 0; i < snapshot.players().size(); i++) {
            if (i > 0) json.append(',');
            PlayerSnapshot player = snapshot.players().get(i);
            json.append('{')
                    .append("\"name\":").append(string(player.name()))
                    .append(",\"uuid\":").append(string(player.uuid()))
                    .append('}');
        }
        return json.append("],\"memoryUsedBytes\":").append(snapshot.memoryUsedBytes())
                .append(",\"memoryMaxBytes\":").append(snapshot.memoryMaxBytes())
                .append(",\"uptimeSeconds\":").append(snapshot.uptimeSeconds())
                .append(",\"mspt\":").append(snapshot.mspt())
                .append(",\"estimatedTps\":").append(snapshot.estimatedTps())
                .append(",\"processCpuPercent\":").append(number(snapshot.processCpuPercent()))
                .append(",\"systemCpuPercent\":").append(number(snapshot.systemCpuPercent()))
                .append('}')
                .toString();
    }

    private static String number(double value) {
        return Double.isFinite(value) && value >= 0.0 ? Double.toString(value) : "null";
    }

    private static String string(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + '"';
    }
}
