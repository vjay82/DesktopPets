package com.desktoppets;

/**
 * Derived mood used to flavour idle behaviour. Computed from the worst need
 * and average satisfaction.
 */
public enum Mood {
    /** All needs above 60. */
    CONTENT,
    /** Some needs in the 20..60 range. */
    NEUTRAL,
    /** At least one need below 20 — pet is grumpy / sad. */
    DISTRESSED;

    public static Mood from(NeedSet needs) {
        double sum = 0;
        double min = 100;
        for (Need n : Need.values()) {
            double v = needs.get(n);
            sum += v;
            if (v < min) {
                min = v;
            }
        }
        double avg = sum / Need.values().length;
        if (min < 20) {
            return DISTRESSED;
        }
        return avg > 60 ? CONTENT : NEUTRAL;
    }
}
