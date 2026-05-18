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
            (pet, _) -> {
                double e = pet.needs.get(Need.ENERGY);
                return e < 25 ? 100 - e : 0;
            },
            (pet, _) -> pet.sleep());

    public static final Activity EAT = new Activity("eat",
            (pet, _) -> {
                double h = pet.needs.get(Need.HUNGER);
                return h < 30 ? 90 - h : 0;
            },
            (pet, _) -> pet.eat());

    /**
     * Mirror of {@link #EAT} but for {@link Need#THIRST}. Same priority
     * shape so a thirsty pet behaves identically to a hungry one: thought
     * bubble first, then a water-bowl prop, then the gauge is restored.
     */
    public static final Activity DRINK = new Activity("drink",
            (pet, _) -> {
                double t = pet.needs.get(Need.THIRST);
                return t < 30 ? 90 - t : 0;
            },
            (pet, _) -> pet.drink());

    public static final Activity SEEK_PETTING = new Activity("seek-petting",
            (pet, _) -> {
                double a = pet.needs.get(Need.AFFECTION);
                // Need-driven ramp when AFFECTION is low, ambient floor 0.35
                // so a content pet still occasionally trots up looking for a pet.
                return a < 30 ? 70 - a : 0.35;
            },
            (pet, _) -> pet.seekPetting());

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
                // playBall now patrols a span on the floor itself; it
                // picks its own start column relative to the pet's current
                // position, clamps to the active monitor, and recomputes
                // floorY each sub-step as the pet runs. We just hand it
                // the world.
                pet.playBall(world);
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
            (_, _) -> 1.0,
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
            (_, _) -> 0.7,
            (pet, world) -> pet.disappearAndReappear(world));

    /**
     * Sudden burst of energy: 2–4 fast back-and-forth sprints across the
     * monitor. Slightly boredom-biased so a bored pet is more likely to have
     * zoomies, mirroring the real-life phenomenon.
     */
    public static final Activity ZOOMIES = new Activity("zoomies",
            (pet, _) -> {
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
            (_, _) -> 5.0,
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
            (_, _) -> World.cursorPos() != null ? 0.6 : 0,
            (pet, world) -> pet.stalkPointer(world));

    /**
     * Predator instinct: if the user has been fiddling with the cursor
     * (substantial motion in the last few seconds) and it is currently
     * within reach on the pet's own monitor, the pet decides to hunt it
     * for a while. Priority ramps with recent cursor motion magnitude so
     * incidental drift doesn't trigger it. Gated by a long cooldown in
     * {@link BehaviorEngine} so the pet doesn't perpetually chase the
     * mouse during normal use.
     */
    public static final Activity HUNT_CURSOR = new Activity("hunt-cursor",
            (pet, _) -> {
                java.awt.Point c = World.cursorPos();
                if (c == null) return 0;
                Rectangle mon = pet.currentMonitorBounds();
                if (!mon.contains(c)) return 0;
                int petW = pet.effectiveWidth();
                int petMid = pet.logicalLocation().x + petW / 2;
                int reach = Math.max(300, petW * 5);
                if (Math.abs(c.x - petMid) > reach) return 0;
                int motion = World.cursorMotionPx(3_000L);
                if (motion < 120) return 0;
                // Ramps from ~2.0 at threshold up to ~5.0 with heavy
                // fiddling so HUNT_CURSOR reliably wins the ambient
                // weighted-random lottery against IDLE + the ~30 other
                // candidates. Previously capped at 1.8 → easily lost to
                // the sum of competing weights.
                return Math.min(5.0, 2.0 + motion / 400.0);
            },
            (pet, world) -> pet.huntCursor(world));

    /** Cat: leap onto a higher perch within range. */
    public static final Activity HIGH_PERCH_LEAP = new Activity("high-perch-leap",
            (_, _) -> 0.5,
            (pet, world) -> pet.highPerchLeap(world));

    /** Cat: grooming/preening sit-and-stretch sequence. */
    public static final Activity GROOMING = new Activity("grooming",
            (pet, _) -> {
                double a = pet.needs.get(Need.AFFECTION);
                return 0.4 + Math.max(0, (50 - a) * 0.01);
            },
            (pet, _) -> pet.grooming());

    /**
     * Generic itch-scratch beat. Available to every resident species so any
     * pet can pause for a quick scratch — priority is mid-range so it
     * doesn't preempt social or need-driven activities but happily fills
     * an otherwise-idle moment. Cooldown in {@link BehaviorEngine}.
     */
    public static final Activity SCRATCH = new Activity("scratch",
            (_, _) -> 0.45,
            (pet, _) -> pet.scratch());

    /**
     * Spontaneous dance — a multi-beat sway loop with a music-note emote.
     * Available to every resident species. Slightly higher base priority
     * than scratch because it's the showpiece animation; the cooldown in
     * {@link BehaviorEngine} keeps it from dominating.
     */
    public static final Activity DANCE = new Activity("dance",
            (_, _) -> 0.5,
            (pet, _) -> pet.dance());

    /** Cat: long-cooldown "knock something off the desk" moment. */
    public static final Activity KNOCK_SOMETHING_OFF = new Activity("knock-something-off",
            (_, world) -> world.topmostWindows().isEmpty() ? 0 : 0.3,
            (pet, world) -> pet.knockSomethingOff(world));

    /** Dog: runs to the cursor, sits, trots back. */
    public static final Activity FETCH_CURSOR = new Activity("fetch-cursor",
            (_, _) -> World.cursorPos() != null ? 0.7 : 0,
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
            (_, world) -> world.foregroundJustChanged() ? 2.0 : 0,
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
            (_, _) -> 0.35,
            (pet, _) -> pet.dig());

    /** Bird: short perch-to-perch hop. */
    public static final Activity FLIT = new Activity("flit",
            (_, _) -> 0.9,
            (pet, world) -> pet.flit(world, 180));

    /** Bird: long figure-8 across the monitor near the top. */
    public static final Activity CIRCLE = new Activity("circle",
            (_, _) -> 0.5,
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
            (pet, _) -> pet.perchSing(2_500L));

    /** Ducky: tiny waddle-loop (left-right step + return). */
    public static final Activity WADDLE_LOOP = new Activity("waddle-loop",
            (_, _) -> 0.6,
            (pet, world) -> pet.waddleLoop(world, 80));

    /** Ducky: slow creep toward the cursor using walkAlongFloor (no run). */
    public static final Activity CRAWL_SNEAK = new Activity("crawl-sneak",
            (_, _) -> World.cursorPos() != null ? 0.5 : 0,
            (pet, world) -> pet.walkTowardCursor(world, 100));

    /** Ducky: pouty crouch when AFFECTION is low and nothing happened recently. */
    public static final Activity CROUCH_POUT = new Activity("crouch-pout",
            (pet, _) -> {
                double a = pet.needs.get(Need.AFFECTION);
                return a < 40 ? 0.8 : 0;
            },
            (pet, _) -> pet.crouchPose(3_500L));

    /** Ducky: animated quack/left-right combo. */
    public static final Activity QUACK_COMBO = new Activity("quack-combo",
            (_, _) -> 0.5,
            (pet, _) -> pet.leftRightCombo());

    /** Ducky: walk slowly toward the cursor and sit when close. */
    public static final Activity FOLLOW_CURSOR = new Activity("follow-cursor",
            (_, _) -> World.cursorPos() != null ? 0.7 : 0,
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
            (pet, _) -> pet.nearestOtherPet(PET_PET_RADIUS) != null ? 0.7 : 0,
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
            (pet, _) -> pet.nearestOtherPet(PET_PET_RADIUS) != null ? 0.5 : 0,
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

    /**
     * Active predator/prey play: hunter signals the nearest sibling to flee
     * (via {@link Pet#requestReaction Reaction.FLEE}) and then sprints toward
     * its current column. The prey's own engine consumes the reaction on its
     * next tick and runs away across the monitor, so the resulting visual is
     * one pet chasing the other through whatever it was previously doing.
     *
     * <p>Distinct from {@link #CHASE_PET}: chase just runs up and bangs the
     * sibling (who carries on with whatever they were doing); hunt explicitly
     * scares the sibling into a counter-walk so both pets are in motion.
     */
    public static final Activity HUNT_PET = new Activity("hunt-pet",
            (pet, _) -> pet.nearestOtherPet(PET_PET_RADIUS) != null ? 0.4 : 0,
            (pet, world) -> {
                Pet prey = pet.nearestOtherPet(PET_PET_RADIUS);
                if (prey == null) return;
                int hunterMidX = pet.logicalLocation().x + pet.effectiveWidth() / 2;
                // Tell prey to flee BEFORE we start moving so its engine
                // picks up the reaction on its next tick and starts running
                // first — otherwise we'd catch it standing still.
                prey.requestReaction(Pet.Reaction.FLEE, 6_000L, hunterMidX);
                pet.showEmote("target", 350);
                // Tiny beat so the prey's flee step starts before the
                // hunter closes the gap (engine ticks ~5 Hz).
                Pet.sleepInterruptible(250);
                if (pet.interrupted()) return;
                int petW = pet.effectiveWidth();
                int preyMid = prey.logicalLocation().x + prey.effectiveWidth() / 2;
                int targetX = pet.logicalLocation().x < preyMid
                        ? preyMid - petW
                        : preyMid;
                pet.runAlongFloor(world, targetX);
            });

    /** How far away (in logical pixels) a resident pet will notice and
     *  start chasing a visiting bird. Generous because the bird itself
     *  is the rare event and we want the chase to fire reliably. */
    private static final int BIRD_HUNT_RADIUS = 800;

    /**
     * Run toward a visiting bird and scare it off. Distinct from
     * {@link #HUNT_PET}: residents never sit on each other's hunt cooldown
     * for chasing a bird, and the prey here ({@link Bird}) is a
     * visitor pet that self-disposes on {@link Pet#scare(int)} instead of
     * fleeing across the monitor. Priority is high so this preempts
     * ambient activities while the bird is on screen \u2014 you only get
     * one shot at the bird before it flies off on its own.
     */
    public static final Activity HUNT_BIRD = new Activity("hunt-bird",
            (pet, _) -> pet.nearestVisitor(BIRD_HUNT_RADIUS) != null ? 3.5 : 0,
            (pet, world) -> {
                Pet bird = pet.nearestVisitor(BIRD_HUNT_RADIUS);
                if (bird == null) return;
                int petW = pet.effectiveWidth();
                int birdMid = bird.logicalLocation().x + bird.effectiveWidth() / 2;
                // Stop one body-width short of the bird so we don't visually
                // overlap; landing-side determined by approach direction.
                int targetX = pet.logicalLocation().x < birdMid
                        ? birdMid - petW - petW / 2
                        : birdMid + petW / 2;
                pet.showEmote("target", 350);
                // Trigger the bird's take-off BEFORE the pet runs over,
                // not after — otherwise the pet arrives, stops, and only
                // then does the bird react, which reads as the hunt
                // happening for no reason. Scaring up-front means the
                // bird is already lifting off while the pet is closing
                // the distance, so the chase has a clear cause/effect:
                // pet spots bird → bird startles → pet runs at the
                // empty perch as the bird flies away.
                int hunterMid = pet.logicalLocation().x + petW / 2;
                bird.scare(hunterMid);
                pet.showEmote("bang", 400);
                pet.runAlongFloor(world, targetX);
            });

    /**
     * Curiosity activity: walk up to the nearest sibling pet, sniff them
     * (a {@code paw} emote held for ~800 ms), then either sit beside them
     * (50%) or wander a few body-widths away (50%). Distinct from
     * {@link #GREET_PET} \u2014 greet always sits and shows a heart; sniff
     * is investigative and may end with the pet walking off.
     */
    public static final Activity SNIFF = new Activity("sniff",
            (pet, _) -> pet.nearestOtherPet(PET_PET_RADIUS) != null ? 0.55 : 0,
            (pet, world) -> {
                Pet other = pet.nearestOtherPet(PET_PET_RADIUS);
                if (other == null) return;
                int petW = pet.effectiveWidth();
                int otherMid = other.logicalLocation().x + other.effectiveWidth() / 2;
                // Stop one body-width away on the same side we approached from
                // so we don't overlap the sniffee.
                int targetX = pet.logicalLocation().x < otherMid
                        ? otherMid - other.effectiveWidth() / 2 - petW
                        : otherMid + other.effectiveWidth() / 2;
                pet.runAlongFloor(world, targetX);
                if (pet.interrupted()) return;
                pet.showEmote("paw", 800);
                Pet.sleepInterruptible(800);
                if (pet.interrupted()) return;
                if (java.util.concurrent.ThreadLocalRandom.current().nextBoolean()) {
                    pet.sit();
                } else {
                    Rectangle mon = pet.currentMonitorBounds();
                    int dir = (pet.logicalLocation().x < otherMid) ? -1 : 1;
                    int away = pet.logicalLocation().x + dir * petW * 3;
                    away = Math.max(mon.x, Math.min(mon.x + mon.width - petW, away));
                    pet.walkAlongFloor(world, away);
                }
            });

    /**
     * Playful bump: pet walks up to the nearest sibling, both pets show a
     * {@code bang} emote (the contact), then the nudger trots two body-widths
     * back the way it came. Distinct from {@link #SNIFF} (which is
     * investigative) and {@link #CHASE_PET} (which doesn't touch).
     */
    public static final Activity NUDGE = new Activity("nudge",
            (pet, _) -> pet.nearestOtherPet(PET_PET_RADIUS) != null ? 0.3 : 0,
            (pet, world) -> {
                Pet other = pet.nearestOtherPet(PET_PET_RADIUS);
                if (other == null) return;
                int petW = pet.effectiveWidth();
                int otherMid = other.logicalLocation().x + other.effectiveWidth() / 2;
                boolean fromLeft = pet.logicalLocation().x < otherMid;
                int targetX = fromLeft
                        ? otherMid - other.effectiveWidth() / 2 - petW
                        : otherMid + other.effectiveWidth() / 2;
                pet.walkAlongFloor(world, targetX);
                if (pet.interrupted()) return;
                pet.showEmote("bang", 500);
                other.showEmote("bang", 500);
                Pet.sleepInterruptible(500);
                if (pet.interrupted()) return;
                Rectangle mon = pet.currentMonitorBounds();
                int back = pet.logicalLocation().x + (fromLeft ? -2 * petW : 2 * petW);
                back = Math.max(mon.x, Math.min(mon.x + mon.width - petW, back));
                pet.walkAlongFloor(world, back);
            });

    /**
     * "You're it!" — pet sprints to the nearest sibling, taps them
     * ({@code paw} emote on the OTHER pet), then runs to the far side of
     * the current monitor as if to escape being tagged back. The tagged pet
     * is left alone (no FLEE reaction) so the visual gag stays a one-shot
     * rather than a chase.
     */
    public static final Activity TAG = new Activity("tag",
            (pet, _) -> pet.nearestOtherPet(PET_PET_RADIUS) != null ? 0.35 : 0,
            (pet, world) -> {
                Pet target = pet.nearestOtherPet(PET_PET_RADIUS);
                if (target == null) return;
                int petW = pet.effectiveWidth();
                int otherMid = target.logicalLocation().x + target.effectiveWidth() / 2;
                boolean fromLeft = pet.logicalLocation().x < otherMid;
                int approachX = fromLeft
                        ? otherMid - target.effectiveWidth() / 2 - petW
                        : otherMid + target.effectiveWidth() / 2;
                pet.runAlongFloor(world, approachX);
                if (pet.interrupted()) return;
                target.showEmote("paw", 700);   // the tap lands on the OTHER pet
                pet.showEmote("sparkle", 350);
                Pet.sleepInterruptible(300);
                if (pet.interrupted()) return;
                Rectangle mon = pet.currentMonitorBounds();
                int escapeX = fromLeft
                        ? mon.x + 8
                        : mon.x + mon.width - petW - 8;
                pet.runAlongFloor(world, escapeX);
            });

    /** Solo: spin in place chasing one's own tail, with a sparkle emote. */
    public static final Activity CHASE_TAIL = new Activity("chase-tail",
            (_, _) -> 0.4,
            (pet, _) -> pet.chaseTail());

    /** Solo: four small vertical bobs with drop emotes \u2014 hiccups. */
    public static final Activity HICCUP = new Activity("hiccup",
            (_, _) -> 0.25,
            (pet, _) -> pet.hiccup());

    /** Solo: sit and look at the sky with slow sparkle emotes. */
    public static final Activity STARGAZE = new Activity("stargaze",
            (_, _) -> 0.3,
            (pet, _) -> pet.stargaze());

    /** Solo: sit and emit three mini-hearts \u2014 spontaneous affection burst. */
    public static final Activity BURST_OF_HEARTS = new Activity("burst-of-hearts",
            (pet, _) -> {
                // Slightly ramps when AFFECTION is HIGH \u2014 a content pet expresses it.
                double a = pet.needs.get(Need.AFFECTION);
                return a > 60 ? 0.45 : 0.2;
            },
            (pet, _) -> pet.burstOfHearts());

    /** Solo: cycle the "look" sprite with a yellow "?" emote \u2014 curious moment. */
    public static final Activity HEAD_TILT = new Activity("head-tilt",
            (_, _) -> 0.4,
            (pet, _) -> pet.headTilt());

    /**
     * Solo: sleepy yawn with a "zzz" emote. Priority ramps as ENERGY drops
     * so a tired pet visibly telegraphs the approaching SLEEP activity.
     */
    public static final Activity YAWN = new Activity("yawn",
            (pet, _) -> {
                double e = pet.needs.get(Need.ENERGY);
                // Below 45 ENERGY, YAWN becomes a likelier-than-baseline filler.
                return e < 45 ? 0.6 : 0.25;
            },
            (pet, _) -> pet.yawn());

    /** Solo: sit and emit three crescent-moon emotes \u2014 quiet nighttime mood. */
    public static final Activity MOON_GAZE = new Activity("moon-gaze",
            (_, _) -> 0.25,
            (pet, _) -> pet.moonGaze());

    /**
     * Solo: anxious pace back and forth three times around the starting
     * column. No new sprite \u2014 reuses the walk gait. Distinct from
     * {@link #WANDER} (which travels) and {@link #CHASE_TAIL} (which spins).
     */
    public static final Activity PACE = new Activity("pace",
            (_, _) -> 0.3,
            (pet, world) -> pet.paceBackAndForth(world));

    /**
     * Affectionate grooming: walk up to the nearest sibling pet, sit beside
     * them, and lick three times (lick emote on the licker, mini-heart on
     * the recipient). Both pets gain a small AFFECTION boost. Distinct from
     * {@link #SNIFF} (investigative, paw emote), {@link #NUDGE} (single
     * bump) and {@link #GREET_PET} (one-shot heart): licking is a sustained
     * social interaction.
     */
    public static final Activity LICK_PET = new Activity("lick-pet",
            (pet, _) -> pet.nearestOtherPet(PET_PET_RADIUS) != null ? 0.4 : 0,
            (pet, world) -> {
                Pet other = pet.nearestOtherPet(PET_PET_RADIUS);
                if (other == null) return;
                int petW = pet.effectiveWidth();
                int otherMid = other.logicalLocation().x + other.effectiveWidth() / 2;
                boolean fromLeft = pet.logicalLocation().x < otherMid;
                int targetX = fromLeft
                        ? otherMid - other.effectiveWidth() / 2 - petW
                        : otherMid + other.effectiveWidth() / 2;
                pet.walkAlongFloor(world, targetX);
                if (pet.interrupted()) return;
                pet.sit();
                for (int i = 0; i < 3; i++) {
                    if (pet.interrupted()) return;
                    pet.showEmote("lick", 600);
                    other.showEmote("mini-heart", 600);
                    Pet.sleepInterruptible(500);
                }
                pet.needs.add(Need.AFFECTION, 25);
                other.needs.add(Need.AFFECTION, 25);
            });

    /**
     * Silly solo gag: walk to the nearest left/right monitor edge, sit, and
     * lick the screen three times \u2014 the pet trying to clean the glass
     * from the inside. No new sprite required beyond {@code lick}.
     */
    public static final Activity LICK_EDGE = new Activity("lick-edge",
            (_, _) -> 0.25,
            (pet, world) -> pet.lickScreenEdge(world));

    /** Time a pet may sit on top of another before {@link #MAKE_SPACE} fires. */
    private static final long OVERLAP_GRACE_MS = 4_000L;

    /**
     * Step aside when two pets have been visually stacked on top of each
     * other for more than {@link #OVERLAP_GRACE_MS}. Intentional social
     * activities (NUDGE, LICK_PET, CHASE_PET, &hellip;) run to completion
     * before the engine picks again, so brief on-purpose overlaps never
     * trip this; only persistent post-activity stack-ups do. Priority is
     * high enough to beat ambient wandering but kept below true urgent
     * needs.
     */
    public static final Activity MAKE_SPACE = new Activity("make-space",
            (pet, _) -> {
                Pet other = pet.overlappingOtherPet();
                long now = System.currentTimeMillis();
                if (other == null) {
                    pet.overlapStartMs = 0L;
                    return 0.0;
                }
                if (pet.overlapStartMs == 0L) {
                    pet.overlapStartMs = now;
                    return 0.0;
                }
                long age = now - pet.overlapStartMs;
                if (age < OVERLAP_GRACE_MS) return 0.0;
                // Climbs from 1.4 -> 1.9 as the overlap lingers, so a
                // longer stack-up beats more ambient activities.
                return Math.min(1.9, 1.4 + age / 20_000.0);
            },
            (pet, world) -> {
                Pet other = pet.overlappingOtherPet();
                if (other == null) { pet.overlapStartMs = 0L; return; }
                Rectangle mon = pet.currentMonitorBounds();
                int petW = pet.effectiveWidth();
                int myMid = pet.logicalLocation().x + petW / 2;
                int otherMid = other.logicalLocation().x + other.effectiveWidth() / 2;
                // Step away horizontally by roughly 1.4 pet-widths, in the
                // direction opposite the other pet. If the other pet is
                // directly on top of us pick a random side.
                int step = (int) (petW * 1.4);
                int dir;
                if (myMid == otherMid) {
                    dir = java.util.concurrent.ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
                } else {
                    dir = myMid >= otherMid ? 1 : -1;
                }
                int targetMid = myMid + dir * step;
                int targetX = targetMid - petW / 2;
                // Stay inside the monitor. Guard against degenerate
                // monitors that are narrower than the pet (or barely
                // wider than petW+4): without the max() below, loX>hiX
                // and the subsequent flip logic could place the pet
                // outside the monitor.
                int loX = mon.x + 2;
                int hiX = Math.max(loX, mon.x + mon.width - petW - 2);
                if (targetX < loX) {
                    targetX = loX;
                    // If clamped against the wall we're heading toward,
                    // flip and go the other way instead. Clamp to BOTH
                    // bounds in case the pet's current position is
                    // already outside the monitor (which would otherwise
                    // produce a flip target still outside [loX, hiX]).
                    if (dir < 0) targetX = Math.max(loX,
                            Math.min(hiX, myMid + step - petW / 2));
                } else if (targetX > hiX) {
                    targetX = hiX;
                    if (dir > 0) targetX = Math.max(loX,
                            Math.min(hiX, myMid - step - petW / 2));
                }
                pet.walkAlongFloor(world, targetX);
                pet.overlapStartMs = 0L;
            });

    /**
     * Brief vocalisation: pet says a per-species phrase ("Woof!", "Meow",
     * "Quack!", "Tweet!") in a speech bubble. The bubble tail points at
     * the addressee \u2014 the nearest sibling pet if one is within
     * {@link #PET_PET_RADIUS}, otherwise the cursor if it's on the same
     * monitor, otherwise centered (talking to itself). Slightly higher
     * baseline priority when another pet is nearby so the rooms feels
     * chatty when multiple pets coexist.
     */
    public static final Activity SPEAK = new Activity("speak",
            (pet, _) -> {
                if (pet.nearestOtherPet(PET_PET_RADIUS) != null) return 0.55;
                java.awt.Point c = World.cursorPos();
                if (c != null && pet.currentMonitorBounds().contains(c)) return 0.35;
                return 0.2;
            },
            (pet, _) -> {
                int targetMidX = Integer.MIN_VALUE;
                Pet sibling = pet.nearestOtherPet(PET_PET_RADIUS);
                if (sibling != null) {
                    targetMidX = sibling.logicalLocation().x + sibling.effectiveWidth() / 2;
                } else {
                    java.awt.Point c = World.cursorPos();
                    if (c != null && pet.currentMonitorBounds().contains(c)) {
                        targetMidX = c.x;
                    }
                }
                pet.speak(pet.randomSound(), 1400L, targetMidX);
            });

    /** All activities in priority tie-break order (urgent → cosmetic). */
    public static final List<Activity> ALL = List.of(
            SLEEP, EAT, DRINK, SEEK_PETTING, PLAY_BALL,
            STARTLE_FLUSH, GREET_FOREGROUND,
            STALK_POINTER, HUNT_CURSOR, FETCH_CURSOR, FOLLOW_CURSOR,
            GREET_PET, CHASE_PET, HUNT_PET, HUNT_BIRD, SNIFF, NUDGE, TAG, LICK_PET,
            DISAPPEAR_REAPPEAR, ZOOMIES, WANDER,
            HIGH_PERCH_LEAP, GROOMING, KNOCK_SOMETHING_OFF,
            FLIT, CIRCLE, PERCH_SING,
            WADDLE_LOOP, CRAWL_SNEAK, CROUCH_POUT, QUACK_COMBO,
            DIG, SCRATCH, DANCE,
            CHASE_TAIL, HICCUP, STARGAZE, BURST_OF_HEARTS,
            HEAD_TILT, YAWN, MOON_GAZE, PACE, LICK_EDGE, SPEAK,
            MAKE_SPACE,
            IDLE);
}
