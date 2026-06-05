package com.desktoppets;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    /**
     * Attach the component to the stage window covering the GIVEN monitor
     * rectangle (matched against the AWT {@link GraphicsDevice} bounds),
     * creating that stage window if it doesn't exist yet, and position the
     * component at the supplied screen-coord bounds. The monitor is chosen
     * EXPLICITLY rather than from the component's top-left point, so a
     * component positioned fully OFF that monitor (e.g. a pet spawning just
     * outside the entry edge to walk in) still lands on the correct
     * monitor's stage and is simply clipped by the canvas until it moves
     * inward.
     */
    public static void attachToMonitor(JComponent c, Rectangle monitor,
            int screenX, int screenY, int w, int h) {
        runOnEdt(() -> {
            StageWindow sw = stageWindowForMonitor(monitor);
            if (sw == null) {
                return;
            }
            if (c.getParent() != sw.canvas) {
                if (c.getParent() != null) {
                    c.getParent().remove(c);
                }
                sw.canvas.add(c);
            }
            c.setBounds(screenX - sw.originX, screenY - sw.originY, w, h);
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
            // Use the no-fallback lookup so a point that lies OFF every
            // monitor (a pet mid-slide off its edge, or in a gap between
            // monitors) keeps the component on its current stage and is
            // simply clipped, instead of being yanked onto the primary
            // monitor's stage.
            StageWindow nw = stageWindowContaining(screenX, screenY);
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
            // See setLocation: keep the component on its current stage when
            // the point is off every monitor rather than re-parenting it
            // onto the primary monitor's stage.
            StageWindow nw = stageWindowContaining(screenX, screenY);
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
     * Re-assert each stage window's z-order — kept at the FRONT of the
     * topmost band (see {@link Win32#placeAtShellZOrder}) so the pets float
     * above ordinary windows. Windows that are "on top of the shell bar"
     * (always-on-top apps, the Start menu, tray fly-outs) are instead cleared
     * out of the canvas per-rectangle at paint time (driven by
     * {@link Win32#collectOccluders}), so the pets appear to hide behind them
     * only where each window is. Throttled internally to at most
     * one Win32 {@code SetWindowPos} per stage per second. Safe to call
     * every behaviour tick from any thread.
     */
    public static synchronized void reassertTopmost() {
        for (StageWindow w : WINDOWS.values()) {
            w.reassertTopmost();
        }
    }

    /** Drives the per-window occlusion cut-outs (see
     *  {@link Win32#collectOccluders}) at a steady cadence so the holes track
     *  windows being dragged / resized over the pets. Started lazily with the
     *  first stage window. */
    private static ScheduledExecutorService occlusionTimer;
    private static final Object OCCLUSION_LOCK = new Object();

    /** Start the shared occlusion timer once, on first stage creation. */
    private static void ensureOcclusionTimer() {
        synchronized (OCCLUSION_LOCK) {
            if (occlusionTimer != null || !Win32.isAvailable()) {
                return;
            }
            ScheduledExecutorService t = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread th = new Thread(r, "pets-occlusion");
                th.setDaemon(true);
                return th;
            });
            // ~30 Hz: responsive enough to follow a dragged window without
            // burning CPU; collectOccluders is cheap and we only repaint a
            // canvas when its occluder set actually changed.
            t.scheduleWithFixedDelay(Stage::updateOcclusion, 200, 33, TimeUnit.MILLISECONDS);
            occlusionTimer = t;
        }
    }

    private static void updateOcclusion() {
        List<StageWindow> snapshot;
        synchronized (Stage.class) {
            snapshot = new ArrayList<>(WINDOWS.values());
        }
        for (StageWindow w : snapshot) {
            int[][] occ = Win32.collectOccluders(w.hwnd);
            if (w.canvas.applyOccluders(occ)) {
                w.canvas.repaint();
            }
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

    /**
     * Stage window for an explicit monitor rectangle (as reported by a
     * {@link GraphicsDevice}'s default configuration). Matches by exact
     * bounds first, then by the device whose bounds contain the monitor's
     * centre, finally falling back to the primary device. Builds the
     * window on first use. Unlike {@link #stageWindowFor} the lookup key is
     * the monitor itself, not an arbitrary point, so it works even when the
     * component being placed sits off that monitor.
     */
    private static synchronized StageWindow stageWindowForMonitor(Rectangle monitor) {
        GraphicsDevice target = null;
        try {
            GraphicsDevice[] devs = GraphicsEnvironment
                    .getLocalGraphicsEnvironment().getScreenDevices();
            if (monitor != null) {
                for (GraphicsDevice d : devs) {
                    if (d.getDefaultConfiguration().getBounds().equals(monitor)) {
                        target = d;
                        break;
                    }
                }
                if (target == null) {
                    int cx = monitor.x + monitor.width / 2;
                    int cy = monitor.y + monitor.height / 2;
                    for (GraphicsDevice d : devs) {
                        if (d.getDefaultConfiguration().getBounds().contains(cx, cy)) {
                            target = d;
                            break;
                        }
                    }
                }
            }
            if (target == null) {
                target = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice();
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

    /**
     * Like {@link #stageWindowFor} but returns {@code null} (instead of
     * falling back to the primary monitor) when {@code (x, y)} lies outside
     * every monitor. Used by {@link #setLocation} / {@link #setBounds} for
     * the re-parent decision so a component sliding OFF its monitor's edge
     * — or sitting in a gap between monitors — stays on its current stage
     * (clipped) rather than being yanked onto the primary monitor's stage.
     * Re-parenting then happens only when the point genuinely enters a
     * DIFFERENT monitor (its stage is built on demand if needed).
     */
    private static synchronized StageWindow stageWindowContaining(int x, int y) {
        for (Map.Entry<GraphicsDevice, StageWindow> e : WINDOWS.entrySet()) {
            if (e.getKey().getDefaultConfiguration().getBounds().contains(x, y)) {
                return e.getValue();
            }
        }
        try {
            for (GraphicsDevice d : GraphicsEnvironment
                    .getLocalGraphicsEnvironment().getScreenDevices()) {
                if (d.getDefaultConfiguration().getBounds().contains(x, y)) {
                    StageWindow existing = WINDOWS.get(d);
                    if (existing != null) {
                        return existing;
                    }
                    StageWindow nw = buildStageWindow(d);
                    if (nw != null) {
                        WINDOWS.put(d, nw);
                    }
                    return nw;
                }
            }
        } catch (Throwable t) {
            return null;
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
            StageCanvas canvas = new StageCanvas();
            canvas.setOpaque(false);
            canvas.setBackground(new Color(0, 0, 0, 0));
            f.setContentPane(canvas);
            f.setBounds(b);
            String title = "DesktopPets-Stage-" + System.identityHashCode(device)
                    + "-" + Long.toHexString(System.nanoTime());
            f.setTitle(title);
            f.setVisible(true);
            long hwnd = Win32.findWindowByTitle(title);
            // Exclude this canvas from the pet floor / perch window finder.
            // It is a full-monitor transparent window; if the finder treated
            // it as a surface it would occlude every real window beneath the
            // pets and drop them to the desktop floor behind the taskbar.
            Win32.registerOwnWindow(hwnd);
            // Make the canvas window click-through so it doesn't trap
            // mouse events meant for the user's apps below. Must happen
            // AFTER setVisible(true) — the HWND only exists once the
            // peer is realised.
            Win32.makeClickThrough(hwnd);
            // Float at the front of the topmost band so the pets are visible
            // above ordinary (non-topmost) windows. Windows that sit "on top
            // of the shell bar" (always-on-top apps, the Start menu, tray
            // fly-outs, or even an ordinary window that happens to be drawn in
            // front of the taskbar) are cleared OUT of this canvas
            // per-rectangle at paint time (Win32.collectOccluders + StageCanvas),
            // so the pets look like they hide behind those windows — but only
            // where each window actually is.
            Win32.placeAtShellZOrder(hwnd);
            ensureOcclusionTimer();
            Log.info("stage", "created for monitor " + b.width + "x" + b.height
                    + "@(" + b.x + "," + b.y + ") hwnd=0x" + Long.toHexString(hwnd));
            return new StageWindow(f, canvas, b.x, b.y, b, hwnd);
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

    /** Immutable empty occluder set. */
    private static final int[][] EMPTY_OCC = new int[0][];

    /**
     * The transparent per-monitor canvas the pets are painted on. Besides
     * hosting the pet {@link JComponent}s it knows the rectangles of any
     * windows currently drawn on top of the shell bar (supplied by the
     * occlusion timer via {@link Win32#collectOccluders}) and, after painting
     * the pets, clears those rectangles back to fully-transparent pixels — so
     * the pets appear to hide BEHIND such windows, but only where each window
     * actually is. Because the stage is a per-pixel-translucent (layered)
     * window, clearing the canvas alpha is the reliable way to punch holes in
     * it; a Win32 window region is not honoured for {@code UpdateLayeredWindow}
     * surfaces.
     */
    private static final class StageCanvas extends JPanel {
        private static final long serialVersionUID = 1L;

        /** Occluder rectangles in logical px, canvas-relative {x,y,w,h}.
         *  Replaced wholesale by the occlusion timer; read on the EDT. */
        private volatile int[][] occluders = EMPTY_OCC;

        StageCanvas() {
            super(null);
        }

        /** Swap in a new occluder set; returns true iff it differs from the
         *  current one (so the caller only repaints on real changes). */
        boolean applyOccluders(int[][] occ) {
            int[][] next = (occ == null) ? EMPTY_OCC : occ;
            if (sameRects(this.occluders, next)) {
                return false;
            }
            this.occluders = next;
            return true;
        }

        private static boolean sameRects(int[][] a, int[][] b) {
            if (a.length != b.length) {
                return false;
            }
            for (int i = 0; i < a.length; i++) {
                if (!Arrays.equals(a[i], b[i])) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void paint(Graphics g) {
            super.paint(g); // transparent background + every pet child
            int[][] occ = occluders;
            if (occ.length == 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setComposite(AlphaComposite.Clear);
                for (int[] r : occ) {
                    g2.fillRect(r[0], r[1], r[2], r[3]);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    private static final class StageWindow {
        final JFrame frame;
        final StageCanvas canvas;
        final int originX;
        final int originY;
        final Rectangle bounds;
        final long hwnd;
        long nextReassertMs;

        StageWindow(JFrame frame, StageCanvas canvas, int originX, int originY,
                Rectangle bounds, long hwnd) {
            this.frame = frame;
            this.canvas = canvas;
            this.originX = originX;
            this.originY = originY;
            this.bounds = bounds;
            this.hwnd = hwnd;
        }

        void reassertTopmost() {
            long now = System.currentTimeMillis();
            if (now < nextReassertMs) return;
            nextReassertMs = now + 1000L;
            // Only RECOVER topmost if some app demoted the stage out of the
            // topmost band entirely — do NOT re-front it within the band.
            // Re-fronting every second would pull the stage back in front of
            // genuinely always-on-top windows (full-screen apps, the Start
            // menu, tray fly-outs), so the occlusion cut-outs — which carve out
            // whatever is drawn in front of the stage — could never let the pets
            // hide behind them. Staying merely topmost keeps the pets above
            // ordinary windows while still letting real always-on-top windows
            // sit in front and occlude them.
            Win32.reassertTopmost(hwnd);
        }
    }
}
