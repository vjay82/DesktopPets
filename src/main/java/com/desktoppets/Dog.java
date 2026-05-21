package com.desktoppets;

/** The cheerful dog. Wags its tail more than the others; barks (sort of) when clicked. */
public final class Dog extends Pet {

    public Dog() {
        super("dog", Personality.dog());
    }

    @Override protected String doodleKind() { return "dog"; }

    /** Dogs chew bones. */
    @Override protected String foodPropKey() { return "prop/bone"; }
    @Override protected int walkStepDelayMs() { return 7; }

    @Override public String[] sounds() {
        return new String[] { "Woof!", "Bark!", "Arf!", "Bork!", "Ruff!" };
    }

    // Dog ships a 4-frame gallop sheet (tongue out, dust streak) under Sprites/Dog/Run/{Left,Right}.
    @Override protected java.util.List<String> runLeftFrames()  { return frames("dog/run-left",  0, 3); }
    @Override protected java.util.List<String> runRightFrames() { return frames("dog/run-right", 0, 3); }
    @Override protected int runStepDelayMs() { return 3; }

    @Override
    public void onClicked() {
        // Half the time stretch (happy dog), half the time sit (good boy).
        boolean stretch = random(2) == 0;
        if (stretch) {
            stretch();
        } else {
            sit();
        }
        clicked.set(false);
    }
}
