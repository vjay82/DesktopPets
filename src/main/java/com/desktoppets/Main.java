package com.desktoppets;

import javax.swing.SwingUtilities;

/**
 * Entry point. Installs the tray icon, then starts the pets listed in
 * {@code config.txt}. There are no more sprite resources to validate — all
 * graphics are drawn procedurally by {@link Doodle}.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        boolean triggerUfo = hasFlag(args, "--ufo", "-ufo", "ufo");
        Log.info("main", "Desktop Pets starting (procedural graphics)");
        // Begin sampling the cursor in the background so HUNT_CURSOR
        // (and any other motion-aware activity) has an accurate recent
        // history independent of which activity each pet is currently
        // running.
        World.startCursorSampler();
        // Stage windows are click-through (so they don't trap user input);
        // hover/click on pets is delivered by polling the cursor + button
        // state in PetMouse and dispatching to whichever pet is under the
        // pointer at press time.
        PetMouse.start();
        PetSupervisor supervisor = new PetSupervisor();
        SwingUtilities.invokeLater(() -> new TrayApp(supervisor).install());
        supervisor.reconcile(Config.readPets());
        // Central special-events scheduler: one daemon timer drives every rare
        // cosmetic event (wandering bird, cross-species solo-pet visitor, and
        // the UFO spectacle). Each event self-gates on there being at least
        // one active (visible) resident pet, so nothing appears on an empty
        // desktop. The timer thread itself is started on the first pet's
        // activation and stopped after the last pet leaves (see
        // startWhenResidentsPresent below). New events: implement
        // SpecialEvents.Event and register() it here.
        SpecialEvents specialEvents = new SpecialEvents(supervisor);
        specialEvents.register(BirdVisitor.event());
        specialEvents.register(PetVisitor.event());
        specialEvents.register(UfoVisitor.event());
        // Residents were just reconciled above, so this starts the timer
        // immediately when config.txt lists any pets; with an empty config it
        // stays idle until the user adds a pet from the tray.
        specialEvents.startWhenResidentsPresent();
        // `--ufo` on the command line fires one visit immediately (once a
        // resident is alive) so the otherwise-rare event can be demoed.
        if (triggerUfo) {
            Log.info("main", "--ufo flag set: triggering a UFO visit now");
            UfoVisitor.triggerOnce(supervisor);
        }
    }

    /** True iff any of {@code names} appears in {@code args} (case-insensitive). */
    private static boolean hasFlag(String[] args, String... names) {
        for (String arg : args) {
            for (String name : names) {
                if (name.equalsIgnoreCase(arg)) {
                    return true;
                }
            }
        }
        return false;
    }
}
