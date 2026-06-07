package com.desktoppets;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Schedules a rare "rain cloud" spectacle: a grumpy little cloud drifts across
 * an upper band of the monitor, lingers to sprinkle over a resident pet, then
 * floats off. Driven by the central {@link SpecialEvents} scheduler — when the
 * event is due, with probability {@link #SPAWN_PROBABILITY_PER_POLL} it spawns
 * a hidden carrier pet whose {@link Pet#rainCloudEvent} flag routes it through
 * the dedicated cinematic in {@link Pet#runRainCloudEventLoop()}.
 *
 * <p>The carrier is an ordinary {@link Cat} that is never shown — it exists
 * only to drive the cloud prop ({@code Sprites/Props/cloud.svg}) through the
 * normal visitor lifecycle. The shared preconditions are enforced centrally by
 * {@link SpecialEvents} before {@code attempt} runs.
 *
 * <p>Rarity tuning: with {@link #POLL_INTERVAL_MS}=150 s and
 * {@link #SPAWN_PROBABILITY_PER_POLL}=0.05, the mean inter-visit time while
 * eligible is ≈ 50 min.
 */
public final class RainCloudVisitor {

    /** Polling interval. */
    private static final long POLL_INTERVAL_MS = 150_000L;

    /** Per-poll spawn probability when the preconditions hold. */
    private static final double SPAWN_PROBABILITY_PER_POLL = 0.05;

    private RainCloudVisitor() {
    }

    /**
     * The rain cloud as a {@link SpecialEvents.Event}. The scheduler enforces
     * the shared gate before {@code attempt} runs, so this only rolls its own
     * probability and spawns. {@code activeResidents} is guaranteed non-empty.
     */
    public static SpecialEvents.Event event() {
        return new SpecialEvents.Event() {
            @Override
            public String id() {
                return "rain-cloud";
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
        int focusX = anchorLoc.x + anchor.effectiveWidth() / 2;

        // Hidden carrier (never shown); only drives the cloud prop.
        Cat carrier = new Cat();
        carrier.markAsVisitor();
        carrier.rainCloudEvent = true;
        carrier.plannedSpawnMonitor = mon;
        carrier.plannedSpawnTargetX = focusX;
        carrier.plannedSpawnFromRight = ThreadLocalRandom.current().nextBoolean();
        carrier.plannedSpawnFromAbove = false;
        Log.info("rain-cloud",
                "spawning rain cloud over " + anchor.name + " focus x=" + focusX);
        supervisor.spawnVisitor(carrier);
    }
}
