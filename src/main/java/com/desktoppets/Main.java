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
        // Start the wandering-bird scheduler AFTER residents reconcile so
        // its first poll already sees the live pet list.
        BirdVisitor.start(supervisor);
        // Rare cross-species visits for the solo-pet case: a lone cat may
        // get visited by a ducky/dog (or vice versa) every ~25 min on
        // average. No-op when 0 or 2+ residents are active.
        PetVisitor.start(supervisor);
    }
}
