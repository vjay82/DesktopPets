package com.desktoppets;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Schedules a rare "laser pointer" spectacle: a darting red dot appears on the
 * floor and a visiting cat frantically chases it from spot to spot before it
 * blinks out. Driven by the central {@link SpecialEvents} scheduler — when the
 * event is due, with probability {@link #SPAWN_PROBABILITY_PER_POLL} it spawns
 * a carrier cat whose {@link Pet#laserEvent} flag routes it through the
 * dedicated cinematic in {@link Pet#runLaserEventLoop()}.
 *
 * <p>The carrier {@link Cat} IS shown and really runs after the dot prop
 * ({@code Sprites/Props/laser-dot.svg}). A landing column near (but not
 * overlapping) the anchor is chosen, like the bird/UFO visitors. The shared
 * preconditions are enforced centrally by {@link SpecialEvents} before
 * {@code attempt} runs.
 *
 * <p>Rarity tuning: with {@link #POLL_INTERVAL_MS}=170 s and
 * {@link #SPAWN_PROBABILITY_PER_POLL}=0.04, the mean inter-visit time while
 * eligible is ≈ 71 min.
 */
public final class LaserPointerVisitor {

    /** Polling interval. */
    private static final long POLL_INTERVAL_MS = 170_000L;

    /** Per-poll spawn probability when the preconditions hold. */
    private static final double SPAWN_PROBABILITY_PER_POLL = 0.04;

    /** Min/max horizontal gap from the anchor pet's centre to the cat's
     *  starting column. */
    private static final int MIN_GAP_FROM_PET = 100;
    private static final int MAX_GAP_FROM_PET = 260;

    private LaserPointerVisitor() {
    }

    /**
     * The laser pointer as a {@link SpecialEvents.Event}. The scheduler
     * enforces the shared gate before {@code attempt} runs, so this only rolls
     * its own probability and spawns. {@code activeResidents} is guaranteed
     * non-empty.
     */
    public static SpecialEvents.Event event() {
        return new SpecialEvents.Event() {
            @Override
            public String id() {
                return "laser-pointer";
            }

            @Override
            public long pollIntervalMs() {
                return POLL_INTERVAL_MS;
            }

            @Override
            public void attempt(PetSupervisor supervisor, List<Pet> activeResidents) {
                if (ThreadLocalRandom.current().nextDouble() >= SPAWN_PROBABILITY_PER_POLL) {
                    return;
                }
                trySpawn(supervisor, activeResidents);
            }

            @Override
            public void triggerNow(PetSupervisor supervisor, List<Pet> activeResidents) {
                // --event test trigger: skip the probability roll, spawn now.
                trySpawn(supervisor, activeResidents);
            }
        };
    }

    private static void trySpawn(PetSupervisor supervisor, List<Pet> live) {
        Pet anchor = live.get(ThreadLocalRandom.current().nextInt(live.size()));
        if (anchor.frame == null) {
            return;
        }
        Rectangle mon = anchor.currentMonitorBounds();
        Point anchorLoc = anchor.logicalLocation();
        int anchorMid = anchorLoc.x + anchor.effectiveWidth() / 2;
        int petSize = supervisor.getPetSize();
        int gap = MIN_GAP_FROM_PET
                + ThreadLocalRandom.current().nextInt(MAX_GAP_FROM_PET - MIN_GAP_FROM_PET);
        int dir = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        int targetX = clampToMonitor(anchorMid + dir * gap - petSize / 2, mon, petSize);

        // If a resident cat is already on this monitor, DON'T add a second
        // visible cat: spawn the carrier HIDDEN so it only drives the dot, and
        // let the existing cat give chase (BehaviorEngine.canChaseLaser). The
        // overlap-avoidance below only matters for a *visible* visiting cat.
        boolean residentCat = hasResidentCatOn(mon);
        if (!residentCat && overlapsAnyPet(targetX, petSize)) {
            int alt = clampToMonitor(anchorMid - dir * gap - petSize / 2, mon, petSize);
            if (overlapsAnyPet(alt, petSize)) {
                return;
            }
            targetX = alt;
        }

        Cat cat = new Cat();
        cat.markAsVisitor();
        cat.laserEvent = true;
        cat.laserResidentChaser = residentCat;
        cat.plannedSpawnMonitor = mon;
        cat.plannedSpawnTargetX = targetX;
        cat.plannedSpawnFromRight = dir > 0;
        cat.plannedSpawnFromAbove = false;
        Log.info("laser-pointer",
                "spawning laser dot near " + anchor.name + " at x=" + targetX
                        + (residentCat ? " (resident cat chases; carrier hidden)"
                                       : " (visiting cat chases)"));
        supervisor.spawnVisitor(cat);
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

    /**
     * True if a normal resident {@link Cat} is already living on {@code mon}
     * (not a visitor, not mid-cinematic, not hidden). Such a cat will chase the
     * dot itself, so we suppress the visible visiting cat and just drive the
     * dot with a hidden carrier.
     */
    private static boolean hasResidentCatOn(Rectangle mon) {
        for (Pet p : Pet.activePets()) {
            if (p.frame == null) {
                continue;
            }
            if (p.isVisitor() || p.isCinematicEvent() || p.isHidden()) {
                continue;
            }
            if (!(p instanceof Cat)) {
                continue;
            }
            Rectangle pm = p.currentMonitorBounds();
            if (pm.x == mon.x && pm.y == mon.y) {
                return true;
            }
        }
        return false;
    }
}
