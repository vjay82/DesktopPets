package com.desktoppets;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Schedules occasional visits from a wild {@link Bird}. Runs on a single
 * daemon thread that wakes every {@link #POLL_INTERVAL_MS} milliseconds
 * and, with probability {@link #SPAWN_PROBABILITY_PER_POLL}, asks the
 * supervisor to spawn a Bird at a column near (but not overlapping) a
 * randomly-chosen resident pet.
 *
 * <p>Preconditions for a spawn:
 * <ul>
 *   <li>at least one resident pet is alive ({@link
 *       PetSupervisor#livePets()} non-empty) — without an "anchor" pet to
 *       sit near there is nothing for the visitor to be a visitor TO;</li>
 *   <li>no visitor is currently alive ({@link
 *       PetSupervisor#hasActiveVisitor()} false) — at most one bird at a
 *       time keeps the gag rare and avoids the chaos of multiple hunts.</li>
 * </ul>
 *
 * <p>The scheduler does not own any state across spawns: each pass either
 * spawns one bird (which then self-disposes via {@link
 * Pet#runVisitorLoop} after its stay or after being scared) or does
 * nothing.
 */
public final class BirdVisitor {

    /** How often the scheduler wakes to consider spawning a visitor. */
    private static final long POLL_INTERVAL_MS = 8_000L;

    /** Per-poll spawn probability when both preconditions hold. With an
     *  8 s poll interval, 0.10 → mean inter-visit time ≈ 80 s of
     *  eligible time; the bird's own stay window (~10-30 s) is excluded
     *  from "eligible" by the visitor-already-alive check. */
    private static final double SPAWN_PROBABILITY_PER_POLL = 0.10;

    /** Horizontal gap from the anchor pet's center to the visitor's center,
     *  in logical pixels. The bird should land NEAR the anchor (close
     *  enough for the resident to notice and run over) but not on top
     *  of it. */
    private static final int MIN_GAP_FROM_PET = 80;
    private static final int MAX_GAP_FROM_PET = 220;

    private BirdVisitor() {
    }

    /** Start the scheduler. Idempotent in spirit — repeated calls would
     *  start additional threads, so {@link Main} only calls it once. */
    public static void start(PetSupervisor supervisor) {
        Thread t = new Thread(() -> loop(supervisor), "bird-visitor-scheduler");
        t.setDaemon(true);
        t.start();
    }

    private static void loop(PetSupervisor supervisor) {
        Log.info("bird-visitor", "scheduler started");
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
            if (live.isEmpty()) {
                continue;
            }
            if (ThreadLocalRandom.current().nextDouble() >= SPAWN_PROBABILITY_PER_POLL) {
                continue;
            }
            try {
                trySpawn(supervisor, live);
            } catch (Throwable t) {
                // Never let a one-off scheduling failure kill the loop —
                // the bird is a non-essential cosmetic feature.
                Log.warn("bird-visitor", "spawn failed: " + t);
            }
        }
    }

    /**
     * Compute a target column ~ {@link #MIN_GAP_FROM_PET}-{@link
     * #MAX_GAP_FROM_PET} px to one side of a random anchor pet, on the
     * anchor's current monitor. If that column would overlap any live
     * pet, try the mirror side once; if that also overlaps, give up
     * (we'll try again next poll). Then hand the planned spawn off to
     * {@link PetSupervisor#spawnVisitor(Pet)}.
     */
    private static void trySpawn(PetSupervisor supervisor, List<Pet> live) {
        Pet anchor = live.get(ThreadLocalRandom.current().nextInt(live.size()));
        if (anchor.frame == null) {
            return;
        }
        Rectangle mon = anchor.currentMonitorBounds();
        Point anchorLoc = anchor.logicalLocation();
        int anchorMid = anchorLoc.x + anchor.effectiveWidth() / 2;
        int birdSize = supervisor.getPetSize();
        int gap = MIN_GAP_FROM_PET
                + ThreadLocalRandom.current().nextInt(MAX_GAP_FROM_PET - MIN_GAP_FROM_PET);
        int dir = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        int targetX = clampToMonitor(anchorMid + dir * gap - birdSize / 2, mon, birdSize);
        if (overlapsAnyPet(targetX, birdSize)) {
            int alt = clampToMonitor(anchorMid - dir * gap - birdSize / 2, mon, birdSize);
            if (overlapsAnyPet(alt, birdSize)) {
                return;
            }
            targetX = alt;
            dir = -dir;
        }
        // Fly in from the side the bird sits OPPOSITE the anchor: a bird
        // landing to the anchor's right should swoop in from the right
        // edge, so its flight path doesn't cross over the resident pet.
        boolean fromRight = dir > 0;
        // ...with a 50% chance, swap a side-entry for a sky-entry so the
        // bird is sometimes seen descending diagonally from above the
        // monitor instead of always cruising in from the edge. fromRight
        // still drives the lateral swoop direction in the from-above
        // entry plan (see Pet#entryPlanForExplicit).
        boolean fromAbove = ThreadLocalRandom.current().nextBoolean();
        Bird bird = new Bird();
        bird.plannedSpawnMonitor = mon;
        bird.plannedSpawnTargetX = targetX;
        bird.plannedSpawnFromRight = fromRight;
        bird.plannedSpawnFromAbove = fromAbove;
        Log.info("bird-visitor",
                "spawning bird near " + anchor.name
                + " at x=" + targetX
                + " from " + (fromAbove ? "above-" : "")
                + (fromRight ? "right" : "left"));
        supervisor.spawnVisitor(bird);
    }

    private static int clampToMonitor(int x, Rectangle mon, int petW) {
        int lo = mon.x + 4;
        int hi = Math.max(lo, mon.x + mon.width - petW - 4);
        return Math.max(lo, Math.min(hi, x));
    }

    /** True if a {@code petSize}-wide column starting at {@code x} would
     *  overlap any currently-live pet's horizontal extent. Bird flight is
     *  vertical-tolerant (visitors land at floor height by design), so
     *  we only check the X axis. */
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
