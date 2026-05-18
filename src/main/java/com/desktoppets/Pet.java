package com.desktoppets;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * Base class for all desktop pets. Owns its borderless window and provides the
 * animation primitives ({@link #idle()}, {@link #walkTo(int, int)}, …) used
 * by both the {@link BehaviorEngine} and direct mouse interactions.
 *
 * <p>All visuals are now drawn procedurally by {@link Doodle} — there are no
 * sprite files. Pet size is settable at runtime via {@link #setSize(int)}.
 * Activity level (0..2, 1 = normal) gates how often the FSM picks active
 * behaviours over plain idle.
 */
public abstract class Pet implements Runnable {

    // --- geometry (mutable; recomputed by setSize) ---
    public volatile int petSize;
    private int heartX, heartY, heartW, heartH, propW, propH;

    // --- timings ---
    private static final int IDLE_FRAME_MS    = 220;
    private static final int IDLE_TAIL_MS     = 80;
    private static final int HEART_FRAME_MS   = 90;
    private static final int HEART_TAIL_MS    = 60;
    private static final int HEART_FRAME_COUNT = 24;
    private static final int SLEEP_HOLD_MS    = 1200;
    private static final int EAT_HOLD_MS      = 600;
    private static final int PROP_FADE_MS     = 200;
    private static final int SIT_HOLD_MS      = 1500;
    private static final int STRETCH_HOLD_MS  = 700;
    private static final int LOOK_HOLD_MS     = 500;

    public final Dimension screen = detectScreenSize();
    /** Created on the EDT in {@link #initOnEdt()}; null until {@link #run()} starts. */
    public JFrame frame;
    public final JLabel petLabel   = new JLabel();
    public final JLabel heartLabel = new JLabel();
    public final JLabel propLabel  = new JLabel();

    public final NeedSet needs = new NeedSet();
    public final Personality personality;
    public final String name;

    /** Mutated from the AWT mouse listener; read from the behavior thread. */
    public volatile boolean hovered = false;
    public volatile boolean clicked = false;

    /** Throttles hover-driven affection gains so a parked cursor can't max it instantly. */
    private volatile long lastHoverGainAtMs = 0L;
    private static final long HOVER_GAIN_INTERVAL_MS = 1500L;
    private static final double HOVER_GAIN_AMOUNT = 30.0;

    /** Set by {@link PetSupervisor} to gate animation between frames. */
    public final AtomicBoolean paused = new AtomicBoolean(false);

    /** 0 = lethargic, 1 = normal, 2 = hyperactive. Read by {@link BehaviorEngine}. */
    public volatile double activityLevel = 1.0;

    private Thread heartThread = new Thread();
    private MouseListener mouseListener;

    protected Pet(String name, Personality personality) {
        this.name = name;
        this.personality = personality;
        applyGeometry(Dpi.scale(64));
    }

    private void applyGeometry(int newSize) {
        this.petSize = newSize;
        // Heart hovers just above the pet's head. Each subclass overrides
        // {@link #heartCenterXRatio()} / {@link #heartTopYRatio()} because
        // the sprite isn't centred in its viewBox the same way for every species.
        this.heartW = (int) (newSize * 0.35);
        this.heartH = (int) (newSize * 0.30);
        this.heartX = Math.max(0,
                Math.min(newSize - heartW,
                        (int) (newSize * heartCenterXRatio()) - heartW / 2));
        this.heartY = Math.max(0,
                (int) (newSize * heartTopYRatio()) - heartH);
        this.propW  = (int) (newSize * 0.60);
        this.propH  = (int) (newSize * 0.60);
    }

    /**
     * Horizontal centre of the heart, as a fraction of pet width (0..1).
     * Defaults to the bbox centre of the idle sprite; subclasses may override
     * for artistic placement (e.g. heart slightly to one side of the head).
     */
    protected double heartCenterXRatio() { return metrics().bodyCenterXRatio(); }

    /**
     * Y of the top of the pet's head, as a fraction of pet height (0..1).
     * The heart sits with its bottom edge at this Y. Defaults to the top of
     * the idle sprite's non-empty pixels.
     */
    protected double heartTopYRatio() { return metrics().headTopYRatio(); }

    /**
     * Y of the pet's feet within its frame, as a fraction of pet height (0..1).
     * 1.0 means the sprite occupies the full frame down to the bottom row;
     * smaller values mean there is transparent padding below the feet inside
     * the frame. Used by {@link Activities#WANDER} / {@link Activities#IDLE} to
     * land the pet's feet on the floor instead of its frame bottom (otherwise
     * pets with padding appear to float). Defaults to the bottom of the idle
     * sprite's non-empty pixels — self-correcting if sprites change.
     */
    protected double feetYRatio() { return metrics().feetYRatio(); }

    /** Auto-detected geometry of this pet's idle sprite. Cached per JVM. */
    private SpriteMetrics.Bounds metrics() {
        return SpriteMetrics.of(Doodle.resolve(doodleKind() + "/idle/0"));
    }

    // ---------------- subclass hooks ----------------

    /** Lowercase pet key used to build doodle keys (e.g. "ducky"). */
    protected abstract String doodleKind();

    /** Idle frames shown by {@link #idle()}. */
    protected List<String> idleFrames() {
        return List.of(
                doodleKind() + "/idle/0",
                doodleKind() + "/idle/1",
                doodleKind() + "/idle/2");
    }

    protected List<String> walkLeftFrames() {
        return frames(doodleKind() + "/walk-left", 0, 5);
    }

    protected List<String> walkRightFrames() {
        return frames(doodleKind() + "/walk-right", 0, 5);
    }

    protected int walkStepDelayMs() { return 10; }
    protected int pixelsPerSpriteStep() { return 16; }

    public void hover() {
        if (!heartThread.isAlive()) {
            heartThread = new Thread(this::hearts, "hearts-" + name);
            heartThread.setDaemon(true);
            heartThread.start();
        }
        attack();
        long now = System.currentTimeMillis();
        if (now - lastHoverGainAtMs >= HOVER_GAIN_INTERVAL_MS) {
            needs.add(Need.AFFECTION, HOVER_GAIN_AMOUNT);
            lastHoverGainAtMs = now;
        }
    }

    protected void attack() {
        idle();
    }

    public void onClicked() {
        idle();
        clicked = false;
    }

    protected int spawnBottomOffset() {
        return petSize;
    }

    // ---------------- lifecycle ----------------

    @Override
    public final void run() {
        try {
            initOnEdt();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        } catch (InvocationTargetException ie) {
            Log.warn("pet:" + name, "init failed: " + ie.getCause());
            return;
        }
        new BehaviorEngine(this).run();
    }

    public final void disposeWindow() {
        if (mouseListener != null) {
            petLabel.removeMouseListener(mouseListener);
            mouseListener = null;
        }
        if (frame == null) {
            return;
        }
        onEdt(() -> {
            frame.setVisible(false);
            frame.dispose();
        });
        Log.info("pet:" + name, "disposed");
    }

    /**
     * Re-assert {@code HWND_TOPMOST} on this pet's native window. Cheap,
     * idempotent, and does not steal focus. Called by {@link BehaviorEngine}
     * once per tick so the pet stays in the topmost band even if another app
     * (or a focus / mode switch) demotes it.
     */
    public final void reassertTopmost() {
        if (hwnd != 0L) {
            Win32.reassertTopmost(hwnd);
        }
    }

    private void initOnEdt() throws InterruptedException, InvocationTargetException {
        Runnable build = () -> {
            ImageIcon favIcon = Sprites.scaled(doodleKind() + "/idle/0", 32, 32);

            frame = new JFrame();
            frame.setSize(petSize, petSize);
            frame.setUndecorated(true);
            frame.setBackground(new Color(0, 0, 0, 0));
            frame.setAlwaysOnTop(true);
            frame.setType(JFrame.Type.UTILITY); // no taskbar entry per pet
            if (favIcon != null) {
                frame.setIconImage(favIcon.getImage());
            }
            // Absolute positioning: layoutLabels() and applyLabelOffset()
            // call setBounds/setLocation directly, and we don't want the
            // default BorderLayout to override them (BorderLayout would
            // stretch petLabel to fill the content pane, and when the
            // frame is narrowed by edge clipping in moveFrameTo, the
            // sprite icon would be rendered centered in the smaller
            // label - looking like the pet was zooming/shrinking instead
            // of sliding off-screen).
            frame.setLayout(null);

            layoutLabels();
            frame.add(heartLabel);
            frame.add(propLabel);
            frame.add(petLabel);

            Point saved = Config.readPosition(name);
            if (saved != null && !isOnVisibleMonitor(saved)) {
                Log.info("pet:" + name,
                        "saved position " + saved.x + "," + saved.y
                        + " is not on any visible monitor, falling back to default"
                        + " (config preserved until pet moves)");
                discardedSavedPos = saved;
                saved = null;
            }
            if (saved != null && savedOverlapsTaskbar(saved)) {
                Log.info("pet:" + name,
                        "saved position " + saved.x + "," + saved.y
                        + " overlaps the taskbar work-area, falling back to default"
                        + " (config preserved until pet moves)");
                discardedSavedPos = saved;
                saved = null;
            }
            Rectangle primary = primaryMonitorBounds();
            Point clamped;
            Point firstSetLocation;
            Rectangle initialBounds; // exact bounds for the first frame.setBounds
            if (saved != null) {
                clamped = clampToScreen(saved);
                firstSetLocation = clamped;
                pendingEntryTargetX = null;
                // Prefer the explicitly saved monitor when it still exists;
                // otherwise fall back to whichever monitor contains the
                // clamped position. Persisting the monitor disambiguates
                // cases where two monitors overlap in coordinate space or
                // where the same logical X belongs to a different physical
                // device after a hotplug.
                Rectangle savedMon = Config.readMonitor(name);
                Rectangle boundMon = null;
                if (savedMon != null) {
                    try {
                        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                            if (d.getDefaultConfiguration().getBounds().equals(savedMon)) {
                                boundMon = savedMon;
                                break;
                            }
                        }
                    } catch (Throwable t) {
                        // ignore - fall through to containment lookup
                    }
                }
                if (boundMon == null) {
                    boundMon = monitorContaining(clamped, primary);
                }
                activeMonitor = boundMon;
                initialBounds = new Rectangle(clamped.x, clamped.y, petSize, petSize);
            } else {
                // Pick a random visible monitor and a random side. The pet's
                // intended logical position starts FULLY OUTSIDE the chosen
                // monitor's entry edge so the first behaviour tick's
                // walkAlongFloor traverses the full distance with proper
                // edge clipping (the JFrame slides in from off-screen,
                // never straddling two monitors).
                EntryPlan plan = pickEntryPlan(primary);
                clamped = plan.target;
                firstSetLocation = plan.entryStart;
                pendingEntryTargetX = plan.target.x;
                activeMonitor = plan.monitor;
                // The native peer needs a non-zero size for setVisible() to
                // create the HWND we look up below. Use a 1-px-wide slice
                // flush with the inside of the entry edge: barely visible,
                // and the first walkAlongFloor() step will grow it.
                int peerEdgeX = plan.fromRight
                        ? plan.monitor.x + plan.monitor.width - 1
                        : plan.monitor.x;
                initialBounds = new Rectangle(peerEdgeX, plan.entryStart.y, 1, petSize);
                Log.info("pet:" + name,
                        "entry from " + (plan.fromRight ? "right" : "left")
                        + " of monitor " + plan.monitor.width + "x" + plan.monitor.height
                        + "@(" + plan.monitor.x + "," + plan.monitor.y + ")"
                        + " \u2192 target " + plan.target.x + "," + plan.target.y);
            }
            initialSpawn = clamped;
            // Seed the intended logical position so logicalLocation() is
            // correct from the very first behaviour tick (it would
            // otherwise return (0,0) until the first moveFrameTo).
            this.intendedX = firstSetLocation.x;
            this.intendedY = firstSetLocation.y;
            frame.setBounds(initialBounds.x, initialBounds.y, initialBounds.width, initialBounds.height);
            // Set a unique title BEFORE setVisible so we can find the native
            // HWND right after the peer is created and re-assert HWND_TOPMOST
            // periodically. Undecorated frames don't render the title bar, so
            // this isn't visible to the user.
            String uniqueTitle = "DesktopPet-" + name + "-"
                    + Long.toHexString(System.nanoTime());
            frame.setTitle(uniqueTitle);
            frame.setVisible(true);
            hwnd = Win32.findWindowByTitle(uniqueTitle);

            // Force an immediate first frame so the pet is visible without
            // waiting for the behavior loop to tick once.
            Sprites.apply(petLabel, idleFrames().get(0));

            Log.info("pet:" + name,
                    "spawned size=" + petSize
                    + " at " + clamped.x + "," + clamped.y
                    + " (screen " + (int) screen.getWidth() + "x" + (int) screen.getHeight()
                    + ", saved=" + (saved != null) + ")"
                    + " visible=" + frame.isVisible()
                    + " onTop=" + frame.isAlwaysOnTop()
                    + " hwnd=0x" + Long.toHexString(hwnd));

            mouseListener = new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { clicked = true; }
                @Override public void mouseEntered(MouseEvent e) { hovered = true; }
                @Override public void mouseExited(MouseEvent e)  { hovered = false; }
            };
            petLabel.addMouseListener(mouseListener);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            build.run();
        } else {
            SwingUtilities.invokeAndWait(build);
        }
    }

    private void layoutLabels() {
        petLabel.setBounds(0, 0, petSize, petSize);
        heartLabel.setBounds(heartX, heartY, heartW, heartH);
        propLabel.setBounds(
                (petSize - propW) / 2 + (int) (petSize * 0.30),
                petSize - propH, propW, propH);
    }

    private Point clampToScreen(Point p) {
        // Clamp by feet position, not by full frame height: the duck sprite
        // has ~16 px of transparent padding below its feet inside its 64-px
        // viewBox, so allowing the frame to extend past screen.height-feetH
        // would lift the visible body off the floor and make it "float".
        // Use the actual union of attached GraphicsDevice bounds so monitors
        // to the left of / above the primary (negative x or y) aren't clipped
        // away — Toolkit.getScreenSize() is primary-only on many JVMs.
        Rectangle u = screenUnionBounds();
        int feetH = Math.max(1, (int) Math.round(petSize * feetYRatio()));
        int x = Math.max(u.x,  Math.min(p.x, u.x + u.width  - petSize));
        int y = Math.max(u.y,  Math.min(p.y, u.y + u.height - feetH));
        return new Point(x, y);
    }

    /** Union of all attached {@link GraphicsDevice} bounds, including negative
     *  coordinates. Falls back to {@code (0, 0, screen.width, screen.height)}. */
    private Rectangle screenUnionBounds() {
        try {
            Rectangle union = null;
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle b = d.getDefaultConfiguration().getBounds();
                union = union == null ? new Rectangle(b) : union.union(b);
            }
            if (union != null) {
                return union;
            }
        } catch (Throwable t) {
            // fall through
        }
        return new Rectangle(0, 0, (int) screen.getWidth(), (int) screen.getHeight());
    }

    /**
     * True if at least the top-left corner of the proposed pet frame lies
     * inside some currently-attached {@link GraphicsDevice}. We use this to
     * detect saved positions that point at a monitor which is now off or
     * disconnected — in that case the pet would spawn invisibly on a virtual
     * desktop the user can't see.
     */
    private boolean isOnVisibleMonitor(Point p) {
        try {
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                if (d.getDefaultConfiguration().getBounds().contains(p)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // If GraphicsEnvironment throws, assume the saved position is fine
            // and let clampToScreen handle it.
            return true;
        }
        return false;
    }

    /**
     * True if the saved {@code (x, y)} top-left position would place the
     * pet's feet inside (or below) the desktop work-area bottom at column
     * {@code x} \u2014 i.e., partially or fully behind the taskbar.
     *
     * <p>Old config files from before the DPI-aware work-area fix can
     * contain such positions; we treat them as invalid so the pet re-runs
     * its random entry walk instead of silently materialising inside the
     * taskbar. A small {@code 2 px} slack lets pets resting exactly on the
     * work-area floor still count as valid.
     */
    private boolean savedOverlapsTaskbar(Point p) {
        int feetH = (petLabel != null && petLabel.getHeight() > 0)
                ? petLabel.getHeight()
                : petSize;
        int feetY = p.y + feetH;
        return feetY > monitorBottomAtColumn(p.x) + 2;
    }

    /** Bounds of the primary {@link GraphicsDevice}, or the union as fallback. */
    private Rectangle primaryMonitorBounds() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        } catch (Throwable t) {
            return new Rectangle(0, 0, (int) screen.getWidth(), (int) screen.getHeight());
        }
    }

    /**
     * Picks a random visible monitor and a random side (left or right) for
     * the spawn entry walk. The pet starts just outside the chosen side at
     * the monitor's work-area floor and a target X is chosen at random
     * within the monitor's interior; {@link #walkAlongFloor} then carries
     * the pet onto the screen along the floor.
     *
     * <p>{@code primary} is used only as a safe fallback if {@code
     * GraphicsEnvironment} throws or returns no devices.
     */
    private EntryPlan pickEntryPlan(Rectangle primary) {
        Rectangle mon;
        try {
            GraphicsDevice[] all =
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            // Skip degenerate screens (zero/negative area) AND duplicate
            // mirrors (same bounds as a previously-seen device): spawning
            // onto a 0×0 phantom monitor leaves the pet permanently
            // off-screen, and spawning onto a mirror would visually
            // duplicate but live on a different GraphicsDevice from the one
            // walkAlongFloor's currentMonitorBounds picks back, breaking
            // edge clipping.
            java.util.List<Rectangle> usable = new java.util.ArrayList<>();
            for (GraphicsDevice d : all) {
                Rectangle b = d.getDefaultConfiguration().getBounds();
                if (b.width <= 0 || b.height <= 0) {
                    continue;
                }
                boolean dup = false;
                for (Rectangle u : usable) {
                    if (u.equals(b)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    usable.add(b);
                }
            }
            if (usable.isEmpty()) {
                mon = primary;
            } else {
                mon = usable.get(ThreadLocalRandom.current().nextInt(usable.size()));
            }
        } catch (Throwable t) {
            mon = primary;
        }
        return entryPlanFor(mon);
    }

    /**
     * Like {@link #pickEntryPlan} but {@value #SAME_MONITOR_REENTRY_BIAS}
     * of the time picks the {@code preferred} monitor (typically the one the
     * pet just left). This keeps DISAPPEAR_REAPPEAR mostly on a single
     * screen so DPI and size stay consistent, while still occasionally
     * letting the pet pop up on another monitor for variety.
     */
    private EntryPlan pickReentryPlan(Rectangle preferred, Rectangle primary) {
        if (preferred != null
                && ThreadLocalRandom.current().nextDouble() < SAME_MONITOR_REENTRY_BIAS) {
            return entryPlanFor(preferred);
        }
        return pickEntryPlan(primary);
    }

    private static final double SAME_MONITOR_REENTRY_BIAS = 0.8;

    private EntryPlan entryPlanFor(Rectangle mon) {
        boolean fromRight = ThreadLocalRandom.current().nextBoolean();
        int gap = Dpi.scale(20);
        // Random target X anywhere inside the monitor (with petSize+gap margin).
        int loX = mon.x + gap;
        int hiX = mon.x + mon.width - gap - petSize;
        int targetX;
        if (hiX <= loX) {
            targetX = mon.x + Math.max(0, (mon.width - petSize) / 2);
        } else {
            targetX = ThreadLocalRandom.current().nextInt(loX, hiX + 1);
        }
        int workBottom = monitorBottomAtColumn(targetX);
        int targetY = workBottom - spawnBottomOffset();
        // Entry start is just outside the monitor edge. The clipping in
        // {@link #moveFrameTo} keeps the JFrame strictly within the
        // monitor, so the pet appears to slide in from off-screen even
        // though the frame never actually straddles two monitors.
        int entryStartX = fromRight
                ? mon.x + mon.width
                : mon.x - petSize;
        Point target = new Point(targetX, targetY);
        Point entryStart = new Point(entryStartX, targetY);
        return new EntryPlan(mon, fromRight, target, entryStart);
    }

    /** Plan for the spawn entry walk: chosen monitor, side, end and start positions. */
    private record EntryPlan(Rectangle monitor, boolean fromRight, Point target, Point entryStart) { }

    /** Change pet size at runtime (16..256). EDT-safe. */
    public final void setSize(int newSize) {
        int clamped = Math.max(16, Math.min(256, newSize));
        if (clamped == petSize) {
            return;
        }
        applyGeometry(clamped);
        if (frame == null) {
            return;
        }
        onEdt(() -> {
            Point loc = logicalLocation();
            frame.setSize(petSize, petSize);
            layoutLabels();
            Point clampedLoc = clampToScreen(loc);
            // Re-route through moveFrameTo so clipping/intendedX still apply.
            moveFrameTo(clampedLoc.x, clampedLoc.y);
            Sprites.apply(petLabel, idleFrames().get(0));
            frame.revalidate();
            frame.repaint();
        });
        Log.info("pet:" + name, "resized to " + petSize + " px");
    }

    // ---------------- shared animations ----------------

    public void idle() {
        playFrames(petLabel, idleFrames(), IDLE_FRAME_MS);
        sleepInterruptible(IDLE_TAIL_MS);
    }

    /** Pet sits in place for a longer hold; calming idle variant. */
    public void sit() {
        Sprites.apply(petLabel, doodleKind() + "/sit");
        sleepInterruptible(SIT_HOLD_MS);
    }

    /** Pet stretches — distinct silhouette for a beat then resumes. */
    public void stretch() {
        Sprites.apply(petLabel, doodleKind() + "/stretch");
        sleepInterruptible(STRETCH_HOLD_MS);
    }

    /** Pet looks left, then right. */
    public void lookAround() {
        Sprites.apply(petLabel, doodleKind() + "/look/0");
        sleepInterruptible(LOOK_HOLD_MS);
        Sprites.apply(petLabel, doodleKind() + "/look/1");
        sleepInterruptible(LOOK_HOLD_MS);
    }

    /** Pet sleeps in place; fully restores ENERGY and shows a Z-Z-Z overlay. */
    public void sleep() {
        showProp("prop/zzz");
        Sprites.apply(petLabel, doodleKind() + "/sleep");
        sleepInterruptible(SLEEP_HOLD_MS);
        needs.add(Need.ENERGY, 100);
        clearProp();
    }

    /** Pet eats in place; fully restores HUNGER and shows a food-bowl overlay. */
    public void eat() {
        showProp("prop/food");
        playFrames(petLabel, idleFrames(), IDLE_FRAME_MS);
        sleepInterruptible(EAT_HOLD_MS);
        needs.add(Need.HUNGER, 100);
        clearProp();
    }

    /** Nudge the ball graphic side-to-side while idling; restores BOREDOM. */
    public void playBall() {
        int ballSize = (int) (petSize * 0.60);
        for (int i = 0; i < 6; i++) {
            if (interrupted()) return;
            showProp("prop/ball");
            int offset = (i % 2 == 0) ? -(int) (petSize * 0.30) : (int) (petSize * 0.30);
            onEdt(() -> propLabel.setBounds(
                    (petSize - ballSize) / 2 + (int) (petSize * 0.30) + offset,
                    petSize - ballSize, ballSize, ballSize));
            playFrames(petLabel, idleFrames(), IDLE_FRAME_MS);
        }
        needs.add(Need.BOREDOM, 100);
        clearProp();
    }

    /** Default: idle with hearts. Hover satisfies the underlying need. */
    public void seekPetting() {
        showProp(null);
        idle();
        if (!heartThread.isAlive()) {
            heartThread = new Thread(this::hearts, "hearts-" + name);
            heartThread.setDaemon(true);
            heartThread.start();
        }
    }

    /**
     * Common left/right horizontal walk to {@code targetX}, linearly
     * interpolating Y from the current location to {@code targetY} so the
     * pet smoothly slopes onto / off a perch instead of teleporting at the
     * window edge. Overridden by {@link Bird} for diagonal flight.
     */
    public void walkTo(int targetX, int targetY) {
        Point start = logicalLocation();
        int dx = targetX - start.x;
        if (dx == 0) {
            idle();
            return;
        }
        boolean goingRight = dx > 0;
        List<String> frames = goingRight ? walkRightFrames() : walkLeftFrames();
        int steps = Math.abs(dx);
        int dyTotal = targetY - start.y;
        int spriteIndex = 0;
        int stepDelay = walkStepDelayMs();
        int pps = pixelsPerSpriteStep();

        for (int i = 1; i <= steps; i++) {
            if (interrupted() || hovered || clicked) {
                break;
            }
            if ((i - 1) % pps == 0) {
                Sprites.apply(petLabel, frames.get(spriteIndex % frames.size()));
                spriteIndex++;
            }
            int signedStep = goingRight ? i : -i;
            int y = start.y + (int) Math.round(dyTotal * (i / (double) steps));
            moveFrameTo(start.x + signedStep, y);
            sleepInterruptible(stepDelay);
        }
        idle();
    }

    // ---------------- helpers exposed to subclasses ----------------

    public final void playFrames(JLabel label, List<String> frames, int frameMs) {
        for (String f : frames) {
            if (interrupted()) return;
            Sprites.apply(label, f);
            sleepInterruptible(frameMs);
        }
    }

    public final void showProp(String key) {
        Sprites.apply(propLabel, key);
    }

    public final void clearProp() {
        sleepInterruptible(PROP_FADE_MS);
        Sprites.apply(propLabel, null);
    }

    /**
     * EDT-safe move of the pet's window to a logical {@code (intendedX,
     * intendedY)}. When {@link #activeMonitor} is set, the actual JFrame
     * bounds are clipped to that monitor: the window narrows as the pet
     * walks off the edge (its sprite is offset inside the smaller frame so
     * it visibly slides off) and is hidden entirely once fully outside.
     * This prevents the frame from ever straddling two monitors, which on
     * misaligned multi-monitor setups would otherwise let the pet appear
     * mid-screen on the neighbouring monitor.
     */
    public final void moveFrameTo(int intendedX, int intendedY) {
        if (frame == null) {
            return;
        }
        // Record the intended logical position FIRST so logicalLocation()
        // is always up to date even if the EDT call below is deferred or
        // the frame ends up hidden (visible width = 0).
        this.intendedX = intendedX;
        this.intendedY = intendedY;
        Rectangle mon = activeMonitor;
        MonitorClipper.Clip clip = MonitorClipper.clip(intendedX, intendedY, petSize, mon);
        if (mon == null) {
            // Unclipped fast path: pet isn't bound to a monitor yet.
            onEdt(() -> frame.setLocation(intendedX, intendedY));
            return;
        }
        final boolean hidden = clip.hidden();
        final Rectangle b = clip.bounds();
        final int offX = clip.offsetX();
        final int offY = clip.offsetY();
        onEdt(() -> {
            if (hidden) {
                if (frame.isVisible()) {
                    frame.setVisible(false);
                }
                return;
            }
            frame.setBounds(b.x, b.y, b.width, b.height);
            applyLabelOffset(offX, offY);
            if (!frame.isVisible()) {
                frame.setVisible(true);
            }
        });
    }

    /**
     * The pet's intended logical position (top-left of a notional
     * petSize×petSize box), regardless of any current edge clipping in
     * {@link #moveFrameTo}. Prefer this over {@code frame.getLocation()}
     * for any position-arithmetic - using the clipped JFrame location
     * would compute wrong distances when the pet is partially or fully
     * off-monitor.
     */
    public final Point logicalLocation() {
        return new Point(intendedX, intendedY);
    }

    /**
     * Shift the child labels inside the frame so that {@code (offX, offY)}
     * sprite pixels are hidden on the left/top. Sizes stay the same; the
     * (possibly smaller) frame clips the rendering naturally.
     */
    private void applyLabelOffset(int offX, int offY) {
        petLabel.setLocation(-offX, -offY);
        heartLabel.setLocation(heartX - offX, heartY - offY);
        propLabel.setLocation(
                (petSize - propW) / 2 + (int) (petSize * 0.30) - offX,
                petSize - propH - offY);
    }

    /**
     * Persist the current location for next launch. Throttled in two ways
     * to avoid hammering {@code config.txt} (3 pets × 1 Hz from the engine
     * loop = 3 disk writes / sec otherwise):
     * <ul>
     *   <li>movement gate: skip if neither X nor Y has moved by &ge;
     *       {@value #PERSIST_DELTA_PX} px since the last persist;</li>
     *   <li>time gate: still force a flush every {@value #PERSIST_FORCE_MS}
     *       ms even if nothing moved, so a crash never loses more than
     *       a few seconds of position drift.</li>
     * </ul>
     */
    public final void persistPosition() {
        if (frame == null) {
            return;
        }
        // Don't persist intermediate frames of the spawn entry walk —
        // otherwise a mid-walk position overwrites a saved off-monitor
        // position before the pet even arrives at its slot.
        if (entryWalkInProgress || pendingEntryTargetX != null) {
            return;
        }
        Point p = logicalLocation();
        // If we discarded a saved position because its monitor was off, keep
        // the on-disk value intact until the pet has actually moved away from
        // the fallback spawn point. That way temporarily unplugging a monitor
        // doesn't erase the user's preferred position on it.
        if (discardedSavedPos != null && initialSpawn != null
                && Math.abs(p.x - initialSpawn.x) < PERSIST_DELTA_PX
                && Math.abs(p.y - initialSpawn.y) < PERSIST_DELTA_PX) {
            return;
        }
        // Pet has moved (or never had a discarded position): clear the marker
        // so we resume normal persistence behaviour from now on.
        discardedSavedPos = null;
        long now = System.currentTimeMillis();
        Point last = lastPersistedAt;
        if (last != null
                && now - lastPersistAtMs < PERSIST_FORCE_MS
                && Math.abs(p.x - last.x) < PERSIST_DELTA_PX
                && Math.abs(p.y - last.y) < PERSIST_DELTA_PX) {
            return;
        }
        Config.writePosition(name, p);
        // Persist the bound monitor too (may be null on the rare path where
        // a pet has no activeMonitor yet); a null write clears any stale
        // entry from a previous session.
        Config.writeMonitor(name, activeMonitor);
        lastPersistedAt = p;
        lastPersistAtMs = now;
    }

    private static final int  PERSIST_DELTA_PX = 8;
    private static final long PERSIST_FORCE_MS = 5_000L;
    private volatile Point lastPersistedAt;
    private volatile long  lastPersistAtMs = 0L;
    /** Saved position discarded at spawn because its monitor was off; while
     *  this is non-null and the pet hasn't moved from the fallback spawn, we
     *  suppress persistence so the user's original position survives. */
    private volatile Point discardedSavedPos;
    /** Fallback spawn point chosen at init when {@link #discardedSavedPos} was set. */
    private volatile Point initialSpawn;
    /** When non-null, the pet was spawned just off the right edge of the
     *  primary monitor and should walk leftward to this X on the first
     *  behaviour tick. Cleared after the entry walk runs. */
    private volatile Integer pendingEntryTargetX;
    /** True while {@link #runPendingEntryWalk} is executing. Persistence is
     *  suppressed during this window so the intermediate walk positions don't
     *  overwrite the user's saved (off-monitor) position. */
    private volatile boolean entryWalkInProgress;
    /** Monitor the pet is currently bound to. When non-null, {@link
     *  #moveFrameTo} clips the frame to these bounds (the JFrame never
     *  straddles two monitors, so it can't leak into a misaligned
     *  neighbour), and the pet sprite is offset within the (possibly
     *  smaller) frame so it visibly slides off the edge as the pet walks
     *  out. Set on spawn and on DISAPPEAR_REAPPEAR re-entry. */
    private volatile Rectangle activeMonitor;

    /** The pet's intended logical position (top-left of an unclipped
     *  petSize×petSize box). This is the position callers think the pet
     *  is at; {@link #frame}'s actual bounds may be narrower because
     *  {@link #moveFrameTo} clips them to {@link #activeMonitor}. Always
     *  read this via {@link #logicalLocation()} instead of
     *  {@code frame.getLocation()} so the math stays consistent when the
     *  pet is partially off-screen. */
    private volatile int intendedX;
    private volatile int intendedY;
    /** Cached HWND of {@link #frame}, looked up via a unique window title
     *  once after {@code setVisible(true)}. Used by {@link #reassertTopmost}
     *  to keep the pet in the topmost band even if another app demotes it. */
    private volatile long hwnd = 0L;

    /**
     * Called once by {@link BehaviorEngine} at the start of each tick. If a
     * pending spawn-entry walk is queued (because the pet was placed off the
     * right edge of the primary monitor at spawn), walk along the floor to
     * the assigned slot. {@link #walkAlongFloor} resamples the floor at every
     * step, so the pet naturally walks across the shell taskbar or any other
     * always-on-top window that reaches the screen edge, dropping to the
     * bottom of the screen wherever none does.
     */
    public final void runPendingEntryWalk(World world) {
        Integer targetX = pendingEntryTargetX;
        if (targetX == null) {
            return;
        }
        pendingEntryTargetX = null;
        entryWalkInProgress = true;
        try {
            walkAlongFloor(world, targetX);
        } finally {
            entryWalkInProgress = false;
        }
    }

    // ---------------- perch helpers ----------------

    /**
     * Pet-width best estimate. Returns {@link #petSize} when the
     * {@link #petLabel} hasn't been laid out yet (would otherwise be 0 and
     * make {@link #floorYAt} compute against a zero-width footprint).
     */
    public final int effectiveWidth() {
        int w = petLabel.getWidth();
        return w > 0 ? w : petSize;
    }

    /** Pet-height best estimate. See {@link #effectiveWidth()}. */
    public final int effectiveHeight() {
        int h = petLabel.getHeight();
        return h > 0 ? h : petSize;
    }

    /**
     * Y the pet's frame should sit at so its feet land on the floor at column
     * {@code x}, computed by gravity from the pet's <b>current</b> feet Y —
     * the pet only ever falls onto surfaces at-or-below where it stands, never
     * teleports upward onto a window that just opened overhead. See
     * {@link World#floorY(int, int, int, int)}.
     */
    public final int floorYAt(World world, int x) {
        int petW = effectiveWidth();
        int petH = effectiveHeight();
        int feetH = (int) Math.round(petH * feetYRatio());
        int currentFeetY = logicalLocation().y + feetH;
        int y = world.floorY(feetH, x, petW, currentFeetY);
        // Cap to the bottom of the monitor at this column, minus any shell
        // taskbar that covers it — see monitorBottomAtColumn for rationale.
        int monMaxY = monitorBottomAtColumn(x) - feetH;
        return Math.min(y, monMaxY);
    }

    /** Convenience: {@link #floorYAt} at the pet's current X. */
    public final int floorYHere(World world) {
        return floorYAt(world, logicalLocation().x);
    }

    /**
     * Bounds of the {@link java.awt.GraphicsDevice} currently containing the
     * pet's frame (so multi-monitor setups don't let WANDER fling the pet
     * onto a virtual screen that isn't visible to the user). Falls back to
     * the full union of all monitors if the pet's location matches none,
     * which can happen briefly while a window is being moved between screens.
     */
    public final Rectangle currentMonitorBounds() {
        // Prefer the pet's bound monitor so wander/zoomies target columns
        // stay on the monitor the pet was assigned to, even briefly while
        // disappear-reappear is teleporting between monitors.
        Rectangle pinned = activeMonitor;
        if (pinned != null) {
            return pinned;
        }
        try {
            Point loc = frame != null
                    ? logicalLocation()
                    : new Point(0, 0);
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle b = d.getDefaultConfiguration().getBounds();
                if (b.contains(loc)) {
                    return b;
                }
            }
        } catch (Throwable t) {
            // fall through to whole-screen fallback
        }
        return new Rectangle(0, 0, (int) screen.getWidth(), (int) screen.getHeight());
    }

    /**
     * The bottom Y of the desktop work area at screen column {@code x} \u2014
     * i.e., the bottom of the monitor at that column, minus that monitor's
     * bottom inset (taskbar) as reported by AWT.
     *
     * <p>We deliberately use {@link Toolkit#getScreenInsets} rather than the
     * raw Win32 {@code Shell_TrayWnd} rect, because the Win32 rect is in
     * <b>physical</b> pixels while {@code frame.setLocation} uses
     * <b>logical</b> (DPI-scaled) pixels. On a 4K display at 200% scaling
     * those differ by 2\u00d7, which previously caused the bar to be
     * "missed" and the pet to land behind it.
     */
    private int monitorBottomAtColumn(int x) {
        try {
            GraphicsConfiguration bestCfg = null;
            Rectangle bestMon = null;
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                GraphicsConfiguration cfg = d.getDefaultConfiguration();
                Rectangle b = cfg.getBounds();
                if (x >= b.x && x < b.x + b.width) {
                    if (bestMon == null || b.y < bestMon.y) {
                        bestMon = b;
                        bestCfg = cfg;
                    }
                }
            }
            if (bestMon == null) {
                // Column not on any attached monitor (e.g. off-screen entry start):
                // fall back to the primary monitor.
                GraphicsDevice pd = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice();
                bestCfg = pd.getDefaultConfiguration();
                bestMon = bestCfg.getBounds();
            }
            int bottom = bestMon.y + bestMon.height;
            try {
                Insets ins = Toolkit.getDefaultToolkit().getScreenInsets(bestCfg);
                if (ins != null && ins.bottom > 0) {
                    bottom -= ins.bottom;
                }
            } catch (Throwable t) {
                // ignore \u2014 fall back to raw monitor bottom
            }
            return bottom;
        } catch (Throwable t) {
            return (int) screen.getHeight();
        }
    }

    /**
     * Horizontal walk to {@code targetX} that resamples the floor at every
     * column via {@link World#floorY}, so the pet steps up onto / down off
     * always-on-top windows it actually crosses instead of gliding through
     * midair toward a far-off perch top.
     *
     * <p>Overridden by {@link Bird}, which flies diagonally and ignores the
     * floor profile entirely.
     */
    public void walkAlongFloor(World world, int targetX) {
        Point start = logicalLocation();
        int dx = targetX - start.x;
        if (dx == 0) {
            idle();
            return;
        }
        boolean goingRight = dx > 0;
        List<String> frames = goingRight ? walkRightFrames() : walkLeftFrames();
        int steps = Math.abs(dx);
        int spriteIndex = 0;
        int stepDelay = walkStepDelayMs();
        int pps = pixelsPerSpriteStep();
        int petW = effectiveWidth();
        int petH = effectiveHeight();
        int feetH = Math.max(1, (int) Math.round(petH * feetYRatio()));
        // Track the pet's CURRENT feet Y across the walk so gravity stays
        // "stuck" to the surface we're traversing (a window's top or the
        // desktop floor) instead of jumping up onto windows that pop up
        // overhead mid-walk.
        int currentFeetY = start.y + feetH;

        for (int i = 1; i <= steps; i++) {
            if (interrupted() || hovered || clicked) {
                break;
            }
            if ((i - 1) % pps == 0) {
                Sprites.apply(petLabel, frames.get(spriteIndex % frames.size()));
                spriteIndex++;
            }
            int signedStep = goingRight ? i : -i;
            int x = start.x + signedStep;
            int y = world.floorY(feetH, x, petW, currentFeetY);
            int monMaxY = monitorBottomAtColumn(x) - feetH;
            if (y > monMaxY) {
                y = monMaxY;
            }
            moveFrameTo(x, y);
            currentFeetY = y + feetH;
            sleepInterruptible(stepDelay);
        }
        idle();
    }

    /**
     * Walk the pet right off the side of its current monitor (so the frame
     * is fully outside the visible region), pause briefly while invisible,
     * then teleport to a fresh entry point on a random monitor/side and walk
     * back into view. Used by {@link Activities#DISAPPEAR_REAPPEAR} to give
     * the pets visible variety beyond ambient pacing.
     *
     * <p>Going off-screen relies on {@link JFrame#setLocation} not clamping
     * — same mechanism used by the spawn entry walk, which starts the pet
     * outside the screen.
     */
    public final void disappearAndReappear(World world) {
        Rectangle mon = currentMonitorBounds();
        int petW = effectiveWidth();
        Point loc = logicalLocation();
        // Exit toward whichever monitor edge is closer.
        int leftDist = (loc.x + petW / 2) - mon.x;
        int rightDist = (mon.x + mon.width) - (loc.x + petW / 2);
        boolean exitRight = rightDist <= leftDist;
        // Walk to a position fully OUTSIDE the monitor. The clipping in
        // moveFrameTo narrows the JFrame as the pet crosses the edge and
        // hides it entirely once visible width hits 0, so the pet appears
        // to slide off-screen without the frame ever straddling two
        // monitors.
        int exitX = exitRight
                ? mon.x + mon.width
                : mon.x - petW;
        walkAlongFloor(world, exitX);
        if (interrupted()) {
            return;
        }
        // If the user hovered/clicked while we were walking off-screen,
        // abort the disappear and walk BACK inward to the original spot —
        // teleporting to the opposite edge and re-entering would feel
        // jarring when the user is clearly trying to interact.
        if (hovered || clicked) {
            int returnX = Math.max(mon.x, Math.min(mon.x + mon.width - petW, loc.x));
            walkAlongFloor(world, returnX);
            return;
        }
        // Skip the wait if the user is trying to interact, so the pet pops
        // back quickly.
        if (!hovered && !clicked) {
            long pauseMs = 800L + ThreadLocalRandom.current().nextLong(0, 2200L);
            sleepInterruptible(pauseMs);
        }
        if (interrupted()) {
            return;
        }
        // Re-enter: mostly on the same monitor (so DPI/size stays
        // consistent), occasionally on a random one.
        EntryPlan plan = pickReentryPlan(mon, primaryMonitorBounds());
        // Bind to the destination monitor BEFORE moving the frame, so the
        // initial teleport to the entry-start position is clipped/hidden
        // by moveFrameTo (visible width is 0 at entryStart, which is just
        // outside the destination monitor).
        activeMonitor = plan.monitor;
        moveFrameTo(plan.entryStart.x, plan.entryStart.y);
        // Re-render the current sprite so its image buffer matches the
        // (possibly DPI-rescaled by AWT) frame after the cross-monitor
        // teleport. Without this, multi-piece sprites can briefly show
        // mis-sized body parts on the first frame after re-entry.
        refreshCurrentSprite();
        walkAlongFloor(world, plan.target.x);
    }

    /** Returns the monitor whose bounds contain {@code p}, else {@code fallback}. */
    private Rectangle monitorContaining(Point p, Rectangle fallback) {
        try {
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle b = d.getDefaultConfiguration().getBounds();
                if (b.contains(p)) {
                    return b;
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return fallback;
    }

    /**
     * Re-apply the first idle sprite to the pet label so its image buffer
     * is regenerated at the label's current pixel size. Cheap no-op when
     * the size matches the cached buffer; essential after a cross-monitor
     * move where Windows may have changed the frame's physical pixel size.
     */
    private void refreshCurrentSprite() {
        if (petLabel == null) {
            return;
        }
        try {
            String first = idleFrames().get(0);
            onEdt(() -> Sprites.apply(petLabel, first));
        } catch (Throwable t) {
            // ignore - sprite refresh is best-effort
        }
    }

    /**
     * Quick burst of activity: a few fast back-and-forth sprints across the
     * current monitor. Mirrors the "zoomies" pets do in real life. Each
     * sprint reuses {@link #walkAlongFloor} so window perches are respected.
     */
    public final void zoomies(World world) {
        Rectangle mon = currentMonitorBounds();
        int petW = effectiveWidth();
        int monLo = mon.x + Dpi.scale(8);
        int monHi = Math.max(monLo + 1, mon.x + mon.width - petW - Dpi.scale(8));
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int sprints = 2 + rng.nextInt(3); // 2..4
        for (int i = 0; i < sprints; i++) {
            if (interrupted() || hovered || clicked) {
                return;
            }
            int targetX = rng.nextInt(monLo, monHi + 1);
            walkAlongFloor(world, targetX);
            sleepInterruptible(120L + rng.nextLong(0, 200L));
        }
    }

    private void hearts() {
        for (int i = 0; i < HEART_FRAME_COUNT; i++) {
            if (interrupted()) {
                break;
            }
            Sprites.apply(heartLabel, "heart/" + i);
            sleepInterruptible(HEART_FRAME_MS);
        }
        sleepInterruptible(HEART_TAIL_MS);
        Sprites.apply(heartLabel, null);
    }

    /** Check interrupt/pause flags between frames. Returns true if we should bail out. */
    public final boolean interrupted() {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        while (paused.get() && !Thread.currentThread().isInterrupted()) {
            sleepInterruptible(200);
        }
        return Thread.currentThread().isInterrupted();
    }

    /** Sleep that propagates interrupts properly (caller should check {@link #interrupted}). */
    public static void sleepInterruptible(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Back-compat alias. */
    public static void sleep(long ms) {
        sleepInterruptible(ms);
    }

    public static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    protected static List<String> frames(String prefix, int fromInclusive, int toInclusive) {
        List<String> out = new ArrayList<>();
        for (int i = fromInclusive; i <= toInclusive; i++) {
            out.add(prefix + "/" + i);
        }
        return Collections.unmodifiableList(out);
    }

    protected static int random(int boundExclusive) {
        return ThreadLocalRandom.current().nextInt(boundExclusive);
    }

    /**
     * Headless-safe virtual screen size — the union of every connected
     * monitor's bounds (so pets can wander across a multi-monitor setup
     * whose primary is at the origin). Falls back to the primary monitor,
     * and finally to 1920×1080 if AWT is headless (tests).
     *
     * <p>Note: we only return width/height as a {@link Dimension}, so this
     * still loses the virtual-screen X/Y offset. That means monitors
     * positioned <i>left of</i> or <i>above</i> the primary (negative
     * coordinates) are not addressable; the more common right-of / below
     * arrangements work fine.
     */
    private static Dimension detectScreenSize() {
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            Rectangle virt = new Rectangle();
            for (GraphicsDevice d : ge.getScreenDevices()) {
                virt = virt.union(d.getDefaultConfiguration().getBounds());
            }
            if (virt.width > 0 && virt.height > 0) {
                return new Dimension(virt.width, virt.height);
            }
            return Toolkit.getDefaultToolkit().getScreenSize();
        } catch (Throwable t) {
            return new Dimension(1920, 1080);
        }
    }
}
