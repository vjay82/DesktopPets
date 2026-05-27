package com.desktoppets;

import java.awt.Color;
import java.awt.Component;

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
    private int x;
    private int y;
    private int width;
    private int height;
    private boolean visible = true;

    public PetWindow() {
        this.panel = new JPanel(null);
        this.panel.setOpaque(false);
        this.panel.setBackground(new Color(0, 0, 0, 0));
    }

    /** The underlying Swing container — pet labels are added to it directly. */
    public JPanel panel() {
        return panel;
    }

    public void add(Component child) {
        panel.add(child);
    }

    public void setSize(int w, int h) {
        this.width = w;
        this.height = h;
        Stage.setBounds(panel, x, y, w, h);
    }

    public void setLocation(int sx, int sy) {
        this.x = sx;
        this.y = sy;
        Stage.setLocation(panel, sx, sy);
    }

    public void setBounds(int sx, int sy, int w, int h) {
        this.x = sx;
        this.y = sy;
        this.width = w;
        this.height = h;
        Stage.setBounds(panel, sx, sy, w, h);
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
     * Attach the panel to the appropriate stage at its current
     * {@code (x, y)} and size, making it visible. Idempotent — safe to
     * call multiple times.
     */
    public void show() {
        Stage.attach(panel, x, y);
        // Size may have been set before attach; make sure it sticks.
        Stage.setBounds(panel, x, y, width, height);
        setVisible(true);
    }

    /** Remove the panel from the stage. The wrapper is single-shot — do not
     *  call {@link #show()} after dispose. */
    public void dispose() {
        Stage.detach(panel);
    }

    /** Initial-bounds variant of {@link #show()} so callers don't have to
     *  setBounds + show separately on first display. */
    public void show(int sx, int sy, int w, int h) {
        this.x = sx;
        this.y = sy;
        this.width = w;
        this.height = h;
        Stage.attach(panel, sx, sy);
        Stage.setBounds(panel, sx, sy, w, h);
        setVisible(true);
    }
}
