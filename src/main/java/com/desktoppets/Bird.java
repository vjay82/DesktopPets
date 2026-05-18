package com.desktoppets;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

/**
 * The skittish bird. Flies diagonally instead of walking horizontally; via
 * its Personality bias it ignores the foreground-window "climb" activity
 * entirely (a bird wouldn't perch on a moving window).
 */
public final class Bird extends Pet {

    public Bird() {
        super("bird", Personality.bird());
    }

    @Override protected String doodleKind() { return "bird"; }
    @Override protected int walkStepDelayMs() { return 8; }

    // Bird sprite sits middle-bottom of its 32-viewBox (body ~x=13-18, head top ~y=19).
    @Override protected double heartCenterXRatio() { return 0.48; }
    @Override protected double heartTopYRatio()    { return 0.60; }

    /**
     * Diagonal flight: linear interpolation in both X and Y from the current
     * location to the caller-supplied perch. Smooth instead of slope-based
     * so the last step actually lands exactly on the target (the previous
     * loop ended one pixel short).
     */
    @Override
    public void walkTo(int targetX, int targetY) {
        Point start = frame.getLocation();
        int dx = targetX - start.x;
        if (dx == 0) {
            idle();
            return;
        }
        int dyTotal = targetY - start.y;
        boolean goingRight = dx > 0;
        List<String> frameSet = goingRight ? walkRightFrames() : walkLeftFrames();
        int steps = Math.abs(dx);
        int spriteIndex = 0;
        for (int i = 1; i <= steps; i++) {
            if (interrupted() || hovered || clicked) {
                break;
            }
            sleepInterruptible(walkStepDelayMs());
            if ((i - 1) % pixelsPerSpriteStep() == 0) {
                Sprites.apply(petLabel, frameSet.get(spriteIndex % frameSet.size()));
                spriteIndex++;
            }
            int signedStep = goingRight ? i : -i;
            int y = start.y + (int) Math.round(dyTotal * (i / (double) steps));
            moveFrameTo(start.x + signedStep, y);
        }
        idle();
    }

    /**
     * Birds fly. Picking up the floor profile at every step (the default
     * {@link Pet#walkAlongFloor} behaviour) would make the bird hug the
     * ground or a perch top instead of flying diagonally through midair to
     * its target, so we forward to the diagonal {@link #walkTo} using a
     * perch top \u2014 or current floor \u2014 as the destination Y.
     */
    @Override
    public void walkAlongFloor(World world, int targetX) {
        int targetY = floorYAt(world, targetX);
        // Birds prefer high perches: if a window is the topmost (z-order)
        // visible window at targetX, fly to its top directly; otherwise drop
        // to the floor at targetX. We only consider the FIRST overlap so the
        // bird doesn't try to land on a window that's hidden behind another.
        for (Rectangle r : world.topmostWindows()) {
            if (targetX >= r.x && targetX < r.x + r.width) {
                int feetH = Math.max(1, (int) Math.round(effectiveHeight() * feetYRatio()));
                int candidate = r.y - feetH;
                if (candidate >= 0 && candidate < targetY) {
                    targetY = candidate;
                }
                break; // first visible window in z-order wins
            }
        }
        walkTo(targetX, targetY);
    }
}
