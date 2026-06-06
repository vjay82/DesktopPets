package com.desktoppets;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Schedules a rare "UFO visit" spectacle: a flying saucer descends over the
 * desktop, beams down a little green alien pet that wanders off, dances, then
 * returns to be beamed back up before the saucer flies away. Driven by the
 * central {@link SpecialEvents} scheduler — when the UFO event is due, with
 * probability {@link #SPAWN_PROBABILITY_PER_POLL} it spawns the alien as a
 * one-shot visitor whose {@link Pet#ufoEvent} flag routes it through the
 * dedicated cinematic in {@link Pet#runUfoEventLoop()}.
 *
 * <p>The alien is a {@link Dog} (its tan coat hue-rotates cleanly to a solid
 * alien green via {@link Pet#hueShift}; the Cat's coat is greyscale and so
 * cannot be tinted). It is given a green hue, marked as a visitor, and landed
 * near a randomly-chosen <em>active</em> resident pet so the resident
 * "witnesses" the event.
 *
 * <p>The shared preconditions are enforced once, centrally, by
 * {@link SpecialEvents} before {@link #event()}'s {@code attempt} runs:
 * <ul>
 *   <li>at least one resident pet is <em>active</em>: alive and on screen
 *       (not hidden/suspended — e.g. parked off-screen by autoMATE during a
 *       Teams meeting or while screen-sharing) — the saucer lands near it;</li>
 *   <li>no visitor (bird, wandering pet, or another saucer) is currently
 *       alive — at most one guest at a time keeps the screen calm and the
 *       gag rare.</li>
 * </ul>
 *
 * <p>Rarity tuning: with {@link #POLL_INTERVAL_MS}=120 s and
 * {@link #SPAWN_PROBABILITY_PER_POLL}=0.04, the mean inter-visit time while
 * eligible is ≈ 50 min — a genuine surprise, never spammy.
 */
public final class UfoVisitor {

    /** Polling interval. */
    private static final long POLL_INTERVAL_MS = 120_000L;

    /** Per-poll spawn probability when both preconditions hold. */
    private static final double SPAWN_PROBABILITY_PER_POLL = 0.04;

    /** Min/max horizontal gap from the anchor pet's centre to the saucer's
     *  landing column, in logical pixels — close enough that the resident
     *  shares the scene, far enough not to overlap it. */
    private static final int MIN_GAP_FROM_PET = 120;
    private static final int MAX_GAP_FROM_PET = 300;

    /** Hue rotation (degrees) applied to the alien {@link Dog}. The dog's tan
     *  coat sits at hue ≈ 30°, so +90° lands its dominant colour at ≈ 120°
     *  (a solid alien green). */
    private static final double ALIEN_HUE_DEGREES = 90.0;

    private UfoVisitor() {
    }

    /**
     * The UFO spectacle as a {@link SpecialEvents.Event}, driven by the
     * central {@link SpecialEvents} scheduler. The shared preconditions (no
     * other visitor active; at least one <em>active</em>, on-screen resident
     * pet) are enforced by the scheduler before {@code attempt} runs, so this
     * only rolls its own probability and spawns. {@code activeResidents} is
     * guaranteed non-empty.
     */
    public static SpecialEvents.Event event() {
        return new SpecialEvents.Event() {
            @Override
            public String id() {
                return "ufo-visitor";
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
        };
    }

    /**
     * Trigger one UFO visit on demand — used by the {@code --ufo} command-line
     * flag so the otherwise-rare spectacle can be demoed without waiting.
     * Starts a short-lived daemon thread that waits up to a few seconds for a
     * resident pet's window to come alive (the saucer needs a resident to land
     * beside), then spawns the alien once, bypassing the random probability
     * and one-visitor gates of the normal {@link #event()} cadence. Logs and
     * no-ops if no active resident appears in time.
     */
    public static void triggerOnce(PetSupervisor supervisor) {
        Thread t = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 10_000L;
            List<Pet> live = SpecialEvents.activeResidents(supervisor);
            while (live.isEmpty() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                live = SpecialEvents.activeResidents(supervisor);
            }
            if (live.isEmpty()) {
                Log.warn("ufo-visitor", "--ufo: no active resident pet appeared; nothing to visit");
                return;
            }
            try {
                trySpawn(supervisor, live);
            } catch (Throwable ex) {
                Log.warn("ufo-visitor", "--ufo trigger failed: " + ex);
            }
        }, "ufo-visitor-trigger");
        t.setDaemon(true);
        t.start();
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
        if (overlapsAnyPet(targetX, petSize)) {
            int alt = clampToMonitor(anchorMid - dir * gap - petSize / 2, mon, petSize);
            if (overlapsAnyPet(alt, petSize)) {
                return;
            }
            targetX = alt;
            dir = -dir;
        }

        Dog alien = new Dog();
        alien.markAsVisitor();
        alien.ufoEvent = true;
        alien.hueShift = ALIEN_HUE_DEGREES;
        alien.plannedSpawnMonitor = mon;
        alien.plannedSpawnTargetX = targetX;
        // The alien is beamed straight down, so the entry side/above flags are
        // irrelevant — set sane defaults so initOnEdt's explicit-entry branch
        // still has a valid (off-screen) start position before runUfoEventLoop
        // hides and repositions the pet under the saucer.
        alien.plannedSpawnFromRight = dir > 0;
        alien.plannedSpawnFromAbove = false;
        Log.info("ufo-visitor",
                "spawning UFO alien near " + anchor.name + " at x=" + targetX);
        supervisor.spawnVisitor(alien);
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
