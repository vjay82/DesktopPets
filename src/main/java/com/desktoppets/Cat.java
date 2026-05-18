package com.desktoppets;

/** The aloof cat. Stretches more than the others; sits when clicked. */
public final class Cat extends Pet {

    public Cat() {
        super("cat", Personality.cat());
    }

    @Override protected String doodleKind() { return "cat"; }

    @Override public String[] sounds() {
        return new String[] { "Meow", "Mrrow!", "Purrr", "Mrrr...", "Nya~" };
    }
    @Override protected int walkStepDelayMs() { return 6; }

    // Cat ships a dedicated 8-frame gallop sheet under Sprites/Cat/Run/{Left,Right}.
    @Override protected java.util.List<String> runLeftFrames()  { return frames("cat/run-left",  0, 7); }
    @Override protected java.util.List<String> runRightFrames() { return frames("cat/run-right", 0, 7); }
    @Override protected int runStepDelayMs() { return 3; }

    @Override
    public void onClicked() {
        boolean stretch = random(2) == 0;
        if (stretch) {
            stretch();
        } else {
            sit();
        }
        clicked.set(false);
    }
}
