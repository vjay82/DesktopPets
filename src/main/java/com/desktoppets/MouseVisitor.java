package com.desktoppets;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Schedules a rare "mouse scurry" spectacle: a little mouse skitters along the
 * floor past a resident pet and off the far edge of the monitor. Driven by the
 * central {@link SpecialEvents} scheduler — when the event is due, with
 * probability {@link #SPAWN_PROBABILITY_PER_POLL} it spawns a hidden carrier
 * pet whose {@link Pet#mouseEvent} flag routes it through the dedicated
 * cinematic in {@link Pet#runMouseEventLoop()}.
 *
 * <p>The carrier is an ordinary {@link Cat} that is never shown — it exists
 * only to drive the mouse prop ({@code Sprites/Props/mouse.svg}) through the
 * normal visitor lifecycle, exactly like the {@link AirplaneVisitor} duck
 * drives the plane. The shared preconditions (no other visitor active; at
 * least one <em>active</em>, on-screen resident pet) are enforced centrally by
 * {@link SpecialEvents} before {@code attempt} runs.
 *
 * <p>Rarity tuning: with {@link #POLL_INTERVAL_MS}=90 s and
 * {@link #SPAWN_PROBABILITY_PER_POLL}=0.05, the mean inter-visit time while
 * eligible is ≈ 30 min.
 */
public final class MouseVisitor {

    /** Polling interval. */
    private static final long POLL_INTERVAL_MS = 90_000L;

    /** Per-poll spawn probability when the preconditions hold. */
    private static final double SPAWN_PROBABILITY_PER_POLL = 0.05;

    /** Min/max horizontal gap from the anchor pet's centre to the mouse's
     *  focus column (the column it passes closest to the resident). */
    private static final int MIN_GAP_FROM_PET = 60;
    private static final int MAX_GAP_FROM_PET = 200;

    private MouseVisitor() {
    }

    /**
     * The mouse scurry as a {@link SpecialEvents.Event}. The scheduler enforces
     * the shared gate before {@code attempt} runs, so this only rolls its own
     * probability and spawns. {@code activeResidents} is guaranteed non-empty.
     */
    public static SpecialEvents.Event event() {
        return new SpecialEvents.Event() {
            @Override
            public String id() {
                return "mouse-visitor";
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
        int gap = MIN_GAP_FROM_PET
                + ThreadLocalRandom.current().nextInt(MAX_GAP_FROM_PET - MIN_GAP_FROM_PET);
        int dir = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        int focusX = Math.max(mon.x + 4,
                Math.min(mon.x + mon.width - 4, anchorMid + dir * gap));

        // Hidden carrier (never shown); only drives the mouse prop.
        Cat carrier = new Cat();
        carrier.markAsVisitor();
        carrier.mouseEvent = true;
        carrier.plannedSpawnMonitor = mon;
        carrier.plannedSpawnTargetX = focusX;
        carrier.plannedSpawnFromRight = dir > 0;
        carrier.plannedSpawnFromAbove = false;
        Log.info("mouse-visitor",
                "spawning mouse near " + anchor.name + " focus x=" + focusX);
        supervisor.spawnVisitor(carrier);
    }
}
