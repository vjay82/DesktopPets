package com.desktoppets;

import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Schedules a rare "airplane fly-by" spectacle: a little propeller plane with a
 * {@link Ducky} sitting in its open cockpit cruises across the screen from one
 * edge to the other, then leaves. Driven by the central {@link SpecialEvents}
 * scheduler — when the airplane event is due, with probability
 * {@link #SPAWN_PROBABILITY_PER_POLL} it spawns the duck as a one-shot visitor
 * whose {@link Pet#airplaneEvent} flag routes it through the dedicated
 * cinematic in {@link Pet#runAirplaneEventLoop()}.
 *
 * <p>The passenger is a {@link Ducky}; the plane itself is a prop
 * ({@code Sprites/Props/airplane.svg}) animated by the pet's own behaviour
 * thread in lockstep with the duck, so the duck always appears to sit in the
 * cockpit. Unlike the UFO event the plane never lands — it simply flies past —
 * so there is no per-pet overlap check; it cruises in a band across the upper
 * part of the monitor, well above the floor where the residents stand.
 *
 * <p>The shared preconditions are enforced once, centrally, by
 * {@link SpecialEvents} before {@link #event()}'s {@code attempt} runs:
 * <ul>
 *   <li>at least one resident pet is <em>active</em>: alive and on screen
 *       (not hidden/suspended) — the plane flies over its monitor so the
 *       resident "witnesses" the event;</li>
 *   <li>no visitor (bird, wandering pet, saucer, or another plane) is
 *       currently alive — at most one guest at a time keeps the screen calm
 *       and the gag rare.</li>
 * </ul>
 *
 * <p>Rarity tuning: with {@link #POLL_INTERVAL_MS}=150 s and
 * {@link #SPAWN_PROBABILITY_PER_POLL}=0.05, the mean inter-visit time while
 * eligible is ≈ 50 min — a genuine surprise, never spammy.
 */
public final class AirplaneVisitor {

    /** Polling interval. */
    private static final long POLL_INTERVAL_MS = 150_000L;

    /** Per-poll spawn probability when both preconditions hold. */
    private static final double SPAWN_PROBABILITY_PER_POLL = 0.05;

    private AirplaneVisitor() {
    }

    /**
     * The airplane spectacle as a {@link SpecialEvents.Event}, driven by the
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
                return "airplane-visitor";
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
        boolean fromRight = ThreadLocalRandom.current().nextBoolean();

        Ducky duck = new Ducky();
        duck.markAsVisitor();
        duck.airplaneEvent = true;
        duck.plannedSpawnMonitor = mon;
        // The duck is repositioned into the cockpit by runAirplaneEventLoop;
        // give it an on-screen target column so initOnEdt attaches its panel to
        // the correct monitor's stage before the cinematic takes over.
        duck.plannedSpawnTargetX = mon.x + mon.width / 2;
        // fromRight: the plane enters from the right edge and flies left.
        duck.plannedSpawnFromRight = fromRight;
        duck.plannedSpawnFromAbove = false;
        Log.info("airplane-visitor",
                "spawning airplane over " + anchor.name
                        + " flying " + (fromRight ? "left" : "right"));
        supervisor.spawnVisitor(duck);
    }
}
