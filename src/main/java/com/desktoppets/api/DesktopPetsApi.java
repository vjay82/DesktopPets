package com.desktoppets.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;

import com.desktoppets.BirdVisitor;
import com.desktoppets.Config;
import com.desktoppets.Doodle;
import com.desktoppets.Log;
import com.desktoppets.Pet;
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
        /** Optional per-pet hue rotation in degrees [0, 360). Outer key is
         *  the species id, inner key is the 0-based instance index within
         *  that species. Absent / missing entries default to {@code 0.0}
         *  (no tinting, backwards-compatible). */
        private final Map<String, Map<Integer, Double>> hueByPet = new LinkedHashMap<>();
        /** Optional per-pet size multiplier in {@code (0.0, 2.0]}, applied
         *  on top of the global {@link #petSize}. Outer key is the
         *  species id, inner key is the 0-based instance index. Absent
         *  entries default to {@code 1.0} (full-size adult). Used to
         *  spawn occasional "child" instances at e.g. {@code 0.65} so a
         *  roster of the same species looks like a small family rather
         *  than identical-twin clones. */
        private final Map<String, Map<Integer, Double>> scaleByPet = new LinkedHashMap<>();
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

        /**
         * Set a per-pet hue rotation in degrees. Negative values and values
         * outside {@code [0, 360)} are normalised. Embedders are expected
         * to derive {@code degrees} deterministically from
         * {@code (species, index)} so the same pet keeps its colour
         * across sessions.
         *
         * <p>{@code degrees == 0} clears any prior entry (no tinting).
         */
        public DesktopPetsConfig setHue(String species, int index, double degrees) {
            if (species == null || index < 0) return this;
            String id = species.toLowerCase(java.util.Locale.ROOT);
            double normalised = ((degrees % 360.0) + 360.0) % 360.0;
            if (normalised < 0.5 || normalised > 359.5) {
                Map<Integer, Double> m = hueByPet.get(id);
                if (m != null) {
                    m.remove(index);
                    if (m.isEmpty()) hueByPet.remove(id);
                }
            } else {
                hueByPet.computeIfAbsent(id, k -> new LinkedHashMap<>())
                        .put(index, normalised);
            }
            return this;
        }

        /** Returns the hue rotation in degrees for the given pet instance,
         *  or {@code 0.0} if not set. */
        public double getHue(String species, int index) {
            if (species == null) return 0.0;
            Map<Integer, Double> m = hueByPet.get(species.toLowerCase(java.util.Locale.ROOT));
            if (m == null) return 0.0;
            Double v = m.get(index);
            return v == null ? 0.0 : v;
        }

        /** Internal: snapshot of the hue map, keyed by composite
         *  {@code "species#index"} string for direct lookup by
         *  {@link PetSupervisor}. */
        Map<String, Double> hueByKey() {
            Map<String, Double> out = new LinkedHashMap<>();
            for (Map.Entry<String, Map<Integer, Double>> e : hueByPet.entrySet()) {
                String species = e.getKey();
                for (Map.Entry<Integer, Double> ie : e.getValue().entrySet()) {
                    out.put(species + "#" + ie.getKey(), ie.getValue());
                }
            }
            return out;
        }

        /**
         * Set a per-pet size multiplier on top of the global pet size
         * (see {@link #setPetSize(int)}). {@code 1.0} = full-size (the
         * default for any pet without an explicit entry), values below
         * {@code 1.0} render the pet smaller (a "child" of its species).
         * Values are clamped to {@code [0.25, 2.0]}; {@code 1.0} clears
         * any prior entry.
         *
         * <p>Embedders are expected to derive {@code scale} deterministically
         * from {@code (species, index)} so a given pet keeps its scale
         * across sessions / reconciles, the same way {@link #setHue} is
         * used for stable per-pet tinting.
         */
        public DesktopPetsConfig setScale(String species, int index, double scale) {
            if (species == null || index < 0) return this;
            String id = species.toLowerCase(java.util.Locale.ROOT);
            double clamped = scale;
            if (clamped < 0.25) clamped = 0.25;
            if (clamped > 2.0) clamped = 2.0;
            // Treat values very close to 1.0 as "no override" so embedders
            // can call setScale unconditionally without polluting the map
            // with no-op entries.
            if (Math.abs(clamped - 1.0) < 0.01) {
                Map<Integer, Double> m = scaleByPet.get(id);
                if (m != null) {
                    m.remove(index);
                    if (m.isEmpty()) scaleByPet.remove(id);
                }
            } else {
                scaleByPet.computeIfAbsent(id, k -> new LinkedHashMap<>())
                        .put(index, clamped);
            }
            return this;
        }

        /** Returns the size multiplier for the given pet instance, or
         *  {@code 1.0} if not set. */
        public double getScale(String species, int index) {
            if (species == null) return 1.0;
            Map<Integer, Double> m = scaleByPet.get(species.toLowerCase(java.util.Locale.ROOT));
            if (m == null) return 1.0;
            Double v = m.get(index);
            return v == null ? 1.0 : v;
        }

        /** Internal: snapshot of the scale map, keyed by composite
         *  {@code "species#index"} string for direct lookup by
         *  {@link PetSupervisor}. */
        Map<String, Double> scaleByKey() {
            Map<String, Double> out = new LinkedHashMap<>();
            for (Map.Entry<String, Map<Integer, Double>> e : scaleByPet.entrySet()) {
                String species = e.getKey();
                for (Map.Entry<Integer, Double> ie : e.getValue().entrySet()) {
                    out.put(species + "#" + ie.getKey(), ie.getValue());
                }
            }
            return out;
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

    /**
     * Lightweight descriptor for a pet species exposed via
     * {@link #listAvailablePets()}. Lets embedding applications (notably
     * autoMATE's settings dialog) discover the catalogue at runtime instead
     * of hard-coding species names locally.
     */
    public static final class PetEntry {
        private final String id;
        private final String displayName;

        public PetEntry(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        /** Lower-case species id (e.g. {@code "ducky"}) — same string the
         *  {@link DesktopPetsConfig#setCount(String, int)} API expects. */
        public String id() {
            return id;
        }

        /** Human-readable name suitable for UI labels (e.g. {@code "Ducky"}). */
        public String displayName() {
            return displayName;
        }

        /** Preview icon rendered at {@code size} px from the idle sprite. */
        public ImageIcon previewIcon(int size) {
            return getPreviewIcon(id, size);
        }
    }

    /**
     * Returns the catalogue of user-selectable resident pet species. Visitor-
     * only species (currently {@code bird}, spawned ad-hoc by
     * {@link BirdVisitor}) are NOT included — they aren't valid keys for
     * {@link DesktopPetsConfig#setCount(String, int)}.
     *
     * <p>The list is stable across calls and may be queried before
     * {@link #start}. Embedding apps should call this once when building
     * their pet-selection UI so new species added to this library
     * automatically appear without code changes on their side.
     */
    public static List<PetEntry> listAvailablePets() {
        return List.of(
                new PetEntry("ducky", "Ducky"),
                new PetEntry("cat",   "Cat"),
                new PetEntry("dog",   "Dog"));
    }

    /**
     * Globally suspend or resume all currently-spawned resident pets.
     * <p>When suspending, each pet walks off the nearest edge of its monitor
     * and then stops behaving (no idling, no activities, no topmost
     * reassertion). When resuming, each pet walks back in from the same edge
     * to roughly where it left.
     *
     * <p>The pet roster is preserved across suspend/resume (no spawn/dispose,
     * no thread churn), so this is cheap to toggle frequently — e.g. from a
     * 10-second Teams-meeting / screen-presenting watchdog.
     *
     * <p>Idempotent and safe to call from any thread. No-op if the API has
     * not been started.
     */
    public static void setSuspended(boolean suspended) {
        synchronized (LOCK) {
            if (!RUNNING.get() || supervisor == null) return;
            for (Pet p : supervisor.livePets()) {
                if (suspended) {
                    p.requestHide();
                } else {
                    p.requestShow();
                }
            }
        }
    }

    // ---------------- internals ----------------

    private static void applyLocked(DesktopPetsConfig cfg) {
        supervisor.setPetSize(cfg.getPetSize());
        supervisor.setActivityLevel(cfg.getActivity());
        supervisor.reconcileCounts(new LinkedHashMap<>(cfg.getCounts()),
                cfg.hueByKey(), cfg.scaleByKey());
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
