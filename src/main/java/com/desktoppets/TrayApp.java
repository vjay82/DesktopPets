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

        settings.addActionListener(e -> SwingUtilities.invokeLater(this::openSettings));
        trayIcon.addActionListener(e -> SwingUtilities.invokeLater(this::openSettings));
        pause.addItemListener(e -> supervisor.setPaused(pause.getState()));
        quit.addActionListener(e -> shutdown(0));

        try {
            SystemTray.getSystemTray().add(trayIcon);
            Toasts.bind(trayIcon);
            Log.info("tray", "icon installed (" + size + " px)");
        } catch (AWTException ex) {
            Log.warn("tray", "failed to install tray icon: " + ex.getMessage());
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (trayIcon != null) {
                try {
                    SystemTray.getSystemTray().remove(trayIcon);
                } catch (Throwable ignored) {
                    // best-effort
                }
            }
        }, "tray-cleanup"));
    }

    private void openSettings() {
        new SettingsDialog(hidden, supervisor).setVisible(true);
    }

    private void shutdown(int code) {
        supervisor.shutdown();
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
        System.exit(code);
    }
}
