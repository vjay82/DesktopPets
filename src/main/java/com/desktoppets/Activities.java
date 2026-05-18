package com.desktoppets;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Catalogue of {@link Activity}s the {@link BehaviorEngine} chooses between
 * each tick. Order in {@link #ALL} is the deterministic tie-break order.
 *
 * <p>Priorities are tuned so that <b>IDLE wins most picks</b> but the engine
 * samples via weighted random rather than argmax, so ambient activities
 * (WANDER, ZOOMIES, DISAPPEAR_REAPPEAR) still fire a fraction of the time —
 * either spontaneously or because a need crossed its threshold
 * (sleep/eat/seek-petting/play-ball). Per-activity cooldowns in
 * {@link BehaviorEngine} cap how often each can repeat.
 *
 * <h2>Where the pet stands</h2>
 * <p>Both IDLE and WANDER read their Y from {@link World#floorY(int,int,int)},
 * which prefers the top of the first visible window (z-order) horizontally
 * overlapping the pet (the shell taskbar falls out of this naturally because
 * it's visible from above), and falls back to the screen bottom. There's no
 * dedicated "climb the foreground window" activity — fullscreen apps are
 * explicitly excluded from the perch list, so the pet won't sit on top of a
 * video player or a maximised browser.
 */
public final class Activities {

    private Activities() {
    }

    public static final Activity SLEEP = new Activity("sleep",
            (pet, world) -> {
                double e = pet.needs.get(Need.ENERGY);
                return e < 25 ? 100 - e : 0;
            },
            (pet, world) -> pet.sleep());

    public static final Activity EAT = new Activity("eat",
            (pet, world) -> {
                double h = pet.needs.get(Need.HUNGER);
                return h < 30 ? 90 - h : 0;
            },
            (pet, world) -> pet.eat());

    public static final Activity SEEK_PETTING = new Activity("seek-petting",
            (pet, world) -> {
                double a = pet.needs.get(Need.AFFECTION);
                // Need-driven ramp when AFFECTION is low, ambient floor 0.35
                // so a content pet still occasionally trots up looking for a pet.
                return a < 30 ? 70 - a : 0.35;
            },
            (pet, world) -> pet.seekPetting());

    /** Only available when an Explorer taskbar is detected. */
    public static final Activity PLAY_BALL = new Activity("play-ball",
            (pet, world) -> {
                if (world.taskbar() == null) {
                    return 0;
                }
                double b = pet.needs.get(Need.BOREDOM);
                // Need-driven ramp when BOREDOM is low, ambient floor 0.5
                // so the ball comes out occasionally even on a fresh-spawned
                // pet (otherwise the user has to watch ~10 min of decay before
                // ever seeing it).
                return b < 35 ? 60 - b : 0.5;
            },
            (pet, world) -> {
                Rectangle bar = world.taskbar();
                if (bar != null) {
                    // Use floorY so feet land on the taskbar top rather than
                    // the full frame extending past it (sprites have foot
                    // padding inside their viewBox — see Pet.clampToScreen).
                    // logicalLocation() (not frame.getX()) so a pet that's
                    // currently edge-clipped doesn't snap to its clipped X.
                    int x = pet.logicalLocation().x;
                    int targetY = pet.floorYAt(world, x);
                    pet.moveFrameTo(x, Math.max(0, targetY));
                }
                pet.playBall();
            });

    /** Fraction of WANDER picks that aim for an existing window's column. */
    private static final double PERCH_BIAS = 0.4;

    /**
     * Occasional ambient walk along the current floor. Base priority is low —
     * IDLE wins most of the time — and {@link BehaviorEngine} enforces a
     * cooldown so the pet doesn't pace the screen continuously.
     *
     * <p>The pet uses {@link Pet#walkAlongFloor} so it samples the floor at
     * every column instead of straight-lining toward the destination Y —
     * otherwise a perch at the top of the screen would make the cat appear
     * to fly diagonally through midair toward it. The bird overrides
     * {@code walkAlongFloor} to keep its diagonal-flight behaviour.
     *
     * <p>With probability {@value #PERCH_BIAS} we pick the target X from
     * inside an existing visible window's horizontal span instead of
     * uniformly across the screen, so pets visibly <i>seek</i> perches when
     * any are available. The rest of the time the X is uniform random.
     *
     * <p>We constrain the target to the bounds of the {@link
     * java.awt.GraphicsDevice} the pet is currently on, so on multi-monitor
     * setups the pet doesn't wander onto an invisible virtual region (e.g.
     * a monitor positioned above or to the side that {@link
     * java.awt.GraphicsEnvironment} unions into the total screen size).
     */
    public static final Activity WANDER = new Activity("wander",
            (pet, world) -> 1.0,
            (pet, world) -> {
                int petW = pet.effectiveWidth();
                Rectangle mon = pet.currentMonitorBounds();
                int monLo = mon.x;
                int monHi = Math.max(monLo + 1, mon.x + mon.width - petW);
                List<Rectangle> perches = world.topmostWindows();
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                int targetX;
                Rectangle chosenPerch = null;
                if (!perches.isEmpty() && rng.nextDouble() < PERCH_BIAS) {
                    // Only consider perches whose horizontal span at least
                    // partially overlaps the current monitor — otherwise the
                    // pet would walk off-monitor toward an unreachable perch.
                    List<Rectangle> onScreen = new ArrayList<>(perches.size());
                    for (Rectangle r : perches) {
                        if (r.x + r.width > monLo && r.x < monLo + mon.width) {
                            onScreen.add(r);
                        }
                    }
                    if (!onScreen.isEmpty()) {
                        chosenPerch = onScreen.get(rng.nextInt(onScreen.size()));
                    }
                }
                if (chosenPerch != null) {
                    int lo = Math.max(monLo, chosenPerch.x);
                    int hi = Math.max(lo + 1, Math.min(monHi, chosenPerch.x + chosenPerch.width));
                    // Guard against perches that lie entirely in the right-edge
                    // clip zone (lo can exceed monHi when the pet is wider than
                    // the perch position allows). Clamp lo so nextInt stays in
                    // the on-monitor range.
                    if (lo >= monHi) {
                        lo = Math.max(monLo, monHi - 1);
                        hi = monHi;
                    }
                    targetX = rng.nextInt(lo, hi);
                } else {
                    targetX = rng.nextInt(monLo, monHi);
                }
                pet.walkAlongFloor(world, targetX);
            });

    /**
     * Ambient "where did the pet go?" moment: the pet walks off the nearest
     * edge of its current monitor, vanishes briefly, then re-enters from a
     * random monitor/side. Low base priority (similar to {@link #WANDER}) so
     * it stays uncommon, and {@link BehaviorEngine} enforces a multi-minute
     * cooldown on top so it doesn't dominate.
     */
    public static final Activity DISAPPEAR_REAPPEAR = new Activity("disappear-reappear",
            (pet, world) -> 0.7,
            (pet, world) -> pet.disappearAndReappear(world));

    /**
     * Sudden burst of energy: 2–4 fast back-and-forth sprints across the
     * monitor. Slightly boredom-biased so a bored pet is more likely to have
     * zoomies, mirroring the real-life phenomenon.
     */
    public static final Activity ZOOMIES = new Activity("zoomies",
            (pet, world) -> {
                double b = pet.needs.get(Need.BOREDOM);
                // 0.7 baseline, plus up to ~0.6 when boredom is rock-bottom.
                return 0.7 + (b < 40 ? (40 - b) * 0.015 : 0);
            },
            (pet, world) -> pet.zoomies(world));

    /**
     * Default behaviour: high base priority so it wins whenever no real need
     * or scheduled wander is firing. Snaps the pet onto the floor for its
     * current X first (so it doesn't float in mid-air when a window underneath
     * it appears, disappears, or is moved), then randomises among basic
     *
     * <p>The settle threshold is {@code petHeight/2} rather than a hard 2 px
     * so the pet doesn't jitter between two overlapping perches whose tops
     * differ by only a few pixels — small floor changes are absorbed, large
     * ones still snap.
     */
    public static final Activity IDLE = new Activity("idle",
            (pet, world) -> 5.0,
            (pet, world) -> {
                // logicalLocation() (not frame.getLocation()) so an
                // edge-clipped pet doesn't think it's at its clipped X/Y
                // and re-settle into the wrong column.
                Point loc = pet.logicalLocation();
                int petH = pet.effectiveHeight();
                int floorY = pet.floorYAt(world, loc.x);
                int hysteresis = Math.max(2, petH / 2);
                if (Math.abs(loc.y - floorY) > hysteresis) {
                    pet.moveFrameTo(loc.x, floorY);
                }
                // Plain idle only — sit/stretch/look are reserved as the
                // visual signature of grooming/wave/crouch-pout/dig/etc., so
                // a random IDLE roll never looks like one of those activities.
                pet.idle();
            });

    // ---------------- new per-pet activities ----------------
    // (gated by Personality.multiplier — 0 disables for a given pet)

    /** Cat: creep toward the cursor X; freezes if cursor jumps. */
    public static final Activity STALK_POINTER = new Activity("stalk-pointer",
            (pet, world) -> World.cursorPos() != null ? 0.6 : 0,
            (pet, world) -> pet.stalkPointer(world));

    /** Cat: leap onto a higher perch within range. */
    public static final Activity HIGH_PERCH_LEAP = new Activity("high-perch-leap",
            (pet, world) -> 0.5,
            (pet, world) -> pet.highPerchLeap(world));

    /** Cat: grooming/preening sit-and-stretch sequence. */
    public static final Activity GROOMING = new Activity("grooming",
            (pet, world) -> {
                double a = pet.needs.get(Need.AFFECTION);
                return 0.4 + Math.max(0, (50 - a) * 0.01);
            },
            (pet, world) -> pet.grooming());

    /** Cat: long-cooldown "knock something off the desk" moment. */
    public static final Activity KNOCK_SOMETHING_OFF = new Activity("knock-something-off",
            (pet, world) -> world.topmostWindows().isEmpty() ? 0 : 0.3,
            (pet, world) -> pet.knockSomethingOff(world));

    /** Dog: runs to the cursor, sits, trots back. */
    public static final Activity FETCH_CURSOR = new Activity("fetch-cursor",
            (pet, world) -> World.cursorPos() != null ? 0.7 : 0,
            (pet, world) -> {
                Point start = pet.logicalLocation();
                pet.showEmote("target", 300);
                pet.walkTowardCursor(world, 30);
                if (pet.interrupted()) return;
                pet.sit();
                if (pet.interrupted()) return;
                pet.walkAlongFloor(world, start.x);
            });

    /** Dog: greet the foreground window when it just changed. */
    public static final Activity GREET_FOREGROUND = new Activity("greet-foreground",
            (pet, world) -> world.foregroundJustChanged() ? 2.0 : 0,
            (pet, world) -> {
                Rectangle fg = world.foreground();
                Rectangle mon = pet.currentMonitorBounds();
                int petW = pet.effectiveWidth();
                int targetX;
                if (fg != null) {
                    targetX = Math.max(mon.x, Math.min(mon.x + mon.width - petW,
                            fg.x + fg.width / 2 - petW / 2));
                } else {
                    targetX = pet.logicalLocation().x;
                }
                pet.walkAlongFloor(world, targetX);
                if (pet.interrupted()) return;
                pet.wave();
            });

    /** Dog: dig in place — short crouch/look cycles. */
    public static final Activity DIG = new Activity("dig",
            (pet, world) -> 0.35,
            (pet, world) -> pet.dig());

    /** Bird: short perch-to-perch hop. */
    public static final Activity FLIT = new Activity("flit",
            (pet, world) -> 0.9,
            (pet, world) -> pet.flit(world, 180));

    /** Bird: long figure-8 across the monitor near the top. */
    public static final Activity CIRCLE = new Activity("circle",
            (pet, world) -> 0.5,
            (pet, world) -> pet.circleFly(world));

    /** Bird: startled by a foreground change nearby — flee to the farthest perch. */
    public static final Activity STARTLE_FLUSH = new Activity("startle-flush",
            (pet, world) -> {
                if (!world.foregroundJustChanged()) return 0;
                Rectangle fg = world.foreground();
                if (fg == null) return 0;
                int petMid = pet.logicalLocation().x + pet.effectiveWidth() / 2;
                int fgMid = fg.x + fg.width / 2;
                return Math.abs(petMid - fgMid) < 200 ? 2.5 : 0;
            },
            (pet, world) -> {
                Rectangle mon = pet.currentMonitorBounds();
                int petW = pet.effectiveWidth();
                int curMid = pet.logicalLocation().x + petW / 2;
                int monMid = mon.x + mon.width / 2;
                int target = curMid < monMid
                        ? mon.x + mon.width - petW - 10
                        : mon.x + 10;
                pet.showEmote("bang", 350);
                pet.walkAlongFloor(world, target);
            });

    /** Bird: sing while perched (only valid when actually on a window). */
    public static final Activity PERCH_SING = new Activity("perch-sing",
            (pet, world) -> {
                // Only fire when there IS a perch beneath us — i.e. the
                // pet's column overlaps some topmost window.
                int x = pet.logicalLocation().x;
                int w = pet.effectiveWidth();
                for (Rectangle r : world.topmostWindows()) {
                    if (r.x + r.width > x && r.x < x + w) {
                        return 0.6;
                    }
                }
                return 0;
            },
            (pet, world) -> pet.perchSing(2_500L));

    /** Ducky: tiny waddle-loop (left-right step + return). */
    public static final Activity WADDLE_LOOP = new Activity("waddle-loop",
            (pet, world) -> 0.6,
            (pet, world) -> pet.waddleLoop(world, 80));

    /** Ducky: slow creep toward the cursor using walkAlongFloor (no run). */
    public static final Activity CRAWL_SNEAK = new Activity("crawl-sneak",
            (pet, world) -> World.cursorPos() != null ? 0.5 : 0,
            (pet, world) -> pet.walkTowardCursor(world, 100));

    /** Ducky: pouty crouch when AFFECTION is low and nothing happened recently. */
    public static final Activity CROUCH_POUT = new Activity("crouch-pout",
            (pet, world) -> {
                double a = pet.needs.get(Need.AFFECTION);
                return a < 40 ? 0.8 : 0;
            },
            (pet, world) -> pet.crouchPose(3_500L));

    /** Ducky: animated quack/left-right combo. */
    public static final Activity QUACK_COMBO = new Activity("quack-combo",
            (pet, world) -> 0.5,
            (pet, world) -> pet.leftRightCombo());

    /** Ducky: walk slowly toward the cursor and sit when close. */
    public static final Activity FOLLOW_CURSOR = new Activity("follow-cursor",
            (pet, world) -> World.cursorPos() != null ? 0.7 : 0,
            (pet, world) -> pet.walkTowardCursor(world, 40));

    // ---------------- pet-pet interactions ----------------
    // (only fire when at least one other pet is alive on the same monitor)

    /** Search radius (logical px) for finding a sibling to interact with. */
    private static final int PET_PET_RADIUS = 1200;

    /**
     * Walk up to the nearest sibling pet, sit beside it, and flash a
     * mini-heart emote. Mutual greeting happens naturally when both pets
     * happen to pick this at roughly the same time.
     */
    public static final Activity GREET_PET = new Activity("greet-pet",
            (pet, world) -> pet.nearestOtherPet(PET_PET_RADIUS) != null ? 0.7 : 0,
            (pet, world) -> {
                Pet other = pet.nearestOtherPet(PET_PET_RADIUS);
                if (other == null) return;
                int petW = pet.effectiveWidth();
                int otherMid = other.logicalLocation().x + other.effectiveWidth() / 2;
                int approachOffset = petW; // stop ~one body-width away
                int targetX = pet.logicalLocation().x < otherMid
                        ? otherMid - approachOffset - petW / 2
                        : otherMid + approachOffset - petW / 2;
                pet.walkAlongFloor(world, targetX);
                if (pet.interrupted()) return;
                pet.sit();
                if (pet.interrupted()) return;
                pet.showEmote("mini-heart", 1200);
            });

    /**
     * Sprint toward the nearest sibling pet's column — stops just short
     * (no overlap), shows a "bang" emote on arrival. The other pet's own
     * activity is not interrupted; if it happens to be wandering away the
     * chase looks like play.
     */
    public static final Activity CHASE_PET = new Activity("chase-pet",
            (pet, world) -> pet.nearestOtherPet(PET_PET_RADIUS) != null ? 0.5 : 0,
            (pet, world) -> {
                Pet other = pet.nearestOtherPet(PET_PET_RADIUS);
                if (other == null) return;
                int petW = pet.effectiveWidth();
                int otherMid = other.logicalLocation().x + other.effectiveWidth() / 2;
                int targetX = pet.logicalLocation().x < otherMid
                        ? otherMid - petW
                        : otherMid;
                pet.showEmote("target", 250);
                pet.walkAlongFloor(world, targetX);
                if (pet.interrupted()) return;
                pet.showEmote("bang", 400);
            });

    /** All activities in priority tie-break order (urgent → cosmetic). */
    public static final List<Activity> ALL = List.of(
            SLEEP, EAT, SEEK_PETTING, PLAY_BALL,
            STARTLE_FLUSH, GREET_FOREGROUND,
            STALK_POINTER, FETCH_CURSOR, FOLLOW_CURSOR,
            GREET_PET, CHASE_PET,
            DISAPPEAR_REAPPEAR, ZOOMIES, WANDER,
            HIGH_PERCH_LEAP, GROOMING, KNOCK_SOMETHING_OFF,
            FLIT, CIRCLE, PERCH_SING,
            WADDLE_LOOP, CRAWL_SNEAK, CROUCH_POUT, QUACK_COMBO,
            DIG,
            IDLE);
}
