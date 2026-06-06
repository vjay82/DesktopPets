package com.desktoppets;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central scheduler for rare "special events" — the occasional cosmetic
 * spectacles that liven up the desktop (a wandering {@link BirdVisitor bird},
 * a cross-species {@link PetVisitor visitor}, the {@link UfoVisitor UFO}).
 *
 * <p>Historically each event ran its own daemon thread with its own poll
 * loop, and each independently re-implemented the same two preconditions
 * (no other visitor currently active; at least one resident pet to witness
 * the event). That duplication is exactly where bugs crept in — e.g. an
 * event firing while every pet was hidden off-screen during a Teams meeting.
 *
 * <p>This class consolidates all of that into <b>one</b> daemon thread and
 * <b>one</b> place that enforces the shared preconditions:
 * <ul>
 *   <li>no visitor is currently on screen ({@link
 *       PetSupervisor#hasActiveVisitor()} is false) — at most one guest at a
 *       time keeps the screen calm and the gags rare;</li>
 *   <li>at least one resident pet is <em>active</em>: alive <em>and</em> on
 *       screen (not parked off-screen by a suspend/hide — e.g. autoMATE
 *       hides all pets during a meeting or while screen-sharing). See
 *       {@link #activeResidents(PetSupervisor)}.</li>
 * </ul>
 * Only when both hold is an event's {@link Event#attempt} invoked, and the
 * non-empty list of active residents is handed to it so it never has to look
 * one up (or accidentally anchor to a hidden pet).
 *
 * <p>Each event keeps its own cadence ({@link Event#pollIntervalMs()}) and
 * rolls its own spawn probability inside {@code attempt}; the scheduler just
 * decides <em>when</em> to poll each one and guarantees the shared gate.
 *
 * <h2>Adding a future special event</h2>
 * Implement {@link Event} (typically as a small static factory on the event's
 * own class, e.g. {@code MyEvent.event()}) and register it:
 * <pre>{@code
 *   SpecialEvents events = new SpecialEvents(supervisor);
 *   events.register(MyEvent.event());
 *   events.start();
 * }</pre>
 * The new event automatically inherits the active-pet gating and the
 * one-visitor-at-a-time rule — nothing else to wire.
 */
public final class SpecialEvents {

    /**
     * A registrable special event. Implementations supply a stable id (for
     * logging), a poll cadence, and the {@link #attempt} body that rolls its
     * own probability and performs the spawn when it decides to fire.
     */
    public interface Event {
        /** Stable short id for logging, e.g. {@code "bird-visitor"}. */
        String id();

        /** How often this event is polled for a possible trigger, in ms. */
        long pollIntervalMs();

        /**
         * Invoked when this event is due <em>and</em> the shared
         * preconditions hold. {@code activeResidents} is guaranteed
         * non-empty (alive + on-screen pets, visitors excluded). The
         * implementation rolls its own probability and, if it fires,
         * performs the spawn. Exceptions are caught and logged by the
         * scheduler, so a one-off failure never kills the timer.
         */
        void attempt(PetSupervisor supervisor, List<Pet> activeResidents);

        /**
         * Force this event to fire now, bypassing its random probability
         * roll — used by {@link SpecialEvents#triggerNow(String)} for the
         * {@code --event} test flag so the otherwise-rare spectacle reliably
         * appears on demand. The shared gate (one visitor at a time; a
         * non-empty {@code activeResidents}) is already enforced by the
         * caller. The default delegates to {@link #attempt}, which is still
         * probability-gated; events override this to guarantee a spawn when
         * explicitly requested.
         */
        default void triggerNow(PetSupervisor supervisor, List<Pet> activeResidents) {
            attempt(supervisor, activeResidents);
        }
    }

    /**
     * Handle returned by {@link #register(Event)}. Lets a caller enable or
     * disable an individual event at runtime without unregistering it — used
     * by the embedding API to honour the user's "visitor birds" toggle while
     * keeping always-on events (UFO, cross-species visitor) running.
     */
    public static final class Registration {
        private final Event event;
        private final AtomicBoolean enabled = new AtomicBoolean(true);
        private volatile long nextDueAtMs;

        private Registration(Event event, long now) {
            this.event = event;
            this.nextDueAtMs = now + event.pollIntervalMs();
        }

        /** Enable or disable this event. Disabled events are still polled on
         *  cadence but skipped before the shared gate — cheap and reversible. */
        public Registration setEnabled(boolean on) {
            enabled.set(on);
            return this;
        }

        public boolean isEnabled() {
            return enabled.get();
        }

        public String id() {
            return event.id();
        }
    }

    private final PetSupervisor supervisor;
    private final List<Registration> registrations = new CopyOnWriteArrayList<>();
    private volatile Thread thread;

    public SpecialEvents(PetSupervisor supervisor) {
        this.supervisor = supervisor;
    }

    /**
     * Register an event. May be called before or after {@link #start()} — the
     * scheduler picks up newly-registered events on its next tick. The first
     * poll happens one {@link Event#pollIntervalMs()} from registration.
     */
    public Registration register(Event event) {
        Registration r = new Registration(event, System.currentTimeMillis());
        registrations.add(r);
        return r;
    }

    /** Start the single scheduler daemon. Idempotent — a second call is a
     *  no-op while already running. */
    public synchronized void start() {
        if (thread != null) {
            return;
        }
        Thread t = new Thread(this::loop, "special-events-scheduler");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /** Stop the scheduler thread. Safe to call multiple times; safe if never
     *  started. Used by the embedding API on {@code stop()} so a later
     *  {@code start()} doesn't leave an orphan timer polling a dead
     *  supervisor. */
    public synchronized void stop() {
        Thread t = thread;
        if (t != null) {
            thread = null;
            t.interrupt();
        }
    }

    /**
     * Bind this scheduler's lifecycle to the supervisor's resident roster so
     * the single timer thread runs only while there is at least one resident
     * pet: it is {@link #start() started} on the first pet's activation and
     * {@link #stop() stopped} after the last pet is removed. No background
     * timer polls an empty desktop.
     *
     * <p>Call once after registering all events. If residents already exist
     * (e.g. the standalone app reconciled its config first) the scheduler is
     * started immediately; otherwise it starts as soon as the first pet
     * appears. The wiring survives any number of empty<->populated cycles
     * (e.g. a roster the user empties and refills from the tray).
     */
    public void startWhenResidentsPresent() {
        supervisor.setResidentPresenceListener(present -> {
            if (present) {
                start();
            } else {
                stop();
            }
        });
    }

    /**
     * Force the registered event with the given {@code id} to fire once now —
     * for manual testing via the {@code --event} command-line flag. Bypasses
     * the event's poll cadence and its random probability roll, but still
     * respects the shared gate: a short-lived daemon waits up to ~10 s for a
     * resident pet's window to come alive, skips if a visitor is already on
     * screen, then triggers the event once. The per-event enabled toggle is
     * deliberately ignored — an explicit test request overrides it.
     *
     * @return {@code true} if an event with that id is registered (so the
     *         trigger was scheduled), {@code false} if the id is unknown.
     */
    public boolean triggerNow(String id) {
        Registration target = null;
        for (Registration r : registrations) {
            if (r.id().equals(id)) {
                target = r;
                break;
            }
        }
        if (target == null) {
            Log.warn("special-events",
                    "--event: no special event with id '" + id + "'; known ids: " + ids());
            return false;
        }
        final Registration r = target;
        Thread t = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 10_000L;
            List<Pet> live = activeResidents(supervisor);
            while (live.isEmpty() && System.currentTimeMillis() < deadline
                    && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                live = activeResidents(supervisor);
            }
            if (live.isEmpty()) {
                Log.warn("special-events",
                        "--event " + r.id() + ": no active resident pet appeared; nothing to witness it");
                return;
            }
            if (supervisor.hasActiveVisitor()) {
                Log.warn("special-events",
                        "--event " + r.id() + ": a visitor is already on screen; skipping");
                return;
            }
            try {
                Log.info("special-events", "--event: triggering " + r.id() + " now");
                r.event.triggerNow(supervisor, live);
            } catch (Throwable ex) {
                Log.warn("special-events", "--event " + r.id() + " trigger failed: " + ex);
            }
        }, "special-event-trigger");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /**
     * Snapshot of resident pets that are currently <em>active</em>: alive (a
     * live window) <em>and</em> on screen (not hidden by a suspend — e.g.
     * autoMATE parks all pets off-screen during a Teams meeting or while the
     * user is screen-sharing). Visitors are already excluded by
     * {@link PetSupervisor#livePets()}. This is the single definition of
     * "is there a pet to witness an event" shared by every special event.
     */
    public static List<Pet> activeResidents(PetSupervisor supervisor) {
        List<Pet> live = supervisor.livePets();
        List<Pet> out = new ArrayList<>(live.size());
        for (Pet p : live) {
            if (p.frame != null && !p.isHidden()) {
                out.add(p);
            }
        }
        return out;
    }

    private void loop() {
        Log.info("special-events", "scheduler started: " + ids());
        while (!Thread.currentThread().isInterrupted()) {
            long now = System.currentTimeMillis();
            long soonest = now + 1_000L;
            for (Registration r : registrations) {
                if (now >= r.nextDueAtMs) {
                    // Re-arm BEFORE firing so a slow attempt() doesn't cause a
                    // catch-up burst, and so a machine wake from sleep fires
                    // each event at most once rather than N times.
                    r.nextDueAtMs = now + r.event.pollIntervalMs();
                    fireIfEligible(r);
                }
                soonest = Math.min(soonest, r.nextDueAtMs);
            }
            long sleep = Math.max(1L, soonest - System.currentTimeMillis());
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Evaluate the shared preconditions once and, if they hold, run the
     *  event. All failures are contained so the timer survives. */
    private void fireIfEligible(Registration r) {
        try {
            if (!r.isEnabled()) {
                return;
            }
            // Shared gate #1: at most one visitor/event on screen at a time.
            if (supervisor.hasActiveVisitor()) {
                return;
            }
            // Shared gate #2: there must be an active (visible) resident to
            // witness the event — never on an empty desktop or while every
            // pet is hidden during a meeting.
            List<Pet> active = activeResidents(supervisor);
            if (active.isEmpty()) {
                return;
            }
            r.event.attempt(supervisor, active);
        } catch (Throwable t) {
            Log.warn("special-events", r.id() + " attempt failed: " + t);
        }
    }

    private String ids() {
        StringBuilder sb = new StringBuilder();
        for (Registration r : registrations) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(r.id());
            if (!r.isEnabled()) {
                sb.append("(disabled)");
            }
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }
}
