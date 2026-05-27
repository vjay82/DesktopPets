package com.desktoppets;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.util.List;

/**
 * Polls the cursor + left-mouse-button state to drive pet hover and click
 * reactions. Necessary because the new {@link Stage} window is
 * click-through and therefore never receives native mouse events — without
 * this poller, hovering / clicking a pet would silently do nothing.
 *
 * <p>Hover model: at most one pet is marked {@code hovered} at a time
 * (whichever is topmost under the cursor in stage z-order, i.e. the last
 * one painted), and any previously-hovered pet whose bounds no longer
 * contain the cursor is cleared on the same tick. This mirrors the old
 * per-pet {@code MouseEntered / MouseExited} contract without depending on
 * native events.
 *
 * <p>Click model: a press is reported the tick we first see the left
 * button held down after seeing it released, so a quick click/release is
 * always observed once. The currently-hovered pet (if any) has its
 * {@code clicked} flag set, exactly like the legacy
 * {@code MouseAdapter.mouseClicked}.
 */
public final class PetMouse {

    /** ~30 FPS — fast enough for snappy hover transitions, slow enough to
     *  stay invisible on the CPU profiler. */
    private static final long POLL_INTERVAL_MS = 33L;

    private PetMouse() {
    }

    private static volatile boolean started;

    /** Start the daemon poller once. Subsequent calls are no-ops. */
    public static synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(PetMouse::loop, "pet-mouse-poller");
        t.setDaemon(true);
        t.start();
    }

    private static void loop() {
        boolean wasDown = false;
        Pet lastHovered = null;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                PointerInfo pi = MouseInfo.getPointerInfo();
                Point cursor = (pi != null) ? pi.getLocation() : null;
                Pet hover = (cursor != null) ? findHover(cursor) : null;
                if (hover != lastHovered) {
                    if (lastHovered != null) lastHovered.hovered = false;
                    if (hover != null) hover.hovered = true;
                    lastHovered = hover;
                } else if (hover != null) {
                    // Re-assert in case anything cleared it externally.
                    hover.hovered = true;
                }
                boolean down = Win32.isKeyDown(Win32.VK_LBUTTON);
                if (down && !wasDown && hover != null) {
                    hover.clicked.set(true);
                }
                wasDown = down;
            } catch (Throwable ignored) {
                // Display changes / shutdown races — keep polling.
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Topmost pet whose logical bounds contain the cursor, or null. */
    private static Pet findHover(Point cursor) {
        // Iterate ACTIVE_PETS in reverse so the most recently spawned (and
        // therefore stacked on top in the stage canvas) wins.
        List<Pet> pets = Pet.activePets();
        for (int i = pets.size() - 1; i >= 0; i--) {
            Pet p;
            try { p = pets.get(i); } catch (IndexOutOfBoundsException ex) { continue; }
            PetWindow w = p.frame;
            if (w == null || !w.isVisible()) continue;
            Rectangle r = new Rectangle(w.getX(), w.getY(), w.getWidth(), w.getHeight());
            if (r.contains(cursor)) {
                return p;
            }
        }
        return null;
    }
}
