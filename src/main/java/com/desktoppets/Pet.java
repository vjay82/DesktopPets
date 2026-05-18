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
    private int bubbleX, bubbleY, bubbleW, bubbleH;

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
    /**
     * Comic speech bubble used by {@link #speak} for per-species sounds
     * ("Woof!", "Meow", "Quack!"). Sits above the head in the same slot
     * as {@link #emoteLabel} but wider, and is invisible by default.
     */
    public final SpeechBubble speechLabel = new SpeechBubble();

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
        // Speech bubble: wider than the emote (text needs room) and pinned
        // to the top of the frame so it sits above the head. Height is
        // bounded so it never eats more than the top third of the frame.
        // Width is floored at 40 px so text stays readable even on a
        // 16-px pet, and X is clamped to 0 so the bubble doesn't render
        // entirely off the left edge of the (frame-clipped) contentPane.
        this.bubbleW = Math.max(40, newSize - 4);
        this.bubbleH = Math.max(18, newSize / 3);
        this.bubbleX = Math.max(0, (newSize - bubbleW) / 2);
        this.bubbleY = 0;
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

    /**
     * Frames used when the pet is in <i>run</i> gait (hunting, fleeing). Default
     * is the walk frames, so a subclass that hasn't shipped dedicated run art
     * just walks faster. Subclasses with real run sprites (Cat, Dog) override.
     */
    protected List<String> runLeftFrames()  { return walkLeftFrames();  }
    protected List<String> runRightFrames() { return walkRightFrames(); }

    /** Per-pixel step delay while running. Default: half the walk delay (min 2). */
    protected int runStepDelayMs() { return Math.max(2, walkStepDelayMs() / 2); }

    /** Pixels traversed per sprite-frame swap while running. Default: same as walk. */
    protected int runPixelsPerSpriteStep() { return pixelsPerSpriteStep(); }

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
            // Visitors (the wandering Bird) are handled by their own
            // dedicated activity (HUNT_BIRD) — exclude them from generic
            // pet-pet sociality (greet/chase/sniff/etc.) so the bird
            // doesn't get sniffed/greeted while it's just visiting.
            if (other.isVisitor()) {
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
     * Nearest live <i>visitor</i> pet on the same monitor (i.e. the
     * wandering Bird) within {@code maxDx} logical pixels. Used by
     * {@link Activities#HUNT_BIRD} so any resident pet can spot and chase
     * a visiting bird without that bird being treated as a regular
     * sibling by {@link #nearestOtherPet}.
     */
    public final Pet nearestVisitor(int maxDx) {
        Rectangle myMon = currentMonitorBounds();
        int myMid = logicalLocation().x + effectiveWidth() / 2;
        Pet best = null;
        int bestDx = Integer.MAX_VALUE;
        for (Pet other : ACTIVE_PETS) {
            if (other == this || other.frame == null || !other.isVisitor()) {
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
    protected enum CollisionPlan { PASS, STOP, JUMP, HUNT }

    // ---------------- reactions to other pets ----------------

    /**
     * A short-lived state another pet has asked us to perform on its behalf.
     * <ul>
     *   <li>{@link #DUCK} — the other pet is jumping over us; we crouch
     *       (sit sprite) for the duration of the hop so the visual reads as
     *       both pets cooperating in a leapfrog.</li>
     *   <li>{@link #FLEE} — the other pet has started hunting us; we sprint
     *       away from {@link #reactionSourceX} on the next engine tick.</li>
     * </ul>
     * The flag is consumed by {@link BehaviorEngine#run} (which prioritises
     * reactions over normal activity selection) and self-clears after
     * {@link #reactionUntilMs}. The fields are {@code volatile} because
     * {@link #requestReaction} is called from <i>another</i> pet's behaviour
     * thread.
     */
    public enum Reaction { NONE, DUCK, FLEE, STEAL_BALL }

    public volatile Reaction reaction = Reaction.NONE;
    public volatile long reactionUntilMs = 0L;
    public volatile int reactionSourceX = 0;

    /**
     * Set a pending reaction on this pet, to be consumed on its next engine
     * tick. Safe to call from another pet's thread. For {@link Reaction#DUCK}
     * we also immediately apply the sit sprite so the visual fires even if
     * the target pet is mid-animation — its engine will hold the pose for
     * the remainder of the window when it next ticks.
     */
    public final void requestReaction(Reaction r, long durationMs, int sourceX) {
        if (r == null || r == Reaction.NONE) {
            return;
        }
        this.reactionSourceX = sourceX;
        this.reactionUntilMs = System.currentTimeMillis() + Math.max(0L, durationMs);
        this.reaction = r;
        if (r == Reaction.DUCK) {
            Sprites.apply(petLabel, doodleKind() + "/sit");
        }
    }

    /** True if {@link #reaction} is non-NONE and hasn't expired yet. */
    public final boolean hasActiveReaction() {
        return reaction != Reaction.NONE
                && System.currentTimeMillis() < reactionUntilMs;
    }

    /**
     * Consume a DUCK reaction: hold the sit sprite for the remainder of the
     * window so the pet stays crouched while the jumper passes over.
     */
    public final void holdDuck() {
        Sprites.apply(petLabel, doodleKind() + "/sit");
        long remaining = reactionUntilMs - System.currentTimeMillis();
        if (remaining > 0) {
            sleepInterruptible(remaining);
        }
        reaction = Reaction.NONE;
    }

    /**
     * Consume a STEAL_BALL reaction: trot to the column the original ball-
     * player was at ({@link #reactionSourceX}) and immediately start our own
     * {@link #playBall()}. The previous owner has already cleared their prop
     * by the time this fires (see the steal probe in {@link #playBall}).
     */
    public final void stealBallFrom(World world) {
        Rectangle mon = currentMonitorBounds();
        int petW = effectiveWidth();
        int targetX = Math.max(mon.x,
                Math.min(mon.x + mon.width - petW, reactionSourceX - petW / 2));
        showEmote("target", 300);
        runAlongFloor(world, targetX);
        reaction = Reaction.NONE;
        if (interrupted() || hovered || clicked) return;
        showEmote("sparkle", 400);
        playBall(world);
    }

    /**
     * Consume a FLEE reaction: sprint roughly a third of the current
     * monitor's width in the direction <i>away</i> from
     * {@link #reactionSourceX}, clamped to the monitor.
     */
    public final void fleeFrom(World world) {
        Rectangle mon = currentMonitorBounds();
        int petW = effectiveWidth();
        Point loc = logicalLocation();
        int myMid = loc.x + petW / 2;
        int dir = (myMid < reactionSourceX) ? -1 : 1;
        int fleeDist = Math.max(petW * 3, mon.width / 3);
        int targetX = loc.x + dir * fleeDist;
        targetX = Math.max(mon.x, Math.min(mon.x + mon.width - petW, targetX));
        showEmote("bang", 250);
        runAlongFloor(world, targetX);
        reaction = Reaction.NONE;
    }

    // ---------------- visitor pets (the wandering bird) ----------------

    /**
     * Visitor pets are spawned ad-hoc by {@link BirdVisitor}, are NOT part
     * of {@link PetSupervisor}'s resident pet roster, and run a stripped-
     * down behaviour loop ({@link #runVisitorLoop}) instead of the normal
     * {@link BehaviorEngine}: they walk in, sit for a short while, and
     * either fly away on their own (timeout) or after being scared by a
     * resident pet via {@link #scare(int)}.
     *
     * <p>Defaults to {@code false}; {@link Bird} overrides to {@code true}.
     */
    public boolean isVisitor() {
        return false;
    }

    /**
     * Set by a hunting resident pet (via {@link Activities#HUNT_BIRD}) to
     * tell a visitor it should take off. Also seeds {@link #reactionSourceX}
     * so {@link #runVisitorLoop} flies off the side opposite the hunter.
     * Safe to call from another pet's behaviour thread.
     */
    public final void scare(int sourceX) {
        this.reactionSourceX = sourceX;
        this.scared = true;
    }

    /**
     * Set on a visitor before its thread is started so {@link #initOnEdt}
     * can place the entry walk near a target column on a specific monitor
     * instead of the random {@link #pickEntryPlan}. The first three fields
     * must be non-null for the override to take effect; {@link
     * #plannedSpawnFromAbove} is optional (defaults to false = side entry)
     * and is only honored for flying pets, since ground pets can't
     * meaningfully descend through the air.
     */
    public volatile Rectangle plannedSpawnMonitor;
    public volatile Integer plannedSpawnTargetX;
    public volatile Boolean plannedSpawnFromRight;
    public volatile Boolean plannedSpawnFromAbove;

    /** Set by {@link #scare(int)} from a hunting pet's thread; read by the
     *  visitor loop to break out of the idle hold and fly off early. */
    public volatile boolean scared = false;

    /**
     * Default visitor stay window in milliseconds. The actual stay is
     * randomised in [{@value #VISITOR_STAY_MIN_MS}, {@value
     * #VISITOR_STAY_MAX_MS}]; this gives the user a reasonable chance to
     * notice the bird before it flies off on its own.
     */
    private static final long VISITOR_STAY_MIN_MS = 10_000L;
    private static final long VISITOR_STAY_MAX_MS = 30_000L;

    /**
     * Behaviour loop for visitor pets. Steps:
     * <ol>
     *   <li>walk in via the queued entry plan ({@link #runPendingEntryWalk});</li>
     *   <li>perch for a randomised interval, looking around and occasionally
     *       chirping, while checking {@link #scared} every beat;</li>
     *   <li>fly off the nearest (or hunter-opposite) edge and dispose.</li>
     * </ol>
     * No needs decay, no activity selection, no reactions — visitors are
     * intentionally simple so they can't get into weird states across the
     * short window they exist.
     */
    private void runVisitorLoop() {
        Log.info("pet:" + name, "visitor arrived");
        World world = World.snapshot(
                (int) screen.getWidth(), (int) screen.getHeight());
        reassertTopmost();
        runPendingEntryWalk(world);
        long stayMs = VISITOR_STAY_MIN_MS
                + ThreadLocalRandom.current().nextLong(0, VISITOR_STAY_MAX_MS - VISITOR_STAY_MIN_MS);
        long stayUntil = System.currentTimeMillis() + stayMs;
        long nextChirpAt = System.currentTimeMillis() + 2_000L
                + ThreadLocalRandom.current().nextLong(0, 3_000L);
        while (!Thread.currentThread().isInterrupted()
                && !scared
                && System.currentTimeMillis() < stayUntil) {
            reassertTopmost();
            int r = ThreadLocalRandom.current().nextInt(3);
            if (r == 0) {
                sit();
            } else if (r == 1) {
                idle();
            } else {
                Sprites.apply(petLabel, doodleKind() + "/look/0");
                sleepInterruptible(700L);
                Sprites.apply(petLabel, doodleKind() + "/look/1");
                sleepInterruptible(700L);
            }
            if (System.currentTimeMillis() >= nextChirpAt) {
                speak(randomSound(), 1100L, Integer.MIN_VALUE);
                nextChirpAt = System.currentTimeMillis() + 4_000L
                        + ThreadLocalRandom.current().nextLong(0, 5_000L);
            }
        }
        if (!Thread.currentThread().isInterrupted()) {
            flyAwayAndExit(world);
        }
        disposeWindow();
        Log.info("pet:" + name, "visitor departed");
    }

    /**
     * Exit flight for a visitor pet. Picks the side opposite the hunter
     * (when {@link #scared} is set and {@link #reactionSourceX} is on the
     * monitor) or the nearer edge, then uses {@link #walkTo} so that
     * {@link Bird#walkTo}'s diagonal flight carries it up and out.
     */
    private void flyAwayAndExit(World world) {
        Rectangle mon = currentMonitorBounds();
        Point loc = logicalLocation();
        int petW = effectiveWidth();
        int midX = loc.x + petW / 2;
        boolean exitRight;
        if (scared) {
            // Away from the threat.
            exitRight = midX >= reactionSourceX;
        } else {
            int leftDist = midX - mon.x;
            int rightDist = (mon.x + mon.width) - midX;
            exitRight = rightDist <= leftDist;
        }
        int exitX = exitRight ? mon.x + mon.width : mon.x - petW;
        int targetY = Math.max(mon.y, loc.y - Math.max(petW, mon.height / 3));
        showEmote("bang", 250);
        walkTo(exitX, targetY);
    }

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
     * Public view of {@link #collidesWithOtherPet} at the pet's *current*
     * location. Used by {@link Activities#MAKE_SPACE} to detect prolonged
     * stack-ups so the pet can shuffle aside.
     */
    public final Pet overlappingOtherPet() {
        Point loc = logicalLocation();
        int feetH = Math.max(1, (int) Math.round(effectiveHeight() * feetYRatio()));
        return collidesWithOtherPet(loc.x, loc.y, effectiveWidth(), feetH);
    }

    /** Timestamp (millis) at which the current overlap with another pet
     *  began. 0 when not overlapping. Maintained by
     *  {@link Activities#MAKE_SPACE}'s priority lambda. */
    public volatile long overlapStartMs = 0L;

    /**
     * Roll the collision-response plan. Weights chosen so PASS (the old
     * silent-through behaviour) is still the most common outcome, STOP is
     * frequent enough to be noticeable, and JUMP is the rare comedic option.
     *
     * @param huntEligible whether HUNT is a valid outcome for this encounter.
     *                     Pass {@code false} when the encountered pet is NOT
     *                     in the walker's direction of travel (so we don't
     *                     reverse direction to chase a sibling that's behind
     *                     us); the HUNT bucket is then folded back into PASS.
     */
    private static CollisionPlan pickCollisionPlan(boolean huntEligible) {
        int r = ThreadLocalRandom.current().nextInt(100);
        if (r < 45) return CollisionPlan.PASS;
        if (r < 75) return CollisionPlan.STOP;
        if (r < 90) return CollisionPlan.JUMP;
        // 10%: bumping into a pet sometimes triggers a chase — but only
        // when the prey is actually ahead of us. If it's behind, fall back
        // to PASS so we don't suddenly U-turn mid-walk.
        return huntEligible ? CollisionPlan.HUNT : CollisionPlan.PASS;
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
            if (isVisitor()) {
                runVisitorLoop();
            } else {
                new BehaviorEngine(this).run();
            }
        } finally {
            ACTIVE_PETS.remove(this);
        }
    }

    public final void disposeWindow() {
        ACTIVE_PETS.remove(this);
        if (frame == null) {
            return;
        }
        // mouseListener removal and frame disposal must both happen on
        // the EDT — Swing component mutation is not safe from the
        // behaviour thread.
        onEdt(() -> {
            if (mouseListener != null) {
                petLabel.removeMouseListener(mouseListener);
                mouseListener = null;
            }
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
            frame.add(speechLabel);
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
                //
                // Visitor pets (the wandering bird) can override the random
                // pick by setting plannedSpawnMonitor / plannedSpawnTargetX
                // / plannedSpawnFromRight BEFORE the thread starts, so
                // BirdVisitor can land the bird near a chosen resident pet.
                EntryPlan plan;
                if (plannedSpawnMonitor != null
                        && plannedSpawnTargetX != null
                        && plannedSpawnFromRight != null) {
                    boolean fromAbove = plannedSpawnFromAbove != null && plannedSpawnFromAbove;
                    plan = entryPlanForExplicit(plannedSpawnMonitor,
                            plannedSpawnTargetX, plannedSpawnFromRight, fromAbove);
                } else {
                    plan = pickEntryPlan(primary);
                }
                clamped = plan.target;
                firstSetLocation = plan.entryStart;
                pendingEntryTargetX = plan.target.x;
                pendingEntryTargetY = plan.fromAbove ? plan.target.y : null;
                activeMonitor = plan.monitor;
                // The native peer needs a non-zero size for setVisible() to
                // create the HWND we look up below. Use a 1-px-wide (or
                // 1-px-tall for above-entry) slice flush with the inside
                // of the entry edge: barely visible, and the first
                // walkAlongFloor()/walkTo() step will grow it.
                if (plan.fromAbove) {
                    initialBounds = new Rectangle(plan.target.x, plan.monitor.y, petSize, 1);
                } else {
                    int peerEdgeX = plan.fromRight
                            ? plan.monitor.x + plan.monitor.width - 1
                            : plan.monitor.x;
                    initialBounds = new Rectangle(peerEdgeX, plan.entryStart.y, 1, petSize);
                }
                Log.info("pet:" + name,
                        "entry from " + (plan.fromAbove
                                ? "above"
                                : (plan.fromRight ? "right" : "left"))
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
        speechLabel.setBounds(bubbleX, bubbleY, bubbleW, bubbleH);
        speechLabel.setVisible(false);
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
        // {@link #moveFrameTo} keeps the JFrame strictly within the
        // monitor, so the pet appears to slide in from off-screen even
        // though the frame never actually straddles two monitors.
        int entryStartX = fromRight
                ? mon.x + mon.width
                : mon.x - petSize;
        Point target = new Point(targetX, targetY);
        Point entryStart = new Point(entryStartX, targetY);
        return new EntryPlan(mon, fromRight, false, target, entryStart);
    }

    /**
     * Build an {@link EntryPlan} from caller-supplied monitor / target /
     * side instead of randomising. Used by visitor pets (see
     * {@link BirdVisitor}) to land the bird at a specific column near a
     * resident pet.
     *
     * <p>If {@code fromAbove} is true, the entry starts above the monitor
     * with a small lateral offset (opposite the {@code fromRight} side, so
     * the bird swoops in diagonally rather than dropping straight down
     * which would no-op {@link Bird#walkTo} because {@code dx == 0}).
     */
    private EntryPlan entryPlanForExplicit(Rectangle mon, int targetX,
            boolean fromRight, boolean fromAbove) {
        // Clamp the requested column to the monitor so a buggy caller
        // can't strand the bird outside it.
        int clampedX = Math.max(mon.x, Math.min(mon.x + mon.width - petSize, targetX));
        int workBottom = monitorBottomAtColumn(clampedX);
        int targetY = workBottom - spawnBottomOffset();
        Point target = new Point(clampedX, targetY);
        Point entryStart;
        if (fromAbove) {
            // Lateral offset so Bird.walkTo (which idle()s on dx==0) has
            // a non-zero horizontal delta to animate. Offset direction is
            // OPPOSITE fromRight: a bird whose anchor sits to its right
            // (fromRight = true means it swoops from the right side)
            // enters from the upper-right and flies down-left.
            int lateral = Math.max(petSize, 2 * petSize);
            int startX = fromRight ? clampedX + lateral : clampedX - lateral;
            entryStart = new Point(startX, mon.y - petSize);
        } else {
            int entryStartX = fromRight
                    ? mon.x + mon.width
                    : mon.x - petSize;
            entryStart = new Point(entryStartX, targetY);
        }
        return new EntryPlan(mon, fromRight, fromAbove, target, entryStart);
    }

    /** Plan for the spawn entry walk: chosen monitor, side, end and start positions. */
    private record EntryPlan(Rectangle monitor, boolean fromRight, boolean fromAbove,
            Point target, Point entryStart) { }

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

    /**
     * Pet eats in place; fully restores HUNGER and shows a food-bowl overlay.
     * Telegraphs the action first by showing a {@code think-food} thought
     * bubble above the pet for ~1 s so the user sees the hunger spike
     * register rather than the bowl appearing out of nowhere.
     */
    public void eat() {
        showEmote("think-food", 1000);
        if (interrupted() || hovered || clicked) return;
        showProp("prop/food");
        playFrames(petLabel, idleFrames(), IDLE_FRAME_MS);
        sleepInterruptible(EAT_HOLD_MS);
        needs.add(Need.HUNGER, 100);
        clearProp();
    }

    /**
     * Pet drinks in place; fully restores THIRST and shows a water-bowl
     * overlay. Telegraphs the action first via a {@code think-water} thought
     * bubble. Mirrors {@link #eat()} so the hunger/thirst pair reads as a
     * single visual pattern.
     */
    public void drink() {
        showEmote("think-water", 1000);
        if (interrupted() || hovered || clicked) return;
        showProp("prop/water");
        playFrames(petLabel, idleFrames(), IDLE_FRAME_MS);
        sleepInterruptible(EAT_HOLD_MS);
        needs.add(Need.THIRST, 100);
        clearProp();
    }

    /**
     * Comedic spin-in-place: alternate right-walk and left-walk frame cycles
     * at high speed so the pet appears to chase its own tail. Universal —
     * looks like rapid spinning for ground pets and rapid flapping for the
     * bird (its walk frames are flap cycles).
     */
    public void chaseTail() {
        showEmote("sparkle", 1400);
        List<String> r = walkRightFrames();
        List<String> l = walkLeftFrames();
        int cycles = 8;
        for (int c = 0; c < cycles; c++) {
            if (interrupted() || hovered || clicked) {
                idle();
                return;
            }
            List<String> dir = (c % 2 == 0) ? r : l;
            for (String f : dir) {
                if (interrupted()) { idle(); return; }
                Sprites.apply(petLabel, f);
                sleepInterruptible(55);
            }
        }
        idle();
    }

    /**
     * Four small vertical bobs with a {@code drop} emote — looks like the
     * pet has the hiccups. Uses moveFrameTo on the current logical column
     * (no horizontal travel) so it never strays from where it started.
     */
    public void hiccup() {
        Point start = logicalLocation();
        for (int i = 0; i < 4; i++) {
            if (interrupted() || hovered || clicked) return;
            showEmote("drop", 250);
            moveFrameTo(start.x, Math.max(0, start.y - 4));
            sleepInterruptible(140);
            moveFrameTo(start.x, start.y);
            sleepInterruptible(500 + ThreadLocalRandom.current().nextInt(400));
        }
    }

    /**
     * Sit down and watch the sky: three slow {@code sparkle} emotes above
     * the seated pet. Peaceful filler that breaks up the more frenetic
     * ambient activities.
     */
    public void stargaze() {
        sit();
        if (interrupted() || hovered || clicked) return;
        for (int i = 0; i < 3; i++) {
            if (interrupted()) return;
            showEmote("sparkle", 1000);
            sleepInterruptible(1200);
        }
    }

    /**
     * Sit and emit three mini-hearts in succession — visible "I love you"
     * moment, with a small AFFECTION top-up as a side effect (matches the
     * {@code needs.add} satisfy-by-N convention used elsewhere).
     */
    public void burstOfHearts() {
        sit();
        if (interrupted() || hovered || clicked) return;
        for (int i = 0; i < 3; i++) {
            if (interrupted()) return;
            showEmote("mini-heart", 700);
            sleepInterruptible(500);
        }
        needs.add(Need.AFFECTION, 30);
    }

    /**
     * Curious head-tilt: cycle the "look" sprite (left then right then left)
     * with a yellow {@code question} emote floating above the pet. Universal
     * — every pet has a {@code look} key in {@link Doodle}, so the sprite
     * always resolves.
     */
    public void headTilt() {
        showEmote("question", 1600);
        for (int i = 0; i < 3; i++) {
            if (interrupted() || hovered || clicked) {
                idle();
                return;
            }
            Sprites.apply(petLabel, doodleKind() + "/look/" + i);
            sleepInterruptible(550);
        }
        idle();
    }

    /**
     * Sleepy yawn: sit, show {@code zzz} emote, sit longer, second {@code zzz}.
     * Mildly restores ENERGY (the yawn doesn't actually sleep — see
     * {@link #sleep()} for the full nap — but it acknowledges that the pet is
     * tired, so we credit a small amount so YAWN doesn't fire back-to-back
     * against an already-very-tired pet).
     */
    public void yawn() {
        sit();
        if (interrupted() || hovered || clicked) return;
        showEmote("zzz", 1600);
        sleepInterruptible(400);
        if (interrupted()) return;
        showEmote("zzz", 1100);
        needs.add(Need.ENERGY, 15);
    }

    /**
     * Quiet night gaze: sit, then three slow {@code moon} emotes 1.2 s apart.
     * Calmer counterpart to {@link #stargaze()} (which uses sparkles) — gives
     * the rotation a second peaceful filler that reads as nighttime mood.
     */
    public void moonGaze() {
        sit();
        if (interrupted() || hovered || clicked) return;
        for (int i = 0; i < 3; i++) {
            if (interrupted()) return;
            showEmote("moon", 1100);
            sleepInterruptible(1200);
        }
    }

    /**
     * Anxious pacing: walk one body-width right, then one body-width left,
     * three times. Stays anchored to the starting column on average so a
     * pacing pet doesn't drift across the whole monitor. No new sprite —
     * reuses the regular walk gait.
     */
    public final void paceBackAndForth(World world) {
        Point start = logicalLocation();
        int petW = effectiveWidth();
        Rectangle mon = currentMonitorBounds();
        int rightX = Math.min(mon.x + mon.width - petW, start.x + petW);
        int leftX  = Math.max(mon.x,                    start.x - petW);
        for (int i = 0; i < 3; i++) {
            if (interrupted() || hovered || clicked) return;
            walkAlongFloor(world, rightX);
            if (interrupted() || hovered || clicked) return;
            walkAlongFloor(world, leftX);
        }
        walkAlongFloor(world, start.x);
    }

    /**
     * Silly solo activity: walk to the nearest left/right monitor edge, sit,
     * and lick the screen three times. Reads as the pet trying to clean the
     * "glass" from the inside. No interaction with siblings — purely visual
     * comedy. Edge picked by proximity so the pet doesn't always traverse
     * the whole monitor.
     */
    public void lickScreenEdge(World world) {
        Rectangle mon = currentMonitorBounds();
        int petW = effectiveWidth();
        int curMid = logicalLocation().x + petW / 2;
        int monMid = mon.x + mon.width / 2;
        int targetX = (curMid < monMid) ? mon.x : mon.x + mon.width - petW;
        walkAlongFloor(world, targetX);
        if (interrupted() || hovered || clicked) return;
        sit();
        for (int i = 0; i < 3; i++) {
            if (interrupted() || hovered || clicked) return;
            showEmote("lick", 700);
            sleepInterruptible(500);
        }
        idle();
    }

    /** Search radius (logical px) within which a sibling pet may steal the ball. */
    private static final int BALL_STEAL_RADIUS = 900;
    /** Per-bounce probability (percent) that a nearby sibling steals the ball. */
    private static final int BALL_STEAL_CHANCE_PCT = 25;

    /**
     * Play with a ball across the floor. Each round is a clean cause →
     * effect sequence:
     * <ol>
     *   <li><b>Kick.</b> Pet stands at its current column facing the
     *       direction of play, plays a {@code paw} bat emote.</li>
     *   <li><b>Ball flies.</b> The ball rolls (ease-out cubic) from the
     *       pet's near side to the far side of the frame while the pet
     *       stays put — you can clearly see the kick has launched the
     *       ball before the pet moves.</li>
     *   <li><b>Pet chases.</b> The pet runs after the ball: the frame
     *       slides toward where the ball now sits, the ball stays at the
     *       same screen position by decreasing its in-frame x to offset
     *       the frame's slide, and the walk frames cycle. At the end the
     *       pet is right next to the ball again, on the OPPOSITE side
     *       from where it started — ready to kick the other way.</li>
     * </ol>
     * Rounds alternate direction so the pet bounces the ball back and
     * forth. Ball size is ~20\u202F% of the pet and rests at the
     * sprite's {@link #feetYRatio() feet line} (not the bottom of the
     * window) so it doesn't float for pets like Ducky that have
     * transparent padding under the feet. Between rounds a sibling pet
     * within {@link #BALL_STEAL_RADIUS} has a
     * {@link #BALL_STEAL_CHANCE_PCT}\u202F% chance to steal it. Restores
     * {@link Need#BOREDOM}.
     */
    public void playBall(World world) {
        final int ballSize = Math.max(8, (int) (petSize * 0.20));
        final int ballLocalLeft  = 0;
        final int ballLocalRight = petSize - ballSize;
        // Ball sits at the pet's feet line (not flush with the bottom of
        // the frame) - some sprites (e.g. Ducky) have transparent padding
        // below the feet inside the viewBox, and pinning the ball to that
        // padding makes it float visibly below the sprite.
        final int feetH = Math.max(1, (int) Math.round(petSize * feetYRatio()));
        final int floorYInFrame = Math.max(0, feetH - ballSize);
        // Kick is fast (ball launches) and chase is a touch slower
        // (running takes effort). Both at ~60 FPS via 16 ms steps.
        final int kickSubSteps = 16;
        final int chaseSubSteps = 24;
        final long stepMs = 16L;
        final int rounds = 6;

        List<String> leftFrames  = walkLeftFrames();
        List<String> rightFrames = walkRightFrames();

        Rectangle mon = currentMonitorBounds();
        Point startLoc = logicalLocation();
        int petW = effectiveWidth();
        // Pet starts at its current column, clamped onto the monitor.
        int petX = Math.max(mon.x, Math.min(mon.x + mon.width - petW, startLoc.x));
        moveFrameTo(petX, floorYAt(world, petX));

        for (int i = 0; i < rounds; i++) {
            if (interrupted()) { clearProp(); return; }

            // Between rounds, give a nearby sibling a chance to snatch the
            // ball. Skip on the very first iteration so the ball at least
            // appears once before the steal can fire.
            if (i > 0) {
                Pet thief = nearestOtherPet(BALL_STEAL_RADIUS);
                if (thief != null
                        && ThreadLocalRandom.current().nextInt(100) < BALL_STEAL_CHANCE_PCT) {
                    Sprites.apply(petLabel, idleFrames().get(0));
                    showEmote("bang", 500);
                    int myMid = logicalLocation().x + petSize / 2;
                    thief.requestReaction(Reaction.STEAL_BALL, 8_000L, myMid);
                    clearProp();
                    // BOREDOM is partly drained \u2014 we did play, just briefly.
                    needs.add(Need.BOREDOM, 40);
                    return;
                }
            }

            boolean goingRight = (i % 2 == 0);
            List<String> facingFrames = goingRight ? rightFrames : leftFrames;
            // Ball starts on the pet's near side (where the foot meets
            // the ball at the moment of contact) and is kicked AWAY across
            // the frame to the far side.
            int ballStart = goingRight ? ballLocalLeft  : ballLocalRight;
            int ballEnd   = goingRight ? ballLocalRight : ballLocalLeft;

            // Face the direction of play; freshen pet's floor Y (the
            // taskbar / underlying window may have moved between rounds).
            moveFrameTo(petX, floorYAt(world, petX));
            Sprites.apply(petLabel, facingFrames.get(0));
            showProp("prop/ball");
            final int fb0 = ballStart;
            onEdt(() -> propLabel.setBounds(fb0, floorYInFrame, ballSize, ballSize));
            showEmote("paw", 220);
            if (interrupted()) { clearProp(); return; }

            // --- Phase A: ball flies. Pet stays put; ball rolls across
            // the frame with ease-out cubic so the kick reads as a burst
            // of speed that bleeds off into a slow finish.
            for (int s = 1; s <= kickSubSteps; s++) {
                if (interrupted()) { clearProp(); return; }
                double t = (double) s / kickSubSteps;
                double eased = 1.0 - Math.pow(1.0 - t, 3.0);
                int ballLocal = (int) Math.round(ballStart + (ballEnd - ballStart) * eased);
                final int fx = ballLocal;
                onEdt(() -> propLabel.setBounds(fx, floorYInFrame, ballSize, ballSize));
                sleepInterruptible(stepMs);
            }

            // --- Phase B: pet runs after the ball.
            // The ball now sits at screen-x = petX + ballEnd. We slide
            // the frame toward that side; clamped to monitor so the pet
            // can't run off-screen. The ball stays at the same SCREEN x
            // by decreasing its in-frame x by the same amount the frame
            // moves.
            int dx = ballEnd - ballStart; // +ve right, -ve left
            int chaseFromX = petX;
            int chaseToX = Math.max(mon.x,
                    Math.min(mon.x + mon.width - petW, petX + dx));
            int actualDx = chaseToX - chaseFromX;
            int ballScreenX = chaseFromX + ballEnd;
            int frameIdx = 0;
            for (int s = 1; s <= chaseSubSteps; s++) {
                if (interrupted()) { clearProp(); return; }
                double t = (double) s / chaseSubSteps;
                int petCurX = chaseFromX + (int) Math.round(actualDx * t);
                int petCurY = floorYAt(world, petCurX);
                moveFrameTo(petCurX, petCurY);
                int ballLocal = Math.max(0,
                        Math.min(petSize - ballSize, ballScreenX - petCurX));
                final int fx = ballLocal;
                onEdt(() -> propLabel.setBounds(fx, floorYInFrame, ballSize, ballSize));
                Sprites.apply(petLabel, facingFrames.get(frameIdx % facingFrames.size()));
                frameIdx++;
                sleepInterruptible(stepMs);
            }
            petX = chaseToX;
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
        speechLabel.setLocation(bubbleX - offX, bubbleY - offY);
    }

    /** When non-null, the pet was spawned just off the edge of a monitor
     *  and should walk inward to this X on the first behaviour tick.
     *  Cleared after the entry walk runs. */
    private volatile Integer pendingEntryTargetX;
    /** When non-null (always paired with {@link #pendingEntryTargetX}),
     *  the entry walk is a "from-above" descent: the entry walk calls
     *  {@link #walkTo}(targetX, targetY) instead of
     *  {@link #walkAlongFloor}, so flying visitors (birds) can swoop
     *  diagonally down to the floor rather than scuttling in along an
     *  edge. Cleared with {@link #pendingEntryTargetX}. */
    private volatile Integer pendingEntryTargetY;
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
        Integer targetY = pendingEntryTargetY;
        pendingEntryTargetX = null;
        pendingEntryTargetY = null;
        entryWalkInProgress = true;
        try {
            if (targetY != null) {
                // Above-entry: dive diagonally from above-monitor straight
                // to the floor target. walkTo handles flight (overridden
                // for Bird) or a plain horizontal walk with a Y snap for
                // ground pets — but in practice only flying visitors use
                // this code path.
                walkTo(targetX, targetY);
            } else {
                walkAlongFloor(world, targetX);
            }
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
        gaitAlongFloor(world, targetX, false);
    }

    /**
     * Same as {@link #walkAlongFloor} but in a faster "run" gait — used for
     * hunting / fleeing. Pets without dedicated run sprites fall back to walk
     * frames cycled at the run step-delay (so they still feel faster).
     * Subclasses that override {@link #walkAlongFloor} (e.g. {@link Bird},
     * which flies) should override this too if they want different
     * fast-travel behaviour; the default delegates to {@link #walkAlongFloor}
     * via virtual dispatch so flyers keep flying.
     */
    public void runAlongFloor(World world, int targetX) {
        gaitAlongFloor(world, targetX, true);
    }

    /**
     * Shared body of {@link #walkAlongFloor} and {@link #runAlongFloor}.
     * Selects walk vs run frame set + step delay + sprite-step distance based
     * on the {@code running} flag. All collision / jump / floor-profile logic
     * is identical between the two gaits — only sprite + pacing differ.
     */
    private void gaitAlongFloor(World world, int targetX, boolean running) {
        Point start = logicalLocation();
        int dx = targetX - start.x;
        if (dx == 0) {
            idle();
            return;
        }
        boolean goingRight = dx > 0;
        List<String> frames = goingRight
                ? (running ? runRightFrames() : walkRightFrames())
                : (running ? runLeftFrames()  : walkLeftFrames());
        int steps = Math.abs(dx);
        int spriteIndex = 0;
        int stepDelay = running ? runStepDelayMs()         : walkStepDelayMs();
        int pps       = running ? runPixelsPerSpriteStep() : pixelsPerSpriteStep();
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
            // A jump arc, once started, must run to completion: bailing
            // out mid-arc would leave the pet visually suspended in the
            // air (and the duck-ee crouching for nothing). We still honor
            // a true thread interrupt (engine shutdown) so the pet can
            // exit, but hover/click are deferred until the pet lands.
            boolean midJump = jumpSpan > 0 && jumpStep < jumpSpan;
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            if (!midJump && (interrupted() || hovered || clicked)) {
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
                // HUNT is only allowed when the collided pet is actually in
                // front of us — i.e. its mid-X lies in the direction we're
                // walking. Otherwise (e.g. we started overlapped and are
                // moving AWAY) HUNT would force a 180° turn that reads as a
                // teleport-and-chase rather than "spotted prey ahead".
                int myMid = logicalLocation().x + petW / 2;
                int aheadMid = ahead.logicalLocation().x + ahead.effectiveWidth() / 2;
                boolean preyInFront = goingRight ? aheadMid >= myMid : aheadMid <= myMid;
                plan = pickCollisionPlan(preyInFront);
                if (plan == CollisionPlan.HUNT) {
                    // We just bumped into a sibling — promote this walk into
                    // an impromptu chase. Tell the prey to flee, show a target
                    // emote, hand them a tiny head-start, then sprint after
                    // them. The original walk is abandoned (return) since
                    // runAlongFloor takes us somewhere else.
                    long fleeMs = 6_000L;
                    ahead.requestReaction(Reaction.FLEE, fleeMs, x + petW / 2);
                    showEmote("target", 350);
                    sleepInterruptible(200);
                    if (interrupted() || hovered || clicked) return;
                    int preyMid = ahead.logicalLocation().x + ahead.effectiveWidth() / 2;
                    int hunterTarget = (logicalLocation().x < preyMid)
                            ? preyMid - petW
                            : preyMid;
                    runAlongFloor(world, hunterTarget);
                    return;
                }
                if (plan == CollisionPlan.JUMP) {
                    // Arc spans the full cross — from first contact until the
                    // pet has travelled past the other pet's far edge — so the
                    // sine curve completes one full hop.
                    jumpStep = 0;
                    jumpSpan = Math.max(1, petW + ahead.effectiveWidth());
                    jumpPeak = Math.max(8, petH / 2);
                    // Ask the pet we're hopping over to crouch so the visual
                    // reads as cooperative leapfrog rather than a mid-air
                    // teleport over a frozen sibling. Duration is the full
                    // arc plus a small settle so the duckee stays low until
                    // we've cleared them.
                    long arcMs = (long) jumpSpan * Math.max(15, stepDelay);
                    ahead.requestReaction(Reaction.DUCK, arcMs + 250L, x + petW / 2);
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
            // Bail as soon as the cursor leaves the pet's frame — the
            // bubble is a hover feedback, not an unconditional animation,
            // so we shouldn't keep playing the remaining frames once the
            // user has moved on.
            if (interrupted() || !hovered) {
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
     * Per-species sound phrases shown in {@link #speak speech bubbles}.
     * Overridden by {@link Dog}, {@link Cat}, {@link Bird}, {@link Ducky}.
     * Default falls back to a generic "Hi!" so an un-overridden subclass
     * still says something rather than crashing.
     */
    public String[] sounds() {
        return new String[] { "Hi!" };
    }

    /**
     * Pick a random sound from {@link #sounds()}. Convenience for callers
     * that don't care which specific phrase fires.
     */
    public final String randomSound() {
        String[] s = sounds();
        if (s == null || s.length == 0) return "Hi!";
        return s[ThreadLocalRandom.current().nextInt(s.length)];
    }

    /**
     * Show a comic-style speech bubble above the pet for {@code ms},
     * with the tail pointing at {@code targetMidX} (the logical-screen X
     * of whoever is being addressed: another pet's centre, the cursor, or
     * the foreground window). The tail side is computed from the target's
     * position relative to this pet so the bubble visibly "points at" the
     * addressee. Blocks the caller for {@code ms}; interruptible.
     *
     * <p>Pass {@link Integer#MIN_VALUE} for {@code targetMidX} to render a
     * centered tail (no specific addressee).
     */
    public final void speak(String text, long ms, int targetMidX) {
        if (text == null || text.isEmpty()) {
            sleepInterruptible(ms);
            return;
        }
        SpeechBubble.Tail side;
        if (targetMidX == Integer.MIN_VALUE) {
            side = SpeechBubble.Tail.CENTER;
        } else {
            int myMid = logicalLocation().x + petSize / 2;
            if      (targetMidX < myMid - 8) side = SpeechBubble.Tail.LEFT;
            else if (targetMidX > myMid + 8) side = SpeechBubble.Tail.RIGHT;
            else                              side = SpeechBubble.Tail.CENTER;
        }
        onEdt(() -> {
            speechLabel.setSpeech(text, side);
            speechLabel.setVisible(true);
            speechLabel.repaint();
        });
        sleepInterruptible(ms);
        onEdt(() -> {
            speechLabel.setVisible(false);
            speechLabel.setSpeech("", SpeechBubble.Tail.CENTER);
        });
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

    /**
     * Hunt the cursor for several seconds: re-target every loop iteration
     * (so a moving cursor visibly drags the pet around) and break into
     * short paw-pounces whenever the pet's column overlaps the cursor.
     * Triggered by {@link Activities#HUNT_CURSOR} when the user has been
     * fiddling the mouse near the pet for a few seconds. Bails out early
     * if the cursor leaves the monitor or goes still. Restores
     * {@link Need#BOREDOM}.
     */
    public final void huntCursor(World world) {
        Point first = World.cursorPos();
        if (first == null) { idle(); return; }
        Sprites.apply(emoteLabel, "emote/target");
        long until = System.currentTimeMillis() + 6_000L;
        int pounces = 0;
        while (System.currentTimeMillis() < until && !interrupted()) {
            Point cur = World.cursorPos();
            if (cur == null) break;
            Rectangle mon = currentMonitorBounds();
            if (!mon.contains(cur)) break;
            int petW = effectiveWidth();
            int petMid = logicalLocation().x + petW / 2;
            int dx = cur.x - petMid;
            if (Math.abs(dx) <= petW / 2) {
                // In range: pounce in place with a paw emote, then loop
                // again so a still-moving cursor pulls the pet onward.
                Sprites.apply(emoteLabel, "emote/paw");
                sleepInterruptible(280);
                Sprites.apply(emoteLabel, "emote/target");
                pounces++;
                if (pounces >= 3) break;
                continue;
            }
            // Aim just past the cursor (in the cursor's direction) so the
            // pet's centre lands roughly under the cursor X rather than
            // stopping a body-width short.
            int sign = dx > 0 ? 1 : -1;
            int targetMid = cur.x - sign * petW / 4;
            int targetX = Math.max(mon.x,
                    Math.min(mon.x + mon.width - petW, targetMid - petW / 2));
            runAlongFloor(world, targetX);
        }
        Sprites.apply(emoteLabel, null);
        needs.add(Need.BOREDOM, 60);
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

    /**
     * Scratch an itch in place. The pet sits, then alternates the
     * {@code /scratch} pose with {@code /look/1} a few times — reads as
     * a leg-kick / paw-flap scratching motion regardless of species. A
     * small {@code paw} emote bobs above to telegraph the "itch", and
     * BOREDOM gets a modest bump like the other one-off filler moves
     * (dig/wave/look-around). Cancels cleanly on hover/click/interrupt
     * via the standard {@link #interrupted} check between beats.
     */
    public final void scratch() {
        Sprites.apply(emoteLabel, "emote/paw");
        Sprites.apply(petLabel, doodleKind() + "/sit");
        sleepInterruptible(220);
        for (int i = 0; i < 4 && !interrupted(); i++) {
            Sprites.apply(petLabel, doodleKind() + "/scratch");
            sleepInterruptible(180);
            Sprites.apply(petLabel, doodleKind() + "/look/1");
            sleepInterruptible(140);
        }
        if (!interrupted()) {
            Sprites.apply(petLabel, doodleKind() + "/sit");
            sleepInterruptible(180);
        }
        Sprites.apply(emoteLabel, null);
        needs.add(Need.BOREDOM, 20);
    }

    /**
     * Dance loop: cycles the two {@code /dance/0..1} sway frames over a
     * music-note emote for a handful of beats. Each species' Dance/
     * sprites are the sit/idle pose translated a couple of pixels in
     * opposite directions, so cycling them at ~280 ms produces a clear
     * side-to-side wiggle. Interrupts cleanly on hover/click/external
     * thread interrupt.
     */
    public final void dance() {
        Sprites.apply(emoteLabel, "emote/note");
        for (int i = 0; i < 8 && !interrupted(); i++) {
            Sprites.apply(petLabel, doodleKind() + "/dance/" + (i % 2));
            sleepInterruptible(280);
        }
        Sprites.apply(emoteLabel, null);
        if (!interrupted()) {
            Sprites.apply(petLabel, doodleKind() + "/sit");
            sleepInterruptible(150);
        }
        needs.add(Need.BOREDOM, 30);
        needs.add(Need.AFFECTION, 10);
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
