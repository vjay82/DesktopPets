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
        PetSupervisor supervisor = new PetSupervisor();
        SwingUtilities.invokeLater(() -> new TrayApp(supervisor).install());
        supervisor.reconcile(Config.readPets());
    }
}
