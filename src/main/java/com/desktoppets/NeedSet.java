package com.desktoppets;

import java.util.EnumMap;
import java.util.Map;

/**
 * Mutable set of {@link Need} gauges, each in [0,100]. 100 = fully satisfied,
 * 0 = desperate. Decay rates are configured per-pet via {@link Personality}.
 *
 * <p>All mutation is synchronized so the behavior engine (background thread)
 * and the Swing UI (settings dialog tooltips, etc.) see consistent values.
 */
public final class NeedSet {

    private final Map<Need, Double> levels = new EnumMap<>(Need.class);

    public NeedSet() {
        for (Need n : Need.values()) {
            levels.put(n, 80.0);
        }
    }

    public synchronized double get(Need n) {
        return levels.get(n);
    }

    public synchronized void add(Need n, double delta) {
        levels.put(n, clamp(levels.get(n) + delta));
    }

    /**
     * Apply per-second decay using the given personality multipliers, scaled
     * by the per-pet activity slider so a hyperactive pet wears its needs
     * down (and therefore triggers play/seek-petting/etc.) noticeably faster
     * than a lethargic one. The factor is {@code max(0.25, activity)} so even
     * the lethargic end still decays gradually.
     */
    public synchronized void decay(Personality p, double seconds, double activityLevel) {
        double factor = Math.max(0.25, activityLevel);
        for (Need n : Need.values()) {
            levels.put(n, clamp(levels.get(n) - p.decayPerSecond(n) * seconds * factor));
        }
    }

    /** Returns the most-urgent need (lowest level), or null if all are above {@code threshold}. */
    public synchronized Need lowestBelow(double threshold) {
        Need worst = null;
        double worstLevel = threshold;
        for (Map.Entry<Need, Double> e : levels.entrySet()) {
            if (e.getValue() < worstLevel) {
                worst = e.getKey();
                worstLevel = e.getValue();
            }
        }
        return worst;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }
}
