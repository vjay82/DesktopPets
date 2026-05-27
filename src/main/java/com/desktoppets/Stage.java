package com.desktoppets;

import java.awt.Color;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Per-screen transparent always-on-top click-through canvas hosting every
 * pet on that monitor. Replaces the previous "one borderless JFrame per
 * pet" rendering model.
 *
 * <p>Background: spawning N pets used to allocate N native top-level
 * windows. Every animation tick called {@code SetWindowPos} on each one,
 * and the topmost band had to be re-asserted per pet, which on rosters of
 * 10+ pets saturated the EDT (see VisualVM profile dated 2026-05-26). The
 * new model creates a single fullscreen layered window per monitor; pets
 * become {@link JPanel}s painted on the shared canvas, so movement is a
 * lightweight {@code Component.setLocation} inside the same window and the
 * native topmost reassertion only ever runs once per monitor.
 *
 * <p>The stage window is click-through ({@code WS_EX_LAYERED |
 * WS_EX_TRANSPARENT | WS_EX_NOACTIVATE} — see
 * {@link Win32#makeClickThrough(long)}), so it never intercepts mouse
 * events meant for the user's other applications. Pet hover / click is
 * driven by {@link PetMouse} polling the cursor + button state instead.
 *
 * <p>All methods are EDT-safe: they marshal onto the EDT internally when
 * the caller isn't already on it.
 */
public final class Stage {

    private Stage() {
    }

    /** One window per attached monitor, keyed by the {@link GraphicsDevice}
     *  reported by AWT. Built lazily on first {@link #attach}. */
    private static final Map<GraphicsDevice, StageWindow> WINDOWS = new LinkedHashMap<>();

    /**
     * Move the given component onto the stage window that covers the
     * monitor containing {@code (screenX, screenY)} (the component's
     * top-left in virtual-desktop coords). If the component is already
     * on a stage and crosses to a new monitor, it is re-parented atomically.
     * After this call the component's parent-local location is set so that
     * it draws at exactly {@code (screenX, screenY)} on the desktop.
     */
    public static void attach(JComponent c, int screenX, int screenY) {
        runOnEdt(() -> {
            StageWindow w = stageWindowFor(screenX, screenY);
            if (w == null) {
                return;
            }
            if (c.getParent() != w.canvas) {
                if (c.getParent() != null) {
                    c.getParent().remove(c);
                }
                w.canvas.add(c);
            }
            c.setLocation(screenX - w.originX, screenY - w.originY);
            c.setVisible(true);
        });
    }

    /** EDT-safe screen-coord setLocation. No-op if not attached. */
    public static void setLocation(JComponent c, int screenX, int screenY) {
        runOnEdt(() -> {
            StageWindow w = ownerOf(c);
            if (w == null) {
                // Reattach: monitor may have changed under us.
                attachOnEdt(c, screenX, screenY);
                return;
            }
            // If the new point crosses onto a different monitor, re-parent.
            StageWindow nw = stageWindowFor(screenX, screenY);
            if (nw != null && nw != w) {
                w.canvas.remove(c);
                nw.canvas.add(c);
                w = nw;
            }
            c.setLocation(screenX - w.originX, screenY - w.originY);
        });
    }

    /** EDT-safe screen-coord setBounds. No-op if not attached. */
    public static void setBounds(JComponent c, int screenX, int screenY, int w, int h) {
        runOnEdt(() -> {
            StageWindow sw = ownerOf(c);
            if (sw == null) {
                attachOnEdt(c, screenX, screenY);
                sw = ownerOf(c);
                if (sw == null) {
                    return;
                }
            }
            StageWindow nw = stageWindowFor(screenX, screenY);
            if (nw != null && nw != sw) {
                sw.canvas.remove(c);
                nw.canvas.add(c);
                sw = nw;
            }
            c.setBounds(screenX - sw.originX, screenY - sw.originY, w, h);
        });
    }

    /** Remove the component from whichever stage it's on. EDT-safe. */
    public static void detach(JComponent c) {
        runOnEdt(() -> {
            if (c.getParent() != null) {
                c.getParent().remove(c);
                c.getParent().repaint(); // erase the last paint of this pet
            }
        });
    }

    /**
     * Re-assert {@code HWND_TOPMOST} on every stage window. Throttled
     * internally to at most one Win32 {@code SetWindowPos} per stage per
     * second. Safe to call every behaviour tick from any thread.
     */
    public static synchronized void reassertTopmost() {
        for (StageWindow w : WINDOWS.values()) {
            w.reassertTopmost();
        }
    }

    /**
     * Bounds (in virtual-desktop screen coords) of the stage window
     * covering the monitor that physically contains {@code (screenX,
     * screenY)}, or {@code null} if no stage window exists there yet
     * (e.g. the point is on an unrecognised device).
     */
    public static Rectangle screenBoundsOfStageAt(int screenX, int screenY) {
        StageWindow w = stageWindowFor(screenX, screenY);
        if (w == null) {
            return null;
        }
        return new Rectangle(w.originX, w.originY, w.canvas.getWidth(), w.canvas.getHeight());
    }

    // ---------------- internals ----------------

    private static synchronized StageWindow stageWindowFor(int x, int y) {
        // Try existing windows first.
        for (Map.Entry<GraphicsDevice, StageWindow> e : WINDOWS.entrySet()) {
            Rectangle b = e.getKey().getDefaultConfiguration().getBounds();
            if (b.contains(x, y)) {
                return e.getValue();
            }
        }
        // No existing window covers it — pick the matching device (or the
        // primary if the point is off-screen entirely) and build one.
        GraphicsDevice target = null;
        try {
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle b = d.getDefaultConfiguration().getBounds();
                if (b.contains(x, y)) {
                    target = d;
                    break;
                }
            }
            if (target == null) {
                target = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            }
        } catch (Throwable t) {
            return null;
        }
        StageWindow existing = WINDOWS.get(target);
        if (existing != null) {
            return existing;
        }
        StageWindow nw = buildStageWindow(target);
        if (nw != null) {
            WINDOWS.put(target, nw);
        }
        return nw;
    }

    private static synchronized StageWindow ownerOf(JComponent c) {
        for (StageWindow w : WINDOWS.values()) {
            if (c.getParent() == w.canvas) {
                return w;
            }
        }
        return null;
    }

    private static void attachOnEdt(JComponent c, int screenX, int screenY) {
        StageWindow w = stageWindowFor(screenX, screenY);
        if (w == null) {
            return;
        }
        if (c.getParent() != null) {
            c.getParent().remove(c);
        }
        w.canvas.add(c);
        c.setLocation(screenX - w.originX, screenY - w.originY);
        c.setVisible(true);
    }

    private static StageWindow buildStageWindow(GraphicsDevice device) {
        try {
            GraphicsConfiguration gc = device.getDefaultConfiguration();
            Rectangle b = gc.getBounds();
            JFrame f = new JFrame(gc);
            f.setUndecorated(true);
            f.setBackground(new Color(0, 0, 0, 0));
            f.setAlwaysOnTop(true);
            f.setType(JFrame.Type.UTILITY); // keep out of the taskbar
            f.setFocusableWindowState(false);
            f.setAutoRequestFocus(false);
            JPanel canvas = new JPanel(null);
            canvas.setOpaque(false);
            canvas.setBackground(new Color(0, 0, 0, 0));
            f.setContentPane(canvas);
            f.setBounds(b);
            String title = "DesktopPets-Stage-" + System.identityHashCode(device)
                    + "-" + Long.toHexString(System.nanoTime());
            f.setTitle(title);
            f.setVisible(true);
            long hwnd = Win32.findWindowByTitle(title);
            // Make the canvas window click-through so it doesn't trap
            // mouse events meant for the user's apps below. Must happen
            // AFTER setVisible(true) — the HWND only exists once the
            // peer is realised.
            Win32.makeClickThrough(hwnd);
            Log.info("stage", "created for monitor " + b.width + "x" + b.height
                    + "@(" + b.x + "," + b.y + ") hwnd=0x" + Long.toHexString(hwnd));
            return new StageWindow(f, canvas, b.x, b.y, hwnd);
        } catch (Throwable t) {
            Log.warn("stage", "buildStageWindow failed: " + t);
            return null;
        }
    }

    private static void runOnEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(r);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (InvocationTargetException ie) {
                Log.warn("stage", "EDT task failed: " + ie.getCause());
            }
        }
    }

    /**
     * Snapshot of every pet container currently attached to any stage,
     * with its screen-coord bounding rectangle. Used by {@link PetMouse}
     * to figure out which pet (if any) the cursor is over without having
     * to crawl pet internals.
     */
    public static synchronized List<Attached> snapshotAttached() {
        List<Attached> out = new ArrayList<>();
        for (StageWindow w : WINDOWS.values()) {
            for (int i = 0; i < w.canvas.getComponentCount(); i++) {
                var child = w.canvas.getComponent(i);
                if (!child.isVisible()) continue;
                if (!(child instanceof JComponent jc)) continue;
                out.add(new Attached(jc,
                        new Rectangle(jc.getX() + w.originX, jc.getY() + w.originY,
                                jc.getWidth(), jc.getHeight())));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** (component, screen-coord rect) pair returned by {@link #snapshotAttached()}. */
    public record Attached(JComponent component, Rectangle screenBounds) { }

    private static final class StageWindow {
        final JFrame frame;
        final JPanel canvas;
        final int originX;
        final int originY;
        final long hwnd;
        long nextReassertMs;

        StageWindow(JFrame frame, JPanel canvas, int originX, int originY, long hwnd) {
            this.frame = frame;
            this.canvas = canvas;
            this.originX = originX;
            this.originY = originY;
            this.hwnd = hwnd;
        }

        void reassertTopmost() {
            long now = System.currentTimeMillis();
            if (now < nextReassertMs) return;
            nextReassertMs = now + 1000L;
            Win32.reassertTopmost(hwnd);
        }
    }
}
