package com.desktoppets;

import java.awt.Rectangle;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Snapshot of the world the pet is reasoning about: screen size, foreground
 * window rectangle, taskbar rectangle, the list of currently visible top-level
 * windows the pet can perch on (in Win32 z-order, top-first), and how long
 * the foreground
 * window has been stable.
 *
 * <p>{@link #snapshot(int, int)} is shared across all running pets via a
 * tiny TTL cache — with several pets ticking 5×/sec, naively calling Win32
 * 15×/sec was wasteful. We refresh at most every {@value #TTL_MS} ms.
 */
public record World(int screenW, int screenH, Rectangle foreground, Rectangle taskbar,
                    List<Rectangle> topmostWindows,
                    long foregroundStableMs) {

    private static final long TTL_MS = 150;
    private static final AtomicReference<Cached> CACHE = new AtomicReference<>();
    /** When the current foreground rectangle was first observed. */
    private static volatile long foregroundSeenSinceMs = 0L;
    private static volatile Rectangle lastObservedForeground = null;

    public static World snapshot(int screenW, int screenH) {
        Cached cached = CACHE.get();
        long now = System.currentTimeMillis();
        if (cached != null
                && now - cached.takenAt < TTL_MS
                && cached.world.screenW == screenW
                && cached.world.screenH == screenH) {
            return cached.world;
        }
        Rectangle fg = Win32.foregroundWindowRect();
        Rectangle tb = Win32.taskbarRect();
        List<Rectangle> topmost = Win32.topmostWindowRects(screenW, screenH);
        if (!Objects.equals(fg, lastObservedForeground)) {
            lastObservedForeground = fg;
            foregroundSeenSinceMs = now;
        }
        long stableMs = fg == null ? 0L : Math.max(0L, now - foregroundSeenSinceMs);
        World fresh = new World(screenW, screenH, fg, tb, topmost, stableMs);
        CACHE.set(new Cached(fresh, now));
        return fresh;
    }

    /** Y coordinate the pet should sit at to "stand on" the taskbar, or {@code defaultY}. */
    public int taskbarTopY(int defaultY) {
        return taskbar != null ? taskbar.y : defaultY;
    }

    /**
     * Pet "stay on current surface" tolerance, in logical pixels.
     *
     * <p>A surface counts as still "under the pet's feet" if its top is
     * within this many pixels of the current feet Y. Without slack, sub-px
     * rounding (DPI conversion, sprite metrics) would cause pets to lose
     * the surface they're standing on every couple of ticks.
     */
    private static final int FLOOR_TOLERANCE_PX = 4;

    /**
     * Gravity-based floor: pick the Y for a pet whose feet are currently
     * at {@code currentFeetY} so it stays on the highest visible surface
     * that is <b>at-or-below</b> its current feet. This deliberately
     * prevents pets from teleporting upward when a new window opens
     * overhead \u2014 they only ever <em>fall</em> downward.
     *
     * <p>Considered surfaces: tops of windows in {@link #topmostWindows}
     * whose horizontal span overlaps {@code [petX, petX+petWidth]} and
     * whose top {@code r.y} is {@code >= currentFeetY - tolerance}. Plus
     * the desktop bottom ({@code screenH}) as the always-present fallback.
     *
     * <p>Result: when no window qualifies (pet's surface vanished, or pet
     * is currently above every visible window in this column), the pet
     * drops to the desktop floor. Callers that need a per-monitor work-area
     * cap (e.g. {@code Pet.floorYAt}) apply it on top.
     */
    public int floorY(int petHeight, int petX, int petWidth, int currentFeetY) {
        int bestTop = Math.max(0, screenH); // desktop floor as ultimate fallback
        for (Rectangle r : topmostWindows) {
            if (r.x + r.width <= petX || r.x >= petX + petWidth) {
                continue; // no horizontal overlap
            }
            int topY = r.y;
            if (topY < 0) {
                // Window's top is off-screen above \u2014 unreachable perch.
                continue;
            }
            if (topY < currentFeetY - FLOOR_TOLERANCE_PX) {
                // Surface is ABOVE the pet \u2014 gravity ignores it. This is
                // what stops pets from jumping up when a window opens
                // higher than where they're standing.
                continue;
            }
            if (topY < bestTop) {
                bestTop = topY;
            }
        }
        return bestTop - petHeight;
    }

    private record Cached(World world, long takenAt) {
    }
}
