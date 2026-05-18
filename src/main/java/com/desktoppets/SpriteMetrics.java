package com.desktoppets;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.attributes.ViewBox;
import com.github.weisj.jsvg.parser.SVGLoader;

/**
 * Computes geometric metrics (bounding box of the sprite's non-transparent
 * pixels relative to its viewBox) from a sprite SVG on the classpath.
 *
 * <p>Used by {@link Pet#feetYRatio()} so a pet's "feet" automatically land
 * on the perch instead of its frame bottom \u2014 even if a new sprite is
 * dropped in with different padding or drawn with arbitrary SVG primitives
 * ({@code <path>}, {@code <rect>}, {@code <polygon>}, \u2026). Cached per
 * classpath path.
 *
 * <p>Implementation: render the SVG via JSVG to a small ARGB buffer and
 * scan the alpha channel for the tight bounding box of non-transparent
 * pixels. This is the same renderer {@link Sprites} uses at runtime, so
 * the metrics always match what the user actually sees on screen.
 */
final class SpriteMetrics {

    /** Sensible fallback when no SVG / no opaque pixels are found. */
    static final Bounds DEFAULT = new Bounds(1.0, 0.0, 0.5);

    /**
     * Resolution we rasterize the sprite at to find its alpha bounding
     * box. Small enough to be cheap (each compute is one-shot per
     * sprite, cached forever after) and large enough that 1\u00a0px of
     * padding maps to ~1.5% of the viewBox \u2014 well within the
     * precision callers need for foot-placement.
     */
    private static final int PROBE_SIZE = 64;
    /**
     * Alpha threshold: pixels with at-or-above this 0..255 value count as
     * "ink". Anti-aliasing on edges yields semi-transparent pixels we
     * want to keep, but JSVG's pixel-art renderer uses nearest-neighbour
     * (no AA on edges) so values are usually 0 or 255 anyway.
     */
    private static final int ALPHA_THRESHOLD = 8;

    private static final SVGLoader LOADER = new SVGLoader();

    private static final Map<String, Bounds> CACHE = new ConcurrentHashMap<>();

    private SpriteMetrics() {
    }

    /**
     * Metrics for the SVG at {@code classpathPath} (e.g.
     * {@code "Sprites/Ducky/Idle/tile000.svg"}). Returns {@link #DEFAULT}
     * for a missing file, unparseable SVG, or a fully-transparent image.
     */
    static Bounds of(String classpathPath) {
        if (classpathPath == null) {
            return DEFAULT;
        }
        return CACHE.computeIfAbsent(classpathPath, SpriteMetrics::compute);
    }

    private static Bounds compute(String classpathPath) {
        URL url = SpriteMetrics.class.getResource("/" + classpathPath);
        if (url == null) {
            return DEFAULT;
        }
        SVGDocument doc;
        try (InputStream in = url.openStream()) {
            doc = LOADER.load(in);
        } catch (IOException e) {
            return DEFAULT;
        }
        if (doc == null) {
            return DEFAULT;
        }

        BufferedImage buffer = new BufferedImage(
                PROBE_SIZE, PROBE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = buffer.createGraphics();
        try {
            // Match the runtime renderer (Sprites.render) so the probe
            // sees exactly the pixels the user does \u2014 pixel-art
            // sprites use NN + no-AA, so semi-transparent edges from
            // bilinear scaling don't skew the bounding box.
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_SPEED);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_OFF);
            doc.render(null, g, new ViewBox(0, 0, PROBE_SIZE, PROBE_SIZE));
        } catch (RuntimeException e) {
            return DEFAULT;
        } finally {
            g.dispose();
        }

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int y = 0; y < PROBE_SIZE; y++) {
            for (int x = 0; x < PROBE_SIZE; x++) {
                int argb = buffer.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha >= ALPHA_THRESHOLD) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX) {
            return DEFAULT;
        }
        // Bounding box is in PROBE_SIZE pixel coordinates. Convert to
        // 0..1 ratios within the viewBox \u2014 the viewBox is implicitly
        // (0, 0, PROBE_SIZE, PROBE_SIZE) because that's what we rendered
        // into. The feet sit at the bottom of the opaque region; "+1"
        // turns the inclusive max pixel index into an exclusive bound so
        // a sprite that fills every row down to the last one yields 1.0.
        double feetY    = clamp01((maxY + 1.0) / PROBE_SIZE);
        double headTopY = clamp01(minY / (double) PROBE_SIZE);
        double centerX  = clamp01(((minX + maxX + 1.0) / 2.0) / PROBE_SIZE);
        return new Bounds(feetY, headTopY, centerX);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * Geometric ratios within a sprite's viewBox (all 0..1):
     * <ul>
     *   <li>{@code feetYRatio} \u2014 Y of the bottom of the sprite's
     *       non-empty pixels, i.e. where the feet sit. 1.0 = bottom edge
     *       of the frame.</li>
     *   <li>{@code headTopYRatio} \u2014 Y of the top of the non-empty
     *       pixels.</li>
     *   <li>{@code bodyCenterXRatio} \u2014 horizontal centre of the
     *       bounding box.</li>
     * </ul>
     */
    record Bounds(double feetYRatio, double headTopYRatio, double bodyCenterXRatio) {
    }
}
