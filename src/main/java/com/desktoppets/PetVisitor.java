package com.desktoppets;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Schedules rare visits from a non-resident pet species when the user is
 * running with only a single resident pet — so a solo cat occasionally
 * gets to meet a wandering ducky or dog (and vice versa). Mirrors
 * {@link BirdVisitor} in shape: a daemon thread polls every
 * {@link #POLL_INTERVAL_MS} ms and, with probability
 * {@link #SPAWN_PROBABILITY_PER_POLL}, spawns one ground visitor that
 * uses {@link Pet#runVisitorLoop} (look around, idle, depart).
 *
 * <p>Preconditions for a spawn:
 * <ul>
 *   <li>exactly one resident pet is alive — the point is to give the
 *       <i>solo</i> pet some company; in multi-pet setups the residents
 *       already interact with each other;</li>
 *   <li>no visitor (bird or otherwise) is currently alive — at most one
 *       guest at a time so the screen stays calm;</li>
 *   <li>the resident pet is not the same species as the visitor (picked
 *       from the remaining species pool).</li>
 * </ul>
 *
 * <p>Rarity tuning: with {@link #POLL_INTERVAL_MS}=60 s and
 * {@link #SPAWN_PROBABILITY_PER_POLL}=0.04, mean inter-visit time when
 * eligible is ≈ 25 min — visible occasionally but never spammy.
 */
public final class PetVisitor {

    /** Polling interval. */
    private static final long POLL_INTERVAL_MS = 60_000L;

    /** Per-poll spawn probability when all preconditions hold. */
    private static final double SPAWN_PROBABILITY_PER_POLL = 0.04;

    /** Min/max horizontal gap from the anchor pet's center to the visitor's. */
    private static final int MIN_GAP_FROM_PET = 100;
    private static final int MAX_GAP_FROM_PET = 280;

    /** Ground species available as visitors (Bird is handled by BirdVisitor). */
    private static final List<String> POSSIBLE = List.of("cat", "ducky", "dog");

    private PetVisitor() {
    }

    public static void start(PetSupervisor supervisor) {
        Thread t = new Thread(() -> loop(supervisor), "pet-visitor-scheduler");
        t.setDaemon(true);
        t.start();
    }

    private static void loop(PetSupervisor supervisor) {
        Log.info("pet-visitor", "scheduler started");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (supervisor.hasActiveVisitor()) {
                continue;
            }
            List<Pet> live = supervisor.livePets();
            if (live.size() != 1) {
                continue; // only fires for solo-pet setups
            }
            if (ThreadLocalRandom.current().nextDouble() >= SPAWN_PROBABILITY_PER_POLL) {
                continue;
            }
            try {
                trySpawn(supervisor, live.get(0));
            } catch (Throwable t) {
                Log.warn("pet-visitor", "spawn failed: " + t);
            }
        }
    }

    private static void trySpawn(PetSupervisor supervisor, Pet anchor) {
        if (anchor.frame == null) {
            return;
        }
        // Pick a species different from the anchor's species name.
        String anchorSpecies = anchor.name.toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>(POSSIBLE);
        options.removeIf(s -> s.equalsIgnoreCase(anchorSpecies));
        if (options.isEmpty()) {
            return;
        }
        String pick = options.get(ThreadLocalRandom.current().nextInt(options.size()));

        Rectangle mon = anchor.currentMonitorBounds();
        Point anchorLoc = anchor.logicalLocation();
        int anchorMid = anchorLoc.x + anchor.effectiveWidth() / 2;
        int petSize = supervisor.getPetSize();
        int gap = MIN_GAP_FROM_PET
                + ThreadLocalRandom.current().nextInt(MAX_GAP_FROM_PET - MIN_GAP_FROM_PET);
        int dir = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        int targetX = clampToMonitor(anchorMid + dir * gap - petSize / 2, mon, petSize);
        if (overlapsAnyPet(targetX, petSize)) {
            int alt = clampToMonitor(anchorMid - dir * gap - petSize / 2, mon, petSize);
            if (overlapsAnyPet(alt, petSize)) {
                return;
            }
            targetX = alt;
            dir = -dir;
        }
        // Walk in from the side the visitor sits opposite the anchor so the
        // entry walk doesn't cross over the resident pet.
        boolean fromRight = dir > 0;

        Pet visitor;
        try {
            visitor = PetFactory.create(pick);
        } catch (IllegalArgumentException e) {
            Log.warn("pet-visitor", "unknown species '" + pick + "': " + e.getMessage());
            return;
        }
        visitor.markAsVisitor();
        visitor.plannedSpawnMonitor = mon;
        visitor.plannedSpawnTargetX = targetX;
        visitor.plannedSpawnFromRight = fromRight;
        // Ground species: always side-entry (fromAbove is meaningless for
        // pets that can't fly).
        visitor.plannedSpawnFromAbove = false;
        Log.info("pet-visitor",
                "spawning " + pick + " near " + anchor.name
                + " at x=" + targetX
                + " from " + (fromRight ? "right" : "left"));
        supervisor.spawnVisitor(visitor);
    }

    private static int clampToMonitor(int x, Rectangle mon, int petW) {
        int lo = mon.x + 4;
        int hi = Math.max(lo, mon.x + mon.width - petW - 4);
        return Math.max(lo, Math.min(hi, x));
    }

    private static boolean overlapsAnyPet(int x, int petSize) {
        for (Pet p : Pet.activePets()) {
            if (p.frame == null) {
                continue;
            }
            Point loc = p.logicalLocation();
            int pw = p.effectiveWidth();
            if (loc.x < x + petSize && loc.x + pw > x) {
                return true;
            }
        }
        return false;
    }
}
