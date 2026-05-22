package com.desktoppets;

/** The needy duck. Wants attention often; quacks (sort of) when clicked. */
public final class Ducky extends Pet {

    public Ducky() {
        super("ducky", Personality.ducky());
    }

    @Override protected String doodleKind() { return "ducky"; }

    /** Duckies peck at seeds. */
    @Override protected String foodPropKey() { return "prop/seed"; }

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

    /**
     * Ducks can't lower the front-half of their body the way a cat or dog
     * play-bows, so the duck signals "let's play!" by bopping up and down
     * in place — alternating crouch/stand three times — and then plants
     * the same {@link Reaction#HUNT} request on the invitee that the
     * default play-bow does. Visually distinct, semantically identical.
     */
    @Override
    public void playBow(Pet other) {
        for (int i = 0; i < 3; i++) {
            if (interrupted() || hovered || clicked.get()) return;
            applySprite("ducky/sit");      // crouch (resolved to crouch.svg)
            sleepInterruptible(300L);
            if (interrupted() || hovered || clicked.get()) return;
            applySprite("ducky/idle/0");   // stand
            if (i == 0) showEmote("sparkle", 700);
            else if (i == 1) showEmote("note", 600);
            sleepInterruptible(300L);
        }
        if (interrupted() || hovered || clicked.get()) return;
        int myMid = logicalLocation().x + effectiveWidth() / 2;
        other.requestReaction(Reaction.HUNT, 6_000L, myMid);
        idle();
    }
}
