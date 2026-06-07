package com.desktoppets;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Schedules a rare "cardboard box" spectacle: a box drops from the top of the
 * monitor onto the floor near a resident pet, wobbles, opens, and a visiting
 * kitten pops its head out before ducking back in and being whisked away.
 * Driven by the central {@link SpecialEvents} scheduler — when the event is
 * due, with probability {@link #SPAWN_PROBABILITY_PER_POLL} it spawns a carrier
 * kitten whose {@link Pet#boxEvent} flag routes it through the dedicated
 * cinematic in {@link Pet#runBoxEventLoop()}.
 *
 * <p>Unlike the hidden-carrier events (mouse, rain cloud, drone) the carrier
 * {@link Cat} IS shown: it is drawn behind the always-on-top box prop
 * ({@code Sprites/Props/box.svg} / {@code box-open.svg}) so only its head, in
 * the box's open flaps, shows. A landing column near (but not overlapping) the
 * anchor is chosen, like the bird/UFO visitors. The shared preconditions are
 * enforced centrally by {@link SpecialEvents} before {@code attempt} runs.
 *
 * <p>Rarity tuning: with {@link #POLL_INTERVAL_MS}=130 s and
 * {@link #SPAWN_PROBABILITY_PER_POLL}=0.04, the mean inter-visit time while
 * eligible is ≈ 54 min.
 */
public final class CardboardBoxVisitor {

    /** Polling interval. */
    private static final long POLL_INTERVAL_MS = 130_000L;

    /** Per-poll spawn probability when the preconditions hold. */
    private static final double SPAWN_PROBABILITY_PER_POLL = 0.04;

    /** Min/max horizontal gap from the anchor pet's centre to the box's
     *  landing column. */
    private static final int MIN_GAP_FROM_PET = 110;
    private static final int MAX_GAP_FROM_PET = 280;

    private CardboardBoxVisitor() {
    }

    /**
     * The cardboard box as a {@link SpecialEvents.Event}. The scheduler
     * enforces the shared gate before {@code attempt} runs, so this only rolls
     * its own probability and spawns. {@code activeResidents} is guaranteed
     * non-empty.
     */
    public static SpecialEvents.Event event() {
        return new SpecialEvents.Event() {
            @Override
            public String id() {
                return "cardboard-box";
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
        if (overlapsAnyPet(targetX, petSize)) {
            int alt = clampToMonitor(anchorMid - dir * gap - petSize / 2, mon, petSize);
            if (overlapsAnyPet(alt, petSize)) {
                return;
            }
            targetX = alt;
        }

        Cat kitten = new Cat();
        kitten.markAsVisitor();
        kitten.boxEvent = true;
        kitten.plannedSpawnMonitor = mon;
        kitten.plannedSpawnTargetX = targetX;
        kitten.plannedSpawnFromRight = dir > 0;
        kitten.plannedSpawnFromAbove = false;
        Log.info("cardboard-box",
                "spawning cardboard box near " + anchor.name + " at x=" + targetX);
        supervisor.spawnVisitor(kitten);
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
