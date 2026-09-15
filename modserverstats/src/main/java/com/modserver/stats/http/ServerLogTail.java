package com.modserver.stats.http;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Reads only the new bytes appended to Minecraft's current log file. */
public final class ServerLogTail {
    private static final int MAX_LINES = 3000;
    private static final int MAX_LINE_LENGTH = 4096;
    private static final long INITIAL_TAIL_BYTES = 256L * 1024L;
    private static final long MAX_BYTES_PER_REFRESH = 1024L * 1024L;

    private final Path logFile;
    private final Object lock = new Object();
    private final ArrayDeque<ConsoleLogBuffer.ConsoleLine> lines = new ArrayDeque<>();
    private long sequence;
    private long fileOffset;
    private boolean initialized;

    public ServerLogTail(Path logFile) {
        this.logFile = logFile.toAbsolutePath().normalize();
    }

    public ConsoleLogBuffer.Snapshot readAfter(long afterSequence, int requestedLimit) {
        synchronized (lock) {
            refresh();
            int limit = Math.max(1, Math.min(requestedLimit, 200));
            long firstSequence = lines.isEmpty()
                    ? sequence + 1 : lines.peekFirst().sequence();
            boolean truncated = !lines.isEmpty() && afterSequence < firstSequence - 1;
            List<ConsoleLogBuffer.ConsoleLine> result = new ArrayList<>(Math.min(limit, lines.size()));
            for (ConsoleLogBuffer.ConsoleLine line : lines) {
                if (line.sequence() <= afterSequence) continue;
                result.add(line);
                if (result.size() >= limit) break;
            }
            // The cursor is the last line actually returned. This lets the
            // client page through the initial tail instead of skipping lines
            // when the response is capped by the requested limit.
            long nextCursor = result.isEmpty()
                    ? sequence : result.get(result.size() - 1).sequence();
            return new ConsoleLogBuffer.Snapshot(nextCursor, truncated, result);
        }
    }

    private void refresh() {
        try {
            if (!Files.isRegularFile(logFile)) return;
            long fileSize = Files.size(logFile);
            if (!initialized) {
                initialized = true;
                fileOffset = Math.max(0L, fileSize - INITIAL_TAIL_BYTES);
                readFromOffset(fileSize, true);
                return;
            }
            if (fileSize < fileOffset) {
                lines.clear();
                sequence = 0L;
                fileOffset = 0L;
            }
            if (fileSize > fileOffset) readFromOffset(fileSize, false);
        } catch (IOException | RuntimeException ignored) {
            // The log may rotate or be temporarily unavailable while the server is running.
        }
    }

    private void readFromOffset(long fileSize, boolean discardPartialFirstLine) throws IOException {
        try (RandomAccessFile reader = new RandomAccessFile(logFile.toFile(), "r")) {
            reader.seek(fileOffset);
            if (discardPartialFirstLine && fileOffset > 0L) {
                reader.readLine();
                fileOffset = reader.getFilePointer();
            }

            long startOffset = reader.getFilePointer();
            long maximumOffset = Math.min(fileSize, startOffset + MAX_BYTES_PER_REFRESH);
            while (reader.getFilePointer() < maximumOffset) {
                long lineStart = reader.getFilePointer();
                String rawLine = reader.readLine();
                if (rawLine == null) break;
                long lineEnd = reader.getFilePointer();
                if (lineEnd > fileSize || (lineEnd == fileSize && !hasLineTerminator(reader, lineEnd))) {
                    reader.seek(lineStart);
                    break;
                }
                append(decode(rawLine));
            }
            fileOffset = reader.getFilePointer();
        }
    }

    private static boolean hasLineTerminator(RandomAccessFile reader, long lineEnd) throws IOException {
        if (lineEnd <= 0L) return false;
        long currentPosition = reader.getFilePointer();
        reader.seek(lineEnd - 1L);
        int lastByte = reader.read();
        reader.seek(currentPosition);
        return lastByte == '\n' || lastByte == '\r';
    }

    private static String decode(String rawLine) {
        return new String(rawLine.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    private void append(String text) {
        String line = text == null ? "" : text;
        if (line.length() > MAX_LINE_LENGTH) {
            line = line.substring(0, MAX_LINE_LENGTH) + "...";
        }
        lines.addLast(new ConsoleLogBuffer.ConsoleLine(++sequence, line));
        while (lines.size() > MAX_LINES) lines.removeFirst();
    }
}
