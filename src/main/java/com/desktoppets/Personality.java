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
                .with(Need.ENERGY,    0.04)
                .bias("wander", 1.5)
                .bias("seek-petting", 0.6)
                // Cat-only activities.
                .bias("stalk-pointer", 1.0)
                .bias("high-perch-leap", 1.0)
                .bias("grooming", 1.0)
                .bias("knock-something-off", 1.0)
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
                .with(Need.ENERGY,    0.06)
                .bias("wander", 1.3)
                .bias("seek-petting", 1.3)
                .bias("play-ball", 1.2)
                .bias("zoomies", 1.2)
                // Dog-only activities.
                .bias("fetch-cursor", 1.0)
                .bias("greet-foreground", 1.0)
                .bias("dig", 1.0)
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
                .with(Need.ENERGY,    0.05)
                .bias("seek-petting", 1.6)
                .bias("play-ball", 1.4)
                // Ducky-only activities.
                .bias("waddle-loop", 1.0)
                .bias("crawl-sneak", 1.0)
                .bias("crouch-pout", 1.0)
                .bias("quack-combo", 1.0)
                .bias("follow-cursor", 1.0)
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

    /** Bird: skittish — sleeps often, flits between perches, startles easily, sings on perch. */
    public static Personality bird() {
        return new Personality("skittish")
                .with(Need.AFFECTION, 0.02)
                .with(Need.BOREDOM,   0.04)
                .with(Need.HUNGER,    0.05)
                .with(Need.ENERGY,    0.10)
                .bias("seek-petting", 0.0)
                // Bird never "climb"s, but the activity is gone; the 0.0 entry
                // is asserted by SmokeTest as a sanity check on the bias map.
                .bias("climb-foreground", 0.0)
                .bias("sleep", 1.3)
                .bias("disappear-reappear", 1.5)
                // Bird-only activities.
                .bias("flit", 1.0)
                .bias("circle", 1.0)
                .bias("startle-flush", 1.0)
                .bias("perch-sing", 1.0)
                // Activities Bird does NOT do.
                .bias("stalk-pointer", 0.0)
                .bias("high-perch-leap", 0.0)
                .bias("grooming", 0.0)
                .bias("knock-something-off", 0.0)
                .bias("fetch-cursor", 0.0)
                .bias("greet-foreground", 0.0)
                .bias("dig", 0.0)
                .bias("waddle-loop", 0.0)
                .bias("crawl-sneak", 0.0)
                .bias("crouch-pout", 0.0)
                .bias("quack-combo", 0.0)
                .bias("follow-cursor", 0.0)
                .bias("zoomies", 0.0)
                .bias("play-ball", 0.0);
    }
}
