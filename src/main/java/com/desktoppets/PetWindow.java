package com.desktoppets;

import java.awt.Color;
import java.awt.Component;
import java.awt.Rectangle;

import javax.swing.JPanel;

/**
 * Thin wrapper around the {@link JPanel} that hosts a single pet's labels
 * on the shared {@link Stage}. Mimics just the subset of the
 * {@link javax.swing.JFrame} API the legacy per-pet rendering used, so
 * {@link Pet} can keep its existing field name {@code frame} and most call
 * sites stay unchanged.
 *
 * <p>Coordinates passed to {@link #setLocation(int, int)} /
 * {@link #setBounds(int, int, int, int)} are in <b>virtual-desktop screen
 * pixels</b> (the same coordinate system the old per-pet
 * {@code JFrame.setLocation} accepted). {@link Stage} translates them into
 * the stage canvas's parent-local coords and re-parents the panel between
 * stages as the pet crosses monitors.
 */
public final class PetWindow {

    private final JPanel panel;
    /** {@code true} → render via {@link DCompBackend} (DirectComposition);
     *  {@code false} → the legacy Swing {@link Stage}. Decided once, on the
     *  first pet-window creation, and fixed for this window's life. */
    private final boolean dcomp;
    private int x;
    private int y;
    private int width;
    private int height;
    private boolean visible = true;

    public PetWindow() {
        this.panel = new JPanel(null);
        this.panel.setOpaque(false);
        this.panel.setBackground(new Color(0, 0, 0, 0));
        // DirectComposition is the default backend; ensureInit() returns
        // false (→ Swing Stage fallback) on non-Windows, device-creation
        // failure, or when disabled via desktoppets.dcomp / DESKTOPPETS_DCOMP.
        this.dcomp = DCompBackend.ensureInit();
    }

    /** The underlying Swing container — pet labels are added to it directly.
     *  Under DComp it is painted off-screen; under Swing it lives on the
     *  {@link Stage} canvas. */
    public JPanel panel() {
        return panel;
    }

    public void add(Component child) {
        panel.add(child);
    }

    /** Visibility as seen by the DComp render driver (the {@code visible}
     *  flag, independent of any realised Swing peer). */
    boolean isVisibleForDComp() {
        return visible;
    }

    public void setSize(int w, int h) {
        this.width = w;
        this.height = h;
        if (!dcomp) {
            Stage.setBounds(panel, x, y, w, h);
        }
        // DComp: the render driver reads width/height every frame.
    }

    public void setLocation(int sx, int sy) {
        this.x = sx;
        this.y = sy;
        if (!dcomp) {
            Stage.setLocation(panel, sx, sy);
        }
    }

    public void setBounds(int sx, int sy, int w, int h) {
        this.x = sx;
        this.y = sy;
        this.width = w;
        this.height = h;
        if (!dcomp) {
            Stage.setBounds(panel, sx, sy, w, h);
        }
    }

    public boolean isVisible() {
        return visible && panel.isVisible();
    }

    public void setVisible(boolean v) {
        this.visible = v;
        panel.setVisible(v);
    }

    public int getWidth()  { return width;  }
    public int getHeight() { return height; }
    public int getX()      { return x; }
    public int getY()      { return y; }

    public void revalidate() { panel.revalidate(); }
    public void repaint()    { panel.repaint(); }

    /**
     * Attach the panel to the appropriate backend at its current
     * {@code (x, y)} and size, making it visible. Idempotent — safe to
     * call multiple times.
     */
    public void show() {
        this.visible = true;
        if (dcomp) {
            DCompBackend.register(this);
        } else {
            Stage.attach(panel, x, y);
            // Size may have been set before attach; make sure it sticks.
            Stage.setBounds(panel, x, y, width, height);
        }
        setVisible(true);
    }

    /** Remove the panel from the backend. The wrapper is single-shot — do not
     *  call {@link #show()} after dispose. */
    public void dispose() {
        if (dcomp) {
            DCompBackend.unregister(this);
        } else {
            Stage.detach(panel);
        }
    }

    /** Bring this panel to the front so it paints over the other pets (e.g. a
     *  cardboard box in front of the kitten sitting inside it). */
    public void toFront() {
        if (dcomp) {
            DCompBackend.toFront(this);
        } else {
            Stage.toFront(panel);
        }
    }

    /** Initial-bounds variant of {@link #show()} so callers don't have to
     *  setBounds + show separately on first display. */
    public void show(int sx, int sy, int w, int h) {
        this.x = sx;
        this.y = sy;
        this.width = w;
        this.height = h;
        if (dcomp) {
            DCompBackend.register(this);
        } else {
            Stage.attach(panel, sx, sy);
            Stage.setBounds(panel, sx, sy, w, h);
        }
        setVisible(true);
    }

    /**
     * Attach the panel at the supplied screen-coord bounds and make it
     * visible. Under Swing the panel lands on the stage covering the GIVEN
     * monitor, so a pet whose top-left starts fully OFF that monitor still
     * lands correctly and is clipped until it walks inward. Under DComp
     * positions are absolute virtual-desktop coordinates, so the monitor
     * hint is unused.
     */
    public void showOnMonitor(Rectangle monitor, int sx, int sy, int w, int h) {
        this.x = sx;
        this.y = sy;
        this.width = w;
        this.height = h;
        if (dcomp) {
            DCompBackend.register(this);
        } else {
            Stage.attachToMonitor(panel, monitor, sx, sy, w, h);
        }
        setVisible(true);
    }
}
