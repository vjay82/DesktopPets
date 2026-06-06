package com.desktoppets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Headless tests for the central {@link SpecialEvents} scheduler.
 *
 * <p>A freshly-constructed {@link PetSupervisor} has zero pets, so its
 * {@link PetSupervisor#livePets()} is empty and {@link
 * SpecialEvents#activeResidents} is empty too. That lets us verify the core
 * guarantee — <b>no special event ever fires unless a resident pet is active
 * (alive + on screen)</b> — without spawning real windows (which would throw
 * under {@code java.awt.headless=true}).
 *
 * <p>The positive "fires when a pet is present" path needs a real on-screen
 * {@code Pet} window and so is exercised manually / in the running app, not
 * here.
 */
final class SpecialEventsTest {

    /** A probe event with a tiny cadence. {@code pollIntervalMs()} counts down
     *  a latch so the test can prove the scheduler thread is actually ticking;
     *  {@code attempt()} bumps a counter that must stay at zero while there are
     *  no active residents. */
    private static final class Probe implements SpecialEvents.Event {
        final CountDownLatch polled;
        final AtomicInteger attempts = new AtomicInteger();

        Probe(int pollsToAwait) {
            this.polled = new CountDownLatch(pollsToAwait);
        }

        @Override public String id() {
            return "test-probe";
        }

        @Override public long pollIntervalMs() {
            polled.countDown();
            return 5L;
        }

        @Override public void attempt(PetSupervisor supervisor, List<Pet> activeResidents) {
            attempts.incrementAndGet();
        }
    }

    @Test
    void pollsRepeatedlyButNeverFiresWithoutActiveResident() throws InterruptedException {
        PetSupervisor supervisor = new PetSupervisor(); // no pets spawned
        Probe probe = new Probe(3);

        SpecialEvents events = new SpecialEvents(supervisor);
        events.register(probe);
        events.start();
        try {
            // Wait until the scheduler has polled the event several times,
            // proving the daemon loop is genuinely running on cadence.
            assertTrue(probe.polled.await(2, TimeUnit.SECONDS),
                    "scheduler did not poll the registered event");
        } finally {
            events.stop();
        }

        // The shared gate must have blocked every attempt: with no resident
        // pet on screen there is nothing to witness a special event.
        assertEquals(0, probe.attempts.get(),
                "a special event fired despite there being no active resident pet");
    }

    @Test
    void registrationStartsEnabledAndTogglesReversibly() {
        SpecialEvents events = new SpecialEvents(new PetSupervisor());
        SpecialEvents.Registration reg = events.register(new Probe(1));

        assertTrue(reg.isEnabled(), "events should be enabled by default");
        assertEquals("test-probe", reg.id());

        assertSame(reg, reg.setEnabled(false), "setEnabled should return the registration for chaining");
        assertFalse(reg.isEnabled());

        reg.setEnabled(true);
        assertTrue(reg.isEnabled());
    }

    @Test
    void startIsIdempotentAndStopIsSafe() throws InterruptedException {
        SpecialEvents events = new SpecialEvents(new PetSupervisor());
        Probe probe = new Probe(2);
        events.register(probe);

        events.start();
        events.start(); // second start must be a no-op, not a second thread
        events.stop();
        events.stop();  // stopping twice / after stop must be safe

        // A fresh start after a stop must work again (no orphaned state).
        Probe probe2 = new Probe(2);
        events.register(probe2);
        events.start();
        try {
            assertTrue(probe2.polled.await(2, TimeUnit.SECONDS),
                    "scheduler did not resume polling after restart");
        } finally {
            events.stop();
        }
        assertEquals(0, probe2.attempts.get());
    }

    @Test
    void doesNotStartTimerUntilAResidentIsPresent() throws InterruptedException {
        PetSupervisor supervisor = new PetSupervisor(); // empty roster
        // Probe(2): register() itself calls pollIntervalMs() once, so the latch
        // still needs a SECOND countdown — which can only come from the timer
        // loop actually running. If startWhenResidentsPresent() wrongly started
        // the timer on an empty desktop, the 5ms loop would drive it to zero.
        Probe probe = new Probe(2);

        SpecialEvents events = new SpecialEvents(supervisor);
        events.register(probe);
        events.startWhenResidentsPresent(); // no residents → must stay idle
        try {
            assertFalse(probe.polled.await(400, TimeUnit.MILLISECONDS),
                    "scheduler timer ran despite there being no resident pets");
        } finally {
            events.stop();
        }
        assertEquals(0, probe.attempts.get(),
                "a special event fired with no resident pets");
    }

    @Test
    void triggerNowReportsUnknownVsKnownIdAndNeverFiresWithoutResident()
            throws InterruptedException {
        PetSupervisor supervisor = new PetSupervisor(); // empty roster
        Probe probe = new Probe(1);

        SpecialEvents events = new SpecialEvents(supervisor);
        events.register(probe);

        assertFalse(events.triggerNow("no-such-event"),
                "triggerNow must report false for an unregistered id");
        assertTrue(events.triggerNow("test-probe"),
                "triggerNow must report true for a registered id");

        // The forced trigger runs on a background daemon that first waits for an
        // active resident; with an empty roster it never fires the event, so the
        // probe's attempt() (the default triggerNow target) must not run.
        Thread.sleep(300L);
        assertEquals(0, probe.attempts.get(),
                "triggerNow fired an event with no active resident present");
    }
}
