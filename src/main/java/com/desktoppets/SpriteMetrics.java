package com.desktoppets;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes geometric metrics (bounding box of all non-empty {@code <rect>}s
 * relative to the {@code viewBox}) from a sprite SVG on the classpath.
 *
 * <p>Used by {@link Pet#feetYRatio()} so a pet's "feet" automatically land on
 * the perch instead of its frame bottom — even if a new sprite is dropped in
 * with different padding. Cached per classpath path.
 *
 * <p>The parser is deliberately minimal — our procedural SVGs are flat
 * lists of {@code <rect x= y= width= height= fill=.../>} elements with no
 * transforms or groups, so a regex pass over the file text is sufficient and
 * needs no XML dependency.
 */
final class SpriteMetrics {

    /** Sensible fallback when no SVG / no rects are found. */
    static final Bounds DEFAULT = new Bounds(1.0, 0.62, 0.50);

    private static final Pattern VIEWBOX = Pattern.compile(
            "viewBox=\"\\s*(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?)"
            + "\\s+(\\d+(?:\\.\\d+)?)\\s+(\\d+(?:\\.\\d+)?)\\s*\"");
    private static final Pattern RECT = Pattern.compile("<rect\\s+([^>/]*)/?>");
    private static final Pattern X     = Pattern.compile("\\sx=\"(-?\\d+(?:\\.\\d+)?)\"");
    private static final Pattern Y     = Pattern.compile("\\sy=\"(-?\\d+(?:\\.\\d+)?)\"");
    private static final Pattern W     = Pattern.compile("\\swidth=\"(\\d+(?:\\.\\d+)?)\"");
    private static final Pattern H     = Pattern.compile("\\sheight=\"(\\d+(?:\\.\\d+)?)\"");

    private static final Map<String, Bounds> CACHE = new ConcurrentHashMap<>();

    private SpriteMetrics() {
    }

    /**
     * Metrics for the SVG at {@code classpathPath} (e.g.
     * {@code "Sprites/Ducky/Idle/tile000.svg"}). Returns {@link #DEFAULT} for
     * a missing file, unparseable viewBox, or no rects.
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
        String text;
        try (InputStream in = url.openStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return DEFAULT;
        }

        Matcher vb = VIEWBOX.matcher(text);
        if (!vb.find()) {
            return DEFAULT;
        }
        double vbX = Double.parseDouble(vb.group(1));
        double vbY = Double.parseDouble(vb.group(2));
        double vbW = Double.parseDouble(vb.group(3));
        double vbH = Double.parseDouble(vb.group(4));
        if (vbW <= 0 || vbH <= 0) {
            return DEFAULT;
        }

        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        boolean any = false;

        Matcher rm = RECT.matcher(text);
        while (rm.find()) {
            String attrs = " " + rm.group(1) + " "; // pad so leading-space-anchored patterns match
            Double x = matchDouble(X, attrs);
            Double y = matchDouble(Y, attrs);
            Double w = matchDouble(W, attrs);
            Double h = matchDouble(H, attrs);
            if (x == null || y == null || w == null || h == null) {
                continue;
            }
            if (w <= 0 || h <= 0) {
                continue;
            }
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x + w);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y + h);
            any = true;
        }
        if (!any) {
            return DEFAULT;
        }
        // Ratios are taken relative to the viewBox origin so a non-zero
        // viewBox offset (e.g. "viewBox='8 8 48 48'") still produces
        // correctly normalised 0..1 coordinates.
        double feetY    = clamp01((maxY - vbY) / vbH);
        double headTopY = clamp01((minY - vbY) / vbH);
        double centerX  = clamp01(((minX + maxX) / 2.0 - vbX) / vbW);
        return new Bounds(feetY, headTopY, centerX);
    }

    private static Double matchDouble(Pattern p, String attrs) {
        Matcher m = p.matcher(attrs);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * Geometric ratios within a sprite's viewBox (all 0..1):
     * <ul>
     *   <li>{@code feetYRatio} — Y of the bottom of the sprite's non-empty
     *       pixels, i.e. where the feet sit. 1.0 = bottom edge of the frame.</li>
     *   <li>{@code headTopYRatio} — Y of the top of the non-empty pixels.</li>
     *   <li>{@code bodyCenterXRatio} — horizontal centre of the bounding box.</li>
     * </ul>
     */
    record Bounds(double feetYRatio, double headTopYRatio, double bodyCenterXRatio) {
    }
}
