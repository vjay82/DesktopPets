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

    // 4-frame sit with tail wagging up-and-down (Sprites/Dog/Sit/tile000-003).
    @Override protected java.util.List<String> sitFrames() {
        return java.util.List.of("dog/sit/0", "dog/sit/1", "dog/sit/2", "dog/sit/3");
    }

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

    /**
     * Dog-specific scratch: derived from the sit pose with three
     * rocking-tilt frames (see tools/gen-dog-scratch-frames.ps1). The
     * frames already encode the body motion, so we cycle through them
     * directly rather than alternating with a {@code look/1} placeholder
     * the way the base {@link Pet#scratch()} does. ~150 ms per frame
     * over 9 frames = ~1.35 s of visible jitter, comparable to the
     * base loop's total duration.
     */
    @Override
    public final void scratch() {
        Sprites.apply(emoteLabel, "emote/paw");
        applySprite("dog/sit");
        sleepInterruptible(220);
        for (int i = 0; i < 9 && !interrupted(); i++) {
            applySprite("dog/scratch/" + (i % 3));
            sleepInterruptible(150);
        }
        if (!interrupted()) {
            applySprite("dog/sit");
            sleepInterruptible(180);
        }
        Sprites.apply(emoteLabel, null);
        needs.add(Need.BOREDOM, 20);
    }
}
