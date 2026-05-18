package com.desktoppets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the set of live pet threads and reconciles them with a desired list of
 * pet names (from the settings dialog or initial config load). Also exposes
 * pause / pet-size / activity-level controls applied uniformly to every pet.
 *
 * <p>Visitor pets (the wandering {@link Bird}) live in a separate
 * {@link #visitors} list and are NOT part of the resident roster: they are
 * spawned ad-hoc by {@link BirdVisitor}, self-dispose after a short stay,
 * and ignore the settings dialog's pet checkboxes. They still inherit the
 * resident pet size / activity-level so they visually match.
 */
public final class PetSupervisor {

    /** Names that {@link #reconcile} silently filters out of {@code wanted}
     *  because they are no longer user-selectable resident pets (e.g.
     *  {@code bird}, which is now a visitor-only species). Kept as a
     *  separate set so legacy {@code config.txt} files that still list a
     *  removed name don't crash and don't silently spawn it as a regular
     *  pet. */
    private static final Set<String> NON_RESIDENT = Set.of("bird");

    private final Map<String, PetHandle> live = new HashMap<>();
    private final CopyOnWriteArrayList<PetHandle> visitors = new CopyOnWriteArrayList<>();
    private boolean paused = false;
    private int petSize = Config.DEFAULT_SIZE;
    private double activityLevel = Config.DEFAULT_ACTIVITY;

    public PetSupervisor() {
        this.petSize = Config.readPetSize();
        this.activityLevel = Config.readActivity();
        Log.info("supervisor",
                "loaded petSize=" + petSize + " activity=" + activityLevel);
    }

    public void reconcile(List<String> wanted) {
        // Collapse the legacy "list of species names" form into the
        // species->count form. Duplicates in the list spawn that many
        // instances of the species, matching the behaviour of
        // {@link #reconcileCounts}.
        Map<String, Integer> counts = new HashMap<>();
        for (String w : wanted) {
            if (w == null) continue;
            String key = w.toLowerCase(Locale.ROOT);
            counts.merge(key, 1, Integer::sum);
        }
        reconcileCounts(counts);
    }

    /**
     * Reconcile to a species->count mapping. Each species can have multiple
     * live instances; instances are keyed internally as {@code species#index}
     * so that incrementing or decrementing a count only spawns/stops the
     * delta (instances 0..count-1 are kept, the rest are stopped).
     *
     * <p>Visitor-only species (currently {@code bird}) are silently dropped
     * the same way as in {@link #reconcile(List)}.
     */
    public void reconcileCounts(Map<String, Integer> wantedCounts) {
        // Build desired set of composite keys "<species>#<index>"
        Map<String, String> wantedKeyToSpecies = new HashMap<>();
        for (Map.Entry<String, Integer> e : wantedCounts.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            String species = e.getKey().toLowerCase(Locale.ROOT);
            if (NON_RESIDENT.contains(species)) continue;
            int n = Math.max(0, e.getValue());
            for (int i = 0; i < n; i++) {
                wantedKeyToSpecies.put(species + "#" + i, species);
            }
        }
        Log.info("supervisor", "reconcileCounts â†’ " + wantedKeyToSpecies.keySet());

        List<PetHandle> toStop = new ArrayList<>();
        List<PetHandle> toStart = new ArrayList<>();
        synchronized (this) {
            for (String key : new HashSet<>(live.keySet())) {
                if (!wantedKeyToSpecies.containsKey(key)) {
                    toStop.add(live.remove(key));
                }
            }
            for (Map.Entry<String, String> e : wantedKeyToSpecies.entrySet()) {
                String key = e.getKey();
                String species = e.getValue();
                if (live.containsKey(key)) {
                    continue;
                }
                try {
                    Pet pet = PetFactory.create(species);
                    pet.paused.set(paused);
                    pet.activityLevel = activityLevel;
                    pet.setSize(petSize);
                    Thread t = new Thread(pet, "pet-" + key);
                    t.setDaemon(true);
                    PetHandle h = new PetHandle(pet, t);
                    live.put(key, h);
                    toStart.add(h);
                } catch (IllegalArgumentException ex) {
                    Log.warn("supervisor", "skipping unknown pet: " + ex.getMessage());
                }
            }
        }
        for (PetHandle h : toStop) {
            h.stop();
        }
        for (PetHandle h : toStart) {
            h.thread.start();
        }
    }

    public synchronized void setPaused(boolean p) {
        this.paused = p;
        for (PetHandle h : live.values()) {
            h.pet.paused.set(p);
        }
        Log.info("supervisor", "paused=" + p);
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    public synchronized int getPetSize() {
        return petSize;
    }

    public synchronized void setPetSize(int size) {
        int clamped = Math.max(16, Math.min(256, size));
        this.petSize = clamped;
        for (PetHandle h : live.values()) {
            h.pet.setSize(clamped);
        }
        // Match any active visitor (e.g. a bird mid-perch) so it doesn't
        // look out-of-scale when the user resizes residents.
        for (PetHandle h : visitors) {
            h.pet.setSize(clamped);
        }
    }

    public synchronized double getActivityLevel() {
        return activityLevel;
    }

    public synchronized void setActivityLevel(double level) {
        double clamped = Math.max(0.0, Math.min(2.0, level));
        this.activityLevel = clamped;
        for (PetHandle h : live.values()) {
            h.pet.activityLevel = clamped;
        }
        for (PetHandle h : visitors) {
            h.pet.activityLevel = clamped;
        }
        Log.info("supervisor", "activityLevel=" + clamped);
    }

    public synchronized void shutdown() {
        for (PetHandle h : live.values()) {
            h.stop();
        }
        live.clear();
        for (PetHandle h : visitors) {
            h.stop();
        }
        visitors.clear();
    }

    // ---------------- visitor pets ----------------

    /**
     * Spawn a visitor pet (currently always a {@link Bird}, but the API is
     * species-agnostic). The caller is expected to have configured the
     * pet's plannedSpawn* fields before calling. The visitor runs on its
     * own daemon thread and self-disposes via {@link Pet#runVisitorLoop}
     * after its stay; a reaper thread removes the handle once that
     * completes.
     */
    public void spawnVisitor(Pet pet) {
        pet.activityLevel = activityLevel;
        pet.setSize(petSize);
        pet.paused.set(paused);
        Thread t = new Thread(pet,
                "pet-visitor-" + pet.name + "-" + Long.toHexString(System.nanoTime()));
        t.setDaemon(true);
        PetHandle h = new PetHandle(pet, t);
        visitors.add(h);
        t.start();
        Thread reaper = new Thread(() -> {
            try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            visitors.remove(h);
        }, "visitor-reaper-" + pet.name);
        reaper.setDaemon(true);
        reaper.start();
    }

    /** True iff at least one visitor pet is currently alive. */
    public boolean hasActiveVisitor() {
        return !visitors.isEmpty();
    }

    /** Snapshot of currently-live resident pets (visitors NOT included). */
    public synchronized List<Pet> livePets() {
        List<Pet> out = new ArrayList<>(live.size());
        for (PetHandle h : live.values()) {
            if (h.pet.frame != null) {
                out.add(h.pet);
            }
        }
        return out;
    }

    private record PetHandle(Pet pet, Thread thread) {
        void stop() {
            thread.interrupt();
            pet.disposeWindow();
        }
    }
}
