package com.desktoppets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.util.List;

import javax.swing.ImageIcon;

import org.junit.jupiter.api.Test;

/**
 * Headless smoke tests. Runs in {@code java.awt.headless=true} (set by
 * surefire) so they pass on CI without a display server.
 *
 * <p>Since all graphics are now procedural ({@link Doodle}), there are no
 * sprite resources to validate. Instead we render at a few sizes to confirm
 * Doodle produces non-null icons for every kind/state we use.
 */
final class SmokeTest {

    @Test
    void doodleRendersAllKindsAndStates() {
        String[] kinds = {"ducky", "cat", "dog", "bird"};
        String[] states = {"idle/0", "idle/1", "idle/2",
                           "walk-left/0", "walk-right/0",
                           "sit", "sleep", "stretch", "look/0", "look/1"};
        for (String k : kinds) {
            for (String s : states) {
                ImageIcon icon = Doodle.icon(k + "/" + s, 64);
                assertNotNull(icon, k + "/" + s + " produced null");
                assertEquals(64, icon.getIconWidth());
                assertEquals(64, icon.getIconHeight());
            }
        }
    }

    @Test
    void doodleRendersHeartsAndProps() {
        for (int i = 0; i < 24; i++) {
            assertNotNull(Doodle.icon("heart/" + i, 32));
        }
        for (String prop : new String[]{"ball", "food", "zzz", "tray"}) {
            assertNotNull(Doodle.icon("prop/" + prop, 32));
        }
    }

    @Test
    void doodleScalesFrom16To128() {
        for (int s : new int[]{16, 32, 64, 96, 128}) {
            ImageIcon icon = Doodle.icon("ducky/idle/0", s);
            assertNotNull(icon);
            assertEquals(s, icon.getIconWidth());
        }
    }

    @Test
    void needsDecayWithinBounds() {
        NeedSet n = new NeedSet();
        Personality p = Personality.cat();
        n.decay(p, 5.0, 1.0);
        for (Need need : Need.values()) {
            double v = n.get(need);
            assertTrue(v >= 0 && v <= 100, need + " out of range: " + v);
        }
    }

    @Test
    void moodFromNeeds() {
        NeedSet n = new NeedSet();
        assertEquals(Mood.CONTENT, Mood.from(n));
        n.add(Need.HUNGER, -85);
        assertEquals(Mood.DISTRESSED, Mood.from(n));
    }

    @Test
    void personalityBiasDefaultsToOne() {
        Personality p = Personality.bird();
        assertEquals(1.0, p.multiplier("nonexistent-activity"));
        assertEquals(0.0, p.multiplier("climb-foreground"), "bird never climbs");
    }

    @Test
    void allActivitiesHaveNames() {
        for (Activity a : Activities.ALL) {
            assertNotNull(a.name());
            assertFalse(a.name().isEmpty());
        }
    }

    @Test
    void configSizeAndActivityHaveSaneDefaults() {
        assertTrue(Config.DEFAULT_SIZE >= 16 && Config.DEFAULT_SIZE <= 128);
        assertTrue(Config.DEFAULT_ACTIVITY >= 0 && Config.DEFAULT_ACTIVITY <= 2);
    }

    @Test
    void floorYIsGravityBasedAndDoesNotJumpUp() {
        // Gravity model: pet only falls to surfaces at-or-below its current
        // feet. A window that opens HIGHER than the pet must NEVER pull the
        // pet upward.
        Rectangle high = new Rectangle(100, 200, 400, 50); // top of screen
        Rectangle mid  = new Rectangle(100, 600, 400, 50); // middle
        World w = new World(1920, 1080, null, null, List.of(high, mid), 0);

        // Pet currently on the desktop floor at feet=1080: no surface
        // qualifies (both are above), stays on the desktop floor.
        assertEquals(1080 - 64, w.floorY(64, 200, 64, 1080));

        // Pet currently on `mid` (feet=600): stays on `mid`, ignores `high`.
        assertEquals(600 - 64, w.floorY(64, 200, 64, 600));

        // Pet currently above `mid` (feet=500) but below `high` (above 200):
        // only `mid` is at-or-below \u2014 falls onto `mid`.
        assertEquals(600 - 64, w.floorY(64, 200, 64, 500));

        // Pet currently above EVERYTHING (feet=100): falls onto the
        // HIGHEST surface below \u2014 `high` at y=200.
        assertEquals(200 - 64, w.floorY(64, 200, 64, 100));

        // No horizontal overlap \u2014 desktop floor regardless of currentFeetY.
        assertEquals(1080 - 64, w.floorY(64, 20, 64, 600));
    }

    @Test
    void floorYStaysOnSurfaceDespiteRoundingNoise() {
        // currentFeetY drifts by a few pixels (DPI rounding etc.). The pet
        // should still recognise it's standing on the same window top.
        Rectangle perch = new Rectangle(100, 600, 400, 50);
        World w = new World(1920, 1080, null, null, List.of(perch), 0);
        // 3 px above the perch's top \u2014 within tolerance (4 px) \u2014 perch counts.
        assertEquals(600 - 64, w.floorY(64, 200, 64, 597));
    }

    @Test
    void floorYIgnoresOffScreenWindows() {
        Rectangle aboveTop = new Rectangle(100, -200, 400, 100); // top off-screen
        World w = new World(1920, 1080, null, null, List.of(aboveTop), 0);
        // Off-screen window is skipped \u2014 falls to desktop floor regardless
        // of where the pet currently is.
        assertEquals(1080 - 64, w.floorY(64, 200, 64, 1080));
    }

    @Test
    void spriteMetricsDetectDuckyPadding() {
        // The duck idle sprite has ~16 rows of transparent padding under
        // its feet inside a 64×64 viewBox, so feetYRatio should be ≈ 0.75.
        SpriteMetrics.Bounds b =
                SpriteMetrics.of(Doodle.resolve("ducky/idle/0"));
        assertTrue(b.feetYRatio() < 0.85,
                "ducky feetYRatio expected ≲0.8 but was " + b.feetYRatio());
        assertTrue(b.feetYRatio() > 0.65,
                "ducky feetYRatio expected ≳0.7 but was " + b.feetYRatio());
    }

    @Test
    void spriteMetricsCatFillsFrame() {
        // Cat idle sprite fills its 32-viewBox down to the bottom row.
        SpriteMetrics.Bounds b = SpriteMetrics.of(Doodle.resolve("cat/idle/0"));
        assertTrue(b.feetYRatio() > 0.95,
                "cat feetYRatio expected ≈1.0 but was " + b.feetYRatio());
    }

    @Test
    void spriteMetricsMissingReturnsDefault() {
        SpriteMetrics.Bounds b = SpriteMetrics.of("Sprites/Does/Not/Exist.svg");
        assertEquals(SpriteMetrics.DEFAULT, b);
    }
}
