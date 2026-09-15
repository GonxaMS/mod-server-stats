package com.modserver.stats.http;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;

/** Captures a bounded, in-memory view of the server log for the optional Android console. */
public final class ConsoleLogBuffer extends AbstractAppender {
    private static final String APPENDER_NAME = "ModServerStatsConsoleBuffer";
    private static final String SQLITE_LOGGER_NAME = "org.sqlite.core.NativeDB";
    private static final int MAX_LINES = 500;
    private static final int MAX_LINE_LENGTH = 2048;
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

    private final LoggerContext loggerContext;
    private final LoggerConfig rootLoggerConfig;
    private final AtomicLong sequence = new AtomicLong();
    private final Object lock = new Object();
    private final ArrayDeque<ConsoleLine> lines = new ArrayDeque<>();
    private volatile boolean closed;

    private ConsoleLogBuffer(LoggerContext loggerContext, LoggerConfig rootLoggerConfig) {
        super(APPENDER_NAME, null, (org.apache.logging.log4j.core.Layout<? extends Serializable>) null, true);
        this.loggerContext = loggerContext;
        this.rootLoggerConfig = rootLoggerConfig;
    }

    /**
     * SQLite's bundled driver logs every SQL statement at TRACE when the server
     * logging level is verbose. Keep warnings and errors, but hide the internal
     * statement trace from both the server console and the Android console.
     */
    public static void silenceSqliteTrace() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        LoggerConfig configuration = context.getConfiguration().getLoggerConfig(SQLITE_LOGGER_NAME);
        if (!SQLITE_LOGGER_NAME.equals(configuration.getName())) {
            context.getConfiguration().addLogger(
                    SQLITE_LOGGER_NAME, new LoggerConfig(SQLITE_LOGGER_NAME, Level.WARN, true));
        } else {
            configuration.setLevel(Level.WARN);
        }
        context.updateLoggers();

        // The driver falls back to java.util.logging when SLF4J is not present.
        java.util.logging.Logger.getLogger(SQLITE_LOGGER_NAME)
                .setLevel(java.util.logging.Level.WARNING);
    }

    public static ConsoleLogBuffer install() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        LoggerConfig root = context.getConfiguration().getRootLogger();
        ConsoleLogBuffer buffer = new ConsoleLogBuffer(context, root);
        buffer.start();
        root.addAppender(buffer, Level.ALL, null);
        context.updateLoggers();
        return buffer;
    }

    @Override
    public void append(LogEvent event) {
        if (closed || event == null) return;
        if (isSqliteTrace(event)) return;

        String time;
        synchronized (TIME_FORMAT) {
            time = TIME_FORMAT.format(new Date(event.getTimeMillis()));
        }
        String level = event.getLevel() == null ? "INFO" : event.getLevel().name();
        String message = event.getMessage() == null
                ? "" : event.getMessage().getFormattedMessage();
        String prefix = "[" + time + "] [" + level + "] ";
        appendText(prefix + (message == null ? "" : message));

        if (event.getThrown() != null) {
            StringWriter stack = new StringWriter();
            event.getThrown().printStackTrace(new PrintWriter(stack));
            appendText(stack.toString());
        }
    }

    private static boolean isSqliteTrace(LogEvent event) {
        if (event.getLevel() != Level.TRACE) return false;
        String loggerName = event.getLoggerName();
        if (loggerName != null && loggerName.startsWith("org.sqlite")) return true;
        String message = event.getMessage() == null
                ? "" : event.getMessage().getFormattedMessage();
        return message != null && message.contains("[SQLite ");
    }

    public Snapshot readAfter(long afterSequence, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        synchronized (lock) {
            long firstSequence = lines.isEmpty() ? sequence.get() + 1 : lines.peekFirst().sequence();
            boolean truncated = !lines.isEmpty() && afterSequence < firstSequence - 1;
            List<ConsoleLine> result = new ArrayList<>(Math.min(limit, lines.size()));
            for (ConsoleLine line : lines) {
                if (line.sequence() <= afterSequence) continue;
                result.add(line);
                if (result.size() >= limit) break;
            }
            return new Snapshot(sequence.get(), truncated, result);
        }
    }

    private void appendText(String text) {
        String[] entries = (text == null ? "" : text).split("\\R", -1);
        synchronized (lock) {
            for (String entry : entries) {
                String line = entry.length() > MAX_LINE_LENGTH
                        ? entry.substring(0, MAX_LINE_LENGTH) + "..."
                        : entry;
                lines.addLast(new ConsoleLine(sequence.incrementAndGet(), line));
                while (lines.size() > MAX_LINES) lines.removeFirst();
            }
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        try {
            rootLoggerConfig.removeAppender(getName());
            loggerContext.updateLoggers();
        } catch (RuntimeException ignored) {
            // Log4j may already be shutting down when the Minecraft server stops.
        } finally {
            stop();
        }
    }

    public record ConsoleLine(long sequence, String text) {}

    public record Snapshot(long cursor, boolean truncated, List<ConsoleLine> lines) {}
}
