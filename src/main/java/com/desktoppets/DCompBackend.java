package com.desktoppets;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Bridges each {@link PetWindow} to the hardware-composited {@link DCompStage}.
 *
 * <p>When active, a pet's Swing {@link JPanel} (its body + heart + prop +
 * emote + speech labels) is rendered off-screen to a premultiplied-ARGB
 * bitmap and pushed to a DirectComposition visual, positioned by
 * {@code SetOffsetX/Y}. The full-monitor, per-pixel-translucent Swing
 * {@link Stage} window is never created, so the DWM composites only
 * pet-sized surfaces instead of a screen-sized alpha layer — the GPU cost
 * that maxed out with the layered {@code Stage}.
 *
 * <p>DirectComposition is the <b>default</b>. It falls back to the Swing
 * {@link Stage} when:
 * <ul>
 *   <li>the platform is not Windows,</li>
 *   <li>the native D3D11 / DirectComposition device cannot be created, or</li>
 *   <li>it is explicitly disabled via the system property
 *       {@code -Ddesktoppets.dcomp=false} or the environment variable
 *       {@code DESKTOPPETS_DCOMP=0}.</li>
 * </ul>
 *
 * <p>All Swing painting runs on the EDT (the render driver is a
 * {@link javax.swing.Timer}); the DComp calls it makes are marshalled onto
 * the DComp owner thread internally by {@link DCompStage}.
 */
public final class DCompBackend {

    /** Render / commit cadence. ~33 ms ≈ 30 FPS — matches {@link PetMouse}. */
    private static final int FRAME_MS = 33;

    private static final Object INIT_LOCK = new Object();
    private static boolean initDone;
    private static volatile boolean active;
    private static DCompStage stage;
    private static Timer timer;

    /** Logical→physical monitor map: each AWT device matched to its Win32
     *  physical rect, so pets map correctly on mixed-DPI multi-monitor setups. */
    private static volatile java.util.List<Mon> monitors = java.util.List.of();

    /** One monitor: AWT logical bounds + DPI scale + real physical origin. */
    private record Mon(int lx, int ly, int lw, int lh, double sx, double sy, int px, int py) {
        boolean containsLogical(int x, int y) {
            return x >= lx && x < lx + lw && y >= ly && y < ly + lh;
        }

        double centerDistSq(int x, int y) {
            double cx = lx + lw / 2.0;
            double cy = ly + lh / 2.0;
            double dx = x - cx;
            double dy = y - cy;
            return dx * dx + dy * dy;
        }
    }

    /** EDT-confined registry of live pet windows and their visual state. */
    private static final Map<PetWindow, State> REGISTRY = new LinkedHashMap<>();

    private static final class State {
        long handle;
        int w = -1;
        int h = -1;
        int lastHash;
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        boolean shown;
    }

    private DCompBackend() {
    }

    /**
     * Decide and (on first call) initialise the backend. Idempotent and
     * thread-safe. Returns {@code true} iff DirectComposition is active and
     * {@link PetWindow} should route to it; {@code false} means fall back to
     * the Swing {@link Stage}.
     */
    static boolean ensureInit() {
        synchronized (INIT_LOCK) {
            if (initDone) {
                return active;
            }
            initDone = true;
            active = tryInit();
            return active;
        }
    }

    static boolean isActive() {
        return active;
    }

    private static boolean tryInit() {
        if (!enabledByConfig()) {
            Log.info("dcomp", "backend disabled via config; using Swing Stage");
            return false;
        }
        try {
            stage = DCompStage.instance();
            if (!stage.start(4000)) {
                Log.info("dcomp", "DComp device unavailable; using Swing Stage");
                return false;
            }
            buildMonitorMap();
            timer = new Timer(FRAME_MS, e -> tick());
            timer.setCoalesce(true);
            timer.start();
            Log.info("dcomp", "backend active (" + monitors.size() + " monitors mapped)");
            return true;
        } catch (Throwable t) {
            Log.warn("dcomp", "init failed; using Swing Stage: " + t);
            return false;
        }
    }

    private static boolean enabledByConfig() {
        String prop = System.getProperty("desktoppets.dcomp");
        if (prop != null) {
            return !"false".equalsIgnoreCase(prop) && !"0".equals(prop);
        }
        String env = System.getenv("DESKTOPPETS_DCOMP");
        if (env != null) {
            return !"false".equalsIgnoreCase(env) && !"0".equals(env);
        }
        return true; // default ON
    }

