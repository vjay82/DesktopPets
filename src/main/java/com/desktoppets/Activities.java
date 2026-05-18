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
                return a < 30 ? 70 - a : 0;
            },
            (pet, world) -> pet.seekPetting());

    /** Only available when an Explorer taskbar is detected. */
    public static final Activity PLAY_BALL = new Activity("play-ball",
            (pet, world) -> {
                if (world.taskbar() == null) {
                    return 0;
                }
                double b = pet.needs.get(Need.BOREDOM);
                return b < 35 ? 60 - b : 0;
            },
            (pet, world) -> {
                Rectangle bar = world.taskbar();
                if (bar != null) {
                    // Use floorY so feet land on the taskbar top rather than
                    // the full frame extending past it (sprites have foot
                    // padding inside their viewBox — see Pet.clampToScreen).
                    int x = pet.frame.getX();
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
                Point loc = pet.frame.getLocation();
                int petH = pet.effectiveHeight();
                int floorY = pet.floorYAt(world, loc.x);
                int hysteresis = Math.max(2, petH / 2);
                if (Math.abs(loc.y - floorY) > hysteresis) {
                    pet.moveFrameTo(loc.x, floorY);
                }
                int roll = ThreadLocalRandom.current().nextInt(100);
                if (roll < 60) {
                    pet.idle();
                } else if (roll < 80) {
                    pet.sit();
                } else if (roll < 92) {
                    pet.lookAround();
                } else {
                    pet.stretch();
                }
            });

    /** All activities in priority tie-break order (urgent → cosmetic). */
    public static final List<Activity> ALL = List.of(
            SLEEP, EAT, SEEK_PETTING, PLAY_BALL,
            DISAPPEAR_REAPPEAR, ZOOMIES, WANDER, IDLE);
}
