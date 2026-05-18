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

            pet.needs.decay(pet.personality, seconds);

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
        // Forced rest 4..14 s before the engine considers another activity.
        long rest = 4_000L + ThreadLocalRandom.current().nextLong(0, 10_000L);
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
        // Need-driven activities (sleep/eat/seek-petting/play-ball) have no
        // cooldown — they're naturally gated by their underlying need.
        return 0L;
    }

    private static double clampActivity(double v) {
        if (Double.isNaN(v)) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(2.0, v));
    }
}
