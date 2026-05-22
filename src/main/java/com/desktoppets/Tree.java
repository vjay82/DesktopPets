package com.desktoppets;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * Standalone "pee tree" world object. Spawned by a pet's
 * {@link Activities#PEE_TREE} activity when it happens to be loitering near
 * a monitor edge; lives as its own transparent always-on-top {@link JFrame}
 * that fades in over {@link #FADE_IN_MS}, holds while pets visit it (each
 * pee resets {@link #lastInterestMs}), and fades out after
 * {@link #IDLE_DESPAWN_MS} of no interest (capped by {@link #MAX_LIFETIME_MS}).
 *
 * <p>Only one tree exists at a time. A long global cooldown
 * ({@link #SPAWN_COOLDOWN_MS}) gates how often a new tree may appear, so
 * the gag stays rare. While a tree is alive, every nearby pet on the same
 * monitor can join in via {@link Activities#PEE_TREE} (independent of the
 * per-pet activity cooldown gate — a single tree session can host multiple
 * pets without each needing to wait out the cooldown).
 *
 * <p>Independent of {@link Ball}; both are world objects that the
 * behaviour engine peeks at outside its normal activity selection.
 */
public final class Tree {

    private static final AtomicReference<Tree> ACTIVE = new AtomicReference<>();
    /** Wall-clock instant of the last completed (or ending) tree session.
     *  Used to gate {@link #spawnCooldownRemainingMs()}. */
    private static final AtomicLong LAST_FINISHED_MS = new AtomicLong(0L);

    /** Global quiet period between trees; refreshed at the start of the
     *  fade-out of each tree. ~5 min so this stays a rare gag. */
    private static final long SPAWN_COOLDOWN_MS = 5L * 60_000L;

    /** Pets on the same monitor whose midline is within this many logical
     *  px of the trunk are considered "near the tree" and will join. */
    public static final int INTEREST_RADIUS = 1200;

    /** A pet within this many logical px of the nearest left/right monitor
     *  edge is eligible to summon a tree. Keeps the tree from spawning in
     *  the middle of the screen. */
    public static final int EDGE_PROXIMITY_PX = 280;

    private static final long FADE_IN_MS  = 1_200L;
    private static final long FADE_OUT_MS = 1_500L;
    /** Hard cap on the visible lifetime of one tree (including fades). */
    private static final long MAX_LIFETIME_MS = 35_000L;
    /** After this much idle time (no pet showed interest) the tree begins
     *  its fade-out. Long enough for a second pet to amble over after the
     *  first one finishes. */
    private static final long IDLE_DESPAWN_MS = 8_000L;
    /** Opacity-tween + lifecycle tick period (~30 FPS). */
    private static final long TICK_MS = 33L;

    private final Rectangle monitor;
    private final int widthPx;
    private final int heightPx;
    private final int xLogical;
    private final int yLogical;
    private final long spawnedAtMs = System.currentTimeMillis();
    private volatile long lastInterestMs = spawnedAtMs;
    private volatile long endingStartedAtMs = 0L;
    private volatile boolean ending = false;
    private volatile boolean disposed = false;
    private volatile float currentOpacity = 0f;

    private JFrame frame;
    private JLabel label;
    /** Pre-rendered tree image at the chosen {@link #widthPx} x
     *  {@link #heightPx}. Stays constant once loaded; per-tick fades
     *  re-composite it into a fresh {@link ImageIcon} at the desired
     *  alpha (see {@link #applyAlpha}). */
    private volatile Image baseImage;
    private final Thread tickThread;

    private Tree(Rectangle monitor, int xLogical, int yLogicalTop,
                 int widthPx, int heightPx) {
        this.monitor = monitor;
        this.xLogical = xLogical;
        this.yLogical = yLogicalTop;
        this.widthPx = widthPx;
        this.heightPx = heightPx;

        try {
            SwingUtilities.invokeAndWait(this::initOnEdt);
        } catch (Throwable ignored) {
            // Headless or EDT shutdown — leave frame null; tick thread
            // won't be started and the tree is immediately considered
            // disposed so callers fall back to no-op.
        }
        if (frame == null) {
            this.tickThread = null;
            this.disposed = true;
        } else {
            this.tickThread = new Thread(this::tickLoop, "tree-tick");
            this.tickThread.setDaemon(true);
            this.tickThread.start();
        }
    }

    /**
     * Spawn (replacing any previous) the global tree on the given monitor,
     * resting on the floor at {@code floorBottomY} (the Y the tree's
     * BOTTOM should sit at).
     */
    public static Tree spawn(Rectangle monitor, boolean leftEdge,
                             int floorBottomY, int widthPx, int heightPx) {
        Tree previous = ACTIVE.get();
        if (previous != null) {
            previous.dispose();
        }
        int margin = 8;
        int x = leftEdge
                ? monitor.x + margin
                : monitor.x + monitor.width - widthPx - margin;
        int y = floorBottomY - heightPx;
        Tree t = new Tree(monitor, x, y, widthPx, heightPx);
        ACTIVE.set(t);
        return t;
    }

    /** Currently-active tree, or {@code null} if none exists / it has been
     *  disposed. While the tree is in its fade-out wind-down this still
     *  returns the instance so latecomers can detect it via
     *  {@link #isEnding()} and choose not to bother walking over. */
    public static Tree active() {
        Tree t = ACTIVE.get();
        return (t != null && !t.disposed) ? t : null;
    }

    /** Remaining ms before another tree may spawn. 0 while a tree is
     *  currently active (the spawn replaces the existing one). */
    public static long spawnCooldownRemainingMs() {
        if (active() != null) {
            return 0L;
        }
        long last = LAST_FINISHED_MS.get();
        if (last == 0L) return 0L;
        long elapsed = System.currentTimeMillis() - last;
        long remaining = SPAWN_COOLDOWN_MS - elapsed;
        return remaining > 0 ? remaining : 0L;
    }

    public int trunkCenterX() { return xLogical + widthPx / 2; }
    public int trunkBaseY()   { return yLogical + heightPx; }
    public int width()        { return widthPx; }
    public Rectangle monitor() { return monitor; }
    public boolean isEnding()  { return ending; }
    public boolean isDisposed() { return disposed; }

    /** Reset the idle despawn timer so the tree doesn't fade out while
     *  a pet is walking up to it. No-op once the fade-out has begun. */
    public void noteInterest() {
        if (ending) return;
        lastInterestMs = System.currentTimeMillis();
    }

    /** Dispose immediately (no fade). Idempotent. */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        ACTIVE.compareAndSet(this, null);
        LAST_FINISHED_MS.set(System.currentTimeMillis());
        if (tickThread != null) {
            tickThread.interrupt();
        }
        if (frame != null) {
            SwingUtilities.invokeLater(() -> {
                frame.setVisible(false);
                frame.dispose();
            });
        }
    }

    private void initOnEdt() {
        frame = new JFrame();
        frame.setUndecorated(true);
        frame.setBackground(new Color(0, 0, 0, 0));
        // Intentionally NOT always-on-top: pet windows are always-on-top,
        // so leaving the tree off that layer puts it visually BEHIND the
        // pets (the desired "scenery" effect) without any explicit
        // z-order juggling.
        frame.setAlwaysOnTop(false);
        frame.setType(JFrame.Type.UTILITY);
        frame.setFocusableWindowState(false);
        frame.setLayout(null);
        label = new JLabel();
        label.setBounds(0, 0, widthPx, heightPx);
        frame.add(label);
        frame.setSize(widthPx, heightPx);
        frame.setLocation(xLogical, yLogical);
        frame.setVisible(true);
        // Pre-render the tree SVG at the exact target dimensions. We use
        // the raw classpath key (not "prop/tree") so we skip the
        // Doodle-icon path, which assumes square icons and would collapse
        // our 0.6 aspect ratio to a square. The cached result is shared
        // across spawns at the same size.
        ImageIcon base = Sprites.scaled("Sprites/Props/tree.svg", widthPx, heightPx);
        baseImage = (base != null) ? base.getImage() : null;
        // Start fully transparent — the tick loop ramps alpha up over
        // FADE_IN_MS so the tree visibly fades in instead of plopping.
        applyAlpha(0f);
    }

    /**
     * Re-composite {@link #baseImage} at the given alpha into a fresh
     * {@link ImageIcon} and apply it to the label on the EDT. Used
     * instead of {@link java.awt.Window#setOpacity} because the latter
     * doesn't compose reliably with the per-pixel translucent
     * background ({@code new Color(0,0,0,0)}) on Windows — the call is
     * silently rejected and the fade is invisible.
     */
    private void applyAlpha(float alpha) {
        Image src = baseImage;
        if (src == null) return;
        float a = clamp01(alpha);
        BufferedImage out = new BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
            g.drawImage(src, 0, 0, widthPx, heightPx, null);
        } finally {
            g.dispose();
        }
        ImageIcon icon = new ImageIcon(out);
        SwingUtilities.invokeLater(() -> {
            if (label != null && !disposed) {
                label.setIcon(icon);
            }
        });
    }

    private void tickLoop() {
        try {
            while (!disposed && !Thread.currentThread().isInterrupted()) {
                long now = System.currentTimeMillis();
                long age = now - spawnedAtMs;

                if (!ending) {
                    boolean lifetimeUp = age >= MAX_LIFETIME_MS;
                    boolean idleUp = (now - lastInterestMs >= IDLE_DESPAWN_MS)
                            && age >= FADE_IN_MS + 1_500L;
                    if (lifetimeUp || idleUp) {
                        ending = true;
                        endingStartedAtMs = now;
                        // Start the global cooldown the moment we decide
                        // to wind down so a new tree can't be queued back
                        // to back if pets happen to roll PEE_TREE again
                        // before this one's fade completes.
                        LAST_FINISHED_MS.set(now);
                    }
                }

                float opacity;
                if (ending) {
                    long t = now - endingStartedAtMs;
                    opacity = clamp01(1f - t / (float) FADE_OUT_MS);
                    if (t >= FADE_OUT_MS) {
                        dispose();
                        return;
                    }
                } else if (age < FADE_IN_MS) {
                    opacity = clamp01(age / (float) FADE_IN_MS);
                } else {
                    opacity = 1f;
                }

                if (Math.abs(opacity - currentOpacity) >= 0.01f
                        || opacity == 0f || opacity == 1f) {
                    currentOpacity = opacity;
                    applyAlpha(opacity);
                }
                Thread.sleep(TICK_MS);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
