package com.desktoppets;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Thin facade over log4j2 that preserves the legacy {@code Log.info(tag, msg)} /
 * {@code Log.warn(tag, msg)} call shape used throughout the codebase. The
 * {@code tag} becomes the log4j2 logger name so per-tag levels can be
 * configured in {@code log4j2.xml} (e.g. silence {@code engine:*} loggers
 * while keeping {@code pet:*}).
 *
 * <p>Loggers are cached per tag to avoid the {@link LogManager#getLogger}
 * lookup on every call.
 */
public final class Log {

    private static final ConcurrentMap<String, Logger> LOGGERS = new ConcurrentHashMap<>();

    private Log() {
    }

    public static void info(String tag, String msg) {
        loggerFor(tag).info(msg);
    }

    public static void warn(String tag, String msg) {
        loggerFor(tag).warn(msg);
    }

    public static void error(String tag, String msg) {
        loggerFor(tag).error(msg);
    }

    public static void error(String tag, String msg, Throwable t) {
        loggerFor(tag).error(msg, t);
    }

    public static void debug(String tag, String msg) {
        loggerFor(tag).debug(msg);
    }

    /** Exposed for callers that want the underlying log4j2 logger directly. */
    public static Logger loggerFor(String tag) {
        return LOGGERS.computeIfAbsent(tag, LogManager::getLogger);
    }
}
