package com.desktoppets;

import java.awt.Rectangle;

/**
 * Pure (Swing-independent) math that clips an intended pet bounding box
 * to an active monitor on the horizontal axis. Extracted from
 * {@link Pet#moveFrameTo(int, int)} so it can be unit-tested without a
 * display and reused if we ever apply clipping in another context (e.g.
 * a settings-preview window).
 *
 * <p><b>Why X-only?</b> Pets walk along the floor; trimming a frame
 * vertically when its computed Y briefly lies off-monitor (perch/zoomies)
 * would visually look like the pet is shrinking. Y stays at the intended
 * value with the full pet height.
 */
public final class MonitorClipper {

    private MonitorClipper() {
    }

    /**
     * Result of clipping an intended {@code petSize×petSize} box at
     * {@code (intendedX, intendedY)} to {@code monitor}.
     *
     * <p>{@link #hidden} is {@code true} when the clipped width is zero or
     * negative - i.e. the pet is fully outside the monitor on this axis -
     * in which case {@link #bounds} is {@code null} and the JFrame should
     * be hidden rather than resized to nothing.
     *
     * <p>{@link #offsetX} is the number of sprite columns hidden on the
     * left (positive when the pet has walked off the left edge), used to
     * translate the sprite label inside its (now narrower) frame so the
     * visible columns line up.
     */
    public record Clip(Rectangle bounds, int offsetX, int offsetY, boolean hidden) {
    }

    /**
     * @param intendedX top-left X of the unclipped pet box, logical px
     * @param intendedY top-left Y of the unclipped pet box, logical px
     * @param petSize   pet box side length, logical px (must be > 0)
     * @param monitor   monitor to clip to; if {@code null} the pet is
     *                  returned unclipped (used when the pet isn't bound
     *                  to a specific monitor yet)
     */
    public static Clip clip(int intendedX, int intendedY, int petSize, Rectangle monitor) {
        if (monitor == null) {
            return new Clip(new Rectangle(intendedX, intendedY, petSize, petSize), 0, 0, false);
        }
        int visL = Math.max(intendedX, monitor.x);
        int visR = Math.min(intendedX + petSize, monitor.x + monitor.width);
        int fW = visR - visL;
        if (fW <= 0) {
            return new Clip(null, 0, 0, true);
        }
        return new Clip(
                new Rectangle(visL, intendedY, fW, petSize),
                visL - intendedX,
                0,
                false);
    }
}
