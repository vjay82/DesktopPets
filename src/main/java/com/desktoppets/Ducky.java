package com.desktoppets;

/** The needy duck. Wants attention often; quacks (sort of) when clicked. */
public final class Ducky extends Pet {

    public Ducky() {
        super("ducky", Personality.ducky());
    }

    @Override protected String doodleKind() { return "ducky"; }

    @Override public String[] sounds() {
        return new String[] { "Quack!", "Quack quack", "Honk!", "Squeak!" };
    }
    @Override protected int walkStepDelayMs() { return 12; }
    @Override protected int spawnBottomOffset() { return (int) (petSize * 0.75); }

    // Ducky sprite sits in the bottom-right of its 64-viewBox. The heart
    // placement is artistic (slightly to the right of the head), so it stays
    // hand-tuned; feetYRatio is auto-detected from the idle SVG via
    // SpriteMetrics (â‰ˆ48/64 â†’ 0.75) and no longer needs an override here.
    @Override protected double heartCenterXRatio() { return 0.55; }
    @Override protected double heartTopYRatio()    { return 0.45; }

    @Override
    protected void attack() {
        // Brief look-around twitch as the duck's "attack" reaction.
        lookAround();
    }

    @Override
    public void onClicked() {
        Sprites.apply(petLabel, "ducky/sit");
        sleepInterruptible(500);
        clicked.set(false);
    }
}
