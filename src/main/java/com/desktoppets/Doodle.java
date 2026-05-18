package com.desktoppets;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.ImageIcon;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.attributes.ViewBox;
import com.github.weisj.jsvg.parser.SVGLoader;

/**
 * Resolves logical sprite keys (e.g. {@code "cat/walk-left/3"}, {@code "heart/12"},
 * {@code "prop/ball"}) to classpath SVG resources under {@code Sprites/} and
 * rasterises them at the requested pixel size. The SVGs themselves are produced
 * once by {@link com.desktoppets.tools.PngToSvg} from the original PNG tiles —
 * one {@code <rect>} per pixel run — so they render pixel-perfect at any scale.
 *
 * <p>The class keeps the name {@code Doodle} for backwards compatibility with
 * {@link Sprites#apply(javax.swing.JLabel, String)} which dispatches to it by
 * key prefix. There is no procedural drawing anymore.
 */
final class Doodle {

    static final String PREFIX = "doodle:";

    private static final SVGLoader SVG = new SVGLoader();
    private static final Map<String, Object> SOURCE_CACHE = new ConcurrentHashMap<>();
    private static final Map<Key, ImageIcon> CACHE = new ConcurrentHashMap<>();
    private static final Object MISSING = new Object();

    private Doodle() {
    }

    static ImageIcon icon(String key, int size) {
        if (size <= 0 || key == null || key.isEmpty()) {
            return null;
        }
        String normalized = key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key;
        return CACHE.computeIfAbsent(new Key(normalized, size), Doodle::render);
    }

    static boolean isDoodleKey(String key) {
        if (key == null) {
            return false;
        }
        if (key.startsWith(PREFIX)) {
            return true;
        }
        return key.startsWith("ducky/") || key.startsWith("cat/") || key.startsWith("dog/")
                || key.startsWith("bird/") || key.startsWith("heart/") || key.startsWith("prop/");
    }

    private static ImageIcon render(Key k) {
        String path = resolve(k.key);
        if (path == null) {
            return blank(k.size);
        }
        Object source = SOURCE_CACHE.computeIfAbsent(path, Doodle::loadSource);
        if (source == MISSING) {
            return blank(k.size);
        }
        BufferedImage img = new BufferedImage(k.size, k.size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
            if (source instanceof SVGDocument svg) {
                svg.render(null, g, new ViewBox(0, 0, k.size, k.size));
            }
        } finally {
            g.dispose();
        }
        return new ImageIcon(img);
    }

