package com.desktoppets;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
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
     * Current mouse cursor location in logical screen coordinates, or
     * {@code null} if it can't be determined (headless, no display, race
     * with screen-config change). Cursor-aware activities (stalk-pointer,
     * fetch-cursor, follow-cursor) consult this each tick — we deliberately
     * do NOT cache it because the user moves the mouse much faster than the
     * 150 ms World cache TTL.
     *
     * <p>Side effect: each call samples the cursor into a small ring buffer
     * (rate-limited to {@value #CURSOR_SAMPLE_MIN_MS} ms between samples)
     * so {@link #cursorMotionPx(long)} can later report how much the cursor
     * has been moving recently. Sampling here (rather than from a dedicated
     * timer) keeps the mechanism free: the same activities that already
     * poll the cursor every tick feed the history.
     */
    public static Point cursorPos() {
        try {
            PointerInfo pi = MouseInfo.getPointerInfo();
            Point p = pi == null ? null : pi.getLocation();
            sampleCursor(p);
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- cursor motion history ----------------
    // A tiny ring buffer of recent cursor (timestamp, x, y) samples used
    // by HUNT_CURSOR to detect "the user is fiddling with the mouse in
    // front of the pet". Sampled by a dedicated background thread (see
    // {@link #startCursorSampler}) so the history stays current even when
    // no activity is polling the cursor — the engine spends most of its
    // time in long-running activities (idle: ~5 s, sleep: longer) and a
    // forced 2..7 s rest window after every non-IDLE pick during which
    // {@code pick()} is short-circuited and no priority lambdas (and thus
    // no cursorPos() calls) fire. Before the sampler existed,
    // {@link #cursorMotionPx(long)} therefore almost always saw
    // {@code cursorCount < 2} inside its 3 s window and returned 0, so
    // HUNT_CURSOR's priority stayed at 0 and the pet never hunted.

    private static final int  CURSOR_HIST_SIZE     = 64;
    private static final long CURSOR_SAMPLE_MIN_MS = 80L;
    private static final Object CURSOR_LOCK = new Object();
    private static final long[] cursorTs = new long[CURSOR_HIST_SIZE];
    private static final int[]  cursorXs = new int[CURSOR_HIST_SIZE];
    private static final int[]  cursorYs = new int[CURSOR_HIST_SIZE];
    private static int  cursorHead       = 0;
    private static int  cursorCount      = 0;
    private static long cursorLastSample = 0L;

    private static void sampleCursor(Point p) {
        if (p == null) return;
        long now = System.currentTimeMillis();
        synchronized (CURSOR_LOCK) {
            if (now - cursorLastSample < CURSOR_SAMPLE_MIN_MS) return;
            cursorLastSample = now;
            cursorTs[cursorHead] = now;
            cursorXs[cursorHead] = p.x;
            cursorYs[cursorHead] = p.y;
            cursorHead = (cursorHead + 1) % CURSOR_HIST_SIZE;
            if (cursorCount < CURSOR_HIST_SIZE) cursorCount++;
        }
    }

    /**
     * Total pixel-distance the cursor has moved across samples taken in
     * the last {@code windowMs} ms (sum of {@code |dx|+|dy|} between
     * consecutive samples). Returns 0 if there is no recent cursor
     * activity (e.g. headless, or nothing has called {@link #cursorPos()}
     * since the JVM started). Used by HUNT_CURSOR to gate on "the cursor
     * has actually been moving" rather than "the cursor exists".
     */
    public static int cursorMotionPx(long windowMs) {
        synchronized (CURSOR_LOCK) {
            if (cursorCount < 2) return 0;
            long cutoff = System.currentTimeMillis() - windowMs;
            int newest = (cursorHead - 1 + CURSOR_HIST_SIZE) % CURSOR_HIST_SIZE;
            int total = 0;
            int prev = newest;
            int seen = 1;
            while (seen < cursorCount) {
                int cur = (prev - 1 + CURSOR_HIST_SIZE) % CURSOR_HIST_SIZE;
                if (cursorTs[cur] < cutoff) break;
                total += Math.abs(cursorXs[prev] - cursorXs[cur])
                       + Math.abs(cursorYs[prev] - cursorYs[cur]);
                prev = cur;
                seen++;
            }
            return total;
        }
    }

    /**
     * Background sampler: polls the cursor every
     * {@value #CURSOR_SAMPLE_MIN_MS} ms into the ring buffer so
     * {@link #cursorMotionPx(long)} stays accurate even while no
     * activity is actively calling {@link #cursorPos()}. Started once
     * from {@link Main}.
     */
    public static void startCursorSampler() {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    PointerInfo pi = MouseInfo.getPointerInfo();
                    if (pi != null) sampleCursor(pi.getLocation());
                } catch (Throwable ignored) {
                    // headless / display change races — ignore and retry
                }
                try {
                    Thread.sleep(CURSOR_SAMPLE_MIN_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "cursor-sampler");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Edge signal: true if the foreground window changed in the last
     * {@value #FG_RECENT_MS} ms (i.e. the user just switched apps). Used by
     * "greet-foreground" / "startle-flush" so pets react to app switches
     * but don't continuously fire while the same window stays in focus.
     */
    public boolean foregroundJustChanged() {
        return foreground != null && foregroundStableMs < FG_RECENT_MS;
    }

    private static final long FG_RECENT_MS = 600L;

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
