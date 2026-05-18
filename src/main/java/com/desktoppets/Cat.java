package com.desktoppets;

/** The aloof cat. Stretches more than the others; sits when clicked. */
public final class Cat extends Pet {

    public Cat() {
        super("cat", Personality.cat());
    }

    @Override protected String doodleKind() { return "cat"; }
    @Override protected int walkStepDelayMs() { return 6; }

    @Override
    public void onClicked() {
        boolean stretch = random(2) == 0;
        if (stretch) {
            stretch();
        } else {
            sit();
        }
        clicked = false;
    }
}
