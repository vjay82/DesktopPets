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
        // Single-flight refresh: with several pet threads ticking 5Ã—/sec
        // they often expire the TTL within microseconds of each other.
        // Without this gate every miss-caller would invoke the full
        // Win32 enumeration in parallel. Re-check the cache inside the
        // lock so only the first thread does the work; the others see
        // the freshly-published result on entry.
        synchronized (CACHE) {
            cached = CACHE.get();
            now = System.currentTimeMillis();
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
     * at {@code currentFeetY} so it lands on the highest <em>visible</em>
     * surface at-or-below its current feet at column {@code petX}. The
     * "visible" qualifier matters: another (higher-Z) window can cover
     * the top edge of a lower-Z window at this column, in which case the
     * lower-Z top is not actually visible to the user and the pet
     * standing on it would float over the covering window. So the search
     * walks {@link #topmostWindows} <b>in z-order (top-first; see
     * {@link Win32#topmostWindowRects})</b> and respects occlusion:
     *
     * <ul>
     *   <li>Windows with no horizontal overlap, or whose top is off-screen
     *       above ({@code y < 0}), are skipped.</li>
     *   <li>A higher-Z window whose body extends past the current
     *       "search floor" advances that floor to its bottom — any
     *       lower-Z window whose top falls inside this band is hidden at
     *       column {@code petX} and ignored.</li>
     *   <li>The first not-occluded window whose top is at-or-below the
     *       (advanced) search floor is the landing surface; gravity
     *       returns its top minus {@code petHeight}.</li>
     * </ul>
     *
     * <p>If no window qualifies (pet's surface vanished, every candidate
     * is hidden by something higher-Z, or pet is currently above every
     * visible top), the pet falls to the desktop floor ({@code screenH}).
     * Callers that need a per-monitor work-area cap (e.g.
     * {@link Pet#floorYAt}) apply it on top.
     *
     * <p>The gravity rule "pet only falls" survives this rewrite: any
     * window whose top is above {@code currentFeetY - tolerance} cannot
     * be a landing surface; it only affects {@code searchFloor} (so the
     * pet doesn't accidentally land on a lower-Z window that's hidden
     * underneath the high one).
     */
    public int floorY(int petHeight, int petX, int petWidth, int currentFeetY) {
        // Lower bound (inclusive) for any landing surface's top, in Y
        // coordinates (so "advance downward" = increase). Starts at the
        // pet's feet (minus tolerance) so gravity can't make the pet rise;
        // grows as higher-Z windows are found to cover the region beneath.
        int searchFloor = currentFeetY - FLOOR_TOLERANCE_PX;
        for (Rectangle r : topmostWindows) {
            if (r.x + r.width <= petX || r.x >= petX + petWidth) {
                continue; // no horizontal overlap at this column
            }
            int topY = r.y;
            int botY = r.y + r.height;
            if (topY < 0) {
                // Top is off-screen above. The window's body might still
                // cover the area beneath the pet — advance searchFloor
                // so lower-Z windows under it are correctly hidden.
                if (botY > searchFloor) {
                    searchFloor = botY;
                }
                continue;
            }
            if (topY >= searchFloor) {
                // Top is visible at column petX (no higher-Z window
                // covers it) and at-or-below the pet's feet — land here.
                return topY - petHeight;
            }
            // topY < searchFloor: this window's top is hidden (either
            // above the pet's original feet, or beneath a previously-seen
            // higher-Z window). If its body extends past the current
            // searchFloor it still occludes anything beneath it at this
            // column — advance the floor.
            if (botY > searchFloor) {
                searchFloor = botY;
            }
        }
        // Desktop floor as ultimate fallback.
        return Math.max(0, screenH) - petHeight;
    }

    private record Cached(World world, long takenAt) {
    }
}
