package com.desktoppets;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives a {@link Pet}'s behaviour. Each tick:
 * <ol>
 *   <li>decay needs by elapsed seconds Ã— personality decay;</li>
 *   <li>snapshot the (cached) {@link World};</li>
 *   <li>pick the highest-priority {@link Activity} (after personality bias,
 *       per-activity cooldowns, foreground-window stability, the user's
 *       activity-level multiplier, and the post-activity rest period);</li>
 *   <li>run one step.</li>
 * </ol>
 *
 * <h2>Calmness rules</h2>
 * <ul>
 *   <li><b>IDLE is the baseline.</b> Its base priority (5) outweighs each
 *       ambient activity (â‰ˆ1), but the engine picks via <i>weighted random
 *       sampling</i> rather than argmax â€” so non-IDLE activities still fire
 *       a fraction of picks, giving visible variety.</li>
 *   <li><b>Cooldowns.</b> WANDER 30â€“90 s, ZOOMIES 1â€“3 min,
 *       DISAPPEAR_REAPPEAR 1.5â€“4 min. Need-driven activities have no
 *       cooldown â€” they're gated by the underlying need.</li>
 *   <li><b>Post-activity rest.</b> After any non-IDLE step, the pet is forced
 *       to idle for 4â€“14 s before the engine considers another activity.</li>
 *   <li><b>Urgent-need bypass.</b> If any need drops below 15 the engine
 *       switches to argmax over need-driven activities, ignoring cooldowns
 *       and rest so the pet still self-cares even at the "lethargic" slider
 *       setting.</li>
 * </ul>
 */
public final class BehaviorEngine {

    private static final double URGENT_NEED_LEVEL = 15.0;

    private final Pet pet;
    private final Map<String, Long> nextEligibleAt = new HashMap<>();
    private long restUntilMs = 0L;
    private String lastChosenName = "";

    public BehaviorEngine(Pet pet) {
        this.pet = pet;
    }