    /**
     * Build the logical→physical monitor map. Each AWT {@link
     * java.awt.GraphicsDevice} is matched to the Win32 physical monitor rect
     * of the same scaled size, giving that monitor's true physical origin.
     * The transform for a monitor is then
     * {@code physical = physOrigin + (logical - logicalOrigin) * scale}.
     */
    private static void buildMonitorMap() {
        java.util.List<Mon> out = new java.util.ArrayList<>();
        try {
            java.util.List<int[]> phys = DCompStage.physicalMonitors(); // {l,t,r,b}
            boolean[] used = new boolean[phys.size()];
            for (java.awt.GraphicsDevice gd : GraphicsEnvironment
                    .getLocalGraphicsEnvironment().getScreenDevices()) {
                java.awt.GraphicsConfiguration cfg = gd.getDefaultConfiguration();
                java.awt.Rectangle b = cfg.getBounds();
                java.awt.geom.AffineTransform tx = cfg.getDefaultTransform();
                double sx = tx.getScaleX() > 0 ? tx.getScaleX() : 1.0;
                double sy = tx.getScaleY() > 0 ? tx.getScaleY() : 1.0;
                int pw = (int) Math.round(b.width * sx);
                int ph = (int) Math.round(b.height * sy);
                int best = -1;
                for (int i = 0; i < phys.size(); i++) {
                    if (used[i]) {
                        continue;
                    }
                    int[] r = phys.get(i);
                    if ((r[2] - r[0]) == pw && (r[3] - r[1]) == ph) {
                        best = i;
                        break;
                    }
                }
                int pox;
                int poy;
                if (best >= 0) {
                    used[best] = true;
                    int[] r = phys.get(best);
                    pox = r[0];
                    poy = r[1];
                } else {
                    // Fallback: assume physical origin = logical origin * scale.
                    pox = (int) Math.round(b.x * sx);
                    poy = (int) Math.round(b.y * sy);
                }
                out.add(new Mon(b.x, b.y, b.width, b.height, sx, sy, pox, poy));
            }
        } catch (Throwable t) {
            Log.warn("dcomp", "buildMonitorMap failed: " + t);
        }
        monitors = out;
    }

    /**
     * Reconcile the backend with a changed display configuration (monitor
     * attached / removed, resolution or DPI change). Invoked by
     * {@link DisplayWatcher}. No-op unless DirectComposition is the active
     * backend.
     *
     * <p>Three cached pieces go stale on a display change and are refreshed
     * here:
     * <ol>
     *   <li>the host window, sized once to the old virtual-screen bounds —
     *       {@link DCompStage#resizeToVirtualScreen()} re-fits it and updates
     *       the origin used for visual offsets;</li>
     *   <li>the logical→physical {@link #monitors} map — rebuilt so DPI
     *       scaling and physical origins are correct for the new layout;</li>
     *   <li>each live pet's last-pushed position — cleared so the next
     *       render {@link #tick()} re-pushes every visual's offset (and
     *       re-evaluates its physical size) against the new origin and
     *       monitor map, even for idle pets whose logical position did not
     *       change.</li>
     * </ol>
     * Runs the registry mutation on the EDT (the registry is EDT-confined).
     */
    static void onDisplaysChanged() {
        if (!active) {
            return;
        }
        onEdt(() -> {
            try {
                if (stage != null) {
                    stage.resizeToVirtualScreen();
                }
            } catch (Throwable t) {
                Log.warn("dcomp", "resizeToVirtualScreen failed: " + t);
            }
            buildMonitorMap();
            // Force every visual to re-push its position (and re-check its
            // physical size) on the next tick — origin / DPI scale may have
            // changed even when a pet's logical position did not.
            for (State s : REGISTRY.values()) {
                s.lastX = Integer.MIN_VALUE;
                s.lastY = Integer.MIN_VALUE;
            }
            Log.info("dcomp", "displays changed → remapped " + monitors.size() + " monitors");
        });
    }

    /** Monitor whose logical bounds contain {@code (x, y)}, or the nearest by
     *  centre distance (pets spawn/walk off-screen), or {@code null} if the
     *  map is empty. */
    private static Mon monitorFor(int x, int y) {
        java.util.List<Mon> ms = monitors;
        if (ms.isEmpty()) {
            return null;
        }
        for (Mon m : ms) {
            if (m.containsLogical(x, y)) {
                return m;
            }
        }
        Mon best = null;
        double bd = Double.MAX_VALUE;
        for (Mon m : ms) {
            double d = m.centerDistSq(x, y);
            if (d < bd) {
                bd = d;
                best = m;
            }
        }
        return best;
    }

    // ──────────────────────────────────────────────────────────────
    //  Called from PetWindow (marshalled onto the EDT)
    // ──────────────────────────────────────────────────────────────

    static void register(PetWindow pw) {
        onEdt(() -> REGISTRY.computeIfAbsent(pw, k -> new State()));
    }

    static void unregister(PetWindow pw) {
        onEdt(() -> {
            State s = REGISTRY.remove(pw);
            if (s != null && s.handle != 0L) {
                stage.destroy(s.handle);
                stage.commit();
            }
        });
    }

