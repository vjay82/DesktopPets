package com.desktoppets;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives a {@link Pet}'s behaviour. Each tick:
 * <ol>
 *   <li>decay needs by elapsed seconds × personality decay;</li>
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
 *       ambient activity (≈1), but the engine picks via <i>weighted random
 *       sampling</i> rather than argmax — so non-IDLE activities still fire
 *       a fraction of picks, giving visible variety.</li>
 *   <li><b>Cooldowns.</b> WANDER 30–90 s, ZOOMIES 1–3 min,
 *       DISAPPEAR_REAPPEAR 1.5–4 min. Need-driven activities have no
 *       cooldown — they're gated by the underlying need.</li>
 *   <li><b>Post-activity rest.</b> After any non-IDLE step, the pet is forced
 *       to idle for 4–14 s before the engine considers another activity.</li>
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

            // Keep the pet window pinned to the topmost band even if another
            // app demoted us (focus / mode switches can do this). Cheap, no
            // focus theft.
            pet.reassertTopmost();

            // One-shot: if the pet was spawned off-screen, walk it into view
            // before any normal activity runs.
            pet.runPendingEntryWalk(world);

            // Reactions from other pets (DUCK while being jumped over, FLEE
            // when hunted) preempt normal activity selection so the visual
            // sequence stays coherent across the two engine threads.
            if (pet.hasActiveReaction()) {
                switch (pet.reaction) {
                    case DUCK -> pet.holdDuck();
                    case FLEE -> pet.fleeFrom(world);
                    case STEAL_BALL -> pet.stealBallFrom(world);
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

            if (pet.clicked) {
                pet.needs.add(Need.AFFECTION, 50);
                pet.onClicked();
            } else if (pet.hovered) {
                pet.hover();
            } else if (chosen != null) {
                if (!chosen.name().equals(lastChosenName)) {
                    Log.info("engine:" + pet.name, "→ " + chosen.name());
                    lastChosenName = chosen.name();
                }
                chosen.perform(pet, world);
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

        // Forced post-activity rest period — unless something is urgent.
        if (!urgent && nowMs < restUntilMs) {
            return Activities.IDLE;
        }

        double idleBoost = Math.max(0.05, 2.0 - activity);

        // Urgent: argmax — the lowest need decides deterministically.
        if (urgent) {
            Activity best = null;
            double bestScore = 0;
            for (Activity a : Activities.ALL) {
                if (pet.hovered || pet.clicked) {
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
            if (pet.hovered || pet.clicked) {
                return null;
            }
            if (a != Activities.IDLE) {
                Long next = nextEligibleAt.get(a.name());
                if (next != null && nowMs < next) {
                    continue;
                }
            }
            double raw = a.priority(pet, world) * pet.personality.multiplier(a.name());
            // IDLE scales with the activity-level gate (lethargic → more idle,
            // hyperactive → less). Non-IDLE activities are floored at 0.25×
            // raw so even "calm" pets still occasionally play / wander — the
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
        if (a == Activities.WANDER) {
            // 30..90 s — occasional pacing.
            return 30_000L + ThreadLocalRandom.current().nextLong(0, 60_000L);
        }
        if (a == Activities.DISAPPEAR_REAPPEAR) {
            // 90..240 s (1.5..4 min) — the "did the pet leave?" moment.
            return 90_000L + ThreadLocalRandom.current().nextLong(0, 150_000L);
        }
        if (a == Activities.ZOOMIES) {
            // 60..180 s (1..3 min) — sudden burst.
            return 60_000L + ThreadLocalRandom.current().nextLong(0, 120_000L);
        }
        if (a == Activities.PLAY_BALL) {
            // 45..120 s — so the ambient floor doesn't cause ball-spam, but
            // bored pets still get rapid play sessions (urgent path bypasses
            // cooldowns anyway).
            return 45_000L + ThreadLocalRandom.current().nextLong(0, 75_000L);
        }
        if (a == Activities.SEEK_PETTING) {
            // 40..100 s for the same reason as PLAY_BALL.
            return 40_000L + ThreadLocalRandom.current().nextLong(0, 60_000L);
        }
        if (a == Activities.GREET_PET) {
            // 60..180 s — visible but not constant.
            return 60_000L + ThreadLocalRandom.current().nextLong(0, 120_000L);
        }
        if (a == Activities.CHASE_PET) {
            // 90..240 s — sprinting at each other is rarer.
            return 90_000L + ThreadLocalRandom.current().nextLong(0, 150_000L);
        }
        if (a == Activities.HUNT_PET) {
            // 90..240 s — same rhythm as CHASE_PET so two simultaneous pursuit
            // activities don't collectively spam the room.
            return 90_000L + ThreadLocalRandom.current().nextLong(0, 150_000L);
        }
        if (a == Activities.HUNT_BIRD) {
            // 20..60 s — visitor birds are rare and self-dispose; we mostly
            // just need to suppress a re-pick in the seconds immediately
            // after the bird flies off (no visitor → priority is 0 anyway,
            // so this cooldown only matters if a SECOND bird arrives back-
            // to-back).
            return 20_000L + ThreadLocalRandom.current().nextLong(0, 40_000L);
        }
        if (a == Activities.HUNT_CURSOR) {
            // 60..180 s — chasing the mouse is a one-off comic moment; with
            // a shorter cooldown it would dominate any session where the user
            // is actively scrolling/clicking.
            return 60_000L + ThreadLocalRandom.current().nextLong(0, 120_000L);
        }
        if (a == Activities.SNIFF) {
            // 45..120 s — investigative interaction, mid-frequency.
            return 45_000L + ThreadLocalRandom.current().nextLong(0, 75_000L);
        }
        if (a == Activities.NUDGE) {
            // 60..180 s — playful bump, infrequent so it lands as a gag.
            return 60_000L + ThreadLocalRandom.current().nextLong(0, 120_000L);
        }
        if (a == Activities.TAG) {
            // 60..180 s — same rhythm as NUDGE so the room doesn't tag-spam.
            return 60_000L + ThreadLocalRandom.current().nextLong(0, 120_000L);
        }
        if (a == Activities.CHASE_TAIL) {
            // 50..130 s — frequent enough that you reliably see at least one
            // per session even with several pets competing for activities.
            return 50_000L + ThreadLocalRandom.current().nextLong(0, 80_000L);
        }
        if (a == Activities.HICCUP) {
            // 90..240 s — rare comic moment.
            return 90_000L + ThreadLocalRandom.current().nextLong(0, 150_000L);
        }
        if (a == Activities.STARGAZE) {
            // 120..300 s — calm pause, rare so it doesn't compete with other sits.
            return 120_000L + ThreadLocalRandom.current().nextLong(0, 180_000L);
        }
        if (a == Activities.BURST_OF_HEARTS) {
            // 90..240 s — pleasant affection moment, paced like HICCUP.
            return 90_000L + ThreadLocalRandom.current().nextLong(0, 150_000L);
        }
        if (a == Activities.HEAD_TILT) {
            // 60..150 s — curious moment, frequent enough to be recognisable.
            return 60_000L + ThreadLocalRandom.current().nextLong(0, 90_000L);
        }
        if (a == Activities.YAWN) {
            // 70..180 s — sleepy filler, paced so a chain of yawns is rare.
            return 70_000L + ThreadLocalRandom.current().nextLong(0, 110_000L);
        }
        if (a == Activities.MOON_GAZE) {
            // 150..360 s — rare nighttime mood piece.
            return 150_000L + ThreadLocalRandom.current().nextLong(0, 210_000L);
        }
        if (a == Activities.PACE) {
            // 90..210 s — anxious pacing, infrequent so it reads as a mood
            // shift rather than the pet's normal locomotion.
            return 90_000L + ThreadLocalRandom.current().nextLong(0, 120_000L);
        }
        if (a == Activities.LICK_PET) {
            // 90..210 s — sustained social grooming, similar pacing to NUDGE.
            return 90_000L + ThreadLocalRandom.current().nextLong(0, 120_000L);
        }
        if (a == Activities.LICK_EDGE) {
            // 150..330 s — silly one-off gag, kept rare so it stays funny.
            return 150_000L + ThreadLocalRandom.current().nextLong(0, 180_000L);
        }
        if (a == Activities.SPEAK) {
            // 45..120 s — pets should chime in often enough to feel alive
            // (especially with siblings around) but not so often that the
            // bubbles feel like spam.
            return 45_000L + ThreadLocalRandom.current().nextLong(0, 75_000L);
        }
        if (a == Activities.SCRATCH) {
            // 60..150 s — frequent enough that every pet visibly scratches a
            // couple of times per session, paced like CHASE_TAIL/HEAD_TILT so
            // it doesn't dominate the cosmetic-filler rotation.
            return 60_000L + ThreadLocalRandom.current().nextLong(0, 90_000L);
        }
        if (a == Activities.DANCE) {
            // 120..300 s — dance is a showpiece moment, kept rare so it
            // doesn't lose its impact. Paced like STARGAZE.
            return 120_000L + ThreadLocalRandom.current().nextLong(0, 180_000L);
        }
        if (a == Activities.MAKE_SPACE) {
            // Small cooldown so a pet that just shuffled aside doesn't
            // immediately re-trigger if its sibling chases the new spot.
            // The grace timer in the priority lambda already resists
            // re-arming for the first few seconds anyway.
            return 1_500L;
        }
        // Other need-driven activities (sleep/eat) have no cooldown — they're
        // naturally gated by their underlying need (priority 0 when satisfied).
        return 0L;
    }

    private static double clampActivity(double v) {
        if (Double.isNaN(v)) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(2.0, v));
    }
}
