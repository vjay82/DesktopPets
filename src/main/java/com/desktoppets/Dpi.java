package com.desktoppets;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;

/**
 * DPI-aware pixel scaling. Reference is 96 DPI. Public so other UI bits
 * (settings dialog, tray icon) can use the same factor.
 *
 * <p>{@link #FACTOR} is the <b>primary</b> monitor's scale and is kept for
 * UI element sizing where a per-monitor value would be impractical (tray
 * icon, default pet size). For coordinate conversions that depend on
 * <em>which</em> monitor a point lies on (e.g. converting Win32
 * physical-pixel window rectangles to AWT logical pixels on multi-monitor
 * setups with mixed DPI) use {@link #scaleXFor(java.awt.Point)} /
 * {@link #scaleYFor(java.awt.Point)} which look up the actual device.
 */
public final class Dpi {

    public static final double FACTOR;
    static {
        double dpi;
        try {
            dpi = Toolkit.getDefaultToolkit().getScreenResolution();
        } catch (Throwable t) {
            dpi = 96;
        }
        FACTOR = dpi / 96.0;
    }

    private Dpi() {
    }

    public static int scale(int px) {
        return (int) Math.round(px * FACTOR);
    }

    /** Scale {@code px} for the given device's DPI. */
    public static int scaleFor(GraphicsDevice gd, int px) {
        return (int) Math.round(px * scaleXFor(gd));
    }

    /** Per-device horizontal scale, falling back to {@link #FACTOR}. */
    public static double scaleXFor(GraphicsDevice gd) {
        if (gd == null) {
            return FACTOR;
        }
        try {
            AffineTransform tx = gd.getDefaultConfiguration().getDefaultTransform();
            double s = tx.getScaleX();
            return s > 0 ? s : FACTOR;
        } catch (Throwable t) {
            return FACTOR;
        }
    }

    /** Per-device vertical scale, falling back to {@link #FACTOR}. */
    public static double scaleYFor(GraphicsDevice gd) {
        if (gd == null) {
            return FACTOR;
        }
        try {
            AffineTransform tx = gd.getDefaultConfiguration().getDefaultTransform();
            double s = tx.getScaleY();
            return s > 0 ? s : FACTOR;
        } catch (Throwable t) {
            return FACTOR;
        }
    }

    /** Snapshot of one monitor's logical bounds + physical bounds + scale. */
    public record MonitorScale(Rectangle logicalBounds,
                               Rectangle physicalBounds,
                               double scaleX,
                               double scaleY) {
        public boolean physicallyContains(int physX, int physY) {
            return physicalBounds.contains(physX, physY);
        }
    }

    /**
     * Snapshot of every screen device's logical and physical bounds plus
     * its DPI scale. Used by {@link Win32#toLogical(Rectangle)} to convert
     * a Win32 GetWindowRect (physical pixels) back to AWT logical pixels
     * even when monitors have different scales.
     *
     * <p>Not cached - GraphicsEnvironment scans are cheap and we want
     * fresh data after a hotplug / DPI change.
     */
    public static List<MonitorScale> monitorScales() {
        List<MonitorScale> out = new ArrayList<>();
        try {
            for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                GraphicsConfiguration cfg = gd.getDefaultConfiguration();
                Rectangle logical = cfg.getBounds();
                AffineTransform tx = cfg.getDefaultTransform();
                double sx = tx.getScaleX();
                double sy = tx.getScaleY();
                if (sx <= 0) sx = 1.0;
                if (sy <= 0) sy = 1.0;
                Rectangle physical = new Rectangle(
                        (int) Math.round(logical.x * sx),
                        (int) Math.round(logical.y * sy),
                        (int) Math.round(logical.width * sx),
                        (int) Math.round(logical.height * sy));
                out.add(new MonitorScale(logical, physical, sx, sy));
            }
        } catch (Throwable t) {
            // headless or other failure - return whatever we collected
        }
        return out;
    }
}

