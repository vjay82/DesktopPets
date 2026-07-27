package com.desktoppets;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Watches the desktop's display configuration and drives a reconciliation of
 * the pet rendering backends whenever a monitor is attached / removed or a
 * resolution / DPI change occurs.
 *
 * <p>The pets themselves already query the live {@link GraphicsEnvironment}
 * every behaviour tick, so their movement and edge-clamping follow display
 * changes for free. What does <b>not</b> follow automatically is the cached,
 * per-monitor rendering state:
 * <ul>
 *   <li>the Swing {@link Stage} builds one fullscreen window per
 *       {@link GraphicsDevice} with fixed bounds, and</li>
 *   <li>the DirectComposition backend ({@link DCompBackend} /
 *       {@link DCompStage}) caches a logical→physical monitor map plus a host
 *       window sized once to the virtual-screen bounds.</li>
 * </ul>
 * When the layout changes those caches are stale (windows on monitors that no
 * longer exist, pets clipped by a too-small host window, wrong DPI scaling).
 *
 * <p>There is no public AWT event for "displays changed", so this watcher
 * polls a cheap signature of the current configuration (each device's id,
 * bounds and DPI scale) on a low-frequency daemon timer and, on any change,
 * routes to the active backend's reconciliation entry point
 * ({@link DCompBackend#onDisplaysChanged()} or {@link Stage#refreshDisplays()}).
 * The poll allocates nothing while the layout is stable and never touches the
 * native rendering threads unless something actually changed.
 */
public final class DisplayWatcher {

    /** How often the display signature is sampled. A display reconfiguration
     *  is a rare, human-initiated event, so a one-second cadence detects it
     *  promptly while costing effectively nothing between changes. */
    private static final long POLL_MS = 1000L;

    private static final Object LOCK = new Object();
    private static ScheduledExecutorService exec;
    private static volatile String lastSignature;

    private DisplayWatcher() {
    }

    /** Start the watcher. Idempotent: a second call while already running is a
     *  no-op. Safe to call from any thread. */
    public static void start() {
        synchronized (LOCK) {
            if (exec != null) {
                return;
            }
            lastSignature = signature();
            ScheduledExecutorService e = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pets-display-watch");
                t.setDaemon(true);
                return t;
            });
            e.scheduleWithFixedDelay(DisplayWatcher::poll, POLL_MS, POLL_MS,
                    TimeUnit.MILLISECONDS);
            exec = e;
            Log.info("display", "watcher started");
        }
    }

    /** Stop the watcher and release its timer thread. Idempotent. */
    public static void stop() {
        ScheduledExecutorService e;
        synchronized (LOCK) {
            e = exec;
            exec = null;
        }
        if (e != null) {
            e.shutdownNow();
        }
    }

    private static void poll() {
        String sig;
        try {
            sig = signature();
        } catch (Throwable t) {
            return; // transient headless / display-change race — retry next tick
        }
        if (sig.equals(lastSignature)) {
            return;
        }
        lastSignature = sig;
        Log.info("display", "configuration changed → reconciling stages");
        try {
            if (DCompBackend.isActive()) {
                DCompBackend.onDisplaysChanged();
            } else {
                Stage.refreshDisplays();
            }
        } catch (Throwable t) {
            Log.warn("display", "reconcile failed: " + t);
        }
    }

    /**
     * A compact, order-stable fingerprint of the current display layout:
     * for every attached device its id string, logical bounds and DPI scale.
     * Any monitor add / remove, move, resolution change or DPI change alters
     * the string; nothing else does.
     */
    private static String signature() {
        StringBuilder sb = new StringBuilder(128);
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (GraphicsDevice d : ge.getScreenDevices()) {
            Rectangle b = d.getDefaultConfiguration().getBounds();
            AffineTransform tx = d.getDefaultConfiguration().getDefaultTransform();
            sb.append(d.getIDstring()).append('[')
                    .append(b.x).append(',').append(b.y).append(',')
                    .append(b.width).append('x').append(b.height).append('@')
                    .append(tx.getScaleX()).append('x').append(tx.getScaleY())
                    .append(']');
        }
        return sb.toString();
    }
}
