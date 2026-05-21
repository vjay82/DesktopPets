package com.desktoppets;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-pet behavioural profile. Two knobs:
 * <ul>
 *   <li>{@link #decayPerSecond(Need)} — how fast each need ticks down;</li>
 *   <li>{@link #multiplier(String)} — bias the priority of named
 *       {@link Activity}s so a Cat picks {@code wander} more than a Ducky.</li>
 * </ul>
 */
public final class Personality {

    private final Map<Need, Double> decay = new EnumMap<>(Need.class);
    private final Map<String, Double> activityBias = new HashMap<>();
    public final String label;

    private Personality(String label) {
        this.label = label;
        // Default ~0.05/sec means a need takes ~25 min to drop from 80 to 0.
        // Pet-specific overrides below tighten or relax this per need.
        for (Need n : Need.values()) {
            decay.put(n, 0.05);
        }
    }

    public double decayPerSecond(Need n) {
        return decay.get(n);
    }

    /** Bias factor for the named activity (1.0 by default). */
    public double multiplier(String activityName) {
        return activityBias.getOrDefault(activityName, 1.0);
    }

    private Personality with(Need n, double rate) {
        decay.put(n, rate);
        return this;
    }

    private Personality bias(String activityName, double multiplier) {
        activityBias.put(activityName, multiplier);
        return this;
    }

    /** Cat: aloof — low affection need, loves to wander/perch, stalks pointers. */
    public static Personality cat() {
        return new Personality("aloof")
                .with(Need.AFFECTION, 0.015)
                .with(Need.BOREDOM,   0.09)
                .with(Need.HUNGER,    0.06)
                .with(Need.THIRST,    0.07)
                .with(Need.ENERGY,    0.04)
                .bias("wander", 1.5)
                .bias("seek-petting", 0.6)
                // Cat-only activities.
                .bias("stalk-pointer", 1.0)
                .bias("high-perch-leap", 1.0)
                .bias("grooming", 1.0)
                .bias("knock-something-off", 1.0)
                // Pet-pet biases (aloof cat: less social, more competitive).
                .bias("converse", 0.7)
                .bias("join-dance", 0.6)
                .bias("startle", 1.3)
                .bias("nap-together", 1.2)
                .bias("follow-leader", 0.6)
                .bias("staring-contest", 1.4)
                .bias("share-food", 0.7)
                .bias("comfort-huddle", 0.9)
                // v2 activity biases (cat: aloof + agile + cursor-fixated).
                .bias("daydream", 1.1)
                .bias("sneeze", 1.0)
                .bias("butt-wiggle-pounce", 1.6)
                .bias("watch-clock", 1.0)
                .bias("screen-scratch", 1.5)
                .bias("roll-over", 0.0)        // cat doesn't do dog-style rolls
                .bias("inspect-window", 1.5)
                .bias("perch-nap", 1.5)
                .bias("window-hop", 1.6)
                .bias("stare-at-foreground", 0.7)
                .bias("pounce-cursor", 1.6)
                .bias("chase-laser", 1.6)
                .bias("type-buddy", 0.6)
                .bias("copycat", 0.6)
                .bias("gift", 0.5)
                .bias("groom-other", 1.0)
                .bias("parallel-pace", 0.8)
                .bias("bird-warning", 0.0)     // cat already hunts via HUNT_BIRD
                .bias("hourly-bark", 1.0)
                // Dog/Bird/Ducky-only activities — disabled.
                .bias("fetch-cursor", 0.0)
                .bias("greet-foreground", 0.0)
                .bias("dig", 0.0)
                .bias("flit", 0.0)
                .bias("circle", 0.0)
                .bias("startle-flush", 0.0)
                .bias("perch-sing", 0.0)
                .bias("waddle-loop", 0.0)
                .bias("crawl-sneak", 0.0)
                .bias("crouch-pout", 0.0)
                .bias("quack-combo", 0.0)
                .bias("follow-cursor", 0.0);
    }

    /** Dog: friendly — wants attention, loves to wander/play, fetches cursor, greets app switches. */
    public static Personality dog() {
        return new Personality("friendly")
                .with(Need.AFFECTION, 0.08)
                .with(Need.BOREDOM,   0.08)
                .with(Need.HUNGER,    0.09)
                .with(Need.THIRST,    0.10)
                .with(Need.ENERGY,    0.06)
                .bias("wander", 1.3)
                .bias("seek-petting", 1.3)
                .bias("play-ball", 1.2)
                .bias("zoomies", 1.2)
                // Dog-only activities.
                .bias("fetch-cursor", 1.0)
                .bias("greet-foreground", 1.0)
                .bias("dig", 1.0)
                // Pet-pet biases (friendly dog: very social).
                .bias("converse", 1.4)
                .bias("join-dance", 1.5)
                .bias("startle", 1.0)
                .bias("nap-together", 1.0)
                .bias("follow-leader", 1.4)
                .bias("staring-contest", 0.5)
                .bias("share-food", 1.3)
                .bias("comfort-huddle", 1.2)
                // v2 activity biases (dog: social + cursor-loving).
                .bias("daydream", 0.9)
                .bias("sneeze", 1.0)
                .bias("butt-wiggle-pounce", 0.7)
                .bias("watch-clock", 1.0)
                .bias("screen-scratch", 0.5)
                .bias("roll-over", 1.5)
                .bias("inspect-window", 1.2)
                .bias("perch-nap", 0.4)        // dogs rarely on perches
                .bias("window-hop", 0.0)
                .bias("stare-at-foreground", 1.2)
                .bias("pounce-cursor", 0.0)    // distinct from FETCH_CURSOR
                .bias("chase-laser", 0.0)
                .bias("type-buddy", 1.4)
                .bias("copycat", 1.2)
                .bias("gift", 1.5)
                .bias("groom-other", 0.6)
                .bias("parallel-pace", 1.2)
                .bias("bird-warning", 1.3)
                .bias("hourly-bark", 1.4)
                // Activities Dog does NOT do.
                .bias("stalk-pointer", 0.0)
                .bias("high-perch-leap", 0.0)
                .bias("grooming", 0.0)
                .bias("knock-something-off", 0.0)
                .bias("flit", 0.0)
                .bias("circle", 0.0)
                .bias("startle-flush", 0.0)
                .bias("perch-sing", 0.0)
                .bias("waddle-loop", 0.0)
                .bias("crawl-sneak", 0.0)
                .bias("crouch-pout", 0.0)
                .bias("quack-combo", 0.0)
                .bias("follow-cursor", 0.0)
                .bias("disappear-reappear", 0.0);
    }

    /** Ducky: needy — wants petting, waddles, follows the cursor, pouts when ignored. */
    public static Personality ducky() {
        return new Personality("needy")
                .with(Need.AFFECTION, 0.10)
                .with(Need.BOREDOM,   0.07)
                .with(Need.HUNGER,    0.08)
                .with(Need.THIRST,    0.11)
                .with(Need.ENERGY,    0.05)
                .bias("seek-petting", 1.6)
                .bias("play-ball", 1.4)
                // Ducky-only activities.
                .bias("waddle-loop", 1.0)
                .bias("crawl-sneak", 1.0)
                .bias("crouch-pout", 1.0)
                .bias("quack-combo", 1.0)
                .bias("follow-cursor", 1.0)
                // Pet-pet biases (needy duck: clingy and seeks comfort).
                .bias("converse", 1.3)
                .bias("join-dance", 1.0)
                .bias("startle", 0.4)
                .bias("nap-together", 1.4)
                .bias("follow-leader", 1.3)
                .bias("staring-contest", 0.6)
                .bias("share-food", 1.2)
                .bias("comfort-huddle", 1.6)
                // v2 activity biases (ducky: clingy + dreamy).
                .bias("daydream", 1.5)
                .bias("sneeze", 1.1)
                .bias("butt-wiggle-pounce", 0.3)
                .bias("watch-clock", 1.0)
                .bias("screen-scratch", 0.4)
                .bias("roll-over", 0.6)
                .bias("inspect-window", 1.0)
                .bias("perch-nap", 0.3)
                .bias("window-hop", 0.0)
                .bias("stare-at-foreground", 1.1)
                .bias("pounce-cursor", 0.0)
                .bias("chase-laser", 0.0)
                .bias("type-buddy", 1.5)
                .bias("copycat", 1.3)
                .bias("gift", 1.2)
                .bias("groom-other", 0.5)
                .bias("parallel-pace", 1.3)
                .bias("bird-warning", 1.4)
                .bias("hourly-bark", 1.2)
                // Activities Ducky does NOT do.
                .bias("stalk-pointer", 0.0)
                .bias("high-perch-leap", 0.0)
                .bias("grooming", 0.0)
                .bias("knock-something-off", 0.0)
                .bias("fetch-cursor", 0.0)
                .bias("greet-foreground", 0.0)
                .bias("dig", 0.0)
                .bias("flit", 0.0)
                .bias("circle", 0.0)
                .bias("startle-flush", 0.0)
                .bias("perch-sing", 0.0)
                .bias("wander", 0.5)
                .bias("zoomies", 0.0)
                .bias("disappear-reappear", 0.0);
    }

    /**
     * Bird: skittish, visitor-only. Bird never runs {@link BehaviorEngine}
     * â€” {@link Pet#run()} routes visitors through {@code runVisitorLoop()}
     * instead, so the activity-bias map is effectively unused at runtime.
     * Kept minimal: only the decay rates (consulted by {@link NeedSet#decay})
     * and the {@code climb-foreground} sanity entry asserted by {@code SmokeTest}
     * remain. If Bird is ever promoted back to a resident species, restore
     * the full bias table.
     */
    public static Personality bird() {
        return new Personality("skittish")
                .with(Need.AFFECTION, 0.02)
                .with(Need.BOREDOM,   0.04)
                .with(Need.HUNGER,    0.05)
                .with(Need.THIRST,    0.06)
                .with(Need.ENERGY,    0.10)
                // Sanity entry asserted by SmokeTest.personalityBiasDefaultsToOne.
                .bias("climb-foreground", 0.0);
    }
}
