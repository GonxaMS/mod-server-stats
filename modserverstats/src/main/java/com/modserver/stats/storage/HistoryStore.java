package com.modserver.stats.storage;

import com.modserver.stats.ModServerStats;
import com.modserver.stats.model.ServerSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class HistoryStore implements AutoCloseable {
    private final Path databasePath;
    private final int retentionDays;
    private final int maxSamplesPerRequest;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "modserverstats-history-writer");
        thread.setDaemon(true);
        return thread;
    });
    private Connection writeConnection;
    private LocalDate lastCleanupDate;

    public HistoryStore(Path directory, int retentionDays, int maxSamplesPerRequest) throws IOException {
        Path historyDirectory = directory.toAbsolutePath().normalize();
        Files.createDirectories(historyDirectory);
        this.databasePath = historyDirectory.resolve("stats.db");
        this.retentionDays = retentionDays;
        this.maxSamplesPerRequest = maxSamplesPerRequest;
        try {
            Class.forName("org.sqlite.JDBC");
            writeConnection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
            configure(writeConnection);
            createSchema(writeConnection);
            deleteExpiredSamples(writeConnection);
            lastCleanupDate = LocalDate.now();
            ModServerStats.LOGGER.info("SQLite history storage ready at {}", databasePath);
        } catch (ClassNotFoundException | SQLException error) {
            closeQuietly();
            throw new IOException("Could not initialize SQLite history storage", error);
        }
    }

    public void append(ServerSnapshot snapshot) {
        writer.execute(() -> {
            try {
                if (!LocalDate.now().equals(lastCleanupDate)) {
                    deleteExpiredSamples(writeConnection);
                    lastCleanupDate = LocalDate.now();
                }
                try (PreparedStatement statement = writeConnection.prepareStatement("""
                        INSERT OR REPLACE INTO samples (
                            timestamp_epoch_ms, server_id, minecraft_version, online_players, max_players,
                            memory_used_bytes, memory_max_bytes, uptime_seconds, mspt, estimated_tps,
                            process_cpu_percent, system_cpu_percent
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setLong(1, snapshot.timestampEpochMs());
                    statement.setString(2, snapshot.serverId());
                    statement.setString(3, snapshot.minecraftVersion());
                    statement.setInt(4, snapshot.onlinePlayers());
                    statement.setInt(5, snapshot.maxPlayers());
                    statement.setLong(6, snapshot.memoryUsedBytes());
                    statement.setLong(7, snapshot.memoryMaxBytes());
                    statement.setLong(8, snapshot.uptimeSeconds());
                    statement.setDouble(9, snapshot.mspt());
                    statement.setDouble(10, snapshot.estimatedTps());
                    setNullableDouble(statement, 11, snapshot.processCpuPercent());
                    setNullableDouble(statement, 12, snapshot.systemCpuPercent());
                    statement.executeUpdate();
                }
            } catch (SQLException error) {
                ModServerStats.LOGGER.warn("Could not write server history to SQLite: {}", error.getMessage());
            }
        });
    }

    public String readRecent(int requestedMinutes, int requestedLimit) {
        int minutes = Math.max(1, Math.min(requestedMinutes, retentionDays * 24 * 60));
        long until = System.currentTimeMillis();
        long from = until - minutes * 60_000L;
        return readRange(from, until, requestedLimit);
    }

    /** Reads an explicit time range, constrained to the configured retention period. */
    public String readRange(long requestedFrom, long requestedUntil, int requestedLimit) {
        long now = System.currentTimeMillis();
        long earliest = now - retentionDays * 86_400_000L;
        long from = Math.max(earliest, requestedFrom);
        long until = Math.min(now, requestedUntil);
        if (until < from) {
            until = from;
        }
        int limit = Math.max(1, Math.min(requestedLimit, maxSamplesPerRequest));
        ArrayDeque<String> samples = new ArrayDeque<>();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT timestamp_epoch_ms, server_id, minecraft_version, online_players, max_players,
                            memory_used_bytes, memory_max_bytes, uptime_seconds, mspt, estimated_tps,
                            process_cpu_percent, system_cpu_percent
                     FROM samples
                     WHERE timestamp_epoch_ms BETWEEN ? AND ?
                     ORDER BY timestamp_epoch_ms DESC
                     LIMIT ?
                     """)) {
            configure(connection);
            statement.setLong(1, from);
            statement.setLong(2, until);
            statement.setInt(3, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    samples.addFirst(rowToJson(rows));
                }
            }
        } catch (SQLException error) {
            ModServerStats.LOGGER.warn("Could not read SQLite history: {}", error.getMessage());
        }

        StringBuilder json = new StringBuilder(samples.size() * 220 + 80);
        json.append("{\"fromEpochMs\":").append(from)
                .append(",\"toEpochMs\":").append(until)
                .append(",\"samples\":[");
        boolean first = true;
        for (String sample : samples) {
            if (!first) json.append(',');
            json.append(sample);
            first = false;
        }
        return json.append("]}").toString();
    }

    private void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS samples (
                        timestamp_epoch_ms INTEGER PRIMARY KEY,
                        server_id TEXT NOT NULL,
                        minecraft_version TEXT NOT NULL,
                        online_players INTEGER NOT NULL,
                        max_players INTEGER NOT NULL,
                        memory_used_bytes INTEGER NOT NULL,
                        memory_max_bytes INTEGER NOT NULL,
                        uptime_seconds INTEGER NOT NULL,
                        mspt REAL NOT NULL,
                        estimated_tps REAL NOT NULL,
                        process_cpu_percent REAL,
                        system_cpu_percent REAL
                    )
                    """);
        }
    }

    private void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }

    private void deleteExpiredSamples(Connection connection) throws SQLException {
        long cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM samples WHERE timestamp_epoch_ms < ?")) {
            statement.setLong(1, cutoff);
            statement.executeUpdate();
        }
    }

    private static void setNullableDouble(PreparedStatement statement, int index, double value)
            throws SQLException {
        if (Double.isFinite(value) && value >= 0.0) {
            statement.setDouble(index, value);
        } else {
            statement.setNull(index, java.sql.Types.REAL);
        }
    }

    private static String rowToJson(ResultSet row) throws SQLException {
        return new StringBuilder(256)
                .append('{')
                .append("\"serverId\":").append(string(row.getString("server_id")))
                .append(",\"timestampEpochMs\":").append(row.getLong("timestamp_epoch_ms"))
                .append(",\"minecraftVersion\":").append(string(row.getString("minecraft_version")))
                .append(",\"onlinePlayers\":").append(row.getInt("online_players"))
                .append(",\"maxPlayers\":").append(row.getInt("max_players"))
                .append(",\"memoryUsedBytes\":").append(row.getLong("memory_used_bytes"))
                .append(",\"memoryMaxBytes\":").append(row.getLong("memory_max_bytes"))
                .append(",\"uptimeSeconds\":").append(row.getLong("uptime_seconds"))
                .append(",\"mspt\":").append(row.getDouble("mspt"))
                .append(",\"estimatedTps\":").append(row.getDouble("estimated_tps"))
                .append(",\"processCpuPercent\":").append(nullableDouble(row, "process_cpu_percent"))
                .append(",\"systemCpuPercent\":").append(nullableDouble(row, "system_cpu_percent"))
                .append('}')
                .toString();
    }

    private static String nullableDouble(ResultSet row, String column) throws SQLException {
        double value = row.getDouble(column);
        return row.wasNull() ? "null" : Double.toString(value);
    }

    private static String string(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + '"';
    }

    private void closeQuietly() {
        if (writeConnection == null) return;
        try {
            writeConnection.close();
        } catch (SQLException ignored) {
            // Nothing useful can be done during construction failure.
        }
        writeConnection = null;
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(2, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
        closeQuietly();
    }
}
