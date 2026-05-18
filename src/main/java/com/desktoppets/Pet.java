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
import java.util.concurrent.CopyOnWriteArrayList;
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
    private int emoteX, emoteY, emoteW, emoteH;

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
    /** Small mood/activity icon shown above the head during ambient activities. */
    public final JLabel emoteLabel = new JLabel();

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
        // Emote sits just above the heart slot, slightly smaller. Clamped so it
        // stays inside the frame even on the smallest pet sizes.
        this.emoteW = Math.max(12, (int) (newSize * 0.30));
        this.emoteH = this.emoteW;
        this.emoteX = Math.max(0,
                Math.min(newSize - emoteW,
                        (int) (newSize * heartCenterXRatio()) - emoteW / 2));
        this.emoteY = Math.max(0,
                (int) (newSize * heartTopYRatio()) - heartH - emoteH);
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

    /**
     * Live registry of pets currently running their behaviour loop. Used by
     * pet-pet interaction activities (greet/chase/play-tag) so a pet can
     * locate its siblings without coupling to {@link PetSupervisor}.
     * Copy-on-write so iteration from the behaviour thread is safe while
     * other pets register/deregister from their own threads.
     */
    private static final CopyOnWriteArrayList<Pet> ACTIVE_PETS = new CopyOnWriteArrayList<>();

    /** Snapshot of currently-running pets. Safe to iterate. */
    public static List<Pet> activePets() {
        return ACTIVE_PETS;
    }

    /**
     * Nearest other live pet on the same monitor whose horizontal distance
     * to this one is at most {@code maxDx} logical pixels. Returns null if
     * no candidate qualifies (e.g. only one pet running).
     */
    public final Pet nearestOtherPet(int maxDx) {
        Rectangle myMon = currentMonitorBounds();
        int myMid = logicalLocation().x + effectiveWidth() / 2;
        Pet best = null;
        int bestDx = Integer.MAX_VALUE;
        for (Pet other : ACTIVE_PETS) {
            if (other == this || other.frame == null) {
                continue;
            }
            Rectangle otherMon = other.currentMonitorBounds();
            if (otherMon.x != myMon.x || otherMon.y != myMon.y) {
                continue;
            }
            int otherMid = other.logicalLocation().x + other.effectiveWidth() / 2;
            int dx = Math.abs(otherMid - myMid);
            if (dx < bestDx && dx <= maxDx) {
                bestDx = dx;
                best = other;
            }
        }
        return best;
    }

    /**
     * What this pet should do when its next step would put it inside another
     * pet's bounding box during {@link #walkAlongFloor}.
     * <ul>
     *   <li>{@link #PASS} — walk straight through (old behaviour);</li>
     *   <li>{@link #STOP} — abort the walk, leaving the pet where it stands;</li>
     *   <li>{@link #JUMP} — render the pet ~half a body-height above the
     *       floor for the duration of the overlap so it visibly hops over.</li>
     * </ul>
     * Both pets roll independently — coordination falls out naturally
     * (sometimes both stop, sometimes one jumps while the other walks, etc.)
     * which looks like play rather than scripted choreography.
     *
     * <p>Birds are excluded from the encounter scan: their overridden
     * {@link Bird#walkAlongFloor} flies diagonally to perch height, so when
     * a bird is mid-flight its bbox sits well above any ground pet's bbox
     * and the overlap test naturally returns no collision — the bird is
     * already "flying over". A bird that happens to be at floor Y still
     * registers as a collidable obstacle for ground pets.
     */
    protected enum CollisionPlan { PASS, STOP, JUMP }

    /**
     * Returns the nearest other pet whose bounding box overlaps the
     * rectangle {@code (newX, newY, petW, feetH)}, or {@code null} if none.
     */
    private Pet collidesWithOtherPet(int newX, int newY, int petW, int feetH) {
        int myRight = newX + petW;
        int myBot = newY + feetH;
        for (Pet other : ACTIVE_PETS) {
            if (other == this || other.frame == null) {
                continue;
            }
            Point op = other.logicalLocation();
            int oW = other.effectiveWidth();
            int oH = other.effectiveHeight();
            int oFeetH = Math.max(1, (int) Math.round(oH * other.feetYRatio()));
            int oRight = op.x + oW;
            int oBot = op.y + oFeetH;
            if (myRight <= op.x || oRight <= newX) {
                continue; // no horizontal overlap
            }
            if (myBot <= op.y || oBot <= newY) {
                continue; // no vertical overlap (e.g. bird flying above)
            }
            return other;
        }
        return null;
    }

    /**
     * Roll the collision-response plan. Weights chosen so PASS (the old
     * silent-through behaviour) is still the most common outcome, STOP is
     * frequent enough to be noticeable, and JUMP is the rare comedic option.
     */
    private static CollisionPlan pickCollisionPlan() {
        int r = ThreadLocalRandom.current().nextInt(100);
        if (r < 50) return CollisionPlan.PASS;
        if (r < 80) return CollisionPlan.STOP;
        return CollisionPlan.JUMP;
    }

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
        ACTIVE_PETS.add(this);
        try {
            new BehaviorEngine(this).run();
        } finally {
            ACTIVE_PETS.remove(this);
        }
    }

    public final void disposeWindow() {
        ACTIVE_PETS.remove(this);
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
            frame.add(emoteLabel);
            frame.add(heartLabel);
            frame.add(propLabel);
            frame.add(petLabel);

            // Pet positions are intentionally not persisted - only the
            // settings dialog values (pets list, size, activity) live in
            // config.txt. Every spawn picks a random visible monitor and
            // walks the pet in from off-screen.
            Rectangle primary = primaryMonitorBounds();
            Point clamped;
            Point firstSetLocation;
            Rectangle initialBounds; // exact bounds for the first frame.setBounds
            {
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
                    + ")"
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
        emoteLabel.setBounds(emoteX, emoteY, emoteW, emoteH);
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
        if (interrupted() || hovered || clicked) {
            return;
        }
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
        // Bounce between the left and right edges of the frame. The earlier
        // formula biased the ball ~0.30*petSize to the right of centre, which
        // pushed the right-hand pose past the frame edge so the ball was
        // visibly clipped (the JFrame is exactly petSize wide).
        int leftX = 0;
        int rightX = petSize - ballSize;
        for (int i = 0; i < 6; i++) {
            if (interrupted()) return;
            showProp("prop/ball");
            final int ballX = (i % 2 == 0) ? leftX : rightX;
            onEdt(() -> propLabel.setBounds(
                    ballX, petSize - ballSize, ballSize, ballSize));
            // Brief pivot pose on each direction flip so the ball pose
            // doesn't snap straight from "looking left" to "looking right".
            if (i > 0) {
                Sprites.apply(petLabel, idleFrames().get(0));
                sleepInterruptible(IDLE_FRAME_MS / 2);
            }
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
            sleepInterruptible(easedStepDelay(stepDelay, i, steps));
        }
        // Hold the final walk frame for one beat so the stop reads as a
        // settled stride instead of snapping straight to idle[0].
        sleepInterruptible(stepDelay);
        idle();
    }

    // ---------------- helpers exposed to subclasses ----------------

    /**
     * Ease-in/ease-out timing for walks: returns a per-step delay that's
     * ~2× base at the start and end of the traverse and ~1× base in the
     * middle, giving walks visible weight on take-off and landing instead
     * of starting/stopping abruptly.
     *
     * <p>The ramp length scales with the walk so a 12-px scoot still gets
     * a noticeable curve and a 600-px sprint isn't slowed for too long.
     */
    private static long easedStepDelay(int baseDelay, int stepIndex1Based, int totalSteps) {
        int rampSteps = Math.min(8, Math.max(1, totalSteps / 4));
        int leftDist  = stepIndex1Based - 1;
        int rightDist = totalSteps - stepIndex1Based;
        int edgeDist  = Math.min(leftDist, rightDist);
        if (edgeDist >= rampSteps) {
            return baseDelay;
        }
        // 1.0 at full speed, 2.0 at the very edge.
        double mult = 1.0 + (rampSteps - edgeDist) / (double) rampSteps;
        return Math.round(baseDelay * mult);
    }

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
        emoteLabel.setLocation(emoteX - offX, emoteY - offY);
        propLabel.setLocation(
                (petSize - propW) / 2 + (int) (petSize * 0.30) - offX,
                petSize - propH - offY);
    }

    /** When non-null, the pet was spawned just off the edge of a monitor
     *  and should walk inward to this X on the first behaviour tick.
     *  Cleared after the entry walk runs. */
    private volatile Integer pendingEntryTargetX;
    /** True while {@link #runPendingEntryWalk} is executing. */
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
        // Latched plan for the current sibling-pet encounter — chosen once
        // per overlap so the pet doesn't re-roll every step and jitter.
        CollisionPlan plan = null;
        // JUMP-arc state. {@code jumpStep} is the current pixel-step along the
        // arc; {@code jumpSpan} is its total length. The arc continues to play
        // for the full span even after the overlap clears, so the pet lands
        // smoothly instead of dropping abruptly the moment its bbox exits.
        int jumpStep = 0;
        int jumpSpan = 0;
        int jumpPeak = petH / 2;

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
            // --- sibling-pet collision ---
            Pet ahead = collidesWithOtherPet(x, y, petW, feetH);
            if (ahead != null && plan == null) {
                plan = pickCollisionPlan();
                if (plan == CollisionPlan.JUMP) {
                    // Arc spans the full cross — from first contact until the
                    // pet has travelled past the other pet's far edge — so the
                    // sine curve completes one full hop.
                    jumpStep = 0;
                    jumpSpan = Math.max(1, petW + ahead.effectiveWidth());
                    jumpPeak = Math.max(8, petH / 2);
                }
            }
            if (plan == CollisionPlan.STOP) {
                idle();
                return;
            }
            int renderY = y;
            if (plan == CollisionPlan.JUMP) {
                // Fluent parabolic hop via sin(πt). The lift is added to the
                // *rendered* Y only — currentFeetY still tracks the real floor
                // so gravity resumes correctly when the arc lands.
                double t = jumpStep / (double) jumpSpan;
                int lift = (int) Math.round(Math.sin(Math.PI * Math.min(1.0, t)) * jumpPeak);
                renderY = Math.max(0, y - lift);
                jumpStep++;
                if (jumpStep >= jumpSpan && ahead == null) {
                    // Arc finished AND we're clear — drop the plan.
                    plan = null;
                }
            } else if (ahead == null) {
                plan = null;
            }
            // PASS falls through and walks straight through, as before.
            moveFrameTo(x, renderY);
            currentFeetY = y + feetH;
            sleepInterruptible(easedStepDelay(stepDelay, i, steps));
        }
        // Hold the final walk frame for one beat so the stop reads as a
        // settled stride instead of snapping straight to idle[0].
        sleepInterruptible(stepDelay);
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

    // ---------------- shared activity helpers ----------------

    /**
     * Show a small emote sprite ({@code key} = "sparkle" / "note" / "bang" /
     * "paw" / "drop" / "mini-heart" / "target") above the pet's head for
     * {@code ms}, then clear it. Interruptible. No-op if the resolved sprite
     * is missing, so callers can stay simple.
     */
    public final void showEmote(String key, long ms) {
        Sprites.apply(emoteLabel, "emote/" + key);
        sleepInterruptible(ms);
        Sprites.apply(emoteLabel, null);
    }

    /**
     * Walk toward the current mouse cursor's X, stopping when within
     * {@code stopWithinPx}. No-op if the cursor isn't available (headless)
     * or already inside the stop band. Reuses {@link #walkAlongFloor}, so
     * perches en route are respected and the pet doesn't pace across the
     * whole monitor toward a cursor that's right next to it.
     */
    public final void walkTowardCursor(World world, int stopWithinPx) {
        Point cursor = World.cursorPos();
        if (cursor == null) {
            idle();
            return;
        }
        Rectangle mon = currentMonitorBounds();
        // Bail out if the cursor is on a different monitor — pets stay put
        // rather than walking across off-monitor virtual coordinates.
        if (!mon.contains(cursor)) {
            idle();
            return;
        }
        int petW = effectiveWidth();
        int cursorX = cursor.x;
        int currentMidX = logicalLocation().x + petW / 2;
        int delta = cursorX - currentMidX;
        if (Math.abs(delta) <= stopWithinPx) {
            sit();
            return;
        }
        int targetMidX = cursorX - (delta > 0 ? stopWithinPx : -stopWithinPx);
        int targetX = Math.max(mon.x, Math.min(mon.x + mon.width - petW, targetMidX - petW / 2));
        walkAlongFloor(world, targetX);
    }

    /** Brief grooming/preening sequence: sit, then a few stretches. */
    public final void grooming() {
        // Show the sparkle for the WHOLE sequence (a 0-ms showEmote would
        // apply-then-clear immediately and never be visible). Cleared on
        // each early-exit branch and at the end.
        Sprites.apply(emoteLabel, "emote/sparkle");
        sit();
        if (interrupted()) { Sprites.apply(emoteLabel, null); return; }
        stretch();
        if (interrupted()) { Sprites.apply(emoteLabel, null); return; }
        sit();
        Sprites.apply(emoteLabel, null);
        needs.add(Need.AFFECTION, 20);
    }

    /** Hold the {@code sit} sprite for {@code holdMs} as a "crouch/pout" pose. */
    public final void crouchPose(long holdMs) {
        Sprites.apply(petLabel, doodleKind() + "/sit");
        showEmote("drop", holdMs);
    }

    /**
     * Small left-right shuffle: walk {@code spanPx} px to one side, then
     * back to roughly the start. Cheaper than wander; satisfies BOREDOM.
     */
    public final void waddleLoop(World world, int spanPx) {
        Point start = logicalLocation();
        Rectangle mon = currentMonitorBounds();
        int petW = effectiveWidth();
        boolean goRight = ThreadLocalRandom.current().nextBoolean();
        int t1 = goRight
                ? Math.min(mon.x + mon.width - petW, start.x + spanPx)
                : Math.max(mon.x, start.x - spanPx);
        walkAlongFloor(world, t1);
        if (interrupted()) return;
        walkAlongFloor(world, start.x);
        needs.add(Need.BOREDOM, 15);
    }

    /**
     * Hold the current perch and play idle frames at 2× speed for
     * {@code ms} — visualises a "singing" / chirping bird without new
     * sprites. Resets to a single idle tile at the end so behaviour
     * looks normal again.
     */
    public final void perchSing(long ms) {
        Sprites.apply(emoteLabel, "emote/note");
        long until = System.currentTimeMillis() + ms;
        int frameDelay = Math.max(40, IDLE_FRAME_MS / 2);
        int i = 0;
        List<String> frames = idleFrames();
        while (System.currentTimeMillis() < until && !interrupted()) {
            Sprites.apply(petLabel, frames.get(i++ % frames.size()));
            sleepInterruptible(frameDelay);
        }
        Sprites.apply(petLabel, frames.get(0));
        Sprites.apply(emoteLabel, null);
        needs.add(Need.BOREDOM, 25);
    }

    /** Short hop toward {@code targetX}, capped at {@code maxHopPx} from current X. */
    public final void flit(World world, int maxHopPx) {
        Rectangle mon = currentMonitorBounds();
        int petW = effectiveWidth();
        int monLo = mon.x;
        int monHi = Math.max(monLo + 1, mon.x + mon.width - petW);
        int curX = logicalLocation().x;
        int dx = (ThreadLocalRandom.current().nextBoolean() ? 1 : -1)
                * (40 + ThreadLocalRandom.current().nextInt(Math.max(1, maxHopPx - 40)));
        int targetX = Math.max(monLo, Math.min(monHi, curX + dx));
        walkAlongFloor(world, targetX);
    }

    /**
     * Fly across the monitor at a fixed Y near screen top, return to the
     * start X. Used by Bird's "circle" activity. Uses {@link #walkTo} for
     * the diagonal-flight subclass override.
     */
    public final void circleFly(World world) {
        Rectangle mon = currentMonitorBounds();
        int petW = effectiveWidth();
        Point start = logicalLocation();
        int highY = mon.y + Math.max(10, mon.height / 8);
        int farLeft = mon.x + 10;
        int farRight = Math.max(farLeft + 1, mon.x + mon.width - petW - 10);
        int firstTarget = (start.x + petW / 2 < mon.x + mon.width / 2) ? farRight : farLeft;
        walkTo(firstTarget, highY);
        if (interrupted()) return;
        walkTo(firstTarget == farRight ? farLeft : farRight, highY);
        if (interrupted()) return;
        walkAlongFloor(world, start.x);
    }

    /**
     * "Knock something off": walk to the closest perch edge, sit, look
     * around, then disappear-reappear. Long-cooldown cat moment.
     */
    public final void knockSomethingOff(World world) {
        Rectangle perch = null;
        Rectangle mon = currentMonitorBounds();
        int petW = effectiveWidth();
        Point loc = logicalLocation();
        int bestDist = Integer.MAX_VALUE;
        for (Rectangle r : world.topmostWindows()) {
            if (r.x + r.width <= mon.x || r.x >= mon.x + mon.width) continue;
            int rightEdge = r.x + r.width - petW;
            int leftEdge = r.x;
            int d = Math.min(Math.abs(rightEdge - loc.x), Math.abs(leftEdge - loc.x));
            if (d < bestDist) { bestDist = d; perch = r; }
        }
        if (perch != null) {
            int rightEdge = perch.x + perch.width - petW;
            int leftEdge = perch.x;
            int target = Math.abs(rightEdge - loc.x) <= Math.abs(leftEdge - loc.x) ? rightEdge : leftEdge;
            walkAlongFloor(world, target);
        }
        if (interrupted()) return;
        sit();
        if (interrupted()) return;
        showEmote("bang", 400);
        if (interrupted()) return;
        lookAround();
        if (interrupted()) return;
        disappearAndReappear(world);
    }

    /**
     * High-perch leap: if a window taller than the current floor is in
     * jump range (within ~pet-width horizontally and at most ~2× pet-height
     * above current feet), walk to its column and "land" on its top via
     * {@link #walkAlongFloor} (which gravity-snaps onto the perch).
     * Otherwise no-op idle.
     */
    public final void highPerchLeap(World world) {
        Rectangle mon = currentMonitorBounds();
        int petW = effectiveWidth();
        int petH = effectiveHeight();
        int feetH = Math.max(1, (int) Math.round(petH * feetYRatio()));
        int curFeetY = logicalLocation().y + feetH;
        Rectangle target = null;
        int bestY = curFeetY;
        for (Rectangle r : world.topmostWindows()) {
            if (r.x + r.width <= mon.x || r.x >= mon.x + mon.width) continue;
            if (r.y >= curFeetY) continue;             // not higher than current floor
            if (curFeetY - r.y > 2 * petH) continue;   // too far up to "leap"
            if (r.y < bestY) { bestY = r.y; target = r; }
        }
        if (target == null) {
            idle();
            return;
        }
        // Walk to the horizontal center of the chosen perch — clamped so
        // the pet ends up over the perch, not off its edge. Gravity in
        // walkAlongFloor would refuse to climb up onto it, so we
        // explicitly jump-snap to the perch top before the horizontal walk.
        int targetX = Math.max(target.x, Math.min(target.x + target.width - petW,
                target.x + target.width / 2 - petW / 2));
        // Quick "leap" snap: teleport vertically to the perch top, then
        // walk horizontally along it.
        moveFrameTo(logicalLocation().x, target.y - feetH);
        walkAlongFloor(world, targetX);
    }

    /**
     * "Stalk": creep toward the current cursor X at half walk-step delay,
     * freezing (switching to look) if the cursor jumps more than
     * {@code skittishPx} between two samples. Best-effort, gives up after
     * ~4 s either way.
     */
    public final void stalkPointer(World world) {
        Point initial = World.cursorPos();
        if (initial == null) { idle(); return; }
        Sprites.apply(emoteLabel, "emote/target");
        long until = System.currentTimeMillis() + 4_000L;
        Point last = initial;
        while (System.currentTimeMillis() < until && !interrupted()) {
            Point cur = World.cursorPos();
            if (cur == null) break;
            int jump = Math.abs(cur.x - last.x) + Math.abs(cur.y - last.y);
            if (jump > 60) {
                lookAround();
                break;
            }
            walkTowardCursor(world, 80);
            last = cur;
            sleepInterruptible(150);
        }
        Sprites.apply(emoteLabel, null);
    }

    /** Dig in place: a couple of crouch-shuffle holds. */
    public final void dig() {
        Sprites.apply(emoteLabel, "emote/paw");
        for (int i = 0; i < 3 && !interrupted(); i++) {
            Sprites.apply(petLabel, doodleKind() + "/sit");
            sleepInterruptible(250);
            Sprites.apply(petLabel, doodleKind() + "/look/0");
            sleepInterruptible(180);
        }
        Sprites.apply(emoteLabel, null);
        needs.add(Need.BOREDOM, 25);
    }

    /** Wave / paw-up: holds the {@code stretch} pose with a small bob. */
    public final void wave() {
        Sprites.apply(emoteLabel, "emote/mini-heart");
        for (int i = 0; i < 2 && !interrupted(); i++) {
            Sprites.apply(petLabel, doodleKind() + "/stretch");
            sleepInterruptible(220);
            Sprites.apply(petLabel, doodleKind() + "/idle/0");
            sleepInterruptible(120);
        }
        Sprites.apply(emoteLabel, null);
    }

    /**
     * Ducky-style left-right combo idle: cycles look/0 ↔ look/1 with
     * pauses ("quack-combo" / sideways waggle). Generic so non-ducky pets
     * can call it too if their personality is set up to.
     */
    public final void leftRightCombo() {
        Sprites.apply(emoteLabel, "emote/note");
        for (int i = 0; i < 4 && !interrupted(); i++) {
            Sprites.apply(petLabel, doodleKind() + "/look/" + (i % 2));
            sleepInterruptible(220);
        }
        Sprites.apply(petLabel, doodleKind() + "/idle/0");
        needs.add(Need.BOREDOM, 15);
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
