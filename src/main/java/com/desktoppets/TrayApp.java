package com.desktoppets;

import java.awt.AWTException;
import java.awt.CheckboxMenuItem;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Installs a system-tray icon with a popup menu (Settings… / Pause / Quit)
 * and a double-click shortcut that opens the settings dialog. Registers a
 * shutdown hook so the tray icon is cleaned up even on abrupt exits.
 */
public final class TrayApp {

    private static final String TRAY_ICON = "prop/tray";

    private final PetSupervisor supervisor;
    private final JFrame hidden = new JFrame(); // dialog owner
    private TrayIcon trayIcon;

    public TrayApp(PetSupervisor supervisor) {
        this.supervisor = supervisor;
    }

    public void install() {
        if (!SystemTray.isSupported()) {
            Log.warn("tray", "system tray not supported; running without it");
            return;
        }
        int size = Math.max(16, Dpi.scale(24));
        ImageIcon icon = Sprites.scaled(TRAY_ICON, size, size);
        Image img = icon != null ? icon.getImage() : new ImageIcon().getImage();

        PopupMenu menu = new PopupMenu();
        MenuItem settings = new MenuItem("Settings\u2026");
        CheckboxMenuItem pause = new CheckboxMenuItem("Pause all pets", supervisor.isPaused());
        MenuItem quit = new MenuItem("Quit");
        menu.add(settings);
        menu.add(pause);
        menu.addSeparator();
        menu.add(quit);

        trayIcon = new TrayIcon(img, "Desktop Pets", menu);
        trayIcon.setImageAutoSize(true);

        settings.addActionListener(_ -> SwingUtilities.invokeLater(this::openSettings));
        trayIcon.addActionListener(_ -> SwingUtilities.invokeLater(this::openSettings));
        pause.addItemListener(_ -> supervisor.setPaused(pause.getState()));
        quit.addActionListener(_ -> shutdown(0));

        try {
            SystemTray.getSystemTray().add(trayIcon);
            Toasts.bind(trayIcon);
            Log.info("tray", "icon installed (" + size + " px)");
        } catch (AWTException ex) {
            Log.warn("tray", "failed to install tray icon: " + ex.getMessage());
            return;
        }

        // NOTE: deliberately NO shutdown hook that calls SystemTray.remove().
        // SystemTray.remove() disposes a hidden AWT Window via
        // EventQueue.invokeAndWait(), which blocks on the EDT. During JVM
        // shutdown (Ctrl-C, or System.exit from the EDT) the EDT is itself
        // busy running shutdown hooks, so that invokeAndWait never returns and
        // Shutdown.runHooks() hangs forever — the process becomes unkillable by
        // Ctrl-C. The OS reclaims the tray icon when the process dies anyway;
        // the explicit removal on the normal Quit path (see shutdown()) covers
        // the tidy case.
    }

    private void openSettings() {
        new SettingsDialog(hidden, supervisor).setVisible(true);
    }

    private void shutdown(int code) {
        // Absolute guarantee that the JVM dies, even if some AWT/EDT operation
        // below wedges: a daemon watchdog force-halts after a short grace
        // period. halt() bypasses shutdown hooks and finalizers, so it cannot
        // itself be blocked by them.
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                // fall through to halt
            }
            Runtime.getRuntime().halt(code);
        }, "exit-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        // Guarantee the JVM actually exits even if tearing down the pets or
        // removing the tray icon throws — otherwise a stray exception here
        // would leave the process alive with the menu already gone, so the
        // user could no longer quit.
        try {
            supervisor.shutdown();
        } catch (Throwable t) {
            Log.warn("tray", "error during supervisor shutdown: " + t);
        }
        try {
            if (trayIcon != null) {
                SystemTray.getSystemTray().remove(trayIcon);
            }
        } catch (Throwable t) {
            Log.warn("tray", "error removing tray icon: " + t);
        }
        System.exit(code);
    }
}
