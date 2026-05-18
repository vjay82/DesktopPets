package com.desktoppets;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * Standalone soccer/play ball. Spawned by a pet's PLAY_BALL activity and
 * lives as its own transparent always-on-top {@link JFrame}, independent of
 * any single pet's window. Any nearby pet (see {@link #INTEREST_RADIUS}) can
 * sense the ball via {@link #active()} and run to chase / kick it
 * ({@link Pet#chaseBall}). Multi-pet scrums emerge naturally: every pet on
 * the same monitor within range converges on the rolling ball, kicks it
 * away from itself, and the next-closest pet takes off after it.
 *
 * <h2>Physics</h2>
 * 1-D rolling along the floor. Each tick the horizontal velocity decays
 * exponentially with {@link #FRICTION_PER_SEC}; once {@code |vx|} drops
 * below {@link #REST_THRESHOLD} the ball is at rest. Hitting a monitor
 * edge bounces with {@link #WALL_BOUNCE} damping so the ball can ricochet
 * across the screen a couple of times before stopping. Y is re-snapped to
 * the floor at the ball's current column each tick using
 * {@link World#floorY}, so the ball rolls correctly on the desktop, the
 * taskbar, or whatever topmost window the column happens to sit under.
 *
 * <h2>Lifecycle</h2>
 * Only one ball exists at a time — spawning a new one disposes any
 * previous instance. Auto-disposes after {@link #IDLE_DESPAWN_MS} of being
 * at rest with no pet inside {@link #INTEREST_RADIUS}. Pets must call
 * {@link #noteInterest()} (which {@link #kick(double)} does automatically)
 * to keep the ball alive.
 *
 * <p>The ball window's z-order is set once at creation via
 * {@code setAlwaysOnTop(true)}; we deliberately don't re-assert topmost
 * each tick the way pets do, so the ball doesn't fight overlapping pet
 * windows for the topmost slot (see Win32.reassertTopmost rationale).
 */
public final class Ball {

    private static final AtomicReference<Ball> ACTIVE = new AtomicReference<>();

    /** Pets notice the ball within this many logical pixels horizontally. */
    public static final int INTEREST_RADIUS = 1400;

    /** Exponential-decay friction coefficient. Higher = ball stops sooner. */
    private static final double FRICTION_PER_SEC = 1.7;
    /** Below this absolute velocity (px/s) we consider the ball at rest. */
    private static final double REST_THRESHOLD = 14.0;
    /** Velocity preserved after a wall bounce (1.0 = elastic, 0 = stick). */
    private static final double WALL_BOUNCE = 0.55;
    /** After kicks stop and no pet shows interest, dispose after this delay. */
    private static final long IDLE_DESPAWN_MS = 12_000L;
    /** Hard cap on a ball's total lifetime regardless of interest. Without
     *  this a multi-pet scrum can keep the ball alive indefinitely
     *  (every kick re-arms IDLE_DESPAWN_MS via noteInterest), and play
     *  visibly never ends. After this many ms the ball auto-disposes;
     *  PLAY_BALL's cooldown then governs how long until the next ball
     *  spawns, giving the screen a calm gap between play sessions. */
    private static final long MAX_LIFETIME_MS = 45_000L;
    /** Once the ball has been alive this long, stop honoring fresh
     *  noteInterest() calls so the idle-despawn timer can finally
     *  catch up even if pets are still circling. Gives a graceful
     *  "pets lose interest" wind-down for ~IDLE_DESPAWN_MS before the
     *  hard MAX_LIFETIME_MS cap fires. */
    private static final long INTEREST_FREEZE_AFTER_MS = 30_000L;
    /** Physics tick period (~60 FPS). */
    private static final long TICK_MS = 16L;
    /** Ignore additional kicks within this many ms of the last accepted one. */
    private static final long KICK_COOLDOWN_MS = 220L;
    /** Hard cap on impulse magnitude (px/s). */
    private static final double MAX_SPEED = 1600.0;
    /** Minimum |vx| (px/s) for the ball to knock a perched visitor off
     *  its perch. Below this the ball is treated as effectively at rest
     *  for collision purposes - a stationary ball under a bird shouldn't
     *  repeatedly trigger the knock-down on every tick. */
    private static final double KNOCK_MIN_SPEED = 60.0;

    private final int sizePx;
    private final Rectangle monitor;
    private final int screenW;
    private final int screenH;

    private volatile double xLogical;
    private volatile int yLogical;
    /** Guarded by {@link #vxLock} for read-modify-write atomicity between the
     *  tick thread's friction/collision math and external {@link #kick} calls
     *  from pet behavior threads. Still {@code volatile} so single reads
     *  (e.g. {@link #vx()} accessor) don't need to take the lock. */
    private volatile double vx = 0;
    private final Object vxLock = new Object();
    private volatile boolean disposed = false;
    private volatile long lastInterestMs;
    private volatile long lastKickMs = 0L;
    /** Wall-clock spawn time, used by MAX_LIFETIME_MS / INTEREST_FREEZE_AFTER_MS. */
    private final long spawnedAtMs = System.currentTimeMillis();

    private JFrame frame;
    private JLabel label;
    private final Thread tickThread;

    private Ball(Rectangle monitor, int xLogical, int yFloor, int sizePx,
                 int screenW, int screenH) {
        this.monitor = monitor;
        this.xLogical = xLogical;
        this.yLogical = yFloor - sizePx;
        this.sizePx = sizePx;
        this.screenW = screenW;
        this.screenH = screenH;
        this.lastInterestMs = System.currentTimeMillis();

        try {
            SwingUtilities.invokeAndWait(this::initOnEdt);
        } catch (Throwable ignored) {
            // headless / EDT shutdown — frame stays null, skip the tick
            // thread entirely so we don't burn CPU on physics for a ball
            // that has no window and can never be seen or interacted with.
        }

        if (frame == null) {
            this.tickThread = null;
            this.disposed = true;
        } else {
            this.tickThread = new Thread(this::tickLoop, "ball-tick");
            this.tickThread.setDaemon(true);
            this.tickThread.start();
        }
    }

    /**
     * Spawn (replacing any previous) the global ball on the given monitor.
     * {@code xLogical} is the desired top-left X of the ball window;
     * {@code yFloor} is the screen-Y its BOTTOM should rest on.
     */
    public static Ball spawn(Rectangle monitor, int xLogical, int yFloor,
                             int sizePx, int screenW, int screenH) {
        Ball previous = ACTIVE.get();
        if (previous != null) {
            previous.dispose();
        }
        // Clamp X to monitor so the ball window doesn't spawn off-screen.
        int minX = monitor.x;
        int maxX = monitor.x + monitor.width - sizePx;
        int clampedX = Math.max(minX, Math.min(maxX, xLogical));
        Ball b = new Ball(monitor, clampedX, yFloor, sizePx, screenW, screenH);
        ACTIVE.set(b);
        return b;
    }

    /** The currently-active ball, or {@code null} if none exists / it was disposed. */
    public static Ball active() {
        Ball b = ACTIVE.get();
        return (b != null && !b.disposed) ? b : null;
    }

    public int width()          { return sizePx; }
    public int centerX()        { return (int) xLogical + sizePx / 2; }
    public int centerY()        { return yLogical + sizePx / 2; }
    public Rectangle monitor()  { return monitor; }
    public double vx()          { return vx; }
    public boolean isAtRest()   { return Math.abs(vx) < REST_THRESHOLD; }
    public boolean isDisposed() { return disposed; }

    /**
     * Mark that a pet is paying attention to this ball. Resets the idle
     * despawn timer so the ball doesn't vanish out from under an
     * approaching chaser.
     *
     * <p>After {@link #INTEREST_FREEZE_AFTER_MS} the timer is no longer
     * extended even by active chasers, so the ball is guaranteed to
     * wind down within ~{@link #IDLE_DESPAWN_MS} of that point and the
     * "play never stops" failure mode (every nearby pet re-arming the
     * timer every behaviour tick) can't perpetuate the scrum forever.
     */
    public void noteInterest() {
        if (System.currentTimeMillis() - spawnedAtMs >= INTEREST_FREEZE_AFTER_MS) {
            return;
        }
        lastInterestMs = System.currentTimeMillis();
    }

    /**
     * Apply a horizontal impulse (positive = right). Per-ball cooldown
     * ({@link #KICK_COOLDOWN_MS}) ignores rapid follow-up kicks so the ball
     * actually gets a chance to travel — without it the kicker would
     * immediately re-detect the ball within kick range on its next tick
     * and stomp on its own kick.
     */
    public void kick(double impulse) {
        long now = System.currentTimeMillis();
        if (now - lastKickMs < KICK_COOLDOWN_MS) {
            return;
        }
        lastKickMs = now;
        // vx is read-modify-written by Ball.step() on the tick thread too;
        // without the lock a kick that interleaves between step()'s read
        // and its write-back gets silently overwritten by step()'s stale
        // "vx * friction" result and the impulse is lost.
        synchronized (vxLock) {
            double newV = vx + impulse;
            if (newV >  MAX_SPEED) newV =  MAX_SPEED;
            if (newV < -MAX_SPEED) newV = -MAX_SPEED;
            this.vx = newV;
        }
        noteInterest();
    }

    /** Dispose the ball window and clear the global slot. Idempotent. */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        ACTIVE.compareAndSet(this, null);
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
        frame.setAlwaysOnTop(true);
        frame.setType(JFrame.Type.UTILITY); // no taskbar entry
        frame.setFocusableWindowState(false); // never steal focus
        frame.setLayout(null);
        label = new JLabel();
        label.setBounds(0, 0, sizePx, sizePx);
        frame.add(label);
        frame.setSize(sizePx, sizePx);
        frame.setLocation((int) xLogical, yLogical);
        frame.setVisible(true);
        Sprites.apply(label, "prop/ball");
    }

    private void tickLoop() {
        long prev = System.nanoTime();
        try {
            while (!disposed && !Thread.currentThread().isInterrupted()) {
                long now = System.nanoTime();
                double dt = (now - prev) / 1_000_000_000.0;
                prev = now;
                step(dt);
                Thread.sleep(TICK_MS);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void step(double dt) {
        // --- Horizontal physics ---
        // Hold vxLock for the whole read-modify-write so a concurrent kick()
        // from a pet thread can't be lost by step's stale write-back.
        synchronized (vxLock) {
            if (Math.abs(vx) > 0.001) {
                double frictionFactor = Math.exp(-FRICTION_PER_SEC * dt);
                double newX = xLogical + vx * dt;
                double newV = vx * frictionFactor;
                if (Math.abs(newV) < REST_THRESHOLD) {
                    newV = 0;
                }
                int minX = monitor.x;
                int maxX = monitor.x + monitor.width - sizePx;
                if (newX < minX) {
                    newX = minX;
                    newV = -newV * WALL_BOUNCE;
                    if (Math.abs(newV) < REST_THRESHOLD) newV = 0;
                } else if (newX > maxX) {
                    newX = maxX;
                    newV = -newV * WALL_BOUNCE;
                    if (Math.abs(newV) < REST_THRESHOLD) newV = 0;
                }
                xLogical = newX;
                vx = newV;
            }
        }

        // --- Floor snap (same gravity rules as pets) ---
        World world = World.snapshot(screenW, screenH);
        int currentFeetY = yLogical + sizePx;
        int snappedTop = world.floorY(sizePx, (int) xLogical, sizePx, currentFeetY);
        int monBottomTop = monitor.y + monitor.height - sizePx;
        int newY = Math.min(snappedTop, monBottomTop);
        yLogical = newY;

        // --- EDT position update ---
        if (frame != null) {
            final int fx = (int) xLogical;
            final int fy = yLogical;
            SwingUtilities.invokeLater(() -> {
                if (!disposed && frame != null) {
                    frame.setLocation(fx, fy);
                }
            });
        }

        // --- Visitor knock-down: a moving ball that overlaps a perched
        // visitor (e.g. a Bird sitting on the floor or a window-top
        // perch) pops it off its perch. The visitor loop sees the
        // knockedDown flag and routes through Pet.fallOutAndExit instead
        // of the normal scared/timeout fly-away, so the bird drops out
        // of the bottom of the screen. We require non-trivial speed so a
        // ball at rest under the bird (e.g. it rolled to a stop right
        // there) doesn't keep re-triggering the knock-down on every tick.
        if (Math.abs(vx) >= KNOCK_MIN_SPEED) {
            int bxL = (int) xLogical;
            int bxR = bxL + sizePx;
            int byT = yLogical;
            int byB = yLogical + sizePx;
            for (Pet p : Pet.activePets()) {
                if (!p.isVisitor() || p.knockedDown) {
                    continue;
                }
                java.awt.Point loc = p.logicalLocation();
                int pw = p.effectiveWidth();
                int ph = p.effectiveHeight();
                if (bxR <= loc.x || bxL >= loc.x + pw) continue;
                if (byB <= loc.y || byT >= loc.y + ph) continue;
                p.knockDown(centerX());
                // Ball loses most of its energy on impact so it doesn't
                // keep rolling through the (still-present-for-this-tick)
                // bird and re-fire on the very next step.
                synchronized (vxLock) {
                    vx *= 0.2;
                }
                break;
            }
        }

        // --- Idle despawn ---
        long age = System.currentTimeMillis() - spawnedAtMs;
        if (age >= MAX_LIFETIME_MS) {
            // Hard cap: end the play session even if pets are still
            // actively kicking. Prevents indefinite scrums.
            dispose();
            return;
        }
        if (isAtRest() && System.currentTimeMillis() - lastInterestMs > IDLE_DESPAWN_MS) {
            dispose();
        }
    }
}