    public void run() {
        Log.info("engine:" + pet.name, "started");
        long previous = System.nanoTime();
        while (!Thread.currentThread().isInterrupted()) {
            long now = System.nanoTime();
            double seconds = (now - previous) / 1_000_000_000.0;
            previous = now;

            pet.needs.decay(pet.personality, seconds, pet.activityLevel);

            World world = World.snapshot(
                    (int) pet.screen.getWidth(),
                    (int) pet.screen.getHeight());

            // Suspend / resume: walk off (or back onto) the screen when
            // the embedding app asked us to via DesktopPetsApi.setSuspended.
            // While hidden, skip all behaviour (no reassertTopmost, no
            // activity selection) and just poll for the resume flag.
            pet.runPendingHideShow(world);
            // Graceful removal: PetSupervisor.reconcileCounts() shrinks the
            // roster by calling Pet.requestHideAndDispose(), which runs an
            // off-screen walk inside runPendingHideShow() and then flags
            // exitRequested. Bail out of the loop here so the pet thread
            // terminates instead of busy-looping in the hidden branch
            // forever.
            if (pet.isExitRequested()) {
                break;
            }
            if (pet.isHidden()) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                continue;
            }

            // Keep the pet window pinned to the topmost band even if another
            // app demoted us (focus / mode switches can do this). Cheap, no
            // focus theft.
            pet.reassertTopmost();

            // One-shot: if the pet was spawned off-screen, walk it into view
            // before any normal activity runs.
            pet.runPendingEntryWalk(world);

            // Safety net: if a ground pet has somehow ended up above the
            // current floor (jump arc cut short, perched window closed
            // while we weren't ticking, mid-air teleport), drop it back
            // onto the next surface before running any activity. Skips
            // for flyers and when already settled.
            if (pet.fallToFloorIfAirborne(world)) {
                continue;
            }

            // Reactions from other pets (DUCK while being jumped over, FLEE
            // when hunted) preempt normal activity selection so the visual
            // sequence stays coherent across the two engine threads.
            if (pet.hasActiveReaction()) {
                switch (pet.reaction()) {
                    case DUCK -> pet.holdDuck();
                    case FLEE -> pet.fleeFrom(world);
                    default   -> { /* unreachable: hasActiveReaction == NONE-guard */ }
                }
                continue;
            }

            // World-object soccer ball: any pet on the same monitor and
            // within range chases it before normal activity selection,
            // bypassing the usual cooldown / forced-rest gating so the
            // scrum stays lively while the ball is in play. The bypass
            // skips when the pet has an urgent need (handled inside
            // canChaseBall) so self-care still wins over play.
            Ball ball = Ball.active();
            if (ball != null && pet.canChaseBall(ball)) {
                pet.chaseBall(world, ball);
                continue;
            }

            Activity chosen = pick(world);

            if (pet.clicked.getAndSet(false)) {
                pet.needs.add(Need.AFFECTION, 50);
                pet.onClicked();
            } else if (pet.hovered) {
                pet.hover();
            } else if (chosen != null) {
                if (!chosen.name().equals(lastChosenName)) {
                    Log.info("engine:" + pet.name, "â†’ " + chosen.name());
                    lastChosenName = chosen.name();
                }
                pet.currentActivityName = chosen.name();
                try {
                    chosen.perform(pet, world);
                } finally {
                    pet.currentActivityName = "";
                }
                noteCompleted(chosen);
            } else {
                pet.idle();
            }
        }
        Log.info("engine:" + pet.name, "stopped");
    }

    private Activity pick(World world) {
        long nowMs = System.currentTimeMillis();
        double activity = clampActivity(pet.activityLevel);
        boolean urgent = pet.needs.lowestBelow(URGENT_NEED_LEVEL) != null;

        // Forced post-activity rest period â€” unless something is urgent.
        if (!urgent && nowMs < restUntilMs) {
            return Activities.IDLE;
        }

        double idleBoost = Math.max(0.05, 2.0 - activity);

        // Urgent: argmax â€” the lowest need decides deterministically.
        if (urgent) {
            Activity best = null;
            double bestScore = 0;
            for (Activity a : Activities.ALL) {
                if (pet.hovered || pet.clicked.get()) {
                    return null;
                }
                double score = a.priority(pet, world) * pet.personality.multiplier(a.name());
                if (score > bestScore) {
                    bestScore = score;
                    best = a;
                }
            }
            return best != null ? best : Activities.IDLE;
        }

        // Ambient: weighted-random sampling so IDLE's high base doesn't make
        // it always win. Each activity's score becomes its sampling weight;
        // activities still off-cooldown are excluded. With default tuning
        // IDLE wins ~60% of picks and the various active behaviours share
        // the rest, giving visible variety instead of pure idling.
        java.util.List<Activity> eligible = new java.util.ArrayList<>(Activities.ALL.size());
        java.util.List<Double> weights = new java.util.ArrayList<>(Activities.ALL.size());
        double total = 0;
        for (Activity a : Activities.ALL) {
            if (pet.hovered || pet.clicked.get()) {
                return null;
            }
            if (a != Activities.IDLE) {
                Long next = nextEligibleAt.get(a.name());
                if (next != null && nowMs < next) {
                    continue;
                }
            }
            double raw = a.priority(pet, world) * pet.personality.multiplier(a.name());
            // IDLE scales with the activity-level gate (lethargic â†’ more idle,
            // hyperactive â†’ less). Non-IDLE activities are floored at 0.25Ã—
            // raw so even "calm" pets still occasionally play / wander â€” the
            // slider modulates frequency rather than killing them off.
            double score = (a == Activities.IDLE)
                    ? raw * idleBoost
                    : raw * Math.max(0.25, activity);
            if (score <= 0) {
                continue;
            }
            eligible.add(a);
            weights.add(score);
            total += score;
        }
        if (eligible.isEmpty() || total <= 0) {
            return Activities.IDLE;
        }
        double r = ThreadLocalRandom.current().nextDouble(total);
        double cum = 0;
        for (int i = 0; i < eligible.size(); i++) {
            cum += weights.get(i);
            if (r < cum) {
                return eligible.get(i);
            }
        }
        return eligible.get(eligible.size() - 1);
    }

    private void noteCompleted(Activity a) {
        long nowMs = System.currentTimeMillis();
        if (a == Activities.IDLE) {
            return;
        }
        long cooldown = computeCooldownMs(a);
        if (cooldown > 0) {
            nextEligibleAt.put(a.name(), nowMs + cooldown);
        }
        // Forced rest 2..7 s before the engine considers another activity.
        long rest = 2_000L + ThreadLocalRandom.current().nextLong(0, 5_000L);
        restUntilMs = nowMs + rest;
    }

    private static long computeCooldownMs(Activity a) {
        CooldownRange r = COOLDOWNS.get(a);
        if (r == null) {
            // Need-driven activities (sleep/eat/drink) intentionally have no
            // cooldown — they're naturally gated by their underlying need
            // (priority 0 when satisfied).
            return 0L;
        }
        return r.minMs + ThreadLocalRandom.current().nextLong(0, r.spanMs);
    }

    /** Half-open cooldown window: actual delay = min + rand[0, span). */
    private record CooldownRange(long minMs, long spanMs) { }

    /**
     * Per-activity cooldowns. Keyed by activity instance identity so adding a
     * new {@link Activity} that needs throttling is a one-line edit here
     * instead of growing the previous if-chain. Activities not present have
     * no cooldown (the natural gating is their priority function).
     */
    private static final Map<Activity, CooldownRange> COOLDOWNS;
    static {
        Map<Activity, CooldownRange> m = new IdentityHashMap<>();
        m.put(Activities.WANDER,             new CooldownRange( 30_000L,  60_000L)); // 30..90 s
        m.put(Activities.DISAPPEAR_REAPPEAR, new CooldownRange( 90_000L, 150_000L)); // 1.5..4 min
        m.put(Activities.ZOOMIES,            new CooldownRange( 60_000L, 120_000L)); // 1..3 min
        m.put(Activities.PLAY_BALL,          new CooldownRange( 45_000L,  75_000L)); // 45..120 s
        m.put(Activities.SEEK_PETTING,       new CooldownRange( 40_000L,  60_000L)); // 40..100 s
        m.put(Activities.GREET_PET,          new CooldownRange( 60_000L, 120_000L)); // 60..180 s
        m.put(Activities.CHASE_PET,          new CooldownRange( 90_000L, 150_000L)); // 90..240 s
        m.put(Activities.HUNT_PET,           new CooldownRange( 90_000L, 150_000L)); // 90..240 s
        m.put(Activities.HUNT_BIRD,          new CooldownRange( 20_000L,  40_000L)); // 20..60 s
        m.put(Activities.HUNT_CURSOR,        new CooldownRange( 60_000L, 120_000L)); // 60..180 s
        m.put(Activities.SNIFF,              new CooldownRange( 45_000L,  75_000L)); // 45..120 s
        m.put(Activities.NUDGE,              new CooldownRange( 60_000L, 120_000L)); // 60..180 s
        m.put(Activities.TAG,                new CooldownRange( 60_000L, 120_000L)); // 60..180 s
        m.put(Activities.CHASE_TAIL,         new CooldownRange( 50_000L,  80_000L)); // 50..130 s
        m.put(Activities.HICCUP,             new CooldownRange( 90_000L, 150_000L)); // 90..240 s
        m.put(Activities.STARGAZE,           new CooldownRange(120_000L, 180_000L)); // 2..5 min
        m.put(Activities.BURST_OF_HEARTS,    new CooldownRange( 90_000L, 150_000L)); // 90..240 s
        m.put(Activities.HEAD_TILT,          new CooldownRange( 60_000L,  90_000L)); // 60..150 s
        m.put(Activities.YAWN,               new CooldownRange( 70_000L, 110_000L)); // 70..180 s
        m.put(Activities.MOON_GAZE,          new CooldownRange(150_000L, 210_000L)); // 2.5..6 min
        m.put(Activities.PACE,               new CooldownRange( 90_000L, 120_000L)); // 90..210 s
        m.put(Activities.LICK_PET,           new CooldownRange( 90_000L, 120_000L)); // 90..210 s
        m.put(Activities.LICK_EDGE,          new CooldownRange(150_000L, 180_000L)); // 2.5..5.5 min
        m.put(Activities.SPEAK,              new CooldownRange( 45_000L,  75_000L)); // 45..120 s
        m.put(Activities.SCRATCH,            new CooldownRange( 60_000L,  90_000L)); // 60..150 s
        m.put(Activities.DANCE,              new CooldownRange(120_000L, 180_000L)); // 2..5 min
        // New pet-pet interactions.
        m.put(Activities.CONVERSE,           new CooldownRange( 60_000L, 120_000L)); // 60..180 s
        m.put(Activities.JOIN_DANCE,         new CooldownRange( 60_000L, 120_000L)); // 60..180 s — opportunistic
        m.put(Activities.STARTLE,            new CooldownRange( 90_000L, 120_000L)); // 90..210 s — easy to spam
        m.put(Activities.NAP_TOGETHER,       new CooldownRange(120_000L, 180_000L)); // 2..5 min
        m.put(Activities.FOLLOW_LEADER,      new CooldownRange( 60_000L, 120_000L)); // 60..180 s
        m.put(Activities.STARING_CONTEST,    new CooldownRange( 90_000L, 150_000L)); // 90..240 s
        m.put(Activities.SHARE_FOOD,         new CooldownRange( 90_000L, 120_000L)); // 90..210 s
        m.put(Activities.COMFORT_HUDDLE,     new CooldownRange( 30_000L,  60_000L)); // 30..90 s — short so distressed pet can re-seek
        // MAKE_SPACE: very short — the grace timer in its priority lambda
        // already resists immediate re-arming. Span needs to be > 0 because
        // ThreadLocalRandom.nextLong(0, 0) would throw; previously this was
        // 1L, which collapses to a constant 1500 ms (dead jitter).
        m.put(Activities.MAKE_SPACE,         new CooldownRange(  1_500L,     500L));
        COOLDOWNS = Map.copyOf(m);
    }

    private static double clampActivity(double v) {
        if (Double.isNaN(v)) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(2.0, v));
    }
}
