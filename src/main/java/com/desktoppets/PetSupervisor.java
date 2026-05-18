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
        Set<String> wantedSet = new HashSet<>();
        for (String w : wanted) {
            String key = w.toLowerCase(Locale.ROOT);
            // Drop visitor-only / removed species silently so legacy
            // config.txt entries (e.g. "bird" before it became a visitor)
            // don't fail or spawn unexpectedly.
            if (NON_RESIDENT.contains(key)) {
                continue;
            }
            wantedSet.add(key);
        }
        Log.info("supervisor", "reconcile â†’ " + wantedSet);

        // Decide everything under the lock, but run blocking work (thread
        // start, disposeWindow which round-trips to the EDT) outside it so a
        // hung EDT doesn't deadlock other reconcile/setSize callers.
        List<PetHandle> toStop = new ArrayList<>();
        List<PetHandle> toStart = new ArrayList<>();
        synchronized (this) {
            for (String name : new HashSet<>(live.keySet())) {
                if (!wantedSet.contains(name)) {
                    toStop.add(live.remove(name));
                }
            }
            for (String name : wantedSet) {
                if (live.containsKey(name)) {
                    continue;
                }
                try {
                    Pet pet = PetFactory.create(name);
                    pet.paused.set(paused);
                    pet.activityLevel = activityLevel;
                    pet.setSize(petSize);
                    Thread t = new Thread(pet, "pet-" + name);
                    t.setDaemon(true);
                    PetHandle h = new PetHandle(pet, t);
                    live.put(name, h);
                    toStart.add(h);
                } catch (IllegalArgumentException e) {
                    Log.warn("supervisor", "skipping unknown pet: " + e.getMessage());
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
