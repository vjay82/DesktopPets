package com.desktoppets;

import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Schedules a rare "delivery drone" spectacle: a little quadcopter flies in
 * over a resident pet, lowers a wrapped gift on its hook down to the floor,
 * releases it, and buzzes off. Driven by the central {@link SpecialEvents}
 * scheduler — when the event is due, with probability
 * {@link #SPAWN_PROBABILITY_PER_POLL} it spawns a hidden carrier pet whose
 * {@link Pet#droneEvent} flag routes it through the dedicated cinematic in
 * {@link Pet#runDroneEventLoop()}.
 *
 * <p>The carrier is an ordinary {@link Cat} that is never shown — it exists
 * only to drive the drone prop ({@code Sprites/Props/drone.svg}) and the gift
 * ({@code Sprites/Props/gift.svg}) through the normal visitor lifecycle. The
 * shared preconditions are enforced centrally by {@link SpecialEvents} before
 * {@code attempt} runs.
 *
 * <p>Rarity tuning: with {@link #POLL_INTERVAL_MS}=150 s and
 * {@link #SPAWN_PROBABILITY_PER_POLL}=0.04, the mean inter-visit time while
 * eligible is ≈ 60 min.
 */
public final class DroneVisitor {

    /** Polling interval. */
    private static final long POLL_INTERVAL_MS = 150_000L;

    /** Per-poll spawn probability when the preconditions hold. */
    private static final double SPAWN_PROBABILITY_PER_POLL = 0.04;

    private DroneVisitor() {
    }

    /**
     * The delivery drone as a {@link SpecialEvents.Event}. The scheduler
     * enforces the shared gate before {@code attempt} runs, so this only rolls
     * its own probability and spawns. {@code activeResidents} is guaranteed
     * non-empty.
     */
    public static SpecialEvents.Event event() {
        return new SpecialEvents.Event() {
            @Override
            public String id() {
                return "drone-delivery";
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
        // Drop anywhere across the FULL width of the monitor rather than over
        // the anchor pet — pets tend to congregate at the screen edges, so
        // anchoring the drop on one made every present land in a corner. A
        // modest 10% margin keeps the package comfortably on-screen.
        int margin = Math.max(40, (int) Math.round(mon.width * 0.10));
        int dropX = mon.x + margin
                + ThreadLocalRandom.current().nextInt(Math.max(1, mon.width - 2 * margin));

        // Hidden carrier (never shown); only drives the drone + gift props.
        Cat carrier = new Cat();
        carrier.markAsVisitor();
        carrier.droneEvent = true;
        carrier.plannedSpawnMonitor = mon;
        carrier.plannedSpawnTargetX = dropX;
        carrier.plannedSpawnFromRight = ThreadLocalRandom.current().nextBoolean();
        carrier.plannedSpawnFromAbove = false;
        Log.info("drone-delivery",
                "spawning delivery drone drop x=" + dropX + " on " + anchor.name + "'s monitor");
        supervisor.spawnVisitor(carrier);
    }
}
