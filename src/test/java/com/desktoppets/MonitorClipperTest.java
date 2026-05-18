package com.desktoppets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import org.junit.jupiter.api.Test;

/**
 * Pure-math tests for {@link MonitorClipper#clip} — no Swing involved, so
 * these run in headless CI. Mirrors the cases the spawn / walk / disappear
 * code paths produce.
 */
final class MonitorClipperTest {

    private static final Rectangle MON = new Rectangle(0, 0, 1920, 1080);
    private static final int PET = 128;

    @Test
    void fullyInside_unchanged() {
        MonitorClipper.Clip c = MonitorClipper.clip(500, 200, PET, MON);
        assertFalse(c.hidden());
        assertNotNull(c.bounds());
        assertEquals(new Rectangle(500, 200, PET, PET), c.bounds());
        assertEquals(0, c.offsetX());
    }

    @Test
    void halfOffRight_clipsWidth_noOffset() {
        // Right edge at 1920; pet at x=1860 spans 1860..1988 → visible 1860..1920 (w=60)
        MonitorClipper.Clip c = MonitorClipper.clip(1860, 500, PET, MON);
        assertFalse(c.hidden());
        assertEquals(new Rectangle(1860, 500, 60, PET), c.bounds());
        assertEquals(0, c.offsetX(), "right-side clip never shifts sprite");
    }

    @Test
    void halfOffLeft_clipsWidth_shiftsSpriteLeft() {
        // Pet at x=-60 spans -60..68 → visible 0..68 (w=68); sprite must shift -60.
        MonitorClipper.Clip c = MonitorClipper.clip(-60, 500, PET, MON);
        assertFalse(c.hidden());
        assertEquals(new Rectangle(0, 500, 68, PET), c.bounds());
        assertEquals(60, c.offsetX(), "left-side clip hides 60 sprite columns");
    }

    @Test
    void fullyOffRight_hidden() {
        MonitorClipper.Clip c = MonitorClipper.clip(MON.x + MON.width, 500, PET, MON);
        assertTrue(c.hidden());
        assertNull(c.bounds());
    }

    @Test
    void fullyOffLeft_hidden() {
        MonitorClipper.Clip c = MonitorClipper.clip(-PET, 500, PET, MON);
        assertTrue(c.hidden());
        assertNull(c.bounds());
    }

    @Test
    void flushAtRightEdge_oneVisibleColumn() {
        // Spawn slide-in starts with a 1-px sliver flush at the inside edge.
        // Intended x = mon.right - 1; clip should return width=1.
        MonitorClipper.Clip c = MonitorClipper.clip(MON.x + MON.width - 1, 500, PET, MON);
        assertFalse(c.hidden());
        assertEquals(1, c.bounds().width);
    }

    @Test
    void nullMonitor_returnsUnclipped() {
        MonitorClipper.Clip c = MonitorClipper.clip(-9999, 500, PET, null);
        assertFalse(c.hidden());
        assertEquals(new Rectangle(-9999, 500, PET, PET), c.bounds());
    }

    @Test
    void secondaryMonitorOffset_clipsRelativeToItsOrigin() {
        // Monitor sitting at (1920,0) 1920x1080 — common second-display layout.
        Rectangle right = new Rectangle(1920, 0, 1920, 1080);
        // Pet half off the left edge of THAT monitor (intended x=1880).
        MonitorClipper.Clip c = MonitorClipper.clip(1880, 100, PET, right);
        assertEquals(new Rectangle(1920, 100, 88, PET), c.bounds());
        assertEquals(40, c.offsetX());
    }
}
