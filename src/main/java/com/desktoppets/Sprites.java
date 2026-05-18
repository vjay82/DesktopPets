package com.desktoppets;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.attributes.ViewBox;
import com.github.weisj.jsvg.parser.SVGLoader;

/**
 * Loads sprite resources (PNG or SVG) from the classpath and serves
 * pre-scaled {@link ImageIcon} instances ready to be dropped into a {@link JLabel}.
 *
 * <p>Caches are concurrent because pets run on their own threads. Icon
 * application is marshalled to the EDT so this is safe to call from anywhere.
 */
final class Sprites {

    /** Sentinel cached for paths that don't resolve, so we don't re-call getResource. */
    private static final ImageIcon MISSING = new ImageIcon();

    private static final SVGLoader SVG = new SVGLoader();

    private static final Map<String, Object> SOURCE_CACHE = new ConcurrentHashMap<>();
    private static final Map<ScaledKey, ImageIcon> SCALED_CACHE = new ConcurrentHashMap<>();

    private Sprites() {
    }

    /** Format a numbered frame path, e.g. {@code "Sprites/Heart/tile007.png"}. */
    static String numbered(String directory, int index) {
        return String.format("%s/tile%03d.png", directory, index);
    }

    /**
     * Set a {@link JLabel}'s icon to the given classpath sprite, scaled once
     * and cached. The {@link JLabel#setIcon(javax.swing.Icon)} call is always
     * dispatched on the EDT.
     */
    static void apply(JLabel label, String key) {
        int w = label.getWidth();
        int h = label.getHeight();
        if (key == null || key.isEmpty() || w <= 0 || h <= 0) {
            onEdt(() -> label.setIcon(null));
            return;
        }
        if (Doodle.isDoodleKey(key)) {
            // Doodle assumes square icons; use the smaller dimension.
            int s = Math.min(w, h);
            ImageIcon di = Doodle.icon(key, s);
            onEdt(() -> label.setIcon(di));
            return;
        }
        ImageIcon scaled = SCALED_CACHE.computeIfAbsent(
                new ScaledKey(key, w, h),
                k -> render(k.path, k.w, k.h));
        ImageIcon finalIcon = scaled == MISSING ? null : scaled;
        onEdt(() -> label.setIcon(finalIcon));
    }

    /** Eagerly produce a scaled icon (e.g. for tray icon use). */
    static ImageIcon scaled(String key, int w, int h) {
        if (Doodle.isDoodleKey(key)) {
            return Doodle.icon(key, Math.min(w, h));
        }
        ImageIcon icon = SCALED_CACHE.computeIfAbsent(
                new ScaledKey(key, w, h), k -> render(k.path, k.w, k.h));
        return icon == MISSING ? null : icon;
    }

    /**
     * Walk a list of expected classpath paths; log a warning for each that
     * doesn't resolve. Returns the count of misses.
     */
    static int validate(Collection<String> paths) {
        int misses = 0;
        for (String p : paths) {
            if (p == null) {
                continue;
            }
            URL url = Sprites.class.getResource("/" + p);
            if (url == null) {
                System.err.println("[sprites] MISSING resource: " + p);
                misses++;
            }
        }
        return misses;
    }

    private static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    @SuppressWarnings("deprecation")
    private static ImageIcon render(String classpathPath, int w, int h) {
        Object source = SOURCE_CACHE.computeIfAbsent(classpathPath, Sprites::loadSource);
        if (source == MISSING) {
            return MISSING;
        }
        BufferedImage buffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = buffer.createGraphics();
        boolean failed = false;
        try {
            if (source instanceof ImageIcon raster) {
                // Raster icons (PNG) are existing high-res art; bilinear
                // + AA gives the smoothest scaled result.
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.drawImage(raster.getImage(), 0, 0, w, h, null);
            } else if (source instanceof SVGDocument svg) {
                // Pet sprites are pixel art (every SVG declares
                // shape-rendering="crispEdges") and many connect adjacent
                // body parts via 1-px-tall strips. Bilinear interpolation
                // blurs those thin strips to ~50% opacity and makes body
                // parts look detached (most visible on the 48\u219264 dog
                // where the body-to-legs cream strip vanishes into a gap).
                // Nearest-neighbour + no AA preserves crisp pixels and
                // keeps the strips solid.
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_SPEED);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_OFF);
                svg.render(null, g, new ViewBox(0, 0, w, h));
            }
        } catch (RuntimeException e) {
            // JSVG can throw on malformed/edge-case docs. Log once per path
            // and cache MISSING so subsequent ticks return cleanly instead
            // of re-throwing on every frame.
            failed = true;
            SOURCE_CACHE.put(classpathPath, MISSING);
            System.err.println("[sprites] render failed for " + classpathPath + ": " + e);
        } finally {
            g.dispose();
        }
        return failed ? MISSING : new ImageIcon(buffer);
    }

    private static Object loadSource(String classpathPath) {
        URL url = Sprites.class.getResource("/" + classpathPath);
        if (url == null) {
            return MISSING;
        }
        if (classpathPath.toLowerCase(Locale.ROOT).endsWith(".svg")) {
            try (InputStream in = url.openStream()) {
                SVGDocument doc = SVG.load(in);
                return doc != null ? doc : MISSING;
            } catch (IOException e) {
                return MISSING;
            }
        }
        return new ImageIcon(url);
    }

    private record ScaledKey(String path, int w, int h) {
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ScaledKey k)) {
                return false;
            }
            return w == k.w && h == k.h && path.equals(k.path);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, w, h);
        }
    }
}
