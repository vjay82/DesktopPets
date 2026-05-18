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
        // Use logicalLocation() rather than frame.getLocation(): the latter
        // returns the JFrame's CLIPPED bounds when the bird is partially
        // off-monitor (moveFrameTo narrows the frame for edge slides), so
        // start.x would be wrong and the flight would over- or undershoot.
        Point start = logicalLocation();
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
        int baseDelay = walkStepDelayMs();
        for (int i = 1; i <= steps; i++) {
            if (interrupted() || hovered || clicked) {
                break;
            }
            // Match base walkTo: ease-in/ease-out so take-offs and landings
            // have weight instead of starting/stopping abruptly.
            sleepInterruptible(easedFlightStepDelay(baseDelay, i, steps));
            if ((i - 1) % pixelsPerSpriteStep() == 0) {
                Sprites.apply(petLabel, frameSet.get(spriteIndex % frameSet.size()));
                spriteIndex++;
            }
            int signedStep = goingRight ? i : -i;
            int y = start.y + (int) Math.round(dyTotal * (i / (double) steps));
            moveFrameTo(start.x + signedStep, y);
        }
        // Hold the final wing-beat frame for one beat so the perch landing
        // reads as a settle instead of an abrupt cut to idle[0].
        sleepInterruptible(baseDelay);
        idle();
    }

    /** Mirror of Pet.easedStepDelay (private there); inlined to avoid
     *  widening that helper's visibility just for this subclass. */
    private static long easedFlightStepDelay(int baseDelay, int stepIndex1Based, int totalSteps) {
        int rampSteps = Math.min(8, Math.max(1, totalSteps / 4));
        int leftDist  = stepIndex1Based - 1;
        int rightDist = totalSteps - stepIndex1Based;
        int edgeDist  = Math.min(leftDist, rightDist);
        if (edgeDist >= rampSteps) {
            return baseDelay;
        }
        double mult = 1.0 + (rampSteps - edgeDist) / (double) rampSteps;
        return Math.round(baseDelay * mult);
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
        // visible window under the bird's body at the destination, fly to
        // its top directly; otherwise drop to the floor at targetX. We check
        // the bird's mid-column (targetX + petW/2) rather than just targetX,
        // so the bird doesn't pick a perch whose body it would mostly hang
        // off (only the leftmost pixel inside the window).
        int petW = effectiveWidth();
        int midX = targetX + petW / 2;
        for (Rectangle r : world.topmostWindows()) {
            if (midX >= r.x && midX < r.x + r.width) {
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
