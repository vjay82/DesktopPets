package com.desktoppets.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;

import com.desktoppets.BirdVisitor;
import com.desktoppets.Config;
import com.desktoppets.Doodle;
import com.desktoppets.Log;
import com.desktoppets.PetSupervisor;
import com.desktoppets.World;

/**
 * Stable public facade for embedding Desktop Pets into another Java
 * application (e.g. autoMATE). All other classes in this project are free
 * to keep evolving without breaking embedders, as long as this class keeps
 * the same signatures.
 *
 * <p>Usage:
 * <pre>{@code
 *   DesktopPetsApi.start(new DesktopPetsConfig()
 *       .setCount("ducky", 2)
 *       .setCount("cat", 1)
 *       .setPetSize(64));
 *   ...
 *   DesktopPetsApi.reconcile(updatedConfig);
 *   ...
 *   DesktopPetsApi.stop();
 * }</pre>
 *
 * <p>Threading: all methods are safe to call from any thread. The internal
 * pet windows live on the Swing EDT and are managed by {@link PetSupervisor}.
 */
public final class DesktopPetsApi {

    private static final Object LOCK = new Object();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static PetSupervisor supervisor;
    private static boolean visitorBirdsStarted;

    private DesktopPetsApi() {
    }

    /**
     * Configuration passed to {@link #start} / {@link #reconcile}.
     * Use the fluent setters to populate. Counts default to zero.
     */
    public static final class DesktopPetsConfig {
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        private int petSize = Config.DEFAULT_SIZE;
        private double activity = Config.DEFAULT_ACTIVITY;
        private boolean visitorBirdsEnabled = false;

        public DesktopPetsConfig setCount(String species, int count) {
            if (species != null) {
                counts.put(species.toLowerCase(java.util.Locale.ROOT), Math.max(0, count));
            }
            return this;
        }

        public DesktopPetsConfig setCounts(Map<String, Integer> all) {
            counts.clear();
            if (all != null) {
                for (Map.Entry<String, Integer> e : all.entrySet()) {
                    setCount(e.getKey(), e.getValue() == null ? 0 : e.getValue());
                }
            }
            return this;
        }

        public DesktopPetsConfig setPetSize(int size) {
            this.petSize = Math.max(16, Math.min(256, size));
            return this;
        }

        public DesktopPetsConfig setActivity(double level) {
            this.activity = Math.max(0.0, Math.min(2.0, level));
            return this;
        }

        public DesktopPetsConfig setVisitorBirdsEnabled(boolean enabled) {
            this.visitorBirdsEnabled = enabled;
            return this;
        }

        public Map<String, Integer> getCounts() {
            return Collections.unmodifiableMap(counts);
        }

        public int getPetSize() {
            return petSize;
        }

        public double getActivity() {
            return activity;
        }

        public boolean isVisitorBirdsEnabled() {
            return visitorBirdsEnabled;
        }

        /** True iff at least one species has a count &gt; 0. */
        public boolean hasAnyPets() {
            for (Integer v : counts.values()) {
                if (v != null && v > 0) return true;
            }
            return false;
        }
    }

    /**
     * Starts the cursor sampler and spawns the pets requested by {@code cfg}.
     * Idempotent: subsequent calls behave like {@link #reconcile}.
     * Does NOT install the built-in tray icon — the embedding application
     * owns the tray.
     */
    public static void start(DesktopPetsConfig cfg) {
        if (cfg == null) cfg = new DesktopPetsConfig();
        synchronized (LOCK) {
            if (!RUNNING.get()) {
                Log.info("api", "DesktopPetsApi.start()");
                // Cursor sampler is a no-op if already started (uses a
                // static guard in World).
                try {
                    World.startCursorSampler();
                } catch (Throwable t) {
                    Log.warn("api", "cursor sampler failed: " + t);
                }
                supervisor = new PetSupervisor();
                RUNNING.set(true);
            }
            applyLocked(cfg);
        }
    }

    /**
     * Applies the given config to a running instance. If the API has not
     * been started yet, this is equivalent to {@link #start}.
     */
    public static void reconcile(DesktopPetsConfig cfg) {
        if (!RUNNING.get()) {
            start(cfg);
            return;
        }
        synchronized (LOCK) {
            applyLocked(cfg == null ? new DesktopPetsConfig() : cfg);
        }
    }

    /**
     * Stops all pets, disposes their windows on the EDT, and releases the
     * supervisor. The cursor sampler thread keeps running (it is harmless
     * and there is no public stop hook for it in the upstream code).
     */
    public static void stop() {
        synchronized (LOCK) {
            if (!RUNNING.get()) return;
            Log.info("api", "DesktopPetsApi.stop()");
            if (supervisor != null) {
                try {
                    supervisor.shutdown();
                } catch (Throwable t) {
                    Log.warn("api", "supervisor.shutdown failed: " + t);
                }
                supervisor = null;
            }
            RUNNING.set(false);
        }
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    /**
     * Returns a preview icon for the given pet species (one of
     * {@code ducky}, {@code cat}, {@code dog}, {@code bird}) rendered from
     * its idle SVG sprite at the requested size. Safe to call before
     * {@link #start}. Returns {@code null} for unknown species.
     */
    public static ImageIcon getPreviewIcon(String species, int size) {
        if (species == null || size <= 0) return null;
        String key = species.toLowerCase(java.util.Locale.ROOT) + "/idle/0";
        return Doodle.icon(key, size);
    }

    // ---------------- internals ----------------

    private static void applyLocked(DesktopPetsConfig cfg) {
        supervisor.setPetSize(cfg.getPetSize());
        supervisor.setActivityLevel(cfg.getActivity());
        supervisor.reconcileCounts(new LinkedHashMap<>(cfg.getCounts()));
        if (cfg.isVisitorBirdsEnabled() && !visitorBirdsStarted) {
            // BirdVisitor.start() registers timers; calling it twice would
            // schedule duplicate visitors, hence the guard.
            SwingUtilities.invokeLater(() -> {
                try {
                    BirdVisitor.start(supervisor);
                } catch (Throwable t) {
                    Log.warn("api", "BirdVisitor.start failed: " + t);
                }
            });
            visitorBirdsStarted = true;
        }
    }
}
