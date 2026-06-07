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
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    /** Created on the EDT in {@link #initOnEdt()}; null until {@link #run()} starts.
     *  Backed by a {@link Stage}-owned JPanel rather than a top-level JFrame:
     *  every pet shares one transparent click-through window per monitor so
     *  movement is a cheap intra-window component move instead of an
     *  {@code SetWindowPos} per pet per tick. */
    public PetWindow frame;
    /** Set by {@link #disposeWindow()} before the EDT dispose runs so any
     *  behaviour-thread {@link #moveFrameTo} call that races with shutdown
     *  doesn't resurrect the JFrame peer via {@code setVisible(true)}. */
    private volatile boolean disposed = false;
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
    /**
     * Set true by the AWT mouse listener; consumed atomically via
     * {@link AtomicBoolean#getAndSet(boolean)} by the behavior thread
     * so a click arriving between the check and the reset is never lost.
     */
    public final AtomicBoolean clicked = new AtomicBoolean(false);

    /** Throttles hover-driven affection gains so a parked cursor can't max it instantly. */
    private volatile long lastHoverGainAtMs = 0L;
    private static final long HOVER_GAIN_INTERVAL_MS = 1500L;
    private static final double HOVER_GAIN_AMOUNT = 30.0;

    /**
     * Name of the activity this pet is currently performing (set by
     * {@link BehaviorEngine} just before calling {@link Activity#perform}
     * and cleared back to {@code ""} once the activity returns). Read by
     * sibling pets' activity priority/action lambdas to coordinate
     * interactions like JOIN_DANCE without poking at engine internals.
     * Volatile so cross-thread reads see the latest value.
     */
    public volatile String currentActivityName = "";

    /**
     * Set by {@link PetSupervisor} (via {@link #setPaused(boolean)}) to gate
     * animation between frames. Read directly is fine; mutate via the helper
     * so paused threads waiting in {@link #interrupted()} get woken on resume.
     */
    public final AtomicBoolean paused = new AtomicBoolean(false);
    /** Monitor used to park behaviour threads while {@link #paused} is true. */
    private final Object pauseLock = new Object();

    /** Set the paused flag and wake any threads parked in {@link #interrupted()}. */
    public final void setPaused(boolean p) {
        synchronized (pauseLock) {
            paused.set(p);
            if (!p) {
                pauseLock.notifyAll();
            }
        }
    }

    /** 0 = lethargic, 1 = normal, 2 = hyperactive. Read by {@link BehaviorEngine}. */
    public volatile double activityLevel = 1.0;

    /**
     * Hue rotation in degrees [0, 360) applied to every sprite frame this
     * pet renders, so visually-identical instances of the same species can
     * be distinguished at a glance. {@code 0.0} disables tinting (the
     * sprite is shown in its natural colours). Set once by
     * {@link PetSupervisor} just after construction, before the behaviour
     * thread starts; subsequent settings-driven changes recreate the pet.
     */
    public volatile double hueShift;

    /**
     * Per-pet size multiplier applied on top of the supervisor's global
     * {@code petSize}. {@code 1.0} = full-size adult (default), values
     * below {@code 1.0} render this pet as a smaller "child" of its
     * species. Set once by {@link PetSupervisor} just after construction
     * and re-read by {@link PetSupervisor#setPetSize(int)} so a global
     * resize keeps each pet's relative scale. Range is clamped by
     * callers; {@link Pet#setSize(int)} still enforces the absolute
     * [16, 256] window so an extreme multiplier can't make a pet
     * illegibly small or absurdly large.
     */
    public volatile double sizeScale = 1.0;

    /**
     * Apply a doodle sprite key to {@link #petLabel} using this pet's
     * {@link #hueShift}. Single funnel so call sites don't need to repeat
     * the hue parameter. Non-pet labels ({@link #heartLabel},
     * {@link #propLabel}, {@link #emoteLabel}, {@link #speechLabel}) keep
     * using {@link Sprites#apply(JLabel, String)} directly so their
     * emote/heart/prop graphics stay in their canonical colours.
     */
    protected final void applySprite(String key) {
        // Hot path skip: pet animation loops (idle holds, look-look-look,
        // sit poses) re-apply the same sprite key on every behaviour
        // tick. Doodle.icon returns the same ImageIcon reference for
        // identical (key, size, hue) so the underlying JLabel.setIcon is
        // a near no-op, but each Sprites.apply call still costs an
        // EDT invokeLater + lambda allocation. Skipping when the key
        // hasn't changed eliminates ~hundreds of EDT events/sec across
        // a 15-pet roster.
        if (key != null && key.equals(this.lastPetSpriteKey)) {
            return;
        }
        Sprites.apply(petLabel, key, hueShift);
        this.lastPetSpriteKey = key;
    }

    /** Last sprite key applied to {@link #petLabel}. Tracked so callers
     *  (e.g. {@link Activities#IDLE}) can tell what pose the pet is
     *  currently holding without inspecting the JLabel's icon. */
    private volatile String lastPetSpriteKey;

    /** True iff the pet's body sprite is currently a sit-pose frame
     *  (key starts with {@code doodleKind() + "/sit"}). Used by the
     *  IDLE activity to keep a recently-sat pet seated through the
     *  forced post-activity rest period, rather than briefly standing
     *  up between two sit-based activities (yawn → daydream etc.). */
    public final boolean isInSitPose() {
        String k = lastPetSpriteKey;
        return k != null && k.startsWith(doodleKind() + "/sit");
    }

    private Thread heartThread = new Thread();

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

    /** Auto-detected geometry of this pet's idle sprite. Cached per JVM
     *  in {@link SpriteMetrics}, and again per-instance here so the
     *  hot-path ratio accessors (called per walk step / per IDLE settle)
     *  don't keep redoing the {@link Doodle#resolve} string-split and
     *  per-call concurrent-map lookup. The idle sprite is determined by
     *  {@link #doodleKind()} which is constant for a pet's lifetime.
     *  {@code volatile} so the behaviour thread sees the constructor-thread
     *  init (or any later (re)initialisation) without a data race. */
    private volatile SpriteMetrics.Bounds cachedMetrics;
    private SpriteMetrics.Bounds metrics() {
        SpriteMetrics.Bounds m = cachedMetrics;
        if (m == null) {
            m = SpriteMetrics.of(Doodle.resolve(doodleKind() + "/idle/0"));
            cachedMetrics = m;
        }
        return m;
    }

    // ---------------- subclass hooks ----------------

    /** Lowercase pet key used to build doodle keys (e.g. "ducky"). */
    protected abstract String doodleKind();

    /** Idle frames shown by {@link #idle()}. */
    protected List<String> idleFrames() {
        List<String> cached = idleFramesCache;
        if (cached == null) {
            cached = List.of(
                    doodleKind() + "/idle/0",
                    doodleKind() + "/idle/1",
                    doodleKind() + "/idle/2");
            idleFramesCache = cached;
        }
        return cached;
    }

    protected List<String> walkLeftFrames() {
        List<String> cached = walkLeftFramesCache;
        if (cached == null) {
            cached = frames(doodleKind() + "/walk-left", 0, 5);
            walkLeftFramesCache = cached;
        }
        return cached;
    }

    protected List<String> walkRightFrames() {
        List<String> cached = walkRightFramesCache;
        if (cached == null) {
            cached = frames(doodleKind() + "/walk-right", 0, 5);
            walkRightFramesCache = cached;
        }
        return cached;
    }

    /** Lazy caches for the default frame lists — every call site previously
     *  allocated a fresh {@code List.of(...)} / {@code unmodifiableList}
     *  wrapper per invocation. The lists are immutable strings derived
     *  from {@link #doodleKind()} which is constant per pet, so a single
     *  cached instance is safe for the pet's lifetime. */
    private volatile List<String> idleFramesCache;
    private volatile List<String> walkLeftFramesCache;
    private volatile List<String> walkRightFramesCache;

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

    /**
     * Play a brief 3-frame leg-swing animation for a ball kick, in place
     * of just freezing on {@code walk[0]} for the duration of the kick
     * (which made the kick visually indistinguishable from standing
     * still — "ball kicking sprites seem missing"). Cycles through
     * mid-walk → follow-through → recovery frames in the chosen
     * direction so a leg is visibly extended at the moment of contact.
     *
     * @param kickRight true to use the right-facing walk cycle.
     * @param totalMs   approximate total animation duration in ms; split
     *                  evenly across the 3 frames.
     */
    protected final void animateKick(boolean kickRight, long totalMs) {
        animateKick(kickRight, totalMs, null);
    }

    /**
     * Variant of {@link #animateKick(boolean, long)} that invokes
     * {@code onContact} the instant the contact (leg-extended) frame
     * becomes visible, i.e. ~1/3 of the way through the animation. Used
     * to apply the {@link Ball#kick(double)} impulse at the moment the
     * pet's foot meets the ball instead of at the end of the swing
     * (otherwise the ball, which keeps rolling under physics during the
     * 180 ms swing, can drift 100+ logical pixels away from the pet
     * before the impulse fires — perceived as "pet kicks the ball from
     * far away").
     */
    protected final void animateKick(boolean kickRight, long totalMs, Runnable onContact) {
        List<String> cycle = kickRight ? walkRightFrames() : walkLeftFrames();
        int n = cycle.size();
        if (n == 0) {
            if (onContact != null) onContact.run();
            sleepInterruptible(totalMs);
            return;
        }
        // Wind-up: mid-cycle (leg passing under body).
        // Contact:  one step further (leg extended forward).
        // Recover:  frame 0 (settled stance).
        String windup  = cycle.get(Math.min(n - 1, n / 2));
        String contact = cycle.get(Math.min(n - 1, n / 2 + 1));
        String recover = cycle.get(0);
        long slice = Math.max(40L, totalMs / 3);
        applySprite(windup);
        sleepInterruptible(slice);
        if (interrupted()) return;
        applySprite(contact);
        if (onContact != null) onContact.run();
        sleepInterruptible(slice);
        if (interrupted()) return;
        applySprite(recover);
        sleepInterruptible(slice);
    }

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
        clicked.set(false);
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
            // The cinematic-event visitors (UFO alien, airplane passenger,
            // mouse, rain cloud, drone, cardboard-box kitten, laser-chaser)
            // each run their own self-contained script and ignore
            // scare/knock-down (and several are airborne), so a hunting
            // resident would just stand next to / under them pointlessly.
            // Leave them be.
            if (other.isCinematicEvent()) {
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
    public enum Reaction { NONE, DUCK, FLEE, HUNT }

    /**
     * Immutable snapshot of a pending pet-pet reaction. The three fields
     * always move together — published atomically via {@link #reactionRef}
     * so a consumer cannot observe a new {@link Reaction} kind alongside a
     * stale {@code sourceX}/{@code untilMs} from a previous request.
     */
    private record ReactionState(Reaction kind, long untilMs, int sourceX) {
        static final ReactionState NONE = new ReactionState(Reaction.NONE, 0L, 0);
    }

    private final AtomicReference<ReactionState> reactionRef =
            new AtomicReference<>(ReactionState.NONE);

    /**
     * Soft "abort the current activity" flag. Unlike a thread interrupt (which
     * the {@link BehaviorEngine} loop guard treats as "terminate the pet"),
     * this only asks the in-progress activity to bail out early: {@link
     * #interrupted()} returns true while it is set, so the activity's
     * per-step/per-frame {@code if (interrupted()) return;} guards unwind it,
     * and control returns to the top of the engine loop where a pending
     * {@link Reaction} (e.g. FLEE) is consumed on the very next tick. The
     * engine clears it once per tick (before reaction handling) via
     * {@link #clearActivityAbort()} so it never aborts the reaction it was
     * meant to trigger. Used by the rain-cloud cinematic to make a rained-on
     * pet drop what it is doing and flee immediately.
     */
    private final AtomicBoolean activityAbort = new AtomicBoolean(false);

    /** Ask this pet to bail out of whatever activity it is currently running
     *  (best-effort, observed at the next {@link #interrupted()} check). Safe
     *  to call from another pet's thread. */
    public final void requestActivityAbort() {
        activityAbort.set(true);
    }

    /** Clear the {@link #activityAbort} flag. Called by the engine once per
     *  tick before it consumes reactions / picks an activity, so the flag only
     *  ever cancels the activity that was already in flight when it was set. */
    public final void clearActivityAbort() {
        activityAbort.set(false);
    }

    /** Convenience accessor: current reaction kind. */
    public final Reaction reaction() {
        return reactionRef.get().kind();
    }

    /** Convenience accessor: x-coordinate of the requester (last set). */
    public final int reactionSourceX() {
        return reactionRef.get().sourceX();
    }

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
        long until = System.currentTimeMillis() + Math.max(0L, durationMs);
        reactionRef.set(new ReactionState(r, until, sourceX));
        if (r == Reaction.DUCK) {
            applySprite(doodleKind() + "/sit");
        }
    }

    /** True if {@link #reaction} is non-NONE and hasn't expired yet. */
    public final boolean hasActiveReaction() {
        ReactionState s = reactionRef.get();
        return s.kind() != Reaction.NONE
                && System.currentTimeMillis() < s.untilMs();
    }

    /**
     * Clear the current reaction, but only if it still matches the snapshot
     * the caller was acting on. Prevents a consumer from wiping a newer
     * reaction another pet just requested mid-handling.
     */
    private void clearReactionIfStill(ReactionState observed) {
        reactionRef.compareAndSet(observed, ReactionState.NONE);
    }

    /**
     * Consume a DUCK reaction: hold the sit sprite for the remainder of the
     * window so the pet stays crouched while the jumper passes over. If
     * another pet extends the reaction mid-sleep we loop and keep holding.
     */
    public final void holdDuck() {
        applySprite(doodleKind() + "/sit");
        ReactionState s = reactionRef.get();
        while (s.kind() == Reaction.DUCK) {
            long remaining = s.untilMs() - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            sleepInterruptible(remaining);
            if (interrupted()) {
                return;
            }
            s = reactionRef.get();
        }
        clearReactionIfStill(s);
    }

    /**
     * Consume a FLEE reaction: sprint roughly a third of the current
     * monitor's width in the direction <i>away</i> from
     * {@link #reactionSourceX}, clamped to the monitor.
     */
    public final void fleeFrom(World world) {
        ReactionState s = reactionRef.get();
        Rectangle mon = currentMonitorBounds();
        int petW = effectiveWidth();
        Point loc = logicalLocation();
        int myMid = loc.x + petW / 2;
        int dir = (myMid < s.sourceX()) ? -1 : 1;
        int fleeDist = Math.max(petW * 3, mon.width / 3);
        int targetX = loc.x + dir * fleeDist;
        targetX = Math.max(mon.x, Math.min(mon.x + mon.width - petW, targetX));
        showEmote("bang", 250);
        runAlongFloor(world, targetX);
        clearReactionIfStill(s);
    }

    /**
     * Consume a HUNT reaction: sprint toward the requester's last known
     * x (the inviting pet that just play-bowed at us). The chase reads as
     * "you wanted to play? I'll catch you" — the invited pet runs to the
     * inviter's column, lands a {@code paw} tap, and lets the inviter
     * carry on whatever it picks next. Distinct from {@link #fleeFrom}
     * (opposite direction) and from {@link Activities#HUNT_PET} (which is
     * a self-initiated chase, not a triggered reaction).
     */
    public final void huntFrom(World world) {
        ReactionState s = reactionRef.get();
        Rectangle mon = currentMonitorBounds();
        int petW = effectiveWidth();
        Point loc = logicalLocation();
        int myMid = loc.x + petW / 2;
        int targetMid = s.sourceX();
        // Stop one body-width short on the approach side so we don't
        // overlap the inviter when we arrive.
        int approachX = (myMid < targetMid)
                ? targetMid - petW - petW / 2
                : targetMid + petW / 2;
        approachX = Math.max(mon.x, Math.min(mon.x + mon.width - petW, approachX));
        showEmote("target", 350);
        runAlongFloor(world, approachX);
        if (!interrupted()) {
            showEmote("paw", 500);
        }
        clearReactionIfStill(s);
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
     * Ground species (Cat/Ducky/Dog) opt in per-instance via
     * {@link #markAsVisitor()} when {@link PetVisitor} spawns them as a
     * one-shot guest of the resident pet.
     */
    public boolean isVisitor() {
        return visitorOverride;
    }

    /**
     * Per-instance opt-in to the visitor behaviour loop. Set BEFORE
     * starting the pet's thread (typically from {@link PetVisitor#trySpawn}).
     * Has no effect on {@link Bird}, which is always a visitor.
     */
    public final void markAsVisitor() {
        this.visitorOverride = true;
    }

    /**
     * Does this species fly? Controls the visitor exit route: fliers swoop
     * diagonally up and off-screen ({@link Bird} overrides {@link #walkTo}
     * for diagonal flight); ground pets walk off the side at floor level.
     * Defaults to false; {@link Bird} overrides to true.
     */
    public boolean flies() {
        return false;
    }

    private volatile boolean visitorOverride = false;

    /**
     * Set by a hunting resident pet (via {@link Activities#HUNT_BIRD}) to
     * tell a visitor it should take off. Also seeds {@link #reactionSourceX}
     * so {@link #runVisitorLoop} flies off the side opposite the hunter.
     * Safe to call from another pet's behaviour thread.
     */
    /** Last source-x supplied to {@link #scare} or {@link #knockDown}. */
    private volatile int visitorSourceX = 0;

    /** Return the source-x set by {@link #scare}/{@link #knockDown}. */
    public final int visitorSourceX() {
        return visitorSourceX;
    }

    public final void scare(int sourceX) {
        this.visitorSourceX = sourceX;
        this.scared = true;
    }

    /**
     * Knock a visitor pet out of its perch — currently triggered by a
     * moving {@link Ball} that collides with a perched bird. Sets the
     * {@link #knockedDown} flag so the visitor loop breaks out and routes
     * through {@link #fallOutAndExit} (a vertical gravity-style drop off
     * the bottom of the monitor) instead of the normal
     * {@link #flyAwayAndExit} swoop. Safe to call from another thread
     * (e.g. the Ball physics thread).
     */
    public final void knockDown(int sourceX) {
        this.visitorSourceX = sourceX;
        this.knockedDown = true;
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

    /**
     * Set by {@link UfoVisitor} before the thread starts to run the special
     * "UFO visit" cinematic instead of the normal {@link #runVisitorLoop}:
     * a flying saucer descends, the pet is beamed down, wanders a few
     * body-widths away, dances, returns under the saucer, and is beamed
     * back up before the saucer flies off. The pet must also be a visitor
     * ({@link #markAsVisitor()}); a green {@link #hueShift} makes it read as
     * a little alien. See {@link #runUfoEventLoop()}.
     */
    public volatile boolean ufoEvent = false;

    /**
     * Set by {@link AirplaneVisitor} before the thread starts to run the
     * special "airplane fly-by" cinematic instead of the normal
     * {@link #runVisitorLoop}: a little propeller plane carrying this pet in
     * its open cockpit cruises across the screen from one edge to the other
     * and leaves. The pet must also be a visitor ({@link #markAsVisitor()}).
     * Mutually exclusive with {@link #ufoEvent}. See
     * {@link #runAirplaneEventLoop()}.
     */
    public volatile boolean airplaneEvent = false;

    /**
     * Set by {@link MouseVisitor} before the thread starts to run the rare
     * "mouse scurry" cinematic ({@link #runMouseEventLoop()}): a little mouse
     * skitters along the floor past a resident and off the far edge. The pet
     * itself stays hidden — it only drives the prop. Mutually exclusive with
     * the other event flags.
     */
    public volatile boolean mouseEvent = false;

    /**
     * Set by {@link RainCloudVisitor} before the thread starts to run the rare
     * "rain cloud" cinematic ({@link #runRainCloudEventLoop()}): a grumpy
     * little cloud drifts over a resident, sprinkling, then floats off. The
     * pet itself stays hidden. Mutually exclusive with the other event flags.
     */
    public volatile boolean rainCloudEvent = false;

    /**
     * Set by {@link DroneVisitor} before the thread starts to run the rare
     * "delivery drone" cinematic ({@link #runDroneEventLoop()}): a quadcopter
     * flies in, lowers a wrapped gift to the floor near a resident, and buzzes
     * off. The pet itself stays hidden. Mutually exclusive with the other
     * event flags.
     */
    public volatile boolean droneEvent = false;

    /**
     * Set by {@link CardboardBoxVisitor} before the thread starts to run the
     * rare "cardboard box" cinematic ({@link #runBoxEventLoop()}): a box drops
     * near a resident and this visiting kitten pops its head out before ducking
     * back in and being whisked away. The pet IS shown (behind the box prop).
     * Mutually exclusive with the other event flags.
     */
    public volatile boolean boxEvent = false;

    /**
     * Set by {@link LaserPointerVisitor} before the thread starts to run the
     * rare "laser pointer" cinematic ({@link #runLaserEventLoop()}): a darting
     * red dot leads this visiting cat on a frantic chase before blinking out.
     * The pet IS shown. Mutually exclusive with the other event flags.
     */
    public volatile boolean laserEvent = false;

    /**
     * Set by {@link LaserPointerVisitor} when a resident cat is already present
     * on the target monitor: the laser carrier then stays HIDDEN and only
     * drives the dot, letting that resident cat give chase (so no duplicate cat
     * appears on screen). When false (no resident cat available), the carrier
     * shows itself, does the chasing, and trots off-screen at the end. Only
     * meaningful together with {@link #laserEvent}.
     */
    public volatile boolean laserResidentChaser = false;

    /**
     * True while this visitor is running one of the self-contained cinematic
     * special events. Such visitors ignore scare/knock-down and drive their
     * own scripts, so residents must not try to hunt or chase them (see
     * {@link #nearestVisitor(int)}).
     */
    final boolean isCinematicEvent() {
        return ufoEvent || airplaneEvent || mouseEvent || rainCloudEvent
                || droneEvent || boxEvent || laserEvent;
    }

    /** Set by {@link #scare(int)} from a hunting pet's thread; read by the
     *  visitor loop to break out of the idle hold and fly off early. */
    public volatile boolean scared = false;

    /** Set by {@link #knockDown(int)} (e.g. from {@link Ball}'s physics
     *  thread when a rolling ball hits a perched bird); read by the
     *  visitor loop to break out of the idle hold and route through
     *  {@link #fallOutAndExit} instead of the normal fly-away. */
    public volatile boolean knockedDown = false;

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
                && !knockedDown
                && System.currentTimeMillis() < stayUntil) {
            reassertTopmost();
            int r = ThreadLocalRandom.current().nextInt(3);
            if (r == 0) {
                sit();
            } else if (r == 1) {
                idle();
            } else {
                applySprite(doodleKind() + "/look/0");
                sleepInterruptible(700L);
                applySprite(doodleKind() + "/look/1");
                sleepInterruptible(700L);
            }
            if (System.currentTimeMillis() >= nextChirpAt) {
                speak(randomSound(), 1100L, Integer.MIN_VALUE);
                nextChirpAt = System.currentTimeMillis() + 4_000L
                        + ThreadLocalRandom.current().nextLong(0, 5_000L);
            }
        }
        if (!Thread.currentThread().isInterrupted()) {
            if (knockedDown) {
                fallOutAndExit();
            } else {
                flyAwayAndExit(world);
            }
        }
        disposeWindow();
        Log.info("pet:" + name, "visitor departed");
    }

    // ---------------- UFO visit (rare green-alien cinematic) ----------------

    /**
     * The rare "UFO visit" event scheduled by {@link UfoVisitor}. Instead of
     * the plain perch loop, this (visitor) pet stars in a self-contained
     * cinematic:
     * <ol>
     *   <li>a flying saucer descends from above the monitor and hovers over
     *       the landing column;</li>
     *   <li>a tractor beam switches on and the pet is beamed down to the
     *       floor (the pet stays hidden until this moment, so it really does
     *       "arrive" with the saucer);</li>
     *   <li>the pet wanders a few body-widths away from under the saucer;</li>
     *   <li>does a {@link #dance()};</li>
     *   <li>walks back under the saucer;</li>
     *   <li>is beamed back up, and the saucer flies off the top of the
     *       monitor.</li>
     * </ol>
     * Everything runs on this pet's own behaviour thread, which also owns
     * and animates the saucer's {@link PetWindow} directly, so the whole
     * sequence is a single linear script with no cross-thread choreography.
     * The saucer window is disposed alongside the pet window in the finally
     * block, even when the thread is interrupted mid-script (app shutdown).
     * A green {@link #hueShift} (set by {@link UfoVisitor}) makes the pet
     * read as a little alien.
     */
    private void runUfoEventLoop() {
        Log.info("pet:" + name, "ufo event begin");
        World world = World.snapshot(
                (int) screen.getWidth(), (int) screen.getHeight());
        reassertTopmost();
        // The pet is beamed down, not walked in: drop any entry walk that
        // initOnEdt queued.
        pendingEntryTargetX = null;
        pendingEntryTargetY = null;

        Rectangle mon = currentMonitorBounds();
        int petW = effectiveWidth();
        int landX = plannedSpawnTargetX != null ? plannedSpawnTargetX : logicalLocation().x;
        landX = Math.max(mon.x + 4, Math.min(mon.x + mon.width - petW - 4, landX));
        int petFloorTopY = floorYAt(world, landX);
        int feetH = Math.max(1, (int) Math.round(effectiveHeight() * feetYRatio()));
        int groundY = petFloorTopY + feetH;

        // Saucer geometry: a square window ~1.9x the pet, centred on the
        // landing column, whose bottom edge sits on the floor so the beam
        // (lower half of ufo-beam.svg) reaches the ground.
        int ufoSize = Math.max(48, (int) Math.round(petSize * 1.9));
        int ufoX = Math.max(mon.x,
                Math.min(mon.x + mon.width - ufoSize, landX + petW / 2 - ufoSize / 2));
        int ufoHoverY = groundY - ufoSize;
        int ufoStartY = mon.y - ufoSize - 8; // fully above the monitor (clipped)
        // Y the pet rises to / drops from while in the beam: just under the
        // saucer disc (~0.58 of the way down the square sprite).
        int beamTopY = ufoHoverY + (int) Math.round(ufoSize * 0.58);

        // Hide the pet at the landing column until it is beamed down.
        setPetWindowVisible(false);
        moveFrameTo(landX, petFloorTopY);
        applySprite(idleFrames().get(0));

        // Build + show the saucer window above the monitor.
        PetWindow ufo = new PetWindow();
        JLabel ufoLabel = new JLabel();
        ufo.add(ufoLabel);
        final int fUfoSize = ufoSize;
        onEdt(() -> ufoLabel.setBounds(0, 0, fUfoSize, fUfoSize));
        ufo.showOnMonitor(mon, ufoX, ufoStartY, ufoSize, ufoSize);
        setUfoSprite(ufoLabel, ufoSize, false);

        try {
            // 1) Descend to the hover height.
            animateUfoWindow(ufo, ufoX, ufoStartY, ufoX, ufoHoverY, 26, 18L);
            if (interrupted()) {
                return;
            }
            sleepInterruptible(250L);

            // 2) Beam down: beam on, slide the pet from under the disc down
            //    to the floor, sparkle, beam off.
            setUfoSprite(ufoLabel, ufoSize, true);
            setPetWindowVisible(true);
            ufoBeamPet(beamTopY, petFloorTopY, landX, true);
            showEmote("sparkle", 350L);
            setUfoSprite(ufoLabel, ufoSize, false);
            if (interrupted()) {
                return;
            }
            idle();

            // 3) Wander a few body-widths toward the roomier side.
            int dir = landX < mon.x + mon.width / 2 ? 1 : -1;
            int gap = (int) Math.round(petSize * (1.5 + ThreadLocalRandom.current().nextDouble()));
            int awayX = Math.max(mon.x + 4,
                    Math.min(mon.x + mon.width - petW - 4, landX + dir * gap));
            walkAlongFloor(world, awayX);
            if (interrupted()) {
                return;
            }
            sleepInterruptible(200L);

            // 4) Dance.
            dance();
            if (interrupted()) {
                return;
            }
            sleepInterruptible(200L);

            // 5) Return under the saucer.
            walkAlongFloor(world, landX);
            if (interrupted()) {
                return;
            }
            moveFrameTo(landX, petFloorTopY);
            idle();

            // 6) Beam up: beam on, sparkle, lift the pet into the saucer, hide.
            setUfoSprite(ufoLabel, ufoSize, true);
            showEmote("sparkle", 300L);
            ufoBeamPet(beamTopY, petFloorTopY, landX, false);
            setPetWindowVisible(false);
            if (interrupted()) {
                return;
            }

            // 7) The saucer flies away off the top of the monitor.
            sleepInterruptible(150L);
            setUfoSprite(ufoLabel, ufoSize, false);
            animateUfoWindow(ufo, ufoX, ufoHoverY, ufoX, ufoStartY, 30, 14L);
        } finally {
            ufo.dispose();
            disposeWindow();
            Log.info("pet:" + name, "ufo event end");
        }
    }

    /**
     * Slide the pet vertically along the tractor beam between the saucer
     * underside ({@code saucerTopY}) and its floor position
     * ({@code floorTopY}) at column {@code x}. {@code descend} true beams
     * the pet down (saucer → floor); false beams it up (floor → saucer).
     */
    private void ufoBeamPet(int saucerTopY, int floorTopY, int x, boolean descend) {
        int y0 = descend ? saucerTopY : floorTopY;
        int y1 = descend ? floorTopY : saucerTopY;
        applySprite(doodleKind() + "/sit");
        int steps = 16;
        for (int i = 0; i <= steps && !interrupted(); i++) {
            double t = i / (double) steps;
            moveFrameTo(x, (int) Math.round(y0 + (y1 - y0) * t));
            sleepInterruptible(28L);
        }
        moveFrameTo(x, y1);
        if (descend) {
            applySprite(idleFrames().get(0));
        }
    }

    /**
     * Move the saucer's {@link PetWindow} from {@code (x0,y0)} to
     * {@code (x1,y1)} over {@code steps} eased steps. Runs on the pet's
     * behaviour thread; {@link PetWindow#setLocation} marshals each move to
     * the EDT.
     */
    private void animateUfoWindow(PetWindow ufo, int x0, int y0, int x1, int y1,
            int steps, long stepDelayMs) {
        for (int i = 1; i <= steps && !interrupted(); i++) {
            double e = ufoSmoothstep(i / (double) steps);
            ufo.setLocation(
                    (int) Math.round(x0 + (x1 - x0) * e),
                    (int) Math.round(y0 + (y1 - y0) * e));
            sleepInterruptible(stepDelayMs);
        }
        ufo.setLocation(x1, y1);
    }

    /** Set the saucer label's icon (no hue — the saucer keeps its own
     *  colours; only the alien pet is tinted green). */
    private static void setUfoSprite(JLabel label, int size, boolean beam) {
        ImageIcon icon = Doodle.icon(beam ? "prop/ufo-beam" : "prop/ufo", size);
        onEdt(() -> label.setIcon(icon));
    }

    /** Show/hide this pet's window on the EDT — used to keep the pet hidden
     *  until it is beamed down, and again once it is beamed back up. */
    private void setPetWindowVisible(boolean visible) {
        if (frame == null) {
            return;
        }
        onEdt(() -> {
            if (frame != null) {
                frame.setVisible(visible);
            }
        });
    }

    /** Smoothstep ease (3t² − 2t³) for the saucer glide in/out. */
    private static double ufoSmoothstep(double t) {
        double c = Math.max(0.0, Math.min(1.0, t));
        return c * c * (3.0 - 2.0 * c);
    }

    /**
     * Special "airplane fly-by" cinematic (see {@link #airplaneEvent}). A
     * little propeller plane — with this Ducky passenger baked right into its
     * cockpit art ({@code Sprites/Props/airplane.svg}) — cruises across the
     * monitor from one edge to the other and leaves. The whole sequence runs on
     * this pet's own behaviour thread, a single linear script with no
     * cross-thread choreography.
     *
     * <p>Unlike the resident pets (and the UFO saucer), the plane does NOT ride
     * on the shared {@link Stage} canvas: that canvas sits at the shell's
     * z-order and punches transparent holes wherever an ordinary window is
     * drawn in front of it, which would make a plane cruising across the upper
     * screen vanish behind your windows. Instead the plane flies in its own
     * dedicated always-on-top {@link AirplaneWindow}, so it passes IN FRONT of
     * ordinary windows like the spectacle it is. The duck is part of the plane
     * sprite, so this pet's own window stays hidden the whole time — the
     * visitor exists only to drive the cinematic and gate it through the normal
     * visitor lifecycle. Both windows are disposed in the finally block, even
     * on interrupt (app shutdown).
     *
     * <p>The plane prop is drawn nose-right; for leftward flight the rasterised
     * icon is mirrored horizontally ({@link AirplaneWindow#create}).
     */
    private void runAirplaneEventLoop() {
        Log.info("pet:" + name, "airplane event begin");
        World world = World.snapshot(
                (int) screen.getWidth(), (int) screen.getHeight());
        reassertTopmost();
        // The duck is part of the plane art and is never shown as its own pet,
        // so drop any entry walk initOnEdt queued and keep this window hidden.
        pendingEntryTargetX = null;
        pendingEntryTargetY = null;
        setPetWindowVisible(false);

        // A small, distant fly-by: a quarter of the former size.
        final double PLANE_SCALE = 0.6;

        Rectangle mon = plannedSpawnMonitor != null
                ? plannedSpawnMonitor : currentMonitorBounds();
        // plannedSpawnFromRight true => plane enters from the right, flies left.
        boolean flyLeft = plannedSpawnFromRight != null && plannedSpawnFromRight;
        int dir = flyLeft ? -1 : 1;

        int planeSize = Math.max(24, (int) Math.round(petSize * PLANE_SCALE));

        // Cruise altitude: a random band across the upper part of the monitor,
        // clamped so the whole plane stays above the floor.
        int floorTopY = floorYAt(world, mon.x + mon.width / 2);
        int topLimit = mon.y + (int) Math.round(mon.height * 0.10);
        int bandSpan = Math.max(1, (int) Math.round(mon.height * 0.28));
        int planeY = topLimit + ThreadLocalRandom.current().nextInt(bandSpan);
        planeY = Math.min(planeY, Math.max(mon.y, floorTopY - planeSize));

        int enterX = flyLeft ? (mon.x + mon.width) : (mon.x - planeSize);
        int exitX = flyLeft ? (mon.x - planeSize) : (mon.x + mon.width);

        // Build + show the dedicated always-on-top plane window off-screen at
        // the entry edge.
        AirplaneWindow plane = AirplaneWindow.create(planeSize, dir, enterX, planeY);

        try {
            int stepPx = Math.max(2, Dpi.scale(7));
            int distance = Math.abs(exitX - enterX);
            int steps = Math.max(80, distance / stepPx);
            double bobAmp = Math.max(2.0, planeSize * 0.04);
            for (int i = 0; i <= steps && !interrupted(); i++) {
                double t = i / (double) steps;
                int px = (int) Math.round(enterX + (exitX - enterX) * t);
                int py = planeY + (int) Math.round(Math.sin(t * Math.PI * 5.0) * bobAmp);
                plane.setLocation(px, py);
                sleepInterruptible(16L);
            }
        } finally {
            plane.dispose();
            disposeWindow();
            Log.info("pet:" + name, "airplane event end");
        }
    }

    // ===================================================================
    // Additional cinematic special events (mouse scurry, rain cloud,
    // delivery drone, cardboard box, laser pointer). Each is routed from
    // run() by its own volatile flag (set by the matching *Visitor before the
    // thread starts) and animates one or two always-on-top OverlayWindow props
    // IN FRONT of ordinary windows, mirroring the UFO / airplane pattern.
    // ===================================================================

    /** Window-top Y at which a prop sprite of {@code size} px — whose feet sit
     *  {@code footRatio} of the way down its square sprite box — rests on the
     *  floor surface at screen column {@code centerX}. */
    private int floorTopForProp(World world, int centerX, int size, double footRatio) {
        int feetH = Math.max(1, (int) Math.round(effectiveHeight() * feetYRatio()));
        int floorSurfaceY = floorYAt(world, centerX) + feetH;
        return floorSurfaceY - (int) Math.round(size * footRatio);
    }

    /** A cinematic prop that can be moved around the screen — either an
     *  always-on-top {@link OverlayWindow} (sky props: airplane, rain cloud,
     *  delivery drone) or a stage-hosted {@link StageProp} (floor props: mouse
     *  scurry, cardboard box, laser pointer). */
    private interface MovableProp {
        void setLocation(int x, int y);
    }

    /** Glide a {@link MovableProp} from {@code (x0,y0)} to {@code (x1,y1)}
     *  over {@code steps} eased steps (smoothstep), sleeping {@code delay} ms
     *  between each. Stops early on interrupt (app shutdown). */
    private void glideOverlay(MovableProp w, int x0, int y0, int x1, int y1,
            int steps, long delay) {
        for (int i = 1; i <= steps && !interrupted(); i++) {
            double e = ufoSmoothstep(i / (double) steps);
            w.setLocation((int) Math.round(x0 + (x1 - x0) * e),
                          (int) Math.round(y0 + (y1 - y0) * e));
            sleepInterruptible(delay);
        }
        w.setLocation(x1, y1);
    }

    /**
     * Position the pet's panel at screen {@code (x, topY)} but CLIP its bottom
     * at screen {@code rimY}: only the portion of the sprite ABOVE the rim is
     * drawn. The pet's {@link PetWindow} is a {@code JPanel} that clips its
     * child label to its bounds, so shrinking the panel's height cuts the
     * sprite off at the bottom. Used by {@link #runBoxEventLoop()} to make the
     * kitten rise up out of the box's opening without its lower body showing
     * through the box sprite's transparent flaps/gap. Restore normal rendering
     * with a full-height {@link PetWindow#setBounds} once clipping is no longer
     * needed.
     */
    private void setFrameClipped(int x, int topY, int rimY) {
        if (frame == null) {
            return;
        }
        int h = clampInt(rimY - topY, 0, petSize);
        frame.setBounds(x, topY, petSize, h);
        this.intendedX = x;
        this.intendedY = topY;
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Special "mouse scurry" cinematic (see {@link #mouseEvent}). A little
     * mouse skitters along the floor from one edge of the monitor to the other
     * in quick, erratic darts (with the occasional freeze-and-sniff). The
     * carrier pet stays hidden — it exists only to drive the prop (a
     * {@link StageProp} sharing the stage canvas) through the normal visitor
     * lifecycle.
     *
     * <p>Unlike a passive prop, this mouse publishes its live screen position
     * as a {@link MouseQuarry} so resident ground pets (cats especially) can
     * sense and <em>hunt</em> it: see {@link #canHuntMouse}/{@link #huntMouse}.
     * When a predator closes in, the mouse {@link MouseQuarry#startled()
     * panics} and bolts the other way, turning the stroll into a real chase. A
     * pounce has a small chance to "catch" the mouse, ending the cinematic
     * early; otherwise a hard ~32&nbsp;s deadline always sends it bolting for
     * the nearest edge so the event terminates.
     */
    private void runMouseEventLoop() {
        Log.info("pet:" + name, "mouse event begin");
        World world = World.snapshot((int) screen.getWidth(), (int) screen.getHeight());
        reassertTopmost();
        pendingEntryTargetX = null;
        pendingEntryTargetY = null;
        setPetWindowVisible(false);

        Rectangle mon = plannedSpawnMonitor != null ? plannedSpawnMonitor : currentMonitorBounds();
        // Half the previous scale (was petSize*0.45) so the mouse reads as a
        // tiny critter underfoot rather than rivalling the resident pets' size.
        int mouseSize = Math.max(10, (int) Math.round(petSize * 0.225));
        boolean fromLeft = ThreadLocalRandom.current().nextBoolean();
        int dir = fromLeft ? 1 : -1;
        int enterX = fromLeft ? (mon.x - mouseSize) : (mon.x + mon.width);
        // Mouse feet sit ~0.83 of the way down its (low, long) sprite box.
        final double FOOT = 0.83;
        int slop = mouseSize + Dpi.scale(8);

        int x = enterX;
        int topAt = floorTopForProp(world, x + mouseSize / 2, mouseSize, FOOT);
        int feetY0 = topAt + (int) Math.round(mouseSize * FOOT);
        StageProp mouse = StageProp.create("mouse", mouseSize, mon, x, topAt, dir < 0);
        MouseQuarry quarry = MouseQuarry.publish(mon, mouseSize, x + mouseSize / 2, feetY0);
        long deadline = System.currentTimeMillis() + 32_000L;
        boolean facingLeft = dir < 0;
        try {
            int slowStep = Math.max(2, Dpi.scale(3));
            int fastStep = Math.max(3, Dpi.scale(5));
            while (!interrupted() && !quarry.caught()) {
                // Leave once fully off either edge of the monitor.
                if (x < mon.x - slop || x > mon.x + mon.width + slop) {
                    break;
                }
                boolean timeUp = System.currentTimeMillis() > deadline;
                boolean panic = !timeUp && quarry.startled();
                if (panic) {
                    // Bolt away from the side the predator is on.
                    dir = (x + mouseSize / 2) <= quarry.threatX() ? -1 : 1;
                } else if (timeUp) {
                    // Head for the nearer edge so the cinematic always ends.
                    int mid = x + mouseSize / 2;
                    dir = (mid - mon.x) < ((mon.x + mon.width) - mid) ? -1 : 1;
                }
                boolean wantLeft = dir < 0;
                if (wantLeft != facingLeft) {
                    facingLeft = wantLeft;
                    mouse.setProp("mouse", mouseSize, facingLeft);
                }
                int stepPx = panic ? fastStep : slowStep;
                long stepDelay = panic ? 8L : 14L;
                int burst = panic ? (70 + ThreadLocalRandom.current().nextInt(120))
                                  : (40 + ThreadLocalRandom.current().nextInt(90));
                for (int moved = 0; moved < burst && !interrupted() && !quarry.caught(); moved += stepPx) {
                    x += dir * stepPx;
                    int jitter = ThreadLocalRandom.current().nextInt(3) - 1;
                    int top = floorTopForProp(world, x + mouseSize / 2, mouseSize, FOOT);
                    mouse.setLocation(x, top + jitter);
                    quarry.update(x + mouseSize / 2, top + (int) Math.round(mouseSize * FOOT));
                    sleepInterruptible(stepDelay);
                    if (x < mon.x - slop || x > mon.x + mon.width + slop) {
                        break;
                    }
                }
                // Brief freeze (sniff) between darts when calm.
                if (!panic && !timeUp && !interrupted()
                        && ThreadLocalRandom.current().nextInt(100) < 50) {
                    sleepInterruptible(120L + ThreadLocalRandom.current().nextInt(220));
                }
            }
            // If a pet caught it, hold a beat before it disappears.
            if (quarry.caught() && !interrupted()) {
                sleepInterruptible(140L);
            }
        } finally {
            quarry.clear();
            mouse.dispose();
            disposeWindow();
            Log.info("pet:" + name, "mouse event end");
        }
    }

    /**
     * Special "rain cloud" cinematic (see {@link #rainCloudEvent}). A grumpy
     * little cloud drifts in just above the pets' heads (not high up), hovers
     * over a randomly-chosen resident, and then "rains" on it: a SEPARATE
     * animated rain sprite (rain1..rain4, cycled below the cloud) appears while
     * the targeted pet bolts to the side and runs away a bit (a
     * {@link Reaction#FLEE} request). The cloud then stops raining and repeats
     * on another random pet a random number of times (max 5) before drifting
     * off the nearest edge. The carrier pet stays hidden; the cloud and the
     * rain each ride their own always-on-top {@link OverlayWindow}.
     */
    private void runRainCloudEventLoop() {
        Log.info("pet:" + name, "rain cloud event begin");
        World world = World.snapshot((int) screen.getWidth(), (int) screen.getHeight());
        reassertTopmost();
        pendingEntryTargetX = null;
        pendingEntryTargetY = null;
        setPetWindowVisible(false);

        Rectangle mon = plannedSpawnMonitor != null ? plannedSpawnMonitor : currentMonitorBounds();
        int cloudSize = Math.max(40, (int) Math.round(petSize * 1.3));
        int rainSize = Math.max(24, (int) Math.round(cloudSize * 0.9));

        // Enter from the nearer side AT HEAD HEIGHT — spawn off the edge level
        // with the first target's head rather than high up and then diving
        // down. If no pet is eligible right now, fall back to a low band near
        // the floor (still not "way up high").
        boolean fromLeft = ThreadLocalRandom.current().nextBoolean();
        int cloudX = fromLeft ? (mon.x - cloudSize) : (mon.x + mon.width);
        Pet firstTarget = pickRainTarget(mon, null);
        int cloudY = (firstTarget != null)
                ? cloudHoverYFor(firstTarget, mon, cloudSize)
                : mon.y + (int) Math.round(mon.height * 0.55);

        OverlayWindow cloud = OverlayWindow.create("cloud", cloudSize, cloudX, cloudY, false);
        OverlayWindow rain = null;
        try {
            // At least 2 attempts to drench a pet (max 5).
            int rounds = 2 + ThreadLocalRandom.current().nextInt(4); // 2..5
            Pet lastTarget = null;
            boolean happyFace = false; // flips to the happy cloud sprite once a pet gets wet
            for (int r = 0; r < rounds && !interrupted(); r++) {
                // Reuse the pet we already sized our entry to for the first
                // round so the cloud arrives level with it; re-pick afterwards.
                Pet target = (r == 0 && firstTarget != null
                        && firstTarget.frame != null && !firstTarget.isHidden())
                        ? firstTarget
                        : pickRainTarget(mon, lastTarget);
                if (target == null) {
                    break;
                }
                lastTarget = target;

                // Approach the target while RE-READING its position every step,
                // so a pet that's walking/running doesn't leave the cloud
                // gliding toward a stale spot. We ease toward the pet's CURRENT
                // head position and stop once we're hovering over it, with a
                // travel cap so the cloud never chases a moving pet forever.
                int maxApproach = 110; // ~110 * 18ms ≈ 2s travel cap
                for (int a = 0; a < maxApproach && !interrupted(); a++) {
                    if (target.frame == null || target.isHidden()) {
                        break;
                    }
                    int tMid = target.logicalLocation().x + target.effectiveWidth() / 2;
                    int destX = clampInt(tMid - cloudSize / 2,
                            mon.x, mon.x + mon.width - cloudSize);
                    int destY = cloudHoverYFor(target, mon, cloudSize);
                    cloudX += (int) Math.round((destX - cloudX) * 0.18);
                    cloudY += (int) Math.round((destY - cloudY) * 0.18);
                    cloud.setLocation(cloudX, cloudY);
                    // Arrived (hovering over the pet's head)?
                    if (Math.abs((cloudX + cloudSize / 2) - tMid) <= Math.max(4, cloudSize / 12)
                            && Math.abs(cloudY - destY) <= Math.max(3, cloudSize / 12)) {
                        cloudX = destX;
                        cloudY = destY;
                        cloud.setLocation(cloudX, cloudY);
                        break;
                    }
                    sleepInterruptible(18L);
                }
                if (interrupted()) {
                    break;
                }
                if (target.frame == null || target.isHidden()) {
                    continue; // pet vanished mid-approach — pick another
                }
                // Settle in place ("try to hover") with a small bob.
                int bobAmp = Math.max(2, (int) Math.round(cloudSize * 0.05));
                for (int b = 0; b < 8 && !interrupted(); b++) {
                    cloud.setLocation(cloudX, cloudY
                            + (int) Math.round(Math.sin(b / 8.0 * Math.PI * 2) * bobAmp));
                    sleepInterruptible(45L);
                }
                cloud.setLocation(cloudX, cloudY);
                if (interrupted()) {
                    break;
                }

                // Start raining: show the animated rain just under the cloud.
                int rainX = clampInt(cloudX + cloudSize / 2 - rainSize / 2,
                        mon.x, mon.x + mon.width - rainSize);
                int rainY = cloudY + (int) Math.round(cloudSize * 0.52);
                if (rain == null) {
                    rain = OverlayWindow.create("rain1", rainSize, rainX, rainY, false);
                } else {
                    rain.setProp("rain1", rainSize, false);
                    rain.setLocation(rainX, rainY);
                    rain.setVisible(true);
                }

                // Keep raining on this fixed spot until the pet has fled out
                // from under the cloud (escaped its footprint) or is gone. The
                // cloud does NOT follow — the pet has to leave the rain band.
                // We (re-)issue the FLEE + activity-abort whenever the pet has
                // no flee pending (it just finished/cleared one, the reaction
                // expired, or it hasn't reacted yet), so a pet that's mid-sleep
                // or didn't get clear is nudged again rather than rained on
                // forever. A safety cap stops the downpour if it somehow can't
                // escape. The abort makes it drop its current activity so the
                // FLEE is consumed on its next tick instead of after whatever
                // it was doing finishes.
                int cloudSpanMid = cloudX + cloudSize / 2;
                int escapeRadius = cloudSize / 2 + Math.max(8, target.effectiveWidth() / 2);
                int rainLeft = rainX;
                int rainRight = rainX + rainSize;
                long rainDeadline = System.currentTimeMillis() + 6000L;
                int f = 0;
                int wetFrames = 0; // consecutive frames the rain truly lands on the pet
                while (!interrupted()) {
                    if (target.frame == null || target.isHidden()) {
                        break;  // already gone
                    }
                    Point petNow = target.logicalLocation();
                    int petWNow = target.effectiveWidth();
                    int petMidNow = petNow.x + petWNow / 2;
                    if (Math.abs(petMidNow - cloudSpanMid) > escapeRadius) {
                        break;  // escaped its parameter
                    }
                    if (System.currentTimeMillis() > rainDeadline) {
                        break;  // safety: don't rain forever if it can't flee
                    }
                    if (!target.hasActiveReaction()) {
                        target.requestReaction(Reaction.FLEE, 2500L, cloudSpanMid);
                        target.requestActivityAbort();
                    }
                    rain.setProp("rain" + (f % 4 + 1), rainSize, false);
                    if (!happyFace) {
                        // Only count it as a real "hit" when the rain column
                        // actually overlaps a decent chunk of the pet's body —
                        // a pet that bolts before the rain lands leaves the
                        // cloud grumpy. Require a few consecutive wet frames so
                        // a glancing edge-of-band moment doesn't count.
                        int overlap = Math.min(rainRight, petNow.x + petWNow)
                                - Math.max(rainLeft, petNow.x);
                        int minOverlap = Math.max(6, Math.min(rainSize, petWNow) / 3);
                        if (overlap >= minOverlap) {
                            if (++wetFrames >= 3) {
                                happyFace = true;
                                cloud.setProp("cloud-happy", cloudSize, false);
                            }
                        } else {
                            wetFrames = 0; // pet stepped out of the rain — reset
                        }
                    }
                    cloud.setLocation(cloudX,
                            cloudY + (int) Math.round(Math.sin(f / 3.0) * 2));
                    sleepInterruptible(110L);
                    f++;
                }
                cloud.setLocation(cloudX, cloudY);

                // Stop raining, then move on to another random pet.
                if (rain != null) {
                    rain.setVisible(false);
                }
                if (interrupted()) {
                    break;
                }
                sleepInterruptible(220L);
            }
            // Drift off the nearest horizontal edge.
            if (!interrupted()) {
                int cloudMid = cloudX + cloudSize / 2;
                boolean exitLeft = (cloudMid - mon.x) <= (mon.x + mon.width - cloudMid);
                int exitX = exitLeft ? (mon.x - cloudSize) : (mon.x + mon.width);
                glideOverlay(cloud, cloudX, cloudY, exitX, cloudY, 56, 16L);
            }
        } finally {
            if (rain != null) {
                rain.dispose();
            }
            cloud.dispose();
            disposeWindow();
            Log.info("pet:" + name, "rain cloud event end");
        }
    }

    /**
     * Pick a random visible resident pet on {@code mon} for the rain cloud to
     * drench, preferring one other than {@code avoid} when more than one is
     * available (so consecutive rounds spread across the desktop rather than
     * picking on the same pet every time). Excludes the hidden carrier itself,
     * visitor/cinematic pets, hidden pets, and pets on other monitors. Returns
     * {@code null} when no eligible resident exists.
     */
    private Pet pickRainTarget(Rectangle mon, Pet avoid) {
        java.util.List<Pet> residents = new java.util.ArrayList<>();
        for (Pet p : ACTIVE_PETS) {
            if (p == this || p.frame == null) {
                continue;
            }
            if (p.isVisitor() || p.isCinematicEvent() || p.isHidden()) {
                continue;
            }
            Rectangle pm = p.currentMonitorBounds();
            if (pm.x != mon.x || pm.y != mon.y) {
                continue;
            }
            residents.add(p);
        }
        if (residents.isEmpty()) {
            return null;
        }
        if (residents.size() > 1 && avoid != null) {
            residents.remove(avoid);
        }
        return residents.get(ThreadLocalRandom.current().nextInt(residents.size()));
    }

    /**
     * The screen Y at which the rain cloud should hover so it sits just above
     * {@code target}'s head ("not that far up"): the cloud box bottom lands a
     * hair below the pet's head-top, so the visible puffs float a little above
     * the head and the rain falls onto it. Clamped to the monitor top.
     */
    private int cloudHoverYFor(Pet target, Rectangle mon, int cloudSize) {
        int petTopY = target.logicalLocation().y;
        return Math.max(mon.y + 2,
                petTopY - cloudSize + (int) Math.round(cloudSize * 0.06));
    }

    /**
     * Pick the visible resident pet nearest the gift column (mid-x {@code
     * giftMidX}) on {@code mon} for the delivery-drone present to be collected
     * by, so the dropped gift is actually picked up rather than silently
     * vanishing. Same eligibility filter as {@link #pickRainTarget}: excludes
     * the hidden carrier itself, visitor/cinematic pets, hidden pets, and pets
     * on other monitors. Returns {@code null} when no eligible resident exists.
     */
    private Pet pickGiftCollector(Rectangle mon, int giftMidX) {
        Pet best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Pet p : ACTIVE_PETS) {
            if (p == this || p.frame == null) {
                continue;
            }
            if (p.isVisitor() || p.isCinematicEvent() || p.isHidden()) {
                continue;
            }
            Rectangle pm = p.currentMonitorBounds();
            if (pm.x != mon.x || pm.y != mon.y) {
                continue;
            }
            int mid = p.logicalLocation().x + p.effectiveWidth() / 2;
            int d = Math.abs(mid - giftMidX);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /**
     * Special "delivery drone" cinematic (see {@link #droneEvent}). A little
     * quadcopter flies in low over the resident pet, hovers, lowers a wrapped
     * gift on its hook down to the floor, releases it, and buzzes off. The
     * nearest resident pet then trots over, paws the present curiously, and it
     * unwraps in a little pop and sparkle. The carrier pet stays hidden; the
     * drone and the gift each ride their own always-on-top {@link OverlayWindow}.
     */
    private void runDroneEventLoop() {
        Log.info("pet:" + name, "drone event begin");
        World world = World.snapshot((int) screen.getWidth(), (int) screen.getHeight());
        reassertTopmost();
        pendingEntryTargetX = null;
        pendingEntryTargetY = null;
        setPetWindowVisible(false);

        Rectangle mon = plannedSpawnMonitor != null ? plannedSpawnMonitor : currentMonitorBounds();
        int dropX = plannedSpawnTargetX != null ? plannedSpawnTargetX : (mon.x + mon.width / 2);
        int droneSize = Math.max(36, (int) Math.round(petSize * 0.95));
        int giftSize = Math.max(20, (int) Math.round(petSize * 0.5));

        boolean fromRight = plannedSpawnFromRight != null
                ? plannedSpawnFromRight : ThreadLocalRandom.current().nextBoolean();
        int dir = fromRight ? -1 : 1;
        int enterX = fromRight ? (mon.x + mon.width) : (mon.x - droneSize);
        int exitX = fromRight ? (mon.x - droneSize) : (mon.x + mon.width);
        int hoverX = clampInt(dropX - droneSize / 2, mon.x, mon.x + mon.width - droneSize);
        // Cruise LOW (was 0.12 — way up near the top) so the little quadcopter
        // buzzes in around head height rather than from the ceiling.
        int cruiseY = mon.y + (int) Math.round(mon.height * 0.30);

        int giftX = clampInt(dropX - giftSize / 2, mon.x + 2, mon.x + mon.width - giftSize - 2);
        int giftTopOnFloor = floorTopForProp(world, giftX + giftSize / 2, giftSize, 0.86);
        // The gift rides slung under the drone's belly while it flies in and
        // hovers, so the delivery reads as the drone CARRYING a package and
        // then lowering it — rather than a present popping out of nowhere.
        int bellyOffset = (int) Math.round(droneSize * 0.52);

        OverlayWindow drone = OverlayWindow.create("drone", droneSize, enterX, cruiseY, dir < 0);
        OverlayWindow gift = OverlayWindow.create("gift", giftSize,
                clampInt(enterX + droneSize / 2 - giftSize / 2,
                        mon.x - giftSize, mon.x + mon.width),
                cruiseY + bellyOffset, false);
        try {
            // 1) Fly in to the drop column with the gift slung underneath.
            int flySteps = 64;
            for (int i = 1; i <= flySteps && !interrupted(); i++) {
                double e = ufoSmoothstep(i / (double) flySteps);
                int dx = (int) Math.round(enterX + (hoverX - enterX) * e);
                drone.setLocation(dx, cruiseY);
                gift.setLocation(clampInt(dx + droneSize / 2 - giftSize / 2,
                        mon.x, mon.x + mon.width - giftSize), cruiseY + bellyOffset);
                sleepInterruptible(16L);
            }
            int giftHoverX = clampInt(hoverX + droneSize / 2 - giftSize / 2,
                    mon.x, mon.x + mon.width - giftSize);
            drone.setLocation(hoverX, cruiseY);
            gift.setLocation(giftHoverX, cruiseY + bellyOffset);
            if (interrupted()) {
                return;
            }

            // 2) Hover and steady in place WITH the package for a clear beat,
            //    bobbing gently, so the drop is obviously not instantaneous.
            int bobAmp = Math.max(2, (int) Math.round(droneSize * 0.06));
            for (int b = 0; b < 22 && !interrupted(); b++) {
                int dy = (int) Math.round(Math.sin(b / 22.0 * Math.PI * 2) * bobAmp);
                drone.setLocation(hoverX, cruiseY + dy);
                gift.setLocation(giftHoverX, cruiseY + bellyOffset + dy);
                sleepInterruptible(45L);
            }
            drone.setLocation(hoverX, cruiseY);
            gift.setLocation(giftHoverX, cruiseY + bellyOffset);
            if (interrupted()) {
                return;
            }

            // 3) Lower ONLY the gift down to the floor on the winch — slow and
            //    clearly animated — while the drone holds its hover.
            glideOverlay(gift, giftHoverX, cruiseY + bellyOffset, giftX, giftTopOnFloor, 44, 26L);
            if (interrupted()) {
                return;
            }
            sleepInterruptible(350L);

            // 4) Release and buzz off, leaving the present on the floor.
            glideOverlay(drone, hoverX, cruiseY, exitX, cruiseY, 64, 16L);
            drone.setVisible(false);
            if (interrupted()) {
                return;
            }
            sleepInterruptible(250L);

            // 5) A nearby pet notices the present, trots over, and OPENS it — so
            //    the gift is actually received rather than left to vanish. The
            //    closest resident gets a HUNT reaction toward the gift column (it
            //    walks over and paws it); on arrival the present pops open in a
            //    burst of sparkle while the pet flashes a happy heart.
            int giftMid = giftX + giftSize / 2;
            Pet collector = pickGiftCollector(mon, giftMid);
            if (collector != null) {
                collector.requestReaction(Reaction.HUNT, 9000L, giftMid);
                collector.requestActivityAbort();
                long arriveDeadline = System.currentTimeMillis() + 9000L;
                boolean arrived = false;
                while (!interrupted() && System.currentTimeMillis() < arriveDeadline) {
                    if (collector.frame == null || collector.isHidden()) {
                        break;
                    }
                    int cMid = collector.logicalLocation().x
                            + collector.effectiveWidth() / 2;
                    if (Math.abs(cMid - giftMid)
                            <= Math.max(collector.effectiveWidth() * 2, giftSize * 2)) {
                        arrived = true;
                        break;
                    }
                    sleepInterruptible(80L);
                }
                if (arrived && !interrupted()) {
                    // Let the pet's paw-tap land, then the present pops open: a
                    // few clear bounces, then it bursts into a sparkle while the
                    // collector flashes a happy heart — it got its gift.
                    sleepInterruptible(300L);
                    int pop = Math.max(4, giftSize / 4);
                    for (int b = 0; b < 3 && !interrupted(); b++) {
                        gift.setLocation(giftX, giftTopOnFloor - pop);
                        sleepInterruptible(95L);
                        gift.setLocation(giftX, giftTopOnFloor);
                        sleepInterruptible(95L);
                    }
                    if (!interrupted()) {
                        collector.setEmote("mini-heart");
                        gift.setProp("sparkle", giftSize, false);
                        sleepInterruptible(800L);
                        collector.hideEmote();
                    }
                } else {
                    // Couldn't reach it in time — let it rest a moment.
                    sleepInterruptible(600L);
                }
            } else {
                // No resident available to collect it — let it sit a beat.
                sleepInterruptible(1200L);
            }
        } finally {
            if (gift != null) {
                gift.dispose();
            }
            drone.dispose();
            disposeWindow();
            Log.info("pet:" + name, "drone event end");
        }
    }

    /**
     * Special "cardboard box" cinematic (see {@link #boxEvent}). A box drops
     * from the top of the monitor onto the floor near a resident, wobbles, then
     * opens — and this visiting kitten springs up OUT of the box (rising while
     * still BEHIND it so it reads as emerging from inside). Once it has cleared
     * the top it is brought to the FRONT of the canvas; the now-empty box then
     * drops straight down and falls off the bottom of the screen while the
     * kitten lands on the floor where the box stood, does a little dance, and
     * finally runs off the nearest edge of the monitor.
     */
    private void runBoxEventLoop() {
        Log.info("pet:" + name, "box event begin");
        World world = World.snapshot((int) screen.getWidth(), (int) screen.getHeight());
        reassertTopmost();
        pendingEntryTargetX = null;
        pendingEntryTargetY = null;
        setPetWindowVisible(false);

        Rectangle mon = currentMonitorBounds();
        int petW = effectiveWidth();
        int landX = plannedSpawnTargetX != null ? plannedSpawnTargetX : logicalLocation().x;
        landX = clampInt(landX, mon.x + 4, mon.x + mon.width - petW - 4);
        int petFloorTopY = floorYAt(world, landX);
        int feetH = Math.max(1, (int) Math.round(effectiveHeight() * feetYRatio()));
        int floorSurfaceY = petFloorTopY + feetH;

        int boxSize = Math.max(44, (int) Math.round(petSize * 1.35));
        int boxCenterX = landX + petW / 2;
        int boxX = clampInt(boxCenterX - boxSize / 2, mon.x, mon.x + mon.width - boxSize);
        // Box body bottom sits ~0.90 down its sprite; anchor that to the floor.
        int boxTopY = floorSurfaceY - (int) Math.round(boxSize * 0.90);
        int boxStartY = mon.y - boxSize - 8;

        int petX = boxCenterX - petW / 2;
        // The box's opening rim, in screen pixels. While the kitten rises out
        // of the box we CLIP its panel to this line (only the part ABOVE the
        // rim is drawn) so the lower body is genuinely hidden — the box sprite
        // has large transparent areas (splayed flaps, the centre gap), so
        // relying on z-order alone lets the body show through while it rises.
        int rimY = boxTopY + (int) Math.round(boxSize * 0.50);
        // Start fully tucked inside (clipped to nothing), just below the rim.
        int startTopY = rimY + (int) Math.round(boxSize * 0.10);
        // Apex of the pop-out: the WHOLE kitten is above the rim here (clip
        // reveals the full sprite), so dropping the clip + the behind→front
        // swap have nothing left to hide.
        int peakTopY = rimY - petSize - (int) Math.round(petSize * 0.05);

        setFrameClipped(petX, startTopY, rimY);
        applySprite(idleFrames().get(0));

        StageProp box = StageProp.create("box", boxSize, mon, boxX, boxStartY, false);
        try {
            // 1) Box descends, closed.
            glideOverlay(box, boxX, boxStartY, boxX, boxTopY, 30, 18L);
            if (interrupted()) {
                return;
            }
            // 2) Wobble on landing.
            for (int i = 0; i < 3 && !interrupted(); i++) {
                box.setLocation(boxX - 3, boxTopY);
                sleepInterruptible(70L);
                box.setLocation(boxX + 3, boxTopY);
                sleepInterruptible(70L);
            }
            box.setLocation(boxX, boxTopY);
            sleepInterruptible(180L);
            // 3) Open the box.
            box.setProp("box-open", boxSize, false);
            sleepInterruptible(220L);
            // 4) Kitten rises UP out of the box's opening. Its panel is CLIPPED
            //    at the rim, so only the part that has emerged above the opening
            //    is ever drawn (no body showing through the box's transparent
            //    flaps/gap). By the apex the whole sprite is above the rim, so
            //    we drop the clip, restore the full panel and bring it to the
            //    FRONT for the landing.
            setPetWindowVisible(true);
            int riseSteps = 20;
            for (int i = 1; i <= riseSteps && !interrupted(); i++) {
                double t = i / (double) riseSteps;
                int topY = (int) Math.round(startTopY + (peakTopY - startTopY) * t);
                setFrameClipped(petX, topY, rimY);
                sleepInterruptible(16L);
            }
            if (interrupted()) {
                return;
            }
            if (frame != null) {
                frame.setBounds(petX, peakTopY, petSize, petSize);
                frame.toFront();
            }
            this.intendedX = petX;
            this.intendedY = peakTopY;
            sleepInterruptible(120L);
            // 5) The empty box drops straight down and falls off the bottom of
            //    the screen while the kitten falls and lands on the floor where
            //    the box stood. Run both with gravity in one interleaved loop so
            //    they fall together; the kitten (now front-most) lands on the
            //    surface and the box continues out of view behind it.
            int boxFallEndY = mon.y + mon.height + boxSize + 8;
            double by = boxTopY;
            double bvy = 0.0;
            double py = peakTopY;
            double pvy = 0.0;
            double grav = Math.max(1.4, 1.6 * Dpi.FACTOR);
            boolean boxDone = false;
            boolean petDone = false;
            while ((!boxDone || !petDone) && !interrupted()) {
                if (!boxDone) {
                    bvy += grav;
                    by += bvy;
                    if (by >= boxFallEndY) {
                        by = boxFallEndY;
                        boxDone = true;
                    }
                    box.setLocation(boxX, (int) Math.round(by));
                }
                if (!petDone) {
                    pvy += grav;
                    py += pvy;
                    if (py >= petFloorTopY) {
                        py = petFloorTopY;
                        petDone = true;
                    }
                    moveFrameTo(petX, (int) Math.round(py));
                }
                sleepInterruptible(16L);
            }
            moveFrameTo(petX, petFloorTopY);
            // 6) Land, settle, then do a little dance. (The box is now below the
            //    screen and clipped from view; it is disposed in the finally.)
            applySprite(idleFrames().get(0));
            sleepInterruptible(220L);
            if (!interrupted()) {
                dance();
            }
            // 7) Run off the nearest edge of the monitor.
            if (!interrupted()) {
                int petMid = petX + petW / 2;
                boolean exitLeft = (petMid - mon.x) <= (mon.x + mon.width - petMid);
                int exitX = exitLeft ? (mon.x - petW - 24) : (mon.x + mon.width + 24);
                runAlongFloor(world, exitX);
            }
        } finally {
            box.dispose();
            disposeWindow();
            Log.info("pet:" + name, "box event end");
        }
    }

    /**
     * Special "laser pointer" cinematic (see {@link #laserEvent}). A darting
     * red dot appears on the floor near this visiting cat, which frantically
     * chases it from spot to spot as it flicks away, until the dot finally
     * blinks out and the cat is left looking around, puzzled. The dot rides its
     * own always-on-top {@link OverlayWindow} so it stays IN FRONT of the cat;
     * the cat IS shown and really runs after it.
     */
    private void runLaserEventLoop() {
        Log.info("pet:" + name, "laser event begin");
        World world = World.snapshot((int) screen.getWidth(), (int) screen.getHeight());
        reassertTopmost();
        pendingEntryTargetX = null;
        pendingEntryTargetY = null;

        Rectangle mon = currentMonitorBounds();
        int petW = effectiveWidth();
        int landX = plannedSpawnTargetX != null ? plannedSpawnTargetX : logicalLocation().x;
        landX = clampInt(landX, mon.x + 4, mon.x + mon.width - petW - 4);
        int petFloorTopY = floorYAt(world, landX);

        // Two modes (chosen by LaserPointerVisitor): if a resident cat is
        // already on this monitor it gives chase, so this carrier stays HIDDEN
        // and only drives the dot (publishing a LaserQuarry the resident reads).
        // Only when NO resident cat is available do we show this visiting cat to
        // do the chasing itself — and then it trots off-screen when the dot is
        // gone rather than vanishing on the spot.
        boolean selfChase = !laserResidentChaser;
        setPetWindowVisible(selfChase);
        if (selfChase) {
            moveFrameTo(landX, petFloorTopY);
            idle();
        }

        int dotSize = Math.max(18, (int) Math.round(petSize * 0.42));
        int dotMin = mon.x + 6;
        int dotMax = mon.x + mon.width - dotSize - 6;
        int dotX = clampInt(landX + petW + 20, dotMin, dotMax);
        // Dot centre rides just above the floor surface (footRatio 0.5).
        int dotY = floorTopForProp(world, dotX + dotSize / 2, dotSize, 0.5);

        // The dot rides an always-on-top OverlayWindow (NOT a StageProp on the
        // shared pet layer): it is a projected light point, so it must stay on
        // top of EVERYTHING — including the cat chasing it. A StageProp shares
        // the pets' z-layer, which let a pet render on top of the dot (a pet
        // "standing on" the laser). OverlayWindow is always-on-top + click-
        // through like the sky props, so the dot is never occluded.
        OverlayWindow dot = OverlayWindow.create("laser-dot", dotSize, dotX, dotY, false);
        // Publish the dot as a live quarry so a resident cat (if any) can chase
        // it from its own BehaviorEngine (see canChaseLaser / chaseLaser).
        LaserQuarry quarry = LaserQuarry.publish(mon, dotSize,
                dotX + dotSize / 2, dotY + dotSize / 2);
        try {
            int darts = 7 + ThreadLocalRandom.current().nextInt(7); // 7..13
            for (int d = 0; d < darts && !interrupted(); d++) {
                final ThreadLocalRandom rnd = ThreadLocalRandom.current();
                // Pick a move "style" so the dot is unpredictable: mostly small
                // twitchy jitters, sometimes a medium hop, and every so often a
                // big dash most of the way across the screen — the cat never
                // knows whether it'll barely flick or bolt far away.
                double roll = rnd.nextDouble();
                int span;
                if (roll < 0.22) {
                    // Big dash: 30%..65% of the monitor width.
                    span = (int) Math.round(mon.width * (0.30 + rnd.nextDouble() * 0.35));
                } else if (roll < 0.55) {
                    // Medium hop.
                    span = (int) Math.round(petSize * (2.0 + rnd.nextDouble() * 2.5));
                } else {
                    // Small twitch.
                    span = (int) Math.round(petSize * (0.4 + rnd.nextDouble() * 1.2));
                }
                int sign = rnd.nextBoolean() ? 1 : -1;
                int nextX = clampInt(dotX + sign * span, dotMin, dotMax);
                // If we barely moved (clamped against an edge), bounce the
                // other way so a big dash near a wall still goes somewhere.
                if (Math.abs(nextX - dotX) < petSize / 2) {
                    nextX = clampInt(dotX - sign * span, dotMin, dotMax);
                }
                int nextY = floorTopForProp(world, nextX + dotSize / 2, dotSize, 0.5);
                // Vary the flick SPEED: more steps for longer hops, but always a
                // quick zip, with a jittered per-step delay.
                int dist = Math.abs(nextX - dotX);
                int steps = clampInt(dist / Math.max(8, dotSize), 6, 20);
                long stepDelay = 5L + rnd.nextInt(8); // 5..12 ms
                glideOverlay(dot, dotX, dotY, nextX, nextY, steps, stepDelay);
                dotX = nextX;
                dotY = nextY;
                quarry.update(dotX + dotSize / 2, dotY + dotSize / 2);
                // Sometimes flick AGAIN right away (a quick double-feint) before
                // the cat catches up, so it isn't a predictable one-move-a-beat.
                if (rnd.nextInt(100) < 30) {
                    int span2 = (int) Math.round(petSize * (1.0 + rnd.nextDouble() * 2.2));
                    int sign2 = rnd.nextBoolean() ? 1 : -1;
                    int n2 = clampInt(dotX + sign2 * span2, dotMin, dotMax);
                    int n2y = floorTopForProp(world, n2 + dotSize / 2, dotSize, 0.5);
                    glideOverlay(dot, dotX, dotY, n2, n2y,
                            clampInt(Math.abs(n2 - dotX) / Math.max(8, dotSize), 6, 16), 6L);
                    dotX = n2;
                    dotY = n2y;
                    quarry.update(dotX + dotSize / 2, dotY + dotSize / 2);
                }
                // When THIS cat is the chaser (no resident cat around) it
                // pounces after the dot itself. Otherwise a resident cat does
                // the chasing via the quarry and this hidden carrier just paces
                // the dot's flicks with the pauses below.
                if (selfChase) {
                    int chaseX = clampInt(dotX + dotSize / 2 - petW / 2,
                            mon.x + 4, mon.x + mon.width - petW - 4);
                    walkAlongFloor(world, chaseX);
                    // Occasional crouch/pounce of a random length.
                    if (!interrupted() && rnd.nextInt(100) < 45) {
                        applySprite(doodleKind() + "/sit");
                        sleepInterruptible(110L + rnd.nextInt(190));
                    }
                    if (!interrupted()) {
                        idle();
                    }
                }
                if (interrupted()) {
                    break;
                }
                // Random pause between darts — from a near-instant twitch to a
                // tantalising freeze where the dot sits still and the cat creeps
                // up, so the rhythm is irregular.
                double pauseRoll = rnd.nextDouble();
                long pause;
                if (pauseRoll < 0.20) {
                    pause = 40L + rnd.nextInt(120);     // near-instant
                } else if (pauseRoll < 0.80) {
                    pause = 200L + rnd.nextInt(360);    // normal
                } else {
                    pause = 700L + rnd.nextInt(900);    // long teasing freeze
                }
                sleepInterruptible(pause);
            }
            // The dot blinks out.
            for (int i = 0; i < 3 && !interrupted(); i++) {
                dot.setVisible(false);
                sleepInterruptible(120L);
                dot.setVisible(true);
                sleepInterruptible(120L);
            }
            // A visiting chaser cat looks around, puzzled, then trots off the
            // nearest edge so it doesn't just pop out of existence. (When a
            // resident cat did the chasing this carrier is hidden — nothing to
            // show or walk off; the resident's own stalking emote is cleared by
            // its BehaviorEngine once the quarry clears.)
            if (selfChase && !interrupted()) {
                showEmote("question", 600L);
                int petMid = logicalLocation().x + petW / 2;
                boolean exitLeft = (petMid - mon.x) <= (mon.x + mon.width - petMid);
                int exitX = exitLeft ? (mon.x - petW - 24) : (mon.x + mon.width + 24);
                runAlongFloor(world, exitX);
            }
        } finally {
            quarry.clear();
            dot.dispose();
            disposeWindow();
            Log.info("pet:" + name, "laser event end");
        }
    }

    /**
     * A dedicated borderless, always-on-top, click-through {@link JFrame} that
     * carries the airplane sprite (duck passenger included) across the screen
     * during {@link #runAirplaneEventLoop()}. A real top-level window — rather
     * than a panel on the shared {@link Stage} — so the plane cruises in the
     * normal topmost band, IN FRONT of ordinary windows, instead of being cut
     * out behind them by the stage's occlusion holes.
     */
    private static final class AirplaneWindow {
        private final JFrame frame;

        private AirplaneWindow(JFrame frame) {
            this.frame = frame;
        }

        /** Build + show the plane window off-screen at {@code (x, y)} with the
         *  airplane icon (mirrored for leftward flight, {@code dir < 0}). All
         *  AWT/Win32 setup happens synchronously on the EDT so the native HWND
         *  exists before it is made click-through. */
        static AirplaneWindow create(int size, int dir, int x, int y) {
            final JFrame[] out = new JFrame[1];
            Runnable build = () -> {
                JFrame f = new JFrame();
                f.setUndecorated(true);
                f.setBackground(new Color(0, 0, 0, 0));
                f.setAlwaysOnTop(true);
                f.setType(JFrame.Type.UTILITY);      // no taskbar entry
                f.setFocusableWindowState(false);    // never steal focus
                f.setAutoRequestFocus(false);
                f.setLayout(null);
                JLabel label = new JLabel();
                label.setBounds(0, 0, size, size);
                ImageIcon icon = Doodle.icon("prop/airplane", size);
                if (dir < 0 && icon != null) {
                    icon = mirroredHoriz(icon);
                }
                label.setIcon(icon);
                f.add(label);
                f.setSize(size, size);
                f.setLocation(x, y);
                String title = "DesktopPets-Airplane-" + Long.toHexString(System.nanoTime());
                f.setTitle(title);
                f.setVisible(true);
                long hwnd = Win32.findWindowByTitle(title);
                // Exclude from the pet floor/perch finder and make it
                // click-through so it never traps clicks meant for the apps it
                // flies over.
                Win32.registerOwnWindow(hwnd);
                Win32.makeClickThrough(hwnd);
                out[0] = f;
            };
            try {
                if (SwingUtilities.isEventDispatchThread()) {
                    build.run();
                } else {
                    SwingUtilities.invokeAndWait(build);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.warn("airplane", "window create failed: " + e);
            }
            return new AirplaneWindow(out[0]);
        }

        /** Move the plane to {@code (x, y)} in virtual-desktop screen pixels. */
        void setLocation(int x, int y) {
            JFrame f = frame;
            if (f != null) {
                SwingUtilities.invokeLater(() -> f.setLocation(x, y));
            }
        }

        /** Hide + dispose the window. Safe if creation failed. */
        void dispose() {
            JFrame f = frame;
            if (f != null) {
                SwingUtilities.invokeLater(() -> {
                    f.setVisible(false);
                    f.dispose();
                });
            }
        }
    }

    /** Horizontally-mirrored copy of an icon — used to flip the nose-right
     *  plane prop for leftward flight. */
    private static ImageIcon mirroredHoriz(ImageIcon src) {
        int w = src.getIconWidth();
        int h = src.getIconHeight();
        if (w <= 0 || h <= 0) {
            return src;
        }
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
                w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        // Destination (0,0)-(w,h) sampled from source (w,0)-(0,h): a horizontal
        // (left<->right) flip.
        g.drawImage(src.getImage(), 0, 0, w, h, w, 0, 0, h, null);
        g.dispose();
        return new ImageIcon(out);
    }

    /**
     * A dedicated borderless, always-on-top, click-through {@link JFrame} that
     * carries a single prop sprite in the topmost band — IN FRONT of ordinary
     * windows and the shared {@link Stage} — for the cinematic special events
     * (mouse scurry, rain cloud, delivery drone, cardboard box, laser pointer).
     * Generalises {@link AirplaneWindow}: the prop and its mirroring can be
     * swapped at runtime ({@link #setProp}) so one window can, e.g., open a box
     * mid-cinematic, and the window can be blinked on and off
     * ({@link #setVisible}).
     */
    private static final class OverlayWindow implements MovableProp {
        private final JFrame frame;
        private final JLabel label;

        private OverlayWindow(JFrame frame, JLabel label) {
            this.frame = frame;
            this.label = label;
        }

        /** Build + show the overlay off-screen at {@code (x, y)} carrying
         *  {@code prop/<propKey>} at {@code size} px (mirrored horizontally
         *  when {@code mirror}). All AWT/Win32 setup runs synchronously on the
         *  EDT so the native HWND exists before it is made click-through. */
        static OverlayWindow create(String propKey, int size, int x, int y, boolean mirror) {
            final JFrame[] outF = new JFrame[1];
            final JLabel[] outL = new JLabel[1];
            Runnable build = () -> {
                JFrame f = new JFrame();
                f.setUndecorated(true);
                f.setBackground(new Color(0, 0, 0, 0));
                f.setAlwaysOnTop(true);
                f.setType(JFrame.Type.UTILITY);      // no taskbar entry
                f.setFocusableWindowState(false);    // never steal focus
                f.setAutoRequestFocus(false);
                f.setLayout(null);
                JLabel lb = new JLabel();
                lb.setBounds(0, 0, size, size);
                ImageIcon icon = Doodle.icon("prop/" + propKey, size);
                if (mirror && icon != null) {
                    icon = mirroredHoriz(icon);
                }
                lb.setIcon(icon);
                f.add(lb);
                f.setSize(size, size);
                f.setLocation(x, y);
                String title = "DesktopPets-Overlay-" + Long.toHexString(System.nanoTime());
                f.setTitle(title);
                f.setVisible(true);
                long hwnd = Win32.findWindowByTitle(title);
                Win32.registerOwnWindow(hwnd);
                Win32.makeClickThrough(hwnd);
                outF[0] = f;
                outL[0] = lb;
            };
            try {
                if (SwingUtilities.isEventDispatchThread()) {
                    build.run();
                } else {
                    SwingUtilities.invokeAndWait(build);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.warn("overlay", "window create failed: " + e);
            }
            return new OverlayWindow(outF[0], outL[0]);
        }

        /** Swap the prop sprite (same window) — e.g. open/close the box. */
        void setProp(String propKey, int size, boolean mirror) {
            JLabel lb = label;
            if (lb == null) {
                return;
            }
            ImageIcon base = Doodle.icon("prop/" + propKey, size);
            final ImageIcon icon = (mirror && base != null) ? mirroredHoriz(base) : base;
            SwingUtilities.invokeLater(() -> {
                lb.setBounds(0, 0, size, size);
                lb.setIcon(icon);
            });
        }

        /** Move the prop to {@code (x, y)} in virtual-desktop screen pixels. */
        @Override
        public void setLocation(int x, int y) {
            JFrame f = frame;
            if (f != null) {
                SwingUtilities.invokeLater(() -> f.setLocation(x, y));
            }
        }

        /** Show/hide the prop (used to blink the laser dot out). */
        void setVisible(boolean v) {
            JFrame f = frame;
            if (f != null) {
                SwingUtilities.invokeLater(() -> f.setVisible(v));
            }
        }

        /** Hide + dispose the window. Safe if creation failed. */
        void dispose() {
            JFrame f = frame;
            if (f != null) {
                SwingUtilities.invokeLater(() -> {
                    f.setVisible(false);
                    f.dispose();
                });
            }
        }
    }

    /**
     * A single cinematic prop rendered on the shared {@link Stage} (rather than
     * an always-on-top {@link OverlayWindow}), so it shares the resident pets'
     * layer: the stage canvas carves it out with the very same window-occlusion
     * holes, so the prop hides BEHIND the user's ordinary windows exactly like
     * the pets do. Used for the FLOOR-level cinematics (mouse scurry, cardboard
     * box, laser pointer): a floor prop floating in FRONT of the user's windows
     * while the pets it shares the floor with hide BEHIND them reads as broken
     * (only the pets' feet poke out under the window). Sky cinematics (airplane,
     * rain cloud, delivery drone) keep using {@link OverlayWindow} so they fly
     * IN FRONT of windows, where there is normally nothing to clash with.
     *
     * <p>The prop is brought to the FRONT of its stage canvas on creation (and
     * whenever its sprite is swapped) so it paints over the pets — e.g. the box
     * in front of the kitten sitting inside it.
     */
    private static final class StageProp implements MovableProp {
        private final PetWindow win;
        private final JLabel label;

        private StageProp(PetWindow win, JLabel label) {
            this.win = win;
            this.label = label;
        }

        /** Build + show the prop on {@code mon}'s stage at screen {@code (x,y)}
         *  carrying {@code prop/<propKey>} at {@code size} px (mirrored
         *  horizontally when {@code mirror}), brought to the front so it paints
         *  over the pets. */
        static StageProp create(String propKey, int size, Rectangle mon,
                int x, int y, boolean mirror) {
            PetWindow w = new PetWindow();
            JLabel lb = new JLabel();
            w.add(lb);
            final int fSize = size;
            onEdt(() -> {
                lb.setBounds(0, 0, fSize, fSize);
                ImageIcon base = Doodle.icon("prop/" + propKey, fSize);
                lb.setIcon((mirror && base != null) ? mirroredHoriz(base) : base);
            });
            w.showOnMonitor(mon, x, y, size, size);
            w.toFront();
            return new StageProp(w, lb);
        }

        /** Swap the prop sprite (same panel) — e.g. open/close the box — and
         *  re-assert front-most z-order over the pets. */
        void setProp(String propKey, int size, boolean mirror) {
            JLabel lb = label;
            if (lb == null) {
                return;
            }
            ImageIcon base = Doodle.icon("prop/" + propKey, size);
            final ImageIcon icon = (mirror && base != null) ? mirroredHoriz(base) : base;
            final int fSize = size;
            onEdt(() -> {
                lb.setBounds(0, 0, fSize, fSize);
                lb.setIcon(icon);
            });
            win.toFront();
        }

        /** Move the prop to {@code (x, y)} in virtual-desktop screen pixels. */
        @Override
        public void setLocation(int x, int y) {
            win.setLocation(x, y);
        }

        /** Show/hide the prop (used to blink the laser dot out). */
        void setVisible(boolean v) {
            win.setVisible(v);
        }

        /** Remove the prop from the stage. Safe if creation failed. */
        void dispose() {
            win.dispose();
        }
    }

    /**
     * Shared, monitor-scoped state for the live "mouse scurry" prop so that
     * resident pets can sense and hunt it (analogous to {@link Ball#active()}).
     * The hidden carrier in {@link #runMouseEventLoop()} {@link #publish
     * publishes} the prop's current screen position every step and
     * {@link #clear() clears} it when the cinematic ends; resident pets'
     * behaviour loops read {@link #active()} and run {@link #huntMouse}.
     *
     * <p>The mouse is hunted from anywhere on its monitor, but only
     * {@link #startle startles} (and bolts) once a predator is close, so a
     * distant cat strolls over while a near one triggers a frantic dash.
     */
    public static final class MouseQuarry {
        private static final java.util.concurrent.atomic.AtomicReference<MouseQuarry> ACTIVE =
                new java.util.concurrent.atomic.AtomicReference<>();

        /** A resident on the mouse's monitor will give chase from anywhere on
         *  that monitor, so the interest gate is effectively the monitor width;
         *  this is just a generous upper bound used by {@link #canHuntMouse}. */
        static final int INTEREST_RADIUS = 100_000;

        private final Rectangle monitor;
        private final int sizePx;
        private volatile int centerX;
        private volatile int feetY;
        private volatile long startledUntilMs;
        private volatile int threatX;
        private volatile boolean caught;

        private MouseQuarry(Rectangle monitor, int sizePx, int centerX, int feetY) {
            this.monitor = monitor;
            this.sizePx = sizePx;
            this.centerX = centerX;
            this.feetY = feetY;
        }

        /** Publish a fresh quarry as the active one, replacing any previous. */
        static MouseQuarry publish(Rectangle monitor, int sizePx, int centerX, int feetY) {
            MouseQuarry q = new MouseQuarry(monitor, sizePx, centerX, feetY);
            ACTIVE.set(q);
            return q;
        }

        /** The mouse currently in play, or {@code null} if none / it was caught
         *  or has already left. */
        public static MouseQuarry active() {
            MouseQuarry q = ACTIVE.get();
            return (q != null && !q.caught) ? q : null;
        }

        /** Update the live floor position (called every step by the carrier). */
        void update(int centerX, int feetY) {
            this.centerX = centerX;
            this.feetY = feetY;
        }

        /** A hunter at {@code fromX} (screen px) is closing in / pouncing, so
         *  the mouse should bolt away from that side for a short while. */
        void startle(int fromX) {
            this.threatX = fromX;
            this.startledUntilMs = System.currentTimeMillis() + 850L;
        }

        boolean startled()  { return System.currentTimeMillis() < startledUntilMs; }
        int threatX()       { return threatX; }
        int centerX()       { return centerX; }
        int feetY()         { return feetY; }
        int sizePx()        { return sizePx; }
        Rectangle monitor() { return monitor; }
        boolean caught()    { return caught; }

        /** Mark the mouse as caught — {@link #active()} then reports none and
         *  the carrier loop ends the cinematic. */
        void markCaught()   { this.caught = true; }

        /** Clear this quarry if it is still the active one. */
        void clear()        { ACTIVE.compareAndSet(this, null); }
    }

    /**
     * Shared, monitor-scoped state for the live "laser pointer" dot so that a
     * resident cat can sense and chase it (analogous to {@link MouseQuarry}).
     * The carrier in {@link #runLaserEventLoop()} publishes the dot's current
     * floor column on every flick and {@link #clear() clears} it when the
     * cinematic ends; a resident cat's behaviour loop reads {@link #active()}
     * and runs {@link #chaseLaser}.
     *
     * <p>Unlike the mouse, the dot is never "caught" — it always blinks out on
     * its own — so there is no catch/startle state, just a live position.
     */
    public static final class LaserQuarry {
        private static final java.util.concurrent.atomic.AtomicReference<LaserQuarry> ACTIVE =
                new java.util.concurrent.atomic.AtomicReference<>();

        private final Rectangle monitor;
        private final int sizePx;
        private volatile int centerX;
        private volatile int feetY;

        private LaserQuarry(Rectangle monitor, int sizePx, int centerX, int feetY) {
            this.monitor = monitor;
            this.sizePx = sizePx;
            this.centerX = centerX;
            this.feetY = feetY;
        }

        /** Publish a fresh dot quarry as the active one, replacing any previous. */
        static LaserQuarry publish(Rectangle monitor, int sizePx, int centerX, int feetY) {
            LaserQuarry q = new LaserQuarry(monitor, sizePx, centerX, feetY);
            ACTIVE.set(q);
            return q;
        }

        /** The dot currently in play, or {@code null} if none / it has ended. */
        public static LaserQuarry active() {
            return ACTIVE.get();
        }

        /** Update the live floor position (called on every flick by the carrier). */
        void update(int centerX, int feetY) {
            this.centerX = centerX;
            this.feetY = feetY;
        }

        int centerX()       { return centerX; }
        int feetY()         { return feetY; }
        int sizePx()        { return sizePx; }
        Rectangle monitor() { return monitor; }

        /** Clear this quarry if it is still the active one. */
        void clear()        { ACTIVE.compareAndSet(this, null); }
    }

    /**
     * Knocked-out-of-perch exit for a visitor pet. The bird (or other
     * visitor) drops straight down with constant gravity acceleration
     * until it's fully past the bottom of its monitor, then the caller
     * disposes the window. Uses no horizontal motion — the impact from
     * the ball happens at the bird's location and we want the fall to
     * read as "plucked off the perch" rather than a controlled glide.
     *
     * <p>Pet position is allowed to go past the monitor bottom; the
     * {@link Stage} canvas clips the panel at the monitor boundary, so
     * we get a clean "fell out of view" effect for free.
     */
    private void fallOutAndExit() {
        Rectangle mon = currentMonitorBounds();
        Point start = logicalLocation();
        showEmote("bang", 700);
        // Freeze on a "stunned" pose for the duration of the fall. Birds
        // have a dedicated belly-up sprite with impact stars
        // (Sprites/Bird/Hit/hit.svg, wired via Doodle as "bird/hit").
        // For any other species (in case ground visitors ever become
        // ball-knockable) Doodle's per-species default falls back to
        // that species' idle frame, which still reads as "limp" while
        // the body drops since the sprite doesn't animate.
        applySprite(doodleKind() + "/hit");
        int targetY = mon.y + mon.height + petSize;
        double y = start.y;
        double vy = 0.0;
        double gravity = 1.6; // px per 16 ms step
        int x = start.x;
        while (y < targetY && !Thread.currentThread().isInterrupted()) {
            vy += gravity;
            y += vy;
            moveFrameTo(x, (int) y);
            sleepInterruptible(16L);
        }
    }

    /**
     * Safety net for ground (non-flying) pets that have ended up "in the
     * air" — e.g. a JUMP arc was cut short by a thread interrupt, the
     * window they were perched on closed/moved while the pet wasn't
     * ticking, or a teleport (disappear-reappear, monitor reassignment)
     * left them above their gravity-resolved floor. The pet drops
     * straight down with constant gravity acceleration until its feet
     * land on the next surface beneath at the current column — that's
     * whatever {@link #floorYAt(World, int)} resolves to (the top of a
     * lower window, or the desktop floor as the always-present
     * fallback). No-op for flyers (Bird) and when the pet is already
     * settled (within {@value #AIRBORNE_TOLERANCE_PX} of the floor).
     *
     * @return true if a fall animation was played
     */
    public final boolean fallToFloorIfAirborne(World world) {
        if (flies() || frame == null) {
            return false;
        }
        Point loc = logicalLocation();
        int targetY = floorYAt(world, loc.x);
        if (loc.y >= targetY - AIRBORNE_TOLERANCE_PX) {
            return false;
        }
        // Severe fall: the perch the pet was standing on disappeared by a
        // lot (e.g. the user covered a high window with a fullscreen one,
        // or closed the perch entirely). Plummeting from the top of the
        // monitor straight down to the desktop floor reads as a glitch.
        // Instead, fall off the bottom of the monitor cleanly and respawn
        // after a short pause via the same re-entry mechanic used by
        // {@link Activities#DISAPPEAR_REAPPEAR}. The pet then walks back
        // in from a random monitor edge, so it feels like a deliberate
        // exit-and-return rather than a long uncontrolled drop.
        Rectangle mon = currentMonitorBounds();
        int fallDistance = targetY - loc.y;
        if (fallDistance > Math.max(120, mon.height / 2)) {
            knockedOffPerchAndRespawn(world, mon);
            return true;
        }
        int x = loc.x;
        double y = loc.y;
        double vy = 0.0;
        double gravity = 1.6; // px per 16 ms step \u2014 matches fallOutAndExit
        while (y < targetY && !Thread.currentThread().isInterrupted()) {
            vy += gravity;
            y = Math.min(targetY, y + vy);
            moveFrameTo(x, (int) y);
            sleepInterruptible(16L);
        }
        moveFrameTo(x, targetY);
        return true;
    }

    /**
     * Long-fall recovery: get the pet off-screen, pause out of sight,
     * then re-enter via {@link #pickReentryPlan}. The way we leave
     * depends on where the pet was perched when its surface vanished:
     *
     * <ul>
     *   <li><b>Near a monitor side edge</b> (within
     *       {@value #EDGE_RUN_OFF_DIST_PX} px of the nearer left/right
     *       edge) \u2014 run off that edge horizontally via
     *       {@link #runAlongFloor}. The {@link Stage} canvas clips the
     *       panel at the monitor boundary, so the pet visibly slides off
     *       and disappears once it's fully outside the canvas.</li>
     *   <li><b>Far from any side</b> \u2014 jump off the closer side
     *       in a parabolic arc that carries the pet up + sideways past
     *       the monitor edge, then let gravity drop it past the bottom
     *       of the monitor. Reads as "leapt off the perch" rather than
     *       a controlled exit.</li>
     * </ul>
     *
     * <p>After the pet is off-screen we pause 1.5\u20134 s, then re-enter
     * via the same plan {@link Activities#DISAPPEAR_REAPPEAR} uses.
     */
    private void knockedOffPerchAndRespawn(World world, Rectangle mon) {
        Point start = logicalLocation();
        int petW = effectiveWidth();
        int midX = start.x + petW / 2;
        int leftDist  = midX - mon.x;
        int rightDist = (mon.x + mon.width) - midX;
        boolean exitRight = rightDist <= leftDist;
        int distToEdge = exitRight ? rightDist : leftDist;

        if (distToEdge <= EDGE_RUN_OFF_DIST_PX) {
            // Close to a side: just bolt off it horizontally. runAlongFloor
            // resamples the floor each step, so the pet still tracks any
            // intermediate windows on the way out.
            int exitX = exitRight ? (mon.x + mon.width) : (mon.x - petW);
            runAlongFloor(world, exitX);
        } else {
            // Mid-monitor: leap off the nearer side. Parabolic arc to a
            // point just past the side edge at lift apex, then gravity
            // takes over and drops the pet past mon.bottom.
            jumpOffEdge(mon, exitRight);
        }
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        // Pause out-of-sight before respawning so the event registers as
        // a brief disappearance, not an instant teleport.
        long pauseMs = 1500L + ThreadLocalRandom.current().nextLong(0, 2500L);
        sleepInterruptible(pauseMs);
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        EntryPlan plan = pickReentryPlan(mon, primaryMonitorBounds());
        activeMonitor = plan.monitor;
        moveFrameTo(plan.entryStart.x, plan.entryStart.y);
        refreshCurrentSprite();
        walkAlongFloor(world, plan.target.x);
    }

    /** Pet is "near a side" if it's within this many logical px of either
     *  the left or the right edge of its current monitor. Below the
     *  threshold the long-fall recovery exits with a horizontal run;
     *  above it, the pet leaps off in a parabolic arc. */
    private static final int EDGE_RUN_OFF_DIST_PX = 220;

    /**
     * Parabolic leap toward the nearer side of {@code mon}, continuing
     * with gravity past the bottom of the monitor so the pet exits the
     * screen cleanly. Horizontal motion lerps from the current X to a
     * point a comfortable distance past the chosen side edge, then
     * stops; vertical motion is a half-sine lift (apex partway through
     * the arc) followed by free-fall acceleration once the apex is
     * passed. Sprite stays on the run frames facing the exit direction.
     */
    private void jumpOffEdge(Rectangle mon, boolean exitRight) {
        Point start = logicalLocation();
        int petW = effectiveWidth();
        int petH = effectiveHeight();
        int startX = start.x;
        int startY = start.y;
        int exitX = exitRight
                ? (mon.x + mon.width  + petW)   // a full pet-width past the right edge
                : (mon.x - petW - petW);        // a full pet-width past the left edge
        int arcSpan = Math.abs(exitX - startX);
        // Apex lift: a fraction of the pet's height, capped so very tall
        // pets don't launch ridiculously high.
        int lift = Math.min(64, Math.max(24, petH / 2));
        // Run animation, facing the exit direction, for the duration of
        // the arc; falls back to walk frames if the species ships no
        // dedicated run sheet.
        java.util.List<String> frames = exitRight ? runRightFrames() : runLeftFrames();
        if (frames == null || frames.isEmpty()) {
            frames = exitRight ? walkRightFrames() : walkLeftFrames();
        }
        // The arc maths below assume gravity is in px per 16 ms tick,
        // so we MUST run the loop at ~16 ms. Previously this honoured
        // {@link #runStepDelayMs()} (often ~8 ms for fast species),
        // which doubled the effective gravity / horizontal speed and
        // made the disappear-jump read as a frame-skipped blur instead
        // of a leap. Sprite cycling uses its own runStepDelayMs-derived
        // cadence (every Nth physics tick) to keep the run animation at
        // its normal pace independent of the physics tick.
        final int physicsTickMs = 16;
        int spriteEveryNTicks = Math.max(1, Math.round(runStepDelayMs() / (float) physicsTickMs));
        int spriteIndex = 0;
        // Drive the arc by pixel-steps along X so the speed is consistent
        // regardless of how far the leap is. 3 px / 16 ms \u2248 187 px/s
        // \u2014 a brisk but readable leap (was 4 px at variable cadence).
        int stepPx = Math.max(2, Dpi.scale(3));
        int signed = exitRight ? stepPx : -stepPx;
        int x = startX;
        double y = startY;
        double vy = -computeInitialVy(lift); // negative = upward
        double gravity = 1.6; // px per 16 ms step \u2014 matches fallOutAndExit
        int stepsTaken = 0;
        int offBottom = mon.y + mon.height + petH;
        while (!Thread.currentThread().isInterrupted()) {
            if ((stepsTaken % spriteEveryNTicks) == 0 && !frames.isEmpty()) {
                applySprite(frames.get(spriteIndex % frames.size()));
                spriteIndex++;
            }
            x += signed;
            vy += gravity;
            y  += vy;
            moveFrameTo(x, (int) y);
            sleepInterruptible(physicsTickMs);
            stepsTaken++;
            boolean pastSideEdge = exitRight ? (x >= exitX) : (x <= exitX);
            if (pastSideEdge && y >= offBottom) {
                break;
            }
            // Safety bound: never loop forever even if speed/lift maths
            // produce an unexpected result.
            if (stepsTaken > 600 || Math.abs(x - startX) > arcSpan + Math.max(mon.width, 2000)) {
                break;
            }
            // If we're already well past the side edge AND past mon.bottom
            // we're fully off the screen; bail.
            if (pastSideEdge && y >= mon.y + mon.height + petH) {
                break;
            }
        }
    }

    /** Initial upward velocity (in px / 16 ms tick) that, under the same
     *  {@code gravity = 1.6} px/step used by {@link #jumpOffEdge}, peaks
     *  at roughly {@code lift} pixels above the launch Y. Solves
     *  {@code lift = v0^2 / (2 * g)} for {@code v0}. */
    private static double computeInitialVy(int lift) {
        return Math.sqrt(2.0 * 1.6 * Math.max(1, lift));
    }

    private static final int AIRBORNE_TOLERANCE_PX = 2;

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
            exitRight = midX >= visitorSourceX;
        } else {
            int leftDist = midX - mon.x;
            int rightDist = (mon.x + mon.width) - midX;
            exitRight = rightDist <= leftDist;
        }
        int exitX = exitRight ? mon.x + mon.width : mon.x - petW;
        showEmote("bang", 250);
        if (flies()) {
            // Diagonal swoop up + off (Bird overrides walkTo to fly).
            int targetY = Math.max(mon.y, loc.y - Math.max(petW, mon.height / 3));
            walkTo(exitX, targetY);
        } else {
            // Ground visitor: run off the edge of the monitor at floor
            // level. walkTo with an above-monitor targetY would walk a
            // ground pet diagonally through midair into the sky, so we
            // route through runAlongFloor instead (it resamples the
            // floor each step).
            runAlongFloor(world, exitX);
        }
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
                if (ufoEvent) {
                    runUfoEventLoop();
                } else if (airplaneEvent) {
                    runAirplaneEventLoop();
                } else if (mouseEvent) {
                    runMouseEventLoop();
                } else if (rainCloudEvent) {
                    runRainCloudEventLoop();
                } else if (droneEvent) {
                    runDroneEventLoop();
                } else if (boxEvent) {
                    runBoxEventLoop();
                } else if (laserEvent) {
                    runLaserEventLoop();
                } else {
                    runVisitorLoop();
                }
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
        // Set BEFORE scheduling the EDT dispose so a racing
        // moveFrameTo() on the behaviour thread (between
        // Thread.interrupt() and the next interrupted() check) bails
        // out instead of re-attaching the now-detached panel.
        disposed = true;
        onEdt(() -> {
            frame.setVisible(false);
            frame.dispose();
        });
        Log.info("pet:" + name, "disposed");
    }

    /**
     * Re-assert {@code HWND_TOPMOST} on the shared stage windows. Cheap,
     * idempotent, throttled to once per second per stage inside
     * {@link Stage#reassertTopmost()}. Called by {@link BehaviorEngine}
     * once per tick so the pets stay in the topmost band even if another
     * app (or a focus / mode switch) demotes them.
     */
    public final void reassertTopmost() {
        long now = System.currentTimeMillis();
        if (now < nextReassertTopmostMs) return;
        nextReassertTopmostMs = now + 1000L;
        Stage.reassertTopmost();
    }

    /** Earliest wall-clock time at which {@link #reassertTopmost()} will
     *  actually call into Win32. See that method's javadoc for the
     *  rationale behind the 1-second throttle. */
    private long nextReassertTopmostMs = 0L;

    private void initOnEdt() throws InterruptedException, InvocationTargetException {
        Runnable build = () -> {
            // Pet windows are now JPanels hosted by a shared per-monitor
            // Stage canvas (transparent always-on-top click-through). The
            // PetWindow wrapper preserves the small slice of the legacy
            // JFrame API the rest of Pet still uses (setBounds /
            // setLocation / setVisible / setSize / add); coordinates are
            // virtual-desktop screen pixels exactly like before.
            frame = new PetWindow();

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
            {
                // Pick a random visible monitor and a random side. The pet's
                // intended logical position starts FULLY OUTSIDE the chosen
                // monitor's entry edge so the first behaviour tick's
                // walkAlongFloor traverses the full distance with proper
                // edge clipping (the panel slides in from off-screen,
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
            // Attach the FULL petSize panel to the entry monitor's stage at
            // the off-screen entry position. The pet is bound to its monitor
            // explicitly (not inferred from the off-screen top-left point),
            // then Swing clips the panel to the stage canvas bounds, so the
            // pet slides in from the edge as the entry walk advances it
            // inward. The panel keeps its petSize footprint for its whole
            // life — movement is pure setLocation (see moveFrameTo) and the
            // canvas does the edge clipping, so the pet is never squashed to
            // a sliver.
            frame.showOnMonitor(activeMonitor, firstSetLocation.x, firstSetLocation.y,
                    petSize, petSize);

            // Force an immediate first frame so the pet is visible without
            // waiting for the behavior loop to tick once.
            applySprite(idleFrames().get(0));

            Log.info("pet:" + name,
                    "spawned size=" + petSize
                    + " at " + clamped.x + "," + clamped.y
                    + " (screen " + (int) screen.getWidth() + "x" + (int) screen.getHeight()
                    + ")"
                    + " visible=" + frame.isVisible());

            // Hover / click are dispatched by PetMouse (the stage window
            // is click-through and never receives native mouse events).
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
     * Like {@link #pickEntryPlan} but biases the re-entry monitor choice
     * based on how many monitors are available:
     * <ul>
     *   <li>Single monitor: trivially same monitor (only option).</li>
     *   <li>Multi-monitor: {@value #DIFFERENT_MONITOR_REENTRY_CHANCE} of
     *       the time the pet re-enters on a DIFFERENT monitor than the
     *       one it just left, so the cross-screen migration is actually
     *       visible to the user; the remaining fraction it re-enters on
     *       the same monitor for DPI/size consistency.</li>
     * </ul>
     * Previously this used an 80% same-monitor bias plus an unconditional
     * random pick across all monitors, which on a typical 2-monitor
     * setup meant only ~10% of disappear/reappear events actually
     * switched screens — making it feel like pets never leave their
     * monitor.
     */
    private EntryPlan pickReentryPlan(Rectangle preferred, Rectangle primary) {
        java.util.List<Rectangle> all = usableMonitors(primary);
        if (preferred != null && all.size() > 1
                && ThreadLocalRandom.current().nextDouble() < DIFFERENT_MONITOR_REENTRY_CHANCE) {
            java.util.List<Rectangle> others = new java.util.ArrayList<>(all.size() - 1);
            for (Rectangle b : all) {
                if (!b.equals(preferred)) {
                    others.add(b);
                }
            }
            if (!others.isEmpty()) {
                Rectangle pick = others.get(
                        ThreadLocalRandom.current().nextInt(others.size()));
                return entryPlanFor(pick);
            }
        }
        if (preferred != null) {
            return entryPlanFor(preferred);
        }
        return pickEntryPlan(primary);
    }

    /** Fraction of disappear/reappear events that re-enter on a DIFFERENT
     *  monitor than the one the pet left. Only consulted when multiple
     *  usable monitors are present; otherwise the pet re-enters on the
     *  only monitor. Tuned so cross-screen migration is noticeable
     *  ("oh, the cat is on the other screen now") but the pet still
     *  spends most of its time on a stable home screen. */
    private static final double DIFFERENT_MONITOR_REENTRY_CHANCE = 0.6;

    /** Collect the de-duplicated, non-degenerate screen rectangles. Mirrors
     *  the filtering inside {@link #pickEntryPlan} so re-entry honors the
     *  same monitor set the entry walk uses. */
    private static java.util.List<Rectangle> usableMonitors(Rectangle fallback) {
        java.util.List<Rectangle> usable = new java.util.ArrayList<>();
        try {
            for (GraphicsDevice d : GraphicsEnvironment
                    .getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle b = d.getDefaultConfiguration().getBounds();
                if (b.width <= 0 || b.height <= 0) continue;
                boolean dup = false;
                for (Rectangle u : usable) {
                    if (u.equals(b)) { dup = true; break; }
                }
                if (!dup) usable.add(b);
            }
        } catch (Throwable t) {
            // ignore
        }
        if (usable.isEmpty() && fallback != null) {
            usable.add(fallback);
        }
        return usable;
    }

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
            //
            // Geometry: Bird.walkTo lerps Y linearly across the dx steps,
            // so the descent ANGLE is atan(dy/dx). To get a gentle ~20°
            // dive (not a near-vertical drop), we want |dx| ≈ dy / tan(20°)
            // ≈ dy * 2.75. With dy = (targetY - (mon.y - petSize)) — i.e.
            // the full descent from just above the monitor top to the
            // floor — that lateral offset is much larger than petSize but
            // perfectly fine: the entryStart sits off-screen above-and-
            // beside the monitor and the bird flies in along the diagonal.
            int verticalDrop = targetY - (mon.y - petSize);
            // tan(20°) ≈ 0.364 → multiplier ≈ 2.75. Floor at 2× petSize
            // so very tall monitors still look like a swoop rather than
            // a line that disappears off-screen.
            int lateral = Math.max(2 * petSize, (int) Math.round(verticalDrop * 2.75));
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
            // Bypass moveFrameTo's intendedX/Y fast-path: setSize keeps
            // the same logical position, but we still need to actually
            // push the new frame size to the stage. Update intendedX/Y
            // directly and drain on the spot (we're already on EDT).
            this.intendedX = clampedLoc.x;
            this.intendedY = clampedLoc.y;
            // Invalidate the no-op position cache: even when the logical
            // (x, y) is unchanged the panel size just changed, so the
            // next drain must re-issue setLocation/setBounds.
            lastAppliedX = Integer.MIN_VALUE;
            lastAppliedY = Integer.MIN_VALUE;
            moveQueued.set(true);
            drainMoveOnEdt();
            // Force a sprite re-render at the new label size by
            // invalidating the applySprite dedupe cache.
            this.lastPetSpriteKey = null;
            applySprite(idleFrames().get(0));
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

    /** Pet sits in place for a longer hold; calming idle variant.
     *  Default is a single static pose held for {@link #SIT_HOLD_MS}.
     *  Subclasses can return multiple keys from {@link #sitFrames()} to
     *  animate during the hold (e.g. Dog wagging its tail). */
    public void sit() {
        List<String> frames = sitFrames();
        if (frames.size() <= 1) {
            applySprite(frames.isEmpty() ? doodleKind() + "/sit" : frames.get(0));
            sleepInterruptible(SIT_HOLD_MS);
            return;
        }
        long deadline = System.currentTimeMillis() + SIT_HOLD_MS;
        while (System.currentTimeMillis() < deadline) {
            if (interrupted() || hovered || clicked.get()) return;
            playFrames(petLabel, frames, IDLE_FRAME_MS);
        }
    }

    /** Frames shown by {@link #sit()}. Default is a single static key
     *  resolved per-species via {@link Doodle}; subclasses override to
     *  animate the sit pose. */
    protected List<String> sitFrames() {
        return List.of(doodleKind() + "/sit");
    }

    /** Pet stretches — distinct silhouette for a beat then resumes. */
    public void stretch() {
        applySprite(doodleKind() + "/stretch");
        sleepInterruptible(STRETCH_HOLD_MS);
    }

    /** Pet looks left, then right. */
    public void lookAround() {
        applySprite(doodleKind() + "/look/0");
        sleepInterruptible(LOOK_HOLD_MS);
        if (interrupted() || hovered || clicked.get()) {
            return;
        }
        applySprite(doodleKind() + "/look/1");
        sleepInterruptible(LOOK_HOLD_MS);
    }

    /** Pet sleeps in place; fully restores ENERGY and shows a Z-Z-Z overlay. */
    public void sleep() {
        showProp("prop/zzz");
        applySprite(doodleKind() + "/sleep");
        sleepInterruptible(SLEEP_HOLD_MS);
        needs.add(Need.ENERGY, 100);
        clearProp();
    }

    /**
     * Per-species food prop key shown by {@link #eat()}. Default is the
     * generic kibble bowl ({@code "prop/food"}); subclasses can override
     * to surface species-flavoured props (Cat→fish, Dog→bone, Ducky→seed).
     */
    protected String foodPropKey() {
        return "prop/food";
    }

    /**
     * Pet eats in place; fully restores HUNGER and shows a species-specific
     * food overlay (see {@link #foodPropKey()}). Telegraphs the action first
     * by showing a {@code think-food} thought bubble above the pet for ~1 s
     * so the user sees the hunger spike register rather than the food
     * appearing out of nowhere. During the meal the pet shows two {@code
     * chomp} emotes ~700 ms apart so it reads as actively eating rather
     * than just standing over the bowl.
     */
    public void eat() {
        showEmote("think-food", 1000);
        if (interrupted() || hovered || clicked.get()) return;
        showProp(foodPropKey());
        // Chew cycle: alternate idle-frame play with a chomp emote so the
        // pet visibly works at the food. EAT_HOLD_MS is the original total
        // hold; we split it evenly across two chomps with idle frames in
        // between so the visual cadence reads as bite/chew/bite/chew.
        long bite = Math.max(400L, EAT_HOLD_MS / 3);
        for (int i = 0; i < 2; i++) {
            if (interrupted() || hovered || clicked.get()) break;
            playFrames(petLabel, idleFrames(), IDLE_FRAME_MS);
            showEmote("chomp", bite);
        }
        needs.add(Need.HUNGER, 100);
        clearProp();
    }

    /**
     * World-aware eat: telegraph hunger, run to the nearest screen edge,
     * fetch the food prop from "behind" it, then chew in place. Used by
     * the solo {@link Activities#EAT} so the food visibly arrives from
     * off-screen instead of materialising under the pet. The no-arg
     * {@link #eat()} is kept for rendezvous activities like {@code
     * SHARE_FOOD} where the prop is supplied by the encounter itself.
     */
    public void eat(World world) {
        showEmote("think-food", 1000);
        if (interrupted() || hovered || clicked.get()) return;
        fetchPropFromEdge(world, foodPropKey());
        if (interrupted() || hovered || clicked.get()) { clearProp(); return; }
        long bite = Math.max(400L, EAT_HOLD_MS / 3);
        for (int i = 0; i < 2; i++) {
            if (interrupted() || hovered || clicked.get()) break;
            playFrames(petLabel, idleFrames(), IDLE_FRAME_MS);
            showEmote("chomp", bite);
        }
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
        if (interrupted() || hovered || clicked.get()) return;
        showProp("prop/water");
        playFrames(petLabel, idleFrames(), IDLE_FRAME_MS);
        sleepInterruptible(EAT_HOLD_MS);
        needs.add(Need.THIRST, 100);
        clearProp();
    }

    /**
     * World-aware drink: like {@link #drink()} but fetches the water bowl
     * from behind the nearest screen edge first via
     * {@link #fetchPropFromEdge(World, String)}. Used by the solo
     * {@link Activities#DRINK}.
     */
    public void drink(World world) {
        showEmote("think-water", 1000);
        if (interrupted() || hovered || clicked.get()) return;
        fetchPropFromEdge(world, "prop/water");
        if (interrupted() || hovered || clicked.get()) { clearProp(); return; }
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
            if (interrupted() || hovered || clicked.get()) {
                idle();
                return;
            }
            List<String> dir = (c % 2 == 0) ? r : l;
            for (String f : dir) {
                if (interrupted()) { idle(); return; }
                applySprite(f);
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
            if (interrupted() || hovered || clicked.get()) return;
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
        if (interrupted() || hovered || clicked.get()) return;
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
        if (interrupted() || hovered || clicked.get()) return;
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
            if (interrupted() || hovered || clicked.get()) {
                idle();
                return;
            }
            applySprite(doodleKind() + "/look/" + i);
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
        if (interrupted() || hovered || clicked.get()) return;
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
        if (interrupted() || hovered || clicked.get()) return;
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
            if (interrupted() || hovered || clicked.get()) return;
            walkAlongFloor(world, rightX);
            if (interrupted() || hovered || clicked.get()) return;
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
        if (interrupted() || hovered || clicked.get()) return;
        sit();
        for (int i = 0; i < 3; i++) {
            if (interrupted() || hovered || clicked.get()) return;
            showEmote("lick", 700);
            sleepInterruptible(500);
        }
        idle();
    }

    // ---------------- additional solo verbs ----------------

    /**
     * Sit and cycle three thought-bubble emotes in succession — the pet
     * appears to be daydreaming. Reuses {@code think-food}, {@code mini-heart}
     * and {@code note}; no new sprite. Small BOREDOM relief: the pet
     * entertained itself.
     */
    public void daydream() {
        sit();
        if (interrupted() || hovered || clicked.get()) return;
        String[] thoughts = {"think-food", "mini-heart", "note"};
        for (String t : thoughts) {
            if (interrupted() || hovered || clicked.get()) return;
            showEmote(t, 1100);
            sleepInterruptible(900);
        }
        needs.add(Need.BOREDOM, 15);
    }

    /**
     * Quick vertical bob + {@code puff} cloud emote: the pet sneezes.
     * Holds in place (no horizontal drift). Idiomatic gap-filler that
     * adds visible variety without affecting needs.
     */
    public void sneeze() {
        Point start = logicalLocation();
        // Tiny pre-sneeze "inhale" hold.
        sit();
        if (interrupted() || hovered || clicked.get()) return;
        // The sneeze itself: short upward bob, puff cloud emote at peak.
        moveFrameTo(start.x, Math.max(0, start.y - 5));
        showEmote("puff", 500);
        sleepInterruptible(180);
        moveFrameTo(start.x, start.y);
        sleepInterruptible(500);
    }

    /**
     * Cat-flavoured pre-pounce: three small left/right oscillations that
     * read as a "wiggle", then a short forward dash. Direction is chosen
     * toward the centre of the current monitor so a wall-pinned pet still
     * has room to dash.
     */
    public void buttWigglePounce(World world) {
        Point start = logicalLocation();
        int petW = effectiveWidth();
        Rectangle mon = currentMonitorBounds();
        int wiggle = Math.max(4, petW / 6);
        // Three micro oscillations.
        for (int i = 0; i < 3; i++) {
            if (interrupted() || hovered || clicked.get()) return;
            moveFrameTo(Math.max(mon.x, start.x - wiggle), start.y);
            sleepInterruptible(110);
            if (interrupted()) return;
            moveFrameTo(Math.min(mon.x + mon.width - petW, start.x + wiggle), start.y);
            sleepInterruptible(110);
        }
        moveFrameTo(start.x, start.y);
        if (interrupted() || hovered || clicked.get()) return;
        // Forward dash toward the monitor centre.
        int monMid = mon.x + mon.width / 2;
        int dir = (start.x + petW / 2 < monMid) ? +1 : -1;
        int dashTarget = Math.max(mon.x,
                Math.min(mon.x + mon.width - petW, start.x + dir * petW * 3));
        showEmote("bang", 250);
        runAlongFloor(world, dashTarget);
    }

    /**
     * Top-of-hour Easter egg: head-tilt with a {@code clock} emote.
     * Cosmetic moment that fires when the system clock just rolled past
     * a new hour (priority lambda gates the eligibility).
     */
    public void watchClock() {
        showEmote("clock", 1600);
        // Two head-tilt look poses; uses the same {@code look} key as
        // {@link #headTilt()} so every species resolves.
        for (int i = 0; i < 2; i++) {
            if (interrupted() || hovered || clicked.get()) {
                idle();
                return;
            }
            applySprite(doodleKind() + "/look/" + i);
            sleepInterruptible(700);
        }
        idle();
    }

    /**
     * Solo cat-leaning gag: walk to the nearest monitor edge, sit, and
     * scratch the screen three times ({@code paw} emote). Sister to
     * {@link #lickScreenEdge(World)} — distinct visual (paw vs tongue)
     * so the rotation gets two distinct edge-bound moments.
     */
    public void screenScratchEdge(World world) {
        Rectangle mon = currentMonitorBounds();
        int petW = effectiveWidth();
        int curMid = logicalLocation().x + petW / 2;
        int monMid = mon.x + mon.width / 2;
        int targetX = (curMid < monMid) ? mon.x : mon.x + mon.width - petW;
        walkAlongFloor(world, targetX);
        if (interrupted() || hovered || clicked.get()) return;
        sit();
        for (int i = 0; i < 3; i++) {
            if (interrupted() || hovered || clicked.get()) return;
            showEmote("paw", 600);
            sleepInterruptible(450);
        }
        idle();
    }

    /**
     * Play-bow gesture: walk adjacent to {@code other}, lower the front
     * half of the body in the dedicated {@code play-bow} pose, flash a
     * {@code note}/{@code sparkle} emote, hold the pose briefly, then
     * stand. At the end of the bow we plant a {@link Reaction#HUNT}
     * request on {@code other} so the invitee chases us back — the
     * "invite to play" half of the gesture turns into a friendly hunt.
     *
     * <p>Subclasses that physically can't bow (e.g. {@link Ducky}) override
     * this with a different inviting visual (a bop / crouch-stand cycle)
     * but still call {@link #requestReaction} on the same beat so the
     * resulting chase behaviour is identical.
     */
    public void playBow(Pet other) {
        sit();
        if (interrupted() || hovered || clicked.get()) return;
        // Face the invitee: pick the mirrored variant when the other pet
        // is to our left so the bow visibly leans TOWARD them instead of
        // away. Without this the bow always tilts right and looks like
        // the pet is ignoring its target.
        int myMid = logicalLocation().x + effectiveWidth() / 2;
        int otherMid = other.logicalLocation().x + other.effectiveWidth() / 2;
        String bowKey = (otherMid < myMid) ? "/play-bow-left" : "/play-bow-right";
        applySprite(doodleKind() + bowKey);
        showEmote("sparkle", 700);
        sleepInterruptible(900L);
        if (interrupted() || hovered || clicked.get()) return;
        showEmote("note", 700);
        sleepInterruptible(700L);
        if (interrupted() || hovered || clicked.get()) return;
        other.requestReaction(Reaction.HUNT, 6_000L, myMid);
        applySprite(doodleKind() + "/sit");
        sleepInterruptible(300L);
        idle();
    }

    /**
     * Lay-down rest: sit briefly, then settle into the dedicated
     * {@code lay-down} pose (idle body vertically squashed toward the
     * feet) and hold for ~3.2s. Restores a portion of ENERGY but
     * intentionally less than {@link #sleep()} so SLEEP stays the
     * canonical full-recovery activity; no Z-Z-Z prop so the visual
     * reads as "resting" rather than "asleep".
     */
    public void layDown() {
        sit();
        if (interrupted() || hovered || clicked.get()) return;
        applySprite(doodleKind() + "/lay-down");
        sleepInterruptible(3_200L);
        if (interrupted() || hovered || clicked.get()) return;
        needs.add(Need.ENERGY, 40);
        applySprite(doodleKind() + "/sit");
        sleepInterruptible(400L);
        idle();
    }

    /**
     * Roll-over gag: sit → flip to the sleep sprite (legs up / belly out
     * for dog and cat) → sit. Brief; relies on the existing
     * {@code sleep}/{@code sit} key resolution per species.
     */
    public void rollOver() {
        sit();
        if (interrupted() || hovered || clicked.get()) return;
        showEmote("sparkle", 700);
        applySprite(doodleKind() + "/sleep");
        sleepInterruptible(800);
        if (interrupted() || hovered || clicked.get()) return;
        applySprite(doodleKind() + "/sit");
        sleepInterruptible(400);
        idle();
    }

    /**
     * "What's this?" gag: pick a topmost window column at random, walk
     * to it, sit, show {@code question} then {@code paw}. Activity gates
     * this on world.topmostWindows() being non-empty.
     */
    public void inspectWindow(World world) {
        java.util.List<Rectangle> wins = world.topmostWindows();
        if (wins.isEmpty()) { idle(); return; }
        Rectangle mon = currentMonitorBounds();
        // Only consider windows overlapping the current monitor.
        java.util.List<Rectangle> onMon = new java.util.ArrayList<>(wins.size());
        for (Rectangle r : wins) {
            if (r.x + r.width > mon.x && r.x < mon.x + mon.width) {
                onMon.add(r);
            }
        }
        if (onMon.isEmpty()) { idle(); return; }
        Rectangle pick = onMon.get(ThreadLocalRandom.current().nextInt(onMon.size()));
        int petW = effectiveWidth();
        int lo = Math.max(mon.x, pick.x);
        int hi = Math.max(lo + 1,
                Math.min(mon.x + mon.width - petW, pick.x + pick.width - petW));
        int targetX = (lo >= hi) ? lo : ThreadLocalRandom.current().nextInt(lo, hi);
        walkAlongFloor(world, targetX);
        if (interrupted() || hovered || clicked.get()) return;
        sit();
        if (interrupted()) return;
        showEmote("question", 800);
        sleepInterruptible(700);
        if (interrupted()) return;
        showEmote("paw", 700);
        idle();
    }

    /**
     * Is this pet currently standing on top of a perch (a topmost window
     * other than the taskbar)? Used by {@link Activities#PERCH_NAP}.
     */
    public final boolean isOnPerch(World world) {
        if (world == null) return false;
        int petW = effectiveWidth();
        int x = logicalLocation().x;
        int feetY = logicalLocation().y + (int) Math.round(effectiveHeight() * feetYRatio());
        // Any topmost window whose horizontal span overlaps the pet AND
        // whose top is within a few pixels of the feet line counts as
        // "feet planted on this perch".
        for (Rectangle r : world.topmostWindows()) {
            if (r.x + r.width <= x || r.x >= x + petW) continue;
            // Perch top should be at or above the feet line (small slack).
            if (Math.abs(r.y - feetY) <= Math.max(4, effectiveHeight() / 6)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sleep cycle restricted to when the pet is already on a perch.
     * Rewards the user for arranging windows by giving an extra
     * AFFECTION top-up on top of the full ENERGY restore.
     */
    public void perchNap() {
        sleep();
        if (interrupted() || hovered || clicked.get()) return;
        needs.add(Need.AFFECTION, 10);
    }

    /**
     * Lateral perch-to-perch hop: pick another topmost window whose
     * horizontal span doesn't overlap the current pet column and walk
     * to its near edge. Distinct from the vertical {@code high-perch-leap}
     * (which jumps up). Cat-leaning gag.
     */
    public void windowHop(World world) {
        java.util.List<Rectangle> wins = world.topmostWindows();
        if (wins.isEmpty()) { idle(); return; }
        Rectangle mon = currentMonitorBounds();
        int petW = effectiveWidth();
        int myMid = logicalLocation().x + petW / 2;
        Rectangle best = null;
        int bestDx = Integer.MAX_VALUE;
        for (Rectangle r : wins) {
            // Stay on the current monitor.
            if (r.x + r.width <= mon.x || r.x >= mon.x + mon.width) continue;
            // Skip the perch we may already be on (overlap with current column).
            if (r.x + r.width > myMid - petW / 2 && r.x < myMid + petW / 2) continue;
            int rMid = r.x + r.width / 2;
            int dx = Math.abs(rMid - myMid);
            if (dx < bestDx) {
                bestDx = dx;
                best = r;
            }
        }
        if (best == null) { idle(); return; }
        // Aim for the near edge of the chosen perch.
        int targetX;
        if (best.x + best.width / 2 > myMid) {
            targetX = Math.max(mon.x, best.x);
        } else {
            targetX = Math.max(mon.x,
                    Math.min(mon.x + mon.width - petW, best.x + best.width - petW));
        }
        showEmote("paw", 350);
        walkAlongFloor(world, targetX);
        if (interrupted() || hovered || clicked.get()) return;
        sit();
    }

    /**
     * Pet has been bored by an inactive desktop — walks to the foreground
     * window's column, sits, and head-tilts twice. Distinct from
     * {@link Activities#GREET_FOREGROUND} (which fires when fg <i>just</i>
     * changed); this one fires when fg has been stable a long time.
     */
    public void stareAtForeground(World world) {
        Rectangle fg = world.foreground();
        if (fg == null) { idle(); return; }
        Rectangle mon = currentMonitorBounds();
        int petW = effectiveWidth();
        int targetX = Math.max(mon.x, Math.min(mon.x + mon.width - petW,
                fg.x + fg.width / 2 - petW / 2));
        walkAlongFloor(world, targetX);
        if (interrupted() || hovered || clicked.get()) return;
        sit();
        for (int i = 0; i < 2; i++) {
            if (interrupted()) return;
            applySprite(doodleKind() + "/look/" + (i % 2));
            showEmote("question", 800);
            sleepInterruptible(900);
        }
        idle();
    }

    /**
     * Pounce on the cursor: short pre-wiggle (one beat) then sprint to the
     * cursor's column with a {@code bang} emote on arrival. Distinct from
     * {@link #stalkPointer} (slow creep) and {@link #huntCursor} (sustained
     * chase) — pounce is one explosive shot.
     */
    public void pounceCursor(World world) {
        Point c = World.cursorPos();
        if (c == null) { idle(); return; }
        Rectangle mon = currentMonitorBounds();
        if (!mon.contains(c)) { idle(); return; }
        int petW = effectiveWidth();
        // Pre-pounce wiggle: one quick L/R hop.
        Point start = logicalLocation();
        int wiggle = Math.max(3, petW / 8);
        moveFrameTo(Math.max(mon.x, start.x - wiggle), start.y);
        sleepInterruptible(100);
        moveFrameTo(Math.min(mon.x + mon.width - petW, start.x + wiggle), start.y);
        sleepInterruptible(100);
        moveFrameTo(start.x, start.y);
        if (interrupted() || hovered || clicked.get()) return;
        int targetX = Math.max(mon.x,
                Math.min(mon.x + mon.width - petW, c.x - petW / 2));
        showEmote("target", 250);
        runAlongFloor(world, targetX);
        if (interrupted()) return;
        showEmote("bang", 500);
    }

    /**
     * Phantom laser chase: pet "sees" an imaginary red dot. Three random
     * on-monitor spots are sprinted to in succession, each with a {@code
     * laser} emote that flashes at the target column. Cat-leaning.
     */
    public void chaseLaser(World world) {
        Rectangle mon = currentMonitorBounds();
        int petW = effectiveWidth();
        for (int i = 0; i < 3; i++) {
            if (interrupted() || hovered || clicked.get()) return;
            int hi = Math.max(mon.x + 1, mon.x + mon.width - petW);
            int targetX = ThreadLocalRandom.current().nextInt(mon.x, hi);
            showEmote("laser", 500);
            runAlongFloor(world, targetX);
            if (interrupted()) return;
            showEmote("paw", 300);
            sleepInterruptible(150);
        }
        needs.add(Need.BOREDOM, 20);
    }

    /**
     * Companionable sit: walk to the cursor's column and sit beside it
     * (one body-width offset so the pet doesn't visually cover the
     * pointer). Activity priority gates this on cursor having been
     * stationary recently.
     */
    public void typeBuddy(World world) {
        Point c = World.cursorPos();
        if (c == null) { idle(); return; }
        Rectangle mon = currentMonitorBounds();
        if (!mon.contains(c)) { idle(); return; }
        int petW = effectiveWidth();
        // Offset to the side that keeps us on-monitor.
        int targetX;
        if (c.x - petW - 8 >= mon.x) {
            targetX = c.x - petW - 8;
        } else {
            targetX = Math.min(mon.x + mon.width - petW, c.x + 8);
        }
        walkAlongFloor(world, targetX);
        if (interrupted() || hovered || clicked.get()) return;
        sit();
        if (interrupted()) return;
        showEmote("mini-heart", 1200);
    }

    /**
     * Mirror a sibling's current activity. Walks adjacent and then plays
     * one of a small whitelist of safe verbs matching the sibling's
     * {@link #currentActivityName}. {@code chat} emote at start signals
     * intent.
     */
    public void copyCat(World world) {
        Pet other = nearestOtherPet(PET_COPY_RADIUS);
        if (other == null) { idle(); return; }
        int petW = effectiveWidth();
        int otherMid = other.logicalLocation().x + other.effectiveWidth() / 2;
        boolean fromLeft = logicalLocation().x < otherMid;
        int targetX = fromLeft
                ? otherMid - other.effectiveWidth() / 2 - petW
                : otherMid + other.effectiveWidth() / 2;
        walkAlongFloor(world, targetX);
        if (interrupted() || hovered || clicked.get()) return;
        showEmote("chat", 500);
        String mirror = other.currentActivityName;
        switch (mirror) {
            case "yawn":  yawn();  break;
            case "dance": dance(); break;
            case "sit":
            case "idle":
            default:      sit();   break;
        }
    }

    /** Search radius for {@link #copyCat}, {@link #groomOther},
     *  {@link #parallelPace} and {@link #gift}. Mirrors {@code Activities.PET_PET_RADIUS}. */
    private static final int PET_COPY_RADIUS = 1200;

    /**
     * Walk to the nearest screen edge, "reach behind it" to fetch
     * {@code propKey}, then walk back to the starting column with the
     * prop in hand. Used by {@link #eat(World)}, {@link #drink(World)}
     * and {@link #gift(World)} so food / water / gift items visibly
     * come from off-screen rather than popping into existence under
     * the pet.
     *
     * <p>The "reach" is implemented by sliding the panel half a
     * {@link #petSize} past the monitor edge; the {@link Stage} canvas
     * clips it so the pet really does disappear behind the edge for the
     * grab beat. The prop is attached only after the pet steps back into
     * view, so the user sees an empty-handed reach followed by a
     * full-handed return.
     *
     * <p>On any interruption (hover, click, engine shutdown) the
     * partial-off-screen slide is undone before returning so the pet
     * isn't left clipped at the edge.
     *
     * @param world    world used by {@link #walkAlongFloor} (floor
     *                 profile / collisions). Must be non-null.
     * @param propKey  prop key to attach after the grab, e.g.
     *                 {@code "prop/fish"} or {@code "prop/gift"}.
     */
    public void fetchPropFromEdge(World world, String propKey) {
        if (frame == null || disposed || world == null) return;
        Point start = logicalLocation();
        int petW = effectiveWidth();
        Rectangle mon = currentMonitorBounds();
        if (mon == null) {
            // No monitor binding yet (very early in life) — degrade to
            // the original in-place behaviour so the pet still shows
            // the prop.
            if (propKey != null) showProp(propKey);
            return;
        }
        int curMid = start.x + petW / 2;
        int monMid = mon.x + mon.width / 2;
        boolean toLeft = curMid < monMid;
        int edgeX = toLeft ? mon.x : mon.x + mon.width - petSize;
        // Walk to the edge with empty hands.
        walkAlongFloor(world, edgeX);
        if (interrupted() || hovered || clicked.get()) return;
        // Face outward so the reach reads as deliberate. Apply the first
        // frame of the matching walk cycle, same trick used by gift().
        List<String> facing = toLeft ? walkLeftFrames() : walkRightFrames();
        if (!facing.isEmpty()) {
            applySprite(facing.get(0));
        }
        showEmote("sparkle", 350);
        if (interrupted() || hovered || clicked.get()) return;
        // Slide half a frame past the edge so the pet's front half is
        // clipped off behind the monitor boundary.
        int reachDx = Math.max(8, petSize / 2);
        int reachX = toLeft ? edgeX - reachDx : edgeX + reachDx;
        Point reachLoc = new Point(reachX, start.y);
        moveFrameTo(reachLoc.x, reachLoc.y);
        sleepInterruptible(450);
        // Slide back to the edge BEFORE bailing on interrupt so the pet
        // isn't left half-off-screen.
        moveFrameTo(edgeX, start.y);
        if (interrupted() || hovered || clicked.get()) return;
        // Now attach the prop — the pet returns into view holding it.
        if (propKey != null) showProp(propKey);
        sleepInterruptible(150);
        if (interrupted() || hovered || clicked.get()) return;
        // Carry it back to where the pet was before fetching.
        walkAlongFloor(world, start.x);
    }

    /**
     * Pet-pet handover: pick up a {@code prop/gift}, carry it to the
     * nearest sibling, turn to face them, and visibly transfer the gift
     * onto the sibling (the box moves from this pet's prop slot to the
     * sibling's). Once received, both the sibling and the giver show the
     * {@code mini-heart} emote together, and both gain AFFECTION.
     *
     * <p>The "transfer" stage is what makes the interaction readable to
     * the user: clearing our prop and only showing a small heart over the
     * other pet (the original behaviour) was easy to miss when the two
     * pets were already adjacent or the heart was occluded — the user
     * just saw a pet lift a gift and "nothing happen". Moving the gift
     * sprite onto the sibling makes the hand-off explicit.
     */
    public void gift(World world) {
        Pet other = nearestOtherPet(PET_COPY_RADIUS);
        if (other == null || other.frame == null) { idle(); return; }
        // Telegraph the intent before fetching so the user can read the
        // run-to-edge as "going to grab a present" rather than random
        // wandering.
        showEmote("gift", 1100);
        if (interrupted() || hovered || clicked.get()) return;
        // Fetch the gift from behind the nearest screen edge instead of
        // conjuring it in place.
        fetchPropFromEdge(world, "prop/gift");
        if (interrupted() || hovered || clicked.get()) { clearProp(); return; }
        if (other.frame == null) { clearProp(); return; }
        int petW = effectiveWidth();
        // Chase loop: the recipient may wander off (its own behaviour
        // thread keeps running while we fetch and walk), so after each
        // approach re-check the gap and walk again if it's grown beyond
        // an adjacent-tolerance. Without this the giver delivers the
        // gift to the recipient's STALE spot from when fetchPropFromEdge
        // started, often visibly far from where the recipient currently
        // stands. Capped so a recipient that keeps moving away (e.g.
        // pacing) doesn't trap the giver in an infinite pursuit.
        final int adjacentTol = Math.max(8, petW / 4);
        final int maxChases = 6;
        boolean fromLeft = false;
        for (int attempt = 0; attempt < maxChases; attempt++) {
            if (interrupted() || hovered || clicked.get()) { clearProp(); return; }
            if (other.frame == null) { clearProp(); return; }
            int otherMid = other.logicalLocation().x + other.effectiveWidth() / 2;
            fromLeft = logicalLocation().x + petW / 2 < otherMid;
            int targetX = fromLeft
                    ? otherMid - other.effectiveWidth() / 2 - petW
                    : otherMid + other.effectiveWidth() / 2;
            Rectangle monC = currentMonitorBounds();
            targetX = Math.max(monC.x, Math.min(monC.x + monC.width - petW, targetX));
            walkAlongFloor(world, targetX);
            if (interrupted() || hovered || clicked.get()) { clearProp(); return; }
            if (other.frame == null) { clearProp(); return; }
            int myMid = logicalLocation().x + petW / 2;
            int curOtherMid = other.logicalLocation().x + other.effectiveWidth() / 2;
            int gap = Math.abs(myMid - curOtherMid) - (petW + other.effectiveWidth()) / 2;
            if (gap <= adjacentTol) break;
        }
        // Recompute facing from FINAL positions so a recipient who
        // ended up on the opposite side mid-chase still gets faced
        // correctly.
        int finalOtherMid = other.logicalLocation().x + other.effectiveWidth() / 2;
        fromLeft = logicalLocation().x + petW / 2 < finalOtherMid;
        // Face the sibling so the hand-off reads as deliberate. Mirrors
        // the trick used by peeTree: apply the first frame of the
        // walk-cycle pointing the right way, then sit on it briefly.
        java.util.List<String> facing = fromLeft ? walkRightFrames() : walkLeftFrames();
        if (!facing.isEmpty()) {
            applySprite(facing.get(0));
        }
        sleepInterruptible(250);
        if (interrupted() || hovered || clicked.get()) { clearProp(); return; }
        // Visible hand-off: gift sprite moves from giver -> receiver. The
        // brief 80 ms gap with no prop on either pet reads as a "pass"
        // rather than the original instantaneous swap.
        clearProp();
        sleepInterruptible(80);
        if (other.frame != null) {
            other.showProp("prop/gift");
        }
        // Both pets react together once the gift is received: a heart
        // emote over the receiver (while it "holds" the gift) AND over the
        // giver, shown simultaneously so the shared moment is unmistakable.
        if (other.frame != null) {
            Sprites.apply(other.emoteLabel, "emote/mini-heart");
        }
        showEmote("mini-heart", 900);
        if (other.frame != null) {
            Sprites.apply(other.emoteLabel, null);
            other.clearProp();
        }
        needs.add(Need.AFFECTION, 15);
        other.needs.add(Need.AFFECTION, 25);
        other.needs.add(Need.BOREDOM, -10);
    }

    /**
     * Grooming variant: approach the sibling and stand <i>behind</i> them
     * (one body-width on the same side as the approach direction), then
     * lick three times. Distinct from {@link Activities#LICK_PET} (sit
     * beside).
     */
    public void groomOther(World world) {
        Pet other = nearestOtherPet(PET_COPY_RADIUS);
        if (other == null) { idle(); return; }
        int petW = effectiveWidth();
        int otherMid = other.logicalLocation().x + other.effectiveWidth() / 2;
        boolean fromLeft = logicalLocation().x < otherMid;
        // Stand on the OPPOSITE side from LICK_PET so the visual reads as
        // "behind" rather than "beside".
        int targetX = fromLeft
                ? otherMid + other.effectiveWidth() / 2
                : otherMid - other.effectiveWidth() / 2 - petW;
        Rectangle mon = currentMonitorBounds();
        targetX = Math.max(mon.x, Math.min(mon.x + mon.width - petW, targetX));
        walkAlongFloor(world, targetX);
        if (interrupted() || hovered || clicked.get()) return;
        for (int i = 0; i < 3; i++) {
            if (interrupted()) return;
            // Flash the recipient's sparkle simultaneously with our lick,
            // not sequentially — see Pet.setEmote/hideEmote.
            other.setEmote("sparkle");
            showEmote("lick", 600);
            other.hideEmote();
            sleepInterruptible(500);
        }
        needs.add(Need.AFFECTION, 15);
        other.needs.add(Need.AFFECTION, 25);
    }

    /**
     * Sympathetic pacing: when a sibling is currently pacing, this pet
     * walks adjacent and runs its own {@link #paceBackAndForth(World)}
     * loop, so both pets pace in roughly the same area.
     */
    public void parallelPace(World world) {
        Pet other = nearestOtherPet(PET_COPY_RADIUS);
        if (other == null) { idle(); return; }
        int petW = effectiveWidth();
        int otherMid = other.logicalLocation().x + other.effectiveWidth() / 2;
        boolean fromLeft = logicalLocation().x < otherMid;
        int approachX = fromLeft
                ? otherMid - other.effectiveWidth() / 2 - petW * 2
                : otherMid + other.effectiveWidth() / 2 + petW;
        Rectangle mon = currentMonitorBounds();
        approachX = Math.max(mon.x, Math.min(mon.x + mon.width - petW, approachX));
        walkAlongFloor(world, approachX);
        if (interrupted() || hovered || clicked.get()) return;
        paceBackAndForth(world);
    }

    /**
     * Visitor-bird reaction for species that don't actively hunt birds
     * (dog, ducky). Pet freezes, shows {@code bang} then {@code question}
     * — it noticed the intruder but isn't chasing.
     */
    public void birdWarning() {
        sit();
        if (interrupted() || hovered || clicked.get()) return;
        showEmote("bang", 500);
        sleepInterruptible(450);
        if (interrupted()) return;
        showEmote("question", 800);
        sleepInterruptible(700);
    }

    /**
     * Top-of-hour vocalisation: emits the per-species {@link #randomSound()}
     * in a speech bubble. Cosmetic; same call as the {@link Activities#SPEAK}
     * verb but priority lambda gates this to the first minute of each hour.
     */
    public void hourlyBark() {
        speak(randomSound(), 1500L, Integer.MIN_VALUE);
    }

    /** Search radius (logical px) within which a sibling pet may steal the ball. */
    @SuppressWarnings("unused")
    private static final int BALL_STEAL_RADIUS = 900;
    /** Per-bounce probability (percent) that a nearby sibling steals the ball. */
    @SuppressWarnings("unused")
    private static final int BALL_STEAL_CHANCE_PCT = 25;

    /**
     * Kick the ball out into the world: the pet shows the {@code paw} bat
     * emote and spawns a new standalone {@link Ball} window at its side
     * with an outward impulse. The Ball is a world object — once spawned,
     * every nearby pet (see {@link Ball#INTEREST_RADIUS}) sees it via
     * {@link Ball#active()} and will run to chase / re-kick it via
     * {@link #chaseBall}; a multi-pet scrum emerges naturally. The
     * follow-up chase is driven by {@link BehaviorEngine}, which on every
     * tick checks for an active ball before falling through to normal
     * activity selection.
     *
     * <p>Ball size scales with the pet (~30\u202F% of {@code petSize}) and
     * its bottom is aligned to the pet's feet line so it rests on the
     * same surface (desktop, taskbar, or topmost window top) the pet is
     * standing on. Restores {@link Need#BOREDOM}; the chase loop adds more
     * each kick.
     */
    public void playBall(World world) {
        Rectangle mon = currentMonitorBounds();
        // Don't double-spawn: if a ball already exists on THIS monitor,
        // just chase it. A ball on a different monitor doesn't concern
        // us \u2014 each screen runs its own independent play session.
        Ball existing = Ball.active();
        if (existing != null) {
            if (existing.monitor().equals(mon)) {
                chaseBall(world, existing);
            }
            return;
        }
        // Honour this monitor's post-play quiet period even when this
        // method is invoked outside the normal Activities.PLAY_BALL
        // priority gate (e.g. future direct callers / tests). Without an
        // active ball to chase, just idle out the activity.
        if (Ball.playCooldownRemainingMs(mon) > 0) {
            return;
        }

        int petW = effectiveWidth();
        Point myLoc = logicalLocation();
        int myMid = myLoc.x + petW / 2;

        // Ball is ~30% of pet size so it's clearly visible at any scale
        // but still reads as "kickable object next to the pet".
        int ballSize = Math.max(16, (int) (petSize * 0.30));

        // Kick AWAY from the closest monitor edge so the ball has runway
        // to roll across the screen instead of immediately wall-bouncing.
        boolean kickRight = (myMid - mon.x) < (mon.x + mon.width - myMid);

        // Spawn the ball at the pet's feet, just outside the pet's frame
        // on the kick side. yFloor = the pet's current feet Y so the
        // ball's bottom rests on the same surface (desktop / taskbar /
        // window top) the pet is standing on.
        int feetH = Math.max(1, (int) Math.round(petSize * feetYRatio()));
        int feetY = myLoc.y + feetH;
        int ballX = kickRight ? myLoc.x + petW : myLoc.x - ballSize;

        // Face the ball and bat-emote BEFORE swinging the leg so the
        // cause→effect reads correctly. The ball is spawned NOW (before
        // the animation) so it is visibly next to the pet during the
        // wind-up; the impulse is applied at the contact frame inside
        // animateKick via the onContact callback. Spawning the ball
        // first and only kicking on contact also prevents the ball from
        // drifting away during the swing ("kicked from far away" bug).
        showEmote("paw", 220);
        Ball ball = Ball.spawn(mon, ballX, feetY, ballSize,
                (int) screen.getWidth(), (int) screen.getHeight());
        final boolean kickRightFinal = kickRight;
        animateKick(kickRight, 180, () -> {
            double impulse = (kickRightFinal ? 1 : -1)
                    * (700 + ThreadLocalRandom.current().nextDouble(0, 500));
            ball.kick(impulse);
        });
        if (interrupted()) return;
        needs.add(Need.BOREDOM, 25);
        // Don't enter chase here — BehaviorEngine's per-tick ball bypass
        // will route THIS pet (and every other nearby one) into chaseBall
        // on the next tick, so the soccer scrum is uniform.
    }

    /**
     * Chase / kick the active world-object {@link Ball}: run toward the
     * ball's current column, and when within kick range bat it away with
     * the {@code paw} emote. Direction of kick is biased toward another
     * pet if one is in range (so the ball bounces between players like
     * soccer), else just in the pet's facing direction.
     *
     * <p>This method runs ONE chase segment per call (either a run or a
     * kick) and returns; {@link BehaviorEngine}'s per-tick ball bypass
     * keeps re-invoking it as long as the ball exists and this pet is
     * still interested, so multiple pets can interleave kicks naturally.
     */
    public final void chaseBall(World world, Ball ball) {
        ball.noteInterest();
        // Use the SPRITE'S opaque bbox, not the JFrame size. The frame is
        // petSize x petSize but the actual pet body is a transparent-
        // padded subregion of that (varies per species/pose: a dog with
        // a long tail has a wider body than a sitting cat, etc.). If we
        // measured kick distance from frame edges, a pet would "kick
        // from a distance" by the width of its transparent padding.
        Rectangle body = bodyBoundsOnScreen();
        int petW = body.width;
        int petH = body.height;
        int myMid = body.x + petW / 2;
        int ballMid = ball.centerX();
        // Edge-to-edge gap between pet body bbox and ball bbox along X.
        // Use this (not center distance) to decide whether we're adjacent
        // enough to kick — otherwise a large pet next to a small ball
        // could "kick from a distance" because center-to-center is still
        // within the combined-radii sum even when the bboxes don't
        // touch.
        int edgeGap = Math.abs(ballMid - myMid) - (petW + ball.width()) / 2;
        int dx = ballMid - myMid;
        // Vertical alignment gate: the pet's feet must be on (or very
        // near) the same floor as the ball's bottom. Without this, a pet
        // standing on a window perch or the taskbar could "kick" a ball
        // rolling along the desktop floor far below it whenever their X
        // columns happened to align ("pets kick the ball when they are
        // not standing on top of it"). Tolerance scales with the pet so
        // bigger pets get a bigger reach.
        int myFeetY = body.y + petH;
        int ballBottomY = ball.centerY() + ball.width() / 2;
        int yTol = Math.max(8, petH / 4);
        boolean sameFloor = Math.abs(myFeetY - ballBottomY) <= yTol;

        if (edgeGap <= 4 && sameFloor) {
            boolean ballMovingAway =
                    (dx > 0 && ball.vx() > 0) || (dx < 0 && ball.vx() < 0);
            if (ballMovingAway && !ball.isAtRest()) {
                // Ball is fleeing — let it; just face it and pause briefly.
                List<String> faceFrames = (dx >= 0) ? walkRightFrames() : walkLeftFrames();
                if (!faceFrames.isEmpty()) {
                    applySprite(faceFrames.get(0));
                }
                sleepInterruptible(120);
                return;
            }
            // Kick! Direction: toward the nearest other pet if any, else
            // continue rolling the ball further in its current direction
            // (or, if at rest, away from the closest monitor edge).
            boolean kickRight;
            Pet target = nearestOtherPet(Ball.INTEREST_RADIUS);
            if (target != null) {
                int targetMid =
                        target.logicalLocation().x + target.effectiveWidth() / 2;
                kickRight = targetMid > ballMid;
            } else if (!ball.isAtRest()) {
                kickRight = ball.vx() > 0;
            } else {
                Rectangle mon = currentMonitorBounds();
                kickRight = (ballMid - mon.x) < (mon.x + mon.width - ballMid);
            }
            List<String> kickFaceFrames = kickRight ? walkRightFrames() : walkLeftFrames();
            if (!kickFaceFrames.isEmpty()) {
                applySprite(kickFaceFrames.get(0));
            }
            showEmote("paw", 180);
            final boolean kickRightFinal = kickRight;
            // 3-frame leg-swing wind-up before the impulse, so the kick
            // VISIBLY swings a leg instead of just freezing on walk[0]
            // ("ball kicking sprites seem missing"). The ball launches
            // on the contact frame (mid-swing) — not after the full
            // animation — so the ball doesn't drift away from the pet
            // during the swing under its own rolling physics ("pets
            // kicked it from far away").
            animateKick(kickRight, 180, () -> {
                double impulse = (kickRightFinal ? 1 : -1)
                        * (600 + ThreadLocalRandom.current().nextDouble(0, 500));
                ball.kick(impulse);
            });
            needs.add(Need.BOREDOM, 12);
            sleepInterruptible(80);
            return;
        }

        // Not close enough — run toward the ball's current column.
        // Aim so the pet's BODY CENTER lands on the ball center, so the
        // sprite visibly stands on top of / overlaps the ball when it
        // arrives instead of stopping next-to or beneath it ("pets stand
        // beneath the ball when kicking — let the sprites overlap").
        // We compensate for the body's offset within its frame; if we
        // aimed the FRAME center at the ball, the body would land
        // {leftPad - rightPad}/2 pixels off-center.
        Rectangle mon = currentMonitorBounds();
        int frameW = petLabel.getWidth();
        if (frameW <= 0) {
            frameW = petSize;
        }
        int leftPad = body.x - logicalLocation().x;
        // Same target regardless of approach direction — we want full
        // overlap, not edge alignment. runAlongFloor handles the facing
        // direction (pet faces the way it walks); the subsequent kick
        // block then re-faces the pet toward the kick direction via
        // applySprite(walkRight/LeftFrames.get(0)).
        int targetX = ballMid - petW / 2 - leftPad;
        targetX = Math.max(mon.x, Math.min(mon.x + mon.width - frameW, targetX));
        runAlongFloor(world, targetX);
    }

    /**
     * True if this pet is eligible to chase the given {@link Ball} on its
     * next tick: same monitor, within {@link Ball#INTEREST_RADIUS} of the
     * ball's center, not a visitor (visitors have their own behaviour
     * loop), not currently hovered/clicked, and no need is urgent (urgent
     * self-care always wins over play). Birds are excluded because they
     * fly — kicking a ball with a beak from above looks silly and would
     * need its own animation pass.
     */
    public final boolean canChaseBall(Ball ball) {
        if (ball == null || ball.isDisposed()) return false;
        if (isVisitor()) return false;
        if (this instanceof Bird) return false;
        if (hovered || clicked.get()) return false;
        if (needs.lowestBelow(15.0) != null) return false;
        Rectangle myMon = currentMonitorBounds();
        Rectangle bm = ball.monitor();
        if (myMon.x != bm.x || myMon.y != bm.y) return false;
        int myMid = logicalLocation().x + effectiveWidth() / 2;
        return Math.abs(myMid - ball.centerX()) <= Ball.INTEREST_RADIUS;
    }

    /**
     * True if this pet is eligible to hunt the live mouse-scurry prop (see
     * {@link MouseQuarry}) on its next tick: a resident (not the hidden
     * carrier / a visitor), a ground species (flying {@link Bird}s are
     * excluded — swooping on a floor mouse would need its own animation),
     * on the mouse's monitor, not hovered/clicked, and with no urgent need
     * (self-care wins over play). Cats are the natural mouser but every
     * eligible ground resident gives chase, so the mouse always "gets
     * hunted" when one is present.
     */
    public final boolean canHuntMouse(MouseQuarry q) {
        if (q == null || q.caught()) return false;
        if (isVisitor()) return false;
        if (this instanceof Bird) return false;
        if (hovered || clicked.get()) return false;
        if (needs.lowestBelow(15.0) != null) return false;
        Rectangle myMon = currentMonitorBounds();
        Rectangle qm = q.monitor();
        if (myMon.x != qm.x || myMon.y != qm.y) return false;
        int myMid = logicalLocation().x + effectiveWidth() / 2;
        return Math.abs(myMid - q.centerX()) <= MouseQuarry.INTEREST_RADIUS;
    }

    /**
     * Chase the live mouse-scurry prop ({@link MouseQuarry}). Mirrors
     * {@link #chaseBall}: run toward the mouse's current column and, when
     * adjacent on the same floor, pounce with a paw emote. Closing in
     * {@link MouseQuarry#startle startles} the mouse so it bolts away,
     * turning the approach into a proper chase; a small chance per pounce
     * "catches" it (chomp emote) and ends the cinematic early. Called once
     * per behaviour tick from {@link BehaviorEngine} (bypassing normal
     * activity selection) while a mouse is in play and {@link #canHuntMouse}
     * holds.
     */
    public final void huntMouse(World world, MouseQuarry q) {
        // Stalking face — a non-blocking emote so the long run below isn't
        // delayed; BehaviorEngine clears it when the chase ends.
        setEmote("target");

        Rectangle body = bodyBoundsOnScreen();
        int petW = body.width;
        int petH = body.height;
        int myMid = body.x + petW / 2;
        int mouseMid = q.centerX();
        int dx = mouseMid - myMid;
        int edgeGap = Math.abs(dx) - (petW + q.sizePx()) / 2;
        int myFeetY = body.y + petH;
        int yTol = Math.max(12, petH / 3);
        boolean sameFloor = Math.abs(myFeetY - q.feetY()) <= yTol;
        // Once this close the mouse panics and bolts.
        int notice = Math.max(260, petW * 4);

        if (edgeGap <= 6 && sameFloor) {
            // Pounce in place, facing the mouse.
            List<String> face = (dx >= 0) ? walkRightFrames() : walkLeftFrames();
            if (!face.isEmpty()) {
                applySprite(face.get(0));
            }
            showEmote("paw", 200);
            q.startle(myMid);
            // Small chance to actually catch it: chomp + end the cinematic.
            if (ThreadLocalRandom.current().nextInt(100) < 12) {
                q.markCaught();
                showEmote("chomp", 600);
                needs.add(Need.BOREDOM, 45);
            } else {
                needs.add(Need.BOREDOM, 12);
            }
            sleepInterruptible(170);
            return;
        }

        // Not adjacent — run toward the mouse's current column, aiming the
        // BODY CENTER onto the mouse so the sprite overlaps it on arrival
        // (same overlap trick as chaseBall).
        if (Math.abs(dx) <= notice) {
            q.startle(myMid); // close enough that the mouse notices the predator
        }
        Rectangle mon = currentMonitorBounds();
        int frameW = petLabel.getWidth();
        if (frameW <= 0) {
            frameW = petSize;
        }
        int leftPad = body.x - logicalLocation().x;
        int targetX = mouseMid - petW / 2 - leftPad;
        targetX = clampInt(targetX, mon.x, mon.x + mon.width - frameW);
        runAlongFloor(world, targetX);
    }

    /**
     * True if this pet is eligible to chase the live laser dot
     * ({@link LaserQuarry}) on its next tick: a resident (not the hidden
     * carrier / a visitor), a {@link Cat} specifically (chasing a laser dot is
     * a cat thing, so dogs/ducks ignore it), on the dot's monitor, not
     * hovered/clicked, and with no urgent need (self-care wins over play).
     */
    public final boolean canChaseLaser(LaserQuarry q) {
        if (q == null) return false;
        if (isVisitor()) return false;
        if (!(this instanceof Cat)) return false;
        if (hovered || clicked.get()) return false;
        if (needs.lowestBelow(15.0) != null) return false;
        Rectangle myMon = currentMonitorBounds();
        Rectangle qm = q.monitor();
        return myMon.x == qm.x && myMon.y == qm.y;
    }

    /**
     * Chase the live laser dot ({@link LaserQuarry}). Mirrors {@link #huntMouse}:
     * run toward the dot's current column and, when adjacent, pounce with a paw
     * emote. The dot never gets caught (it blinks out on its own); this just
     * makes a resident cat give frantic chase. Called once per behaviour tick
     * from {@link BehaviorEngine} (bypassing normal activity selection) while a
     * dot is in play and {@link #canChaseLaser} holds.
     */
    public final void chaseLaser(World world, LaserQuarry q) {
        // Stalking face — non-blocking so the run below isn't delayed;
        // BehaviorEngine clears it when the chase ends.
        setEmote("target");

        Rectangle body = bodyBoundsOnScreen();
        int petW = body.width;
        int myMid = body.x + petW / 2;
        int dotMid = q.centerX();
        int dx = dotMid - myMid;
        int edgeGap = Math.abs(dx) - (petW + q.sizePx()) / 2;

        if (edgeGap <= 6) {
            // Pounce in place, facing the dot.
            List<String> face = (dx >= 0) ? walkRightFrames() : walkLeftFrames();
            if (!face.isEmpty()) {
                applySprite(face.get(0));
            }
            showEmote("paw", 180);
            needs.add(Need.BOREDOM, 10);
            sleepInterruptible(150);
            return;
        }

        // Not adjacent — run toward the dot's current column, aiming the BODY
        // CENTER onto the dot so the sprite overlaps it on arrival (same trick
        // as huntMouse / chaseBall).
        Rectangle mon = currentMonitorBounds();
        int frameW = petLabel.getWidth();
        if (frameW <= 0) {
            frameW = petSize;
        }
        int leftPad = body.x - logicalLocation().x;
        int targetX = dotMid - petW / 2 - leftPad;
        targetX = clampInt(targetX, mon.x, mon.x + mon.width - frameW);
        runAlongFloor(world, targetX);
    }

    /**
     * Pee against the global {@link Tree}: walk over to it, face the trunk,
     * sit, and emit a few {@code splash} emotes. Spawns the tree on the
     * nearest monitor edge if no tree is currently active (the
     * {@link Activities#PEE_TREE} priority gate guarantees the caller is
     * within {@link Tree#EDGE_PROXIMITY_PX} of an edge in that case). Birds
     * and visitors should not call this; {@link Activities#PEE_TREE}
     * filters them out.
     */
    public void peeAgainstTree(World world) {
        Tree tree = Tree.active();
        if (tree == null || tree.isEnding()) {
            // No active tree (or it's already fading out) — summon one
            // on the side of the screen we're closer to. The activity's
            // priority gate already verified we're near an edge.
            Rectangle mon = currentMonitorBounds();
            int petW = effectiveWidth();
            int myMid = logicalLocation().x + petW / 2;
            boolean leftEdge = (myMid - mon.x) <= (mon.x + mon.width - myMid);
            // Cap tree at 1.5x pet height so it never looms ridiculously
            // large on small monitors / large pets. Width follows the
            // tree.svg aspect ratio (~0.6 wide:tall).
            int treeH = (int) Math.round(petSize * 1.5);
            int treeW = Math.max(48, (int) Math.round(treeH * 0.6));
            // Tree base Y must be in LOGICAL pixels (frame.setLocation is
            // logical). world.taskbar() comes from raw Win32 and is in
            // PHYSICAL pixels on Windows (per-monitor DPI aware V2), so
            // using it directly placed the tree at a Y too small for the
            // logical screen and the tree appeared to float. Use AWT's
            // per-monitor screen insets instead — those are reported in
            // logical pixels, so subtracting them from the monitor bottom
            // gives the true work-area floor regardless of DPI scaling or
            // wherever the pet happens to be standing right now.
            java.awt.GraphicsConfiguration gc = null;
            for (java.awt.GraphicsDevice d : java.awt.GraphicsEnvironment
                    .getLocalGraphicsEnvironment().getScreenDevices()) {
                java.awt.GraphicsConfiguration cfg = d.getDefaultConfiguration();
                Rectangle b = cfg.getBounds();
                if (b.x == mon.x && b.y == mon.y && b.width == mon.width && b.height == mon.height) {
                    gc = cfg;
                    break;
                }
            }
            int baseY;
            if (gc != null) {
                java.awt.Insets ins = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc);
                baseY = mon.y + mon.height - ins.bottom;
            } else {
                baseY = logicalLocation().y
                        + (int) Math.round(effectiveHeight() * feetYRatio());
            }
            tree = Tree.spawn(mon, leftEdge, baseY, treeW, treeH);
            // EDT init may fail (headless / shutdown) — give up gracefully.
            if (tree == null || tree.isDisposed()) {
                idle();
                return;
            }
        }
        tree.noteInterest();

        // Walk to the side of the trunk on our approach side.
        int petW = effectiveWidth();
        int trunkX = tree.trunkCenterX();
        int myMid0 = logicalLocation().x + petW / 2;
        boolean fromLeft = myMid0 < trunkX;
        Rectangle mon = currentMonitorBounds();
        int targetX = fromLeft
                ? trunkX - tree.width() / 2 - petW + 4
                : trunkX + tree.width() / 2 - 4;
        targetX = Math.max(mon.x, Math.min(mon.x + mon.width - petW, targetX));
        walkAlongFloor(world, targetX);
        if (interrupted()) return;
        tree.noteInterest();

        // Face the trunk.
        java.util.List<String> facing = fromLeft ? walkRightFrames() : walkLeftFrames();
        if (!facing.isEmpty()) {
            applySprite(facing.get(0));
            sleepInterruptible(200);
            if (interrupted()) return;
        }

        // Pee: hold the dedicated pee pose (idle body + yellow stream
        // and puddle overlay) and drip splash emotes for a few beats.
        // The sprite is direction-agnostic — the trunk side was set up
        // by walkAlongFloor above, and the splash emote sits on top.
        applySprite(doodleKind() + "/pee");
        for (int i = 0; i < 4; i++) {
            if (interrupted() || hovered || clicked.get()) return;
            showEmote("splash", 600);
            sleepInterruptible(500);
            tree.noteInterest();
        }
        showEmote("drop", 500);
        sleepInterruptible(200);
        // Real-world logic: emptying the bladder costs a bit of THIRST
        // (more thirsty afterwards), and the visit is a refreshing break
        // — small BOREDOM relief.
        needs.add(Need.BOREDOM, -10);
        needs.add(Need.THIRST,  -6);
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
            if (interrupted() || hovered || clicked.get()) {
                break;
            }
            if ((i - 1) % pps == 0) {
                applySprite(frames.get(spriteIndex % frames.size()));
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
        // Only the pet's body sprite is hue-rotated; emote / heart / prop
        // labels keep their canonical colours so e.g. red hearts stay red.
        double hue = (label == petLabel) ? hueShift : 0.0;
        boolean trackPose = (label == petLabel);
        for (String f : frames) {
            if (interrupted()) return;
            Sprites.apply(label, f, hue);
            if (trackPose) {
                this.lastPetSpriteKey = f;
            }
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
     * intendedY)} in virtual-desktop coordinates. The pet's panel lives
     * on a per-monitor {@link Stage} canvas; {@link PetWindow#setLocation}
     * routes the screen point to the matching stage and Swing clips the
     * panel to the canvas bounds, so the pet visibly slides off a monitor
     * edge without ever straddling two monitors.
     */
    public final void moveFrameTo(int intendedX, int intendedY) {
        if (frame == null || disposed) {
            return;
        }
        // Cheap no-op fast path: pet threads call moveFrameTo every
        // behaviour tick even when nothing moves (idle holds, sit,
        // sleep). Skipping the CAS + invokeLater when the intended
        // position is unchanged saves a substantial amount of churn for
        // stationary pets.
        if (intendedX == this.intendedX && intendedY == this.intendedY) {
            return;
        }
        // Record the intended logical position FIRST so logicalLocation()
        // is always up to date even if the EDT call below is deferred or
        // the frame ends up hidden (visible width = 0).
        this.intendedX = intendedX;
        this.intendedY = intendedY;
        // Coalesce: at most ONE drainMoveOnEdt runnable per pet may be
        // queued at a time. The pet thread may call moveFrameTo at full
        // tilt (10..30 Hz), but the drainer reads the latest intendedX/Y
        // when it actually runs. This bounds the EDT queue to at most N
        // (one runnable per pet) regardless of how fast pet threads tick,
        // which was the root cause of EDT saturation with 15+ pets:
        // Window.setBounds is a synchronous native SetWindowPos call and
        // can take 1-3 ms each, so an uncoalesced queue grew faster than
        // it drained and the sprites visibly "froze" for seconds at a
        // time. See the VisualVM profile dated 2026-05-26.
        if (moveQueued.compareAndSet(false, true)) {
            onEdt(this::drainMoveOnEdt);
        }
    }

    private final AtomicBoolean moveQueued = new AtomicBoolean(false);

    /** EDT-only drainer for {@link #moveFrameTo}. Reads the latest
     *  intendedX/Y/petSize snapshot and applies it via
     *  {@link PetWindow#setLocation(int, int)} (which routes through the
     *  {@link Stage} canvas; Swing clips the panel to the stage canvas
     *  bounds automatically, so no per-pet clipping is needed).
     *  Skips entirely when the position is identical to what was last
     *  pushed to the stage. */
    private void drainMoveOnEdt() {
        // Reset the queued flag FIRST so any moveFrameTo call that
        // arrives during this run will re-enqueue and pick up its newer
        // target on the next drain.
        moveQueued.set(false);
        if (disposed || frame == null) {
            return;
        }
        final int x = this.intendedX;
        final int y = this.intendedY;
        if (x == lastAppliedX && y == lastAppliedY) {
            return;
        }
        lastAppliedX = x;
        lastAppliedY = y;
        frame.setLocation(x, y);
    }

    /** Last position actually pushed to the stage. Used by
     *  {@link #moveFrameTo} to coalesce no-op ticks; see the javadoc there
     *  for the reasoning. */
    private int lastAppliedX = Integer.MIN_VALUE;
    private int lastAppliedY = Integer.MIN_VALUE;

    /**
     * The pet's intended logical position (top-left of a notional
     * petSize×petSize box). Prefer this over {@code frame.getLocation()}
     * for any position-arithmetic.
     */
    public final Point logicalLocation() {
        return new Point(intendedX, intendedY);
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
    /** Monitor the pet is currently bound to for behavior purposes (floor
     *  Y, walk-target bounds, edge-wiggle limits, entry-walk anchors).
     *  Set on spawn and on DISAPPEAR_REAPPEAR re-entry. Render-time
     *  clipping to this rect is no longer needed: pets paint onto the
     *  per-monitor {@link Stage} canvas and Swing clips them naturally
     *  to the canvas bounds. */
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

    // ---------------- suspend / resume (hide animation) ----------------

    /** Set by {@link #requestHide()}; consumed by {@link #runPendingHideShow}
     *  on the behaviour thread, which performs an off-screen walk and then
     *  flips {@link #hidden} to {@code true}. */
    private volatile boolean hideRequested = false;
    /** Set by {@link #requestShow()}; consumed by {@link #runPendingHideShow}
     *  to walk the pet back in from its exit edge and flip {@link #hidden}
     *  back to {@code false}. */
    private volatile boolean showRequested = false;
    /** {@code true} while the pet is parked off-screen after a successful
     *  hide animation. {@link BehaviorEngine} skips all normal behaviour
     *  (including topmost reassertion) while this is set. */
    private volatile boolean hidden = false;
    /** Logical X to walk back to on resume — captured at the start of the
     *  hide walk so the pet returns roughly where it was suspended. */
    private volatile Integer hiddenReturnX = null;
    /** Set by {@link #requestHideAndDispose()}: once the pending hide walk
     *  completes, {@link #runPendingHideShow} disposes the window and flags
     *  {@link #exitRequested} so {@link BehaviorEngine} exits its loop. */
    private volatile boolean disposeAfterHide = false;
    /** Set after the dispose-on-hide flow finishes (or directly by
     *  {@link #requestHideAndDispose()} when the pet is already hidden).
     *  {@link BehaviorEngine} polls this and breaks out of its run loop so
     *  the pet thread terminates without leaking. */
    private volatile boolean exitRequested = false;

    /**
     * Request a non-destructive off-screen exit. Cheap and idempotent: a
     * no-op if the pet is already hidden or already requested. The actual
     * walk runs on the behaviour thread inside
     * {@link #runPendingHideShow(World)} so it composes with the normal
     * locomotion + monitor-clipping path. Safe from any thread.
     */
    public final void requestHide() {
        if (hidden || hideRequested) return;
        showRequested = false;
        hideRequested = true;
    }

    /**
     * Request a return from a previous {@link #requestHide()}. No-op if the
     * pet is already visible. Safe from any thread.
     */
    public final void requestShow() {
        if (!hidden && !hideRequested) return;
        hideRequested = false;
        showRequested = true;
    }

    /** True while the pet is parked off-screen (between hide and show). */
    public final boolean isHidden() {
        return hidden;
    }

    /**
     * Request a non-destructive off-screen exit followed by permanent
     * disposal of the window and termination of the behaviour thread.
     * Used by {@link PetSupervisor#reconcileCounts} when the desired pet
     * count shrinks (e.g. on leaving a Teams meeting) so the soon-to-be-
     * removed pets walk out of view instead of vanishing in place. Cheap
     * and idempotent; safe from any thread.
     *
     * <p>If the pet is already hidden (parked off-screen from a previous
     * {@link #requestHide()}), the window is disposed immediately and the
     * behaviour thread is signalled to exit on its next tick — no second
     * walk-off animation, because the pet is not visible anyway.
     */
    public final void requestHideAndDispose() {
        if (exitRequested) {
            return;
        }
        disposeAfterHide = true;
        if (hidden) {
            // Already off-screen — just clean up. The behaviour thread is
            // sleeping inside the hidden-poll branch of BehaviorEngine; the
            // exitRequested flag we set below will make it break out of
            // the loop on its next 250 ms wake-up.
            disposeWindow();
            exitRequested = true;
            return;
        }
        requestHide();
    }

    /**
     * True once {@link #requestHideAndDispose()} has finished its exit walk
     * (or skipped it because the pet was already hidden) and the pet is
     * ready to have its behaviour thread terminated. {@link BehaviorEngine}
     * polls this once per tick and breaks out of its run loop when set.
     */
    public final boolean isExitRequested() {
        return exitRequested;
    }

    /**
     * Called once per {@link BehaviorEngine} tick. Synchronously executes
     * a hide- or show-walk if one is pending; otherwise returns immediately.
     * The walks use {@link #walkAlongFloor}, so {@link #moveFrameTo}'s
     * monitor clipping naturally shrinks the JFrame to zero width as the
     * pet crosses the edge.
     */
    public final void runPendingHideShow(World world) {
        if (hideRequested && !hidden) {
            hideRequested = false;
            Rectangle mon = activeMonitor;
            if (mon == null || frame == null) {
                // Nothing visible to animate — just flip the state so the
                // engine stops behaving.
                hidden = true;
                if (disposeAfterHide) {
                    disposeWindow();
                    exitRequested = true;
                }
                return;
            }
            int petW = effectiveWidth();
            int curX = logicalLocation().x;
            hiddenReturnX = curX;
            int curMid = curX + petW / 2;
            int leftDist  = curMid - mon.x;
            int rightDist = (mon.x + mon.width) - curMid;
            boolean exitRight = rightDist <= leftDist;
            int distToEdge = exitRight ? rightDist : leftDist;
            if (distToEdge <= EDGE_RUN_OFF_DIST_PX) {
                // Already near a side: a short horizontal walk off the
                // nearer edge reads naturally and keeps ground contact.
                int exitX = exitRight
                        ? (mon.x + mon.width + 4)
                        : (mon.x - petW - 4);
                walkAlongFloor(world, exitX);
            } else {
                // Mid-monitor: walking all the way to an edge would feel
                // like a long, listless exit. Mirror the perch-recovery
                // path and leap off the nearer side in a parabolic arc
                // that carries the pet up + sideways and then off the
                // bottom of the screen. This is what
                // knockedOffPerchAndRespawn uses for the same "I need
                // to get off-screen from far away" situation, so the
                // visual vocabulary stays consistent.
                jumpOffEdge(mon, exitRight);
            }
            hidden = true;
            if (disposeAfterHide) {
                // Pet is now off-screen and will not come back: tear down
                // the window and let BehaviorEngine exit on its next tick.
                disposeWindow();
                exitRequested = true;
            }
            return;
        }
        if (showRequested && hidden) {
            showRequested = false;
            hidden = false;
            Integer ret = hiddenReturnX;
            hiddenReturnX = null;
            if (ret == null || activeMonitor == null) return;
            walkAlongFloor(world, ret);
        }
    }

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
     * Tight bounding box of the pet's currently-rendered body in logical
     * screen coordinates, computed from the opaque pixels of the active
     * sprite (so the transparent margins around the body are excluded).
     *
     * <p>Used by physics/proximity code that would otherwise reach "too
     * far" using the full {@code petSize x petSize} frame. Falls back to
     * the full frame rectangle when no icon is set yet (early ticks).
     */
    public final Rectangle bodyBoundsOnScreen() {
        java.awt.Point loc = logicalLocation();
        int frameX = loc.x;
        int frameY = loc.y;
        int labelX = petLabel.getX();
        int labelY = petLabel.getY();
        int w = effectiveWidth();
        int h = effectiveHeight();
        javax.swing.Icon icon = petLabel.getIcon();
        if (icon == null) {
            return new Rectangle(frameX + labelX, frameY + labelY, w, h);
        }
        Rectangle bb = Sprites.opaqueBounds(icon);
        if (bb.width <= 0 || bb.height <= 0) {
            return new Rectangle(frameX + labelX, frameY + labelY, w, h);
        }
        return new Rectangle(
                frameX + labelX + bb.x,
                frameY + labelY + bb.y,
                bb.width,
                bb.height);
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
        // taskbar that covers it \u2014 BUT only when the taskbar is actually
        // visible at this column. If a higher-Z window has slid on top of
        // the taskbar, drop the cap so the pet falls onto whatever surface
        // is really visible (typically the desktop floor at screenH).
        int monMaxY = effectiveMonitorBottomAtColumn(world, x, petW) - feetH;
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
        MonitorBottom mb = monitorBottomInfoAtColumn(x);
        return mb.workBottom;
    }

    /**
     * Like {@link #monitorBottomAtColumn(int)}, but consults {@code world}
     * to decide whether the AWT bottom inset (taskbar) at column {@code x}
     * is actually still visible. If a higher-Z top-level window covers the
     * column in the taskbar band, the taskbar at this column is occluded
     * \u2014 so the cap is dropped to the raw monitor bottom and the pet is
     * allowed to fall down onto whatever surface {@link World#floorY}
     * resolves (which will skip the hidden taskbar via the same occlusion
     * rule). When the taskbar is visible (or we can't tell), the cap stays
     * at the work-area bottom so pets don't stand on the taskbar wallpaper.
     *
     * <p>Rationale: previously this cap was unconditional, so a pet
     * standing above the taskbar kept floating there even after another
     * app's window slid on top and visually hid the taskbar. Per the
     * "fall onto whatever's actually on top" rule, the pet should drop
     * to the covering window's bottom edge (or to the desktop floor).
     */
    private int effectiveMonitorBottomAtColumn(World world, int x, int petWidth) {
        MonitorBottom mb = monitorBottomInfoAtColumn(x);
        if (mb.workBottom >= mb.monBottom || world == null) {
            return mb.workBottom; // no taskbar inset to cap against, or no world snapshot
        }
        boolean taskbarSeenOnTop = false;
        boolean coveredByHigherZ  = false;
        // Walk topmost windows in z-order, top-first. The first window we
        // encounter that overlaps the pet's column AND extends across the
        // taskbar band is the one actually visible there.
        for (Rectangle r : world.topmostWindows()) {
            if (r.x + r.width <= x || r.x >= x + Math.max(1, petWidth)) {
                continue; // no horizontal overlap at the pet's footprint
            }
            int topY = r.y;
            int botY = r.y + r.height;
            if (botY <= mb.workBottom) {
                continue; // sits entirely above the taskbar band \u2014 doesn't cover it
            }
            // A rect whose top is at-or-below the work-area bottom and
            // whose body sits inside the inset band is the taskbar itself
            // (or a panel occupying that band). If we hit it before any
            // covering window, the taskbar is still on top.
            if (topY >= mb.workBottom - 2) {
                taskbarSeenOnTop = true;
                break;
            }
            // Otherwise: a higher-Z window whose top is above the work
            // area and whose body reaches into the taskbar band \u2014 it
            // covers the taskbar at this column.
            coveredByHigherZ = true;
            break;
        }
        if (coveredByHigherZ) {
            return mb.monBottom; // taskbar hidden \u2014 allow falling all the way down
        }
        // Either the taskbar was seen first (still on top) or we never
        // saw anything overlapping at this column (taskbar list filter or
        // headless). Keep the work-area cap as before.
        return mb.workBottom;
    }

    /** Pair of (monitor bottom, work-area bottom) for a screen column. */
    private record MonitorBottom(int monBottom, int workBottom) {}

    private MonitorBottom monitorBottomInfoAtColumn(int x) {
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
            int monBottom = bestMon.y + bestMon.height;
            int workBottom = monBottom;
            try {
                Insets ins = Toolkit.getDefaultToolkit().getScreenInsets(bestCfg);
                if (ins != null && ins.bottom > 0) {
                    workBottom -= ins.bottom;
                }
            } catch (Throwable t) {
                // ignore \u2014 fall back to raw monitor bottom
            }
            return new MonitorBottom(monBottom, workBottom);
        } catch (Throwable t) {
            int h = (int) screen.getHeight();
            return new MonitorBottom(h, h);
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
        gaitAlongFloor(world, targetX, false, false);
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
        gaitAlongFloor(world, targetX, true, false);
    }

    /**
     * Moonwalk gait: same horizontal traversal as {@link #walkAlongFloor}
     * but the walk-cycle frames are taken from the OPPOSITE-facing
     * direction. Visually the pet slides toward {@code targetX} while its
     * sprite keeps facing the way it came from — the classic Michael
     * Jackson illusion. Uses walk frames (never run) and the standard walk
     * step delay so the gag reads clearly.
     */
    public void moonwalkAlongFloor(World world, int targetX) {
        gaitAlongFloor(world, targetX, false, true);
    }

    /**
     * Shared body of {@link #walkAlongFloor} and {@link #runAlongFloor}.
     * Selects walk vs run frame set + step delay + sprite-step distance based
     * on the {@code running} flag. All collision / jump / floor-profile logic
     * is identical between the two gaits — only sprite + pacing differ.
     *
     * <p>When {@code moonwalk} is true the frame set is taken from the
     * opposite-facing direction so the pet appears to slide backwards
     * while facing the way it came from. Movement, collision and
     * floor-profile logic are unchanged.
     */
    private void gaitAlongFloor(World world, int targetX, boolean running, boolean moonwalk) {
        Point start = logicalLocation();
        int dx = targetX - start.x;
        if (dx == 0) {
            idle();
            return;
        }
        boolean goingRight = dx > 0;
        boolean facingRight = moonwalk ? !goingRight : goingRight;
        List<String> frames = facingRight
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
        // The pet is pinned to {@link #activeMonitor} for the duration of
        // the walk (moveFrameTo clips to it; the pet can't cross monitors
        // mid-walk), so the work-area bottom is constant across all
        // pixel-steps. Sample it ONCE here rather than calling
        // {@link #monitorBottomAtColumn(int)} per step — that method
        // iterates every GraphicsDevice and calls AWT
        // {@code getScreenInsets}, which on Windows reaches into the
        // shell. For a 600-px walk this is 600 native calls; now it's 1.
        //
        // Use the destination column when it lies on a real monitor (the
        // common case — callers clamp targetX to the monitor); otherwise
        // fall back to the start column. For off-monitor exit walks
        // (disappear-reappear) the frame is hidden by moveFrameTo before
        // the cap matters, so even a primary-monitor fallback is safe.
        int monBottomCap;
        {
            Rectangle am = activeMonitor;
            int probeX = targetX;
            if (am != null) {
                int amHi = am.x + am.width - 1;
                if (probeX < am.x) probeX = am.x;
                else if (probeX > amHi) probeX = amHi;
            }
            monBottomCap = effectiveMonitorBottomAtColumn(world, probeX, petW) - feetH;
        }

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
            if (!midJump && (interrupted() || hovered || clicked.get())) {
                break;
            }
            if ((i - 1) % pps == 0) {
                applySprite(frames.get(spriteIndex % frames.size()));
                spriteIndex++;
            }
            int signedStep = goingRight ? i : -i;
            int x = start.x + signedStep;
            int y = world.floorY(feetH, x, petW, currentFeetY);
            if (y > monBottomCap) {
                y = monBottomCap;
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
                    if (interrupted() || hovered || clicked.get()) return;
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
                if (jumpStep >= jumpSpan) {
                    // Arc finished — drop the plan so the next step (or the
                    // next overlap) gets a fresh roll. Latching JUMP past the
                    // arc would silently disable STOP/HUNT/PASS for the rest
                    // of the walk if we're still overlapping a sibling.
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
        if (hovered || clicked.get()) {
            int returnX = Math.max(mon.x, Math.min(mon.x + mon.width - petW, loc.x));
            walkAlongFloor(world, returnX);
            return;
        }
        // Skip the wait if the user is trying to interact, so the pet pops
        // back quickly.
        if (!hovered && !clicked.get()) {
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
            // Force a re-render at the label's current pixel size: bypass
            // the applySprite dedupe by clearing lastPetSpriteKey first.
            onEdt(() -> {
                this.lastPetSpriteKey = null;
                applySprite(first);
            });
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
            if (interrupted() || hovered || clicked.get()) {
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
        if (Thread.currentThread().isInterrupted() || activityAbort.get()) {
            return true;
        }
        if (paused.get()) {
            synchronized (pauseLock) {
                while (paused.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        pauseLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return true;
                    }
                }
            }
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
        if (boundExclusive <= 0) {
            return 0;
        }
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
     * Non-blocking sibling of {@link #showEmote}: sets the emote sprite on
     * this pet without sleeping the caller. Use when one pet wants another
     * pet's emote to flash <i>simultaneously</i> with its own &mdash; the
     * naive sequential pattern
     * <pre>pet.showEmote("bang", 500); other.showEmote("bang", 500);</pre>
     * runs serially on the caller's thread (1 s total, each pet's bang
     * invisible while the other's is up). Use instead:
     * <pre>other.setEmote("bang");
     *pet.showEmote("bang", 500);
     *other.hideEmote();</pre>
     * so both bangs are on screen for the same 500 ms window. Pass {@code
     * null} to clear.
     */
    public final void setEmote(String key) {
        Sprites.apply(emoteLabel, key == null ? null : "emote/" + key);
    }

    /** Clear any emote currently shown on this pet. Counterpart to
     *  {@link #setEmote(String)} for the "simultaneous emote" pattern. */
    public final void hideEmote() {
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
        applySprite(doodleKind() + "/sit");
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
            applySprite(frames.get(i++ % frames.size()));
            sleepInterruptible(frameDelay);
        }
        applySprite(frames.get(0));
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
            applySprite(doodleKind() + "/sit");
            sleepInterruptible(250);
            applySprite(doodleKind() + "/look/0");
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
    public void scratch() {
        Sprites.apply(emoteLabel, "emote/paw");
        applySprite(doodleKind() + "/sit");
        sleepInterruptible(220);
        for (int i = 0; i < 4 && !interrupted(); i++) {
            applySprite(doodleKind() + "/scratch");
            sleepInterruptible(180);
            applySprite(doodleKind() + "/look/1");
            sleepInterruptible(140);
        }
        if (!interrupted()) {
            applySprite(doodleKind() + "/sit");
            sleepInterruptible(180);
        }
        Sprites.apply(emoteLabel, null);
        needs.add(Need.BOREDOM, 20);
    }

    /**
     * Dance loop: cycles the 6 {@code /dance/0..5} body-transform frames
     * (hop, lean-left, big-hop, lean-right, mirror-spin, squat) over a
     * music-note emote. Each frame is the pet's idle body wrapped in a
     * fresh viewBox-space transform (see tools/gen-dance-frames.ps1) so
     * actual movement is visible rather than a 2-frame side-shuffle.
     * At ~180 ms/frame a full cycle is ~1.1 s; 18 frames ≈ three full
     * cycles, long enough to read as a routine without overstaying.
     * Interrupts cleanly on hover/click/external thread interrupt.
     */
    public final void dance() {
        Sprites.apply(emoteLabel, "emote/note");
        for (int i = 0; i < 18 && !interrupted(); i++) {
            applySprite(doodleKind() + "/dance/" + i);
            sleepInterruptible(180);
        }
        Sprites.apply(emoteLabel, null);
        if (!interrupted()) {
            applySprite(doodleKind() + "/sit");
            sleepInterruptible(150);
        }
        needs.add(Need.BOREDOM, 30);
        needs.add(Need.AFFECTION, 10);
    }

    /** Wave / paw-up: holds the {@code stretch} pose with a small bob. */
    public final void wave() {
        Sprites.apply(emoteLabel, "emote/mini-heart");
        for (int i = 0; i < 2 && !interrupted(); i++) {
            applySprite(doodleKind() + "/stretch");
            sleepInterruptible(220);
            applySprite(doodleKind() + "/idle/0");
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
            applySprite(doodleKind() + "/look/" + (i % 2));
            sleepInterruptible(220);
        }
        applySprite(doodleKind() + "/idle/0");
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
            // Seed from the first device rather than an empty (0,0,0,0)
            // Rectangle — Rectangle.union treats the empty seed as a point
            // at the origin and would inflate the width whenever no monitor
            // actually contains (0,0) (e.g. primary at x=1920).
            Rectangle virt = null;
            for (GraphicsDevice d : ge.getScreenDevices()) {
                Rectangle b = d.getDefaultConfiguration().getBounds();
                virt = (virt == null) ? new Rectangle(b) : virt.union(b);
            }
            if (virt != null && virt.width > 0 && virt.height > 0) {
                return new Dimension(virt.width, virt.height);
            }
            return Toolkit.getDefaultToolkit().getScreenSize();
        } catch (Throwable t) {
            return new Dimension(1920, 1080);
        }
    }
}
