package com.desktoppets;

import java.awt.TrayIcon;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tiny indirection so {@link BehaviorEngine} can fire need-crisis
 * notifications without coupling to {@link TrayApp}. {@code TrayApp}
 * registers its {@link TrayIcon} once on startup; if no tray is available
 * we fall back to a {@link System#err} log.
 */
public final class Toasts {

    private static final AtomicReference<TrayIcon> SINK = new AtomicReference<>();

    private Toasts() {
    }

    public static void bind(TrayIcon icon) {
        SINK.set(icon);
    }

    public static void notify(String caption, String message) {
        TrayIcon icon = SINK.get();
        if (icon != null) {
            try {
                icon.displayMessage(caption, message, TrayIcon.MessageType.INFO);
                return;
            } catch (Throwable t) {
                // Some platforms don't support balloons; fall through to stderr.
            }
        }
        System.err.println("[toast] " + caption + " — " + message);
    }
}