    static void toFront(PetWindow pw) {
        onEdt(() -> {
            State s = REGISTRY.get(pw);
            if (s != null && s.handle != 0L && s.shown) {
                // Re-attaching inserts the visual at the front of the root.
                stage.hide(s.handle);
                stage.show(s.handle);
                stage.commit();
            }
        });
    }

    // ──────────────────────────────────────────────────────────────
    //  Render driver (EDT)
    // ──────────────────────────────────────────────────────────────

    private static void tick() {
        if (!active || !stage.isReady() || REGISTRY.isEmpty()) {
            return;
        }
        boolean any = false;
        for (Map.Entry<PetWindow, State> e : REGISTRY.entrySet()) {
            try {
                if (updateOne(e.getKey(), e.getValue())) {
                    any = true;
                }
            } catch (Throwable t) {
                // Never let one pet's failure stall the others.
            }
        }
        if (any) {
            stage.commit();
        }
    }

    /** @return {@code true} if a native change was issued (a commit is needed). */
    private static boolean updateOne(PetWindow pw, State s) {
        if (!pw.isVisibleForDComp()) {
            if (s.shown && s.handle != 0L) {
                stage.hide(s.handle);
                s.shown = false;
                return true;
            }
            return false;
        }
        int w = pw.getWidth();
        int h = pw.getHeight();
        if (w <= 0 || h <= 0) {
            return false;
        }

        // Map the pet's logical position to physical device pixels using the
        // monitor it is (nearest) on — correct across mixed-DPI monitors.
        int lx = pw.getX();
        int ly = pw.getY();
        Mon mon = monitorFor(lx + w / 2, ly + h / 2);
        double sx = mon != null ? mon.sx() : 1.0;
        double sy = mon != null ? mon.sy() : 1.0;

        boolean changed = false;
        int pxW = Math.max(1, (int) Math.round(w * sx));
        int pxH = Math.max(1, (int) Math.round(h * sy));

        // (Re)create the visual on first use or when the physical size changed
        // (pet resized, or moved to a monitor with a different DPI scale).
        if (s.handle == 0L || s.w != pxW || s.h != pxH) {
            if (s.handle != 0L) {
                stage.destroy(s.handle);
            }
            s.handle = stage.createVisual(pxW, pxH);
            s.w = pxW;
            s.h = pxH;
            s.lastHash = 0;
            s.lastX = Integer.MIN_VALUE;
            s.lastY = Integer.MIN_VALUE;
            s.shown = false;
            changed = true;
        }
        if (s.handle == 0L) {
            return changed;
        }

        // Upload pixels only when the pet's appearance actually changed.
        int[] px = renderPanel(pw.panel(), w, h, pxW, pxH, sx, sy);
        int hash = Arrays.hashCode(px);
        if (hash != s.lastHash) {
            stage.updateBitmap(s.handle, px, pxW, pxH);
            s.lastHash = hash;
            changed = true;
        }

        // Move only when the position actually changed (idle pets are free).
        if (lx != s.lastX || ly != s.lastY) {
            int physX = mon != null
                    ? (int) Math.round(mon.px() + (lx - mon.lx()) * sx)
                    : lx;
            int physY = mon != null
                    ? (int) Math.round(mon.py() + (ly - mon.ly()) * sy)
                    : ly;
            stage.setPosition(s.handle, physX, physY);
            s.lastX = lx;
            s.lastY = ly;
            changed = true;
        }

        if (!s.shown) {
            stage.show(s.handle);
            s.shown = true;
            changed = true;
        }
        return changed;
    }

    /**
     * Render a pet panel to a premultiplied-ARGB {@code int[]} of size
     * {@code pxW × pxH}. The panel uses a null layout with explicitly-bounded
     * child labels, so it paints correctly off-screen without being attached
     * to a realised window.
     */
    private static int[] renderPanel(JPanel panel, int w, int h, int pxW, int pxH,
            double sx, double sy) {
        BufferedImage img = new BufferedImage(pxW, pxH, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = img.createGraphics();
        try {
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, pxW, pxH);
            g.setComposite(AlphaComposite.SrcOver);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                    RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            if (sx != 1.0 || sy != 1.0) {
                g.scale(sx, sy);
            }
            panel.setSize(w, h);
            panel.doLayout();
            panel.paint(g);
        } finally {
            g.dispose();
        }
        // A TYPE_INT_ARGB_PRE raster is premultiplied ARGB (0xAARRGGBB); in
        // little-endian memory that is B,G,R,A = DXGI B8G8R8A8_UNORM
        // premultiplied — exactly what the DComp surface expects.
        return ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
    }

    private static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }
}
