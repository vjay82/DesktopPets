package com.desktoppets;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Minimal logger. {@code [HH:mm:ss.SSS] [tag] msg} to stdout (info) or
 * stderr (warn). Cheap enough to leave on permanently.
 */
public final class Log {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {
    }

    public static void info(String tag, String msg) {
        System.out.println("[" + LocalTime.now().format(TS) + "] [" + tag + "] " + msg);
    }

    public static void warn(String tag, String msg) {
        System.err.println("[" + LocalTime.now().format(TS) + "] [" + tag + "] " + msg);
    }
}
