package com.desktoppets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Owns the set of live pet threads and reconciles them with a desired list of
 * pet names (from the settings dialog or initial config load). Also exposes
 * pause / pet-size / activity-level controls applied uniformly to every pet.
 */
public final class PetSupervisor {

    private final Map<String, PetHandle> live = new HashMap<>();
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
            wantedSet.add(w.toLowerCase(Locale.ROOT));
        }
        Log.info("supervisor", "reconcile → " + wantedSet);

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
        Log.info("supervisor", "activityLevel=" + clamped);
    }

    public synchronized void shutdown() {
        for (PetHandle h : live.values()) {
            h.stop();
        }
        live.clear();
    }

    private record PetHandle(Pet pet, Thread thread) {
        void stop() {
            thread.interrupt();
            pet.disposeWindow();
        }
    }
}
