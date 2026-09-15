package com.modserver.stats.storage;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;

/** Keeps SQLite's internal SQL trace out of the server console. */
public final class SqliteLogging {
    private static final String SQLITE_LOGGER_NAME = "org.sqlite.core.NativeDB";

    private SqliteLogging() {}

    public static void silenceTrace() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        LoggerConfig configuration = context.getConfiguration().getLoggerConfig(SQLITE_LOGGER_NAME);
        if (!SQLITE_LOGGER_NAME.equals(configuration.getName())) {
            context.getConfiguration().addLogger(
                    SQLITE_LOGGER_NAME, new LoggerConfig(SQLITE_LOGGER_NAME, Level.WARN, true));
        } else {
            configuration.setLevel(Level.WARN);
        }
        context.updateLoggers();

        java.util.logging.Logger.getLogger(SQLITE_LOGGER_NAME)
                .setLevel(java.util.logging.Level.WARNING);
    }
}
