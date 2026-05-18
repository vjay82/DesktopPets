package com.desktoppets;

/** The cheerful dog. Wags its tail more than the others; barks (sort of) when clicked. */
public final class Dog extends Pet {

    public Dog() {
        super("dog", Personality.dog());
    }

    @Override protected String doodleKind() { return "dog"; }
    @Override protected int walkStepDelayMs() { return 7; }

    @Override
    public void onClicked() {
        // Half the time stretch (happy dog), half the time sit (good boy).
        boolean stretch = random(2) == 0;
        if (stretch) {
            stretch();
        } else {
            sit();
        }
        clicked = false;
    }
}