    private static ImageIcon blank(int size) {
        return new ImageIcon(new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB));
    }

    private static Object loadSource(String classpathPath) {
        URL url = Doodle.class.getResource("/" + classpathPath);
        if (url == null) {
            return MISSING;
        }
        try (InputStream in = url.openStream()) {
            SVGDocument doc = SVG.load(in);
            return doc != null ? doc : MISSING;
        } catch (IOException e) {
            return MISSING;
        }
    }

    /**
     * Map a logical sprite key (e.g. {@code "ducky/idle/0"}) to its
     * classpath SVG path (e.g. {@code "Sprites/Ducky/Idle/tile000.svg"}).
     * Package-private so {@link SpriteMetrics} can scan the chosen sprite.
     */
    static String resolve(String key) {
        if (key == null) {
            return null;
        }
        String normalized = key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key;
        String[] p = normalized.split("/");
        if (p.length == 0) {
            return null;
        }
        switch (p[0]) {
            case "cat":   return resolveCat(p);
            case "dog":   return resolveDog(p);
            case "ducky": return resolveDucky(p);
            case "bird":  return resolveBird(p);
            case "heart": return tile("Heart", safeInt(p, 1, 0) % 24);
            case "prop":  return prop(p.length > 1 ? p[1] : "");
            case "emote": return emote(p.length > 1 ? p[1] : "");
            default:      return null;
        }
    }

    private static String emote(String name) {
        switch (name) {
            case "sparkle":    return "Sprites/Emote/sparkle.svg";
            case "note":       return "Sprites/Emote/note.svg";
            case "bang":       return "Sprites/Emote/bang.svg";
            case "paw":        return "Sprites/Emote/paw.svg";
            case "drop":       return "Sprites/Emote/drop.svg";
            case "mini-heart": return "Sprites/Emote/mini-heart.svg";
            case "target":     return "Sprites/Emote/target.svg";
            default:           return null;
        }
    }

    private static String resolveCat(String[] p) {
        // Cat/Idle tiles 000-004 are full-figure frames; 005-007 are near-empty
        // placeholder slots from the original sheet, so we avoid them.
        // sleep.png / idle1.png / etc. are wide multi-frame sheets and would
        // render squashed into a square label, so we substitute an idle tile.
        if (p.length < 2) return tile("Cat/Idle", 0);
        String state = p[1];
        int i = safeInt(p, 2, 0);
        switch (state) {
            case "idle":       return tile("Cat/Idle",       i % 5);
            case "walk-left":  return tile("Cat/Walk/Left",  i % 8);
            case "walk-right": return tile("Cat/Walk/Right", i % 8);
            case "sit":        return tile("Cat/Idle", 0);
            case "stretch":    return tile("Cat/Idle", 3);
            case "look":       return tile("Cat/Idle", 1 + (i % 2));
            case "sleep":      return tile("Cat/Idle", 2);
            default:           return tile("Cat/Idle", 0);
        }
    }

    private static String resolveDog(String[] p) {
        // Dog has 4 idle frames (tail-position variations) and 4 walk frames
        // per direction. Sit/sleep/stretch/look reuse idle tiles like Cat.
        if (p.length < 2) return tile("Dog/Idle", 0);
        String state = p[1];
        int i = safeInt(p, 2, 0);
        switch (state) {
            case "idle":       return tile("Dog/Idle",       i % 4);
            case "walk-left":  return tile("Dog/Walk/Left",  i % 4);
            case "walk-right": return tile("Dog/Walk/Right", i % 4);
            case "sit":        return "Sprites/Dog/Sit/sit.svg"; // dedicated haunches-down pose
            case "stretch":    return tile("Dog/Idle", 1);   // tail up = alert/stretch
            case "look":       return tile("Dog/Idle", 1 + (i % 2));
            case "sleep":      return tile("Dog/Idle", 2);
            default:           return tile("Dog/Idle", 0);
        }
    }

    private static String resolveDucky(String[] p) {
        if (p.length < 2) return tile("Ducky/Idle", 0);
        String state = p[1];
        int i = safeInt(p, 2, 0);
        switch (state) {
            case "idle":       return tile("Ducky/Idle",       i % 4);
            case "walk-left":  return tile("Ducky/Walk/Left",  i % 4);
            case "walk-right": return tile("Ducky/Walk/Right", i % 4);
            case "sit":        return "Sprites/Ducky/Crouch/crouch.svg";
            case "stretch":    return tile("Ducky/LeftRightCombo", 2);
            case "look":       return tile("Ducky/Idle", 1 + (i % 2));
            case "sleep":      return "Sprites/Ducky/Crouch/crouch.svg";
            default:           return tile("Ducky/Idle", 0);
        }
    }

    private static String resolveBird(String[] p) {
        if (p.length < 2) return tile("Bird/Idle", 1);
        String state = p[1];
        int i = safeInt(p, 2, 0);
        // Bird tiles start at 001.
        switch (state) {
            case "idle":       return tileFrom("Bird/Idle",       1, 4, i);
            case "walk-left":  return tileFrom("Bird/Walk/Left",  1, 6, i);
            case "walk-right": return tileFrom("Bird/Walk/Right", 1, 6, i);
            case "sit":        return tile("Bird/Idle", 1);
            case "stretch":    return tile("Bird/Walk/Right", 1);
            case "look":       return tileFrom("Bird/Idle", 1, 4, 1 + i);
            case "sleep":      return tile("Bird/Idle", 1);
            default:           return tile("Bird/Idle", 1);
        }
    }

    /** {@code Sprites/<dir>/tileNNN.svg} (zero-based). */
    private static String tile(String dir, int n) {
        return String.format("Sprites/%s/tile%03d.svg", dir, n);
    }

    /** Like {@link #tile} but the directory's tiles start at {@code start} and span {@code count}. */
    private static String tileFrom(String dir, int start, int count, int i) {
        int n = start + Math.floorMod(i, count);
        return String.format("Sprites/%s/tile%03d.svg", dir, n);
    }

    private static String prop(String name) {
        switch (name) {
            case "ball": return "Sprites/Props/ball.svg";
            case "food": return "Sprites/Props/food.svg";
            case "zzz":  return "Sprites/Props/zzz.svg";
            case "tray": return "Sprites/Props/tray.svg";
            default:     return null;
        }
    }

    private static int safeInt(String[] arr, int idx, int dflt) {
        if (idx >= arr.length) return dflt;
        try {
            return Integer.parseInt(arr[idx]);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private record Key(String key, int size) { }
}
