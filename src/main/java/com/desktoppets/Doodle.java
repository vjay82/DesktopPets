package com.desktoppets;

import java.awt.Component;
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
public final class Doodle {

    static final String PREFIX = "doodle:";

    private static final SVGLoader SVG = new SVGLoader();
    private static final Map<String, Object> SOURCE_CACHE = new ConcurrentHashMap<>();
    private static final Map<Key, ImageIcon> CACHE = new ConcurrentHashMap<>();
    private static final Object MISSING = new Object();

    private Doodle() {
    }

    public static ImageIcon icon(String key, int size) {
        return icon(key, size, 0.0);
    }

    /**
     * Same as {@link #icon(String, int)} but with a hue rotation applied to
     * the rasterised pixels (alpha and per-pixel brightness/saturation are
     * preserved). Used by tinted pets so a roster of e.g. 5 ducks shows up
     * in 5 different dominant colours. {@code hueDegrees == 0} short-circuits
     * to the untinted cache.
     *
     * <p>The hue is quantised to whole degrees for the cache key, so the
     * tinted cache is bounded to at most 360 variants per (sprite, size).
     */
    public static ImageIcon icon(String key, int size, double hueDegrees) {
        if (size <= 0 || key == null || key.isEmpty()) {
            return null;
        }
        String normalized = key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key;
        // Normalise hue into [0, 360) and quantise to int degrees.
        double n = ((hueDegrees % 360.0) + 360.0) % 360.0;
        int hueQuant = (int) Math.round(n) % 360;
        return CACHE.computeIfAbsent(new Key(normalized, size, hueQuant), Doodle::render);
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
                svg.render((Component) null, g, new ViewBox(0, 0, k.size, k.size));
            }
        } finally {
            g.dispose();
        }
        if (k.hueQuant != 0) {
            applyHueRotation(img, k.hueQuant);
        }
        return new ImageIcon(img);
    }

    /**
     * Rotate the hue of every visible pixel in {@code img} by
     * {@code degrees} (0..359). Alpha and per-pixel brightness/saturation
     * are preserved. Fully-transparent pixels are skipped. Operates in
     * place. Uses {@link java.awt.Color#RGBtoHSB} / {@code HSBtoRGB} which
     * are the JDK standard hue-rotation helpers.
     */
    private static void applyHueRotation(BufferedImage img, int degrees) {
        float hueOffset = (degrees % 360) / 360f;
        int w = img.getWidth();
        int h = img.getHeight();
        float[] hsb = new float[3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha == 0) continue; // fully transparent — leave as-is
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                java.awt.Color.RGBtoHSB(r, g, b, hsb);
                // Skip pure grays: rotating their hue is a no-op because
                // saturation is 0; this also avoids tinting outline pixels
                // that anti-aliasing emitted as neutral gray.
                if (hsb[1] < 0.001f) continue;
                hsb[0] = (hsb[0] + hueOffset) % 1f;
                int rgb = java.awt.Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]);
                img.setRGB(x, y, (alpha << 24) | (rgb & 0x00FFFFFF));
            }
        }
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
            case "question":   return "Sprites/Emote/question.svg";
            case "zzz":        return "Sprites/Emote/zzz.svg";
            case "moon":       return "Sprites/Emote/moon.svg";
            case "lick":       return "Sprites/Emote/lick.svg";
            case "think-food": return "Sprites/Emote/think-food.svg";
            case "think-water":return "Sprites/Emote/think-water.svg";
            case "chomp":      return "Sprites/Emote/chomp.svg";
            case "chat":       return "Sprites/Emote/chat.svg";
            case "vs":         return "Sprites/Emote/vs.svg";
            case "puff":       return "Sprites/Emote/puff.svg";
            case "clock":      return "Sprites/Emote/clock.svg";
            case "gift":       return "Sprites/Emote/gift.svg";
            case "laser":      return "Sprites/Emote/laser.svg";
            case "splash":     return "Sprites/Emote/splash.svg";
            default:           return null;
        }
    }

    private static String resolveCat(String[] p) {
        // Cat/Idle tiles 000-003 are full-figure frames; the original sheet's
        // remaining slots were near-empty placeholders and have been removed.
        // sleep.png / idle1.png / etc. are wide multi-frame sheets and would
        // render squashed into a square label, so we substitute an idle tile.
        if (p.length < 2) return tile("Cat/Idle", 0);
        String state = p[1];
        int i = safeInt(p, 2, 0);
        switch (state) {
            case "idle":       return tile("Cat/Idle",       i % 4);
            case "walk-left":  return tile("Cat/Walk/Left",  i % 8);
            case "walk-right": return tile("Cat/Walk/Right", i % 8);
            case "run-left":   return tile("Cat/Run/Left",   i % 8);
            case "run-right":  return tile("Cat/Run/Right",  i % 8);
            case "sit":        return tile("Cat/Idle", 0);
            case "stretch":    return tile("Cat/Idle", 3);
            case "look":       return tile("Cat/Idle", 1 + (i % 2));
            case "sleep":      return tile("Cat/Idle", 2);
            // Lay-down pose: idle body vertically squashed toward the feet.
            // Generated by tools/gen-lay-down-frames.ps1.
            case "lay-down":   return tile("Cat/LayDown", 0);
            // Play-bow pose: idle body rotated forward at the feet to read
            // as "front low, hind high" — the universal invite-to-play
            // signal. Generated by tools/gen-play-bow-frames.ps1.
            // tile000=right-facing, tile001=left-facing (mirrored).
            case "play-bow":       return tile("Cat/PlayBow", 0);
            case "play-bow-right": return tile("Cat/PlayBow", 0);
            case "play-bow-left":  return tile("Cat/PlayBow", 1);
            // Dedicated pixel-art scratch pose (lifted hind paw + motion lines).
            case "scratch":    return "Sprites/Cat/Scratch/scratch.svg";
            // PEE_TREE pose: normal idle body + yellow stream and ground
            // puddle overlay (see Sprites/Cat/Pee/pee.svg). Direction-
            // agnostic — the trunk side is conveyed by the pet's
            // walk-to-trunk approach, not by the body sprite.
            case "pee":        return "Sprites/Cat/Pee/pee.svg";
            // 6-frame dance: hop, lean-left, big-hop, lean-right,
            // mirror-spin, squat. Each frame wraps the idle body in a
            // viewBox-space transform (see tools/gen-dance-frames.ps1).
            case "dance":      return tile("Cat/Dance", Math.floorMod(i, 6));
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
            case "run-left":   return tile("Dog/Run/Left",   i % 4);
            case "run-right":  return tile("Dog/Run/Right",  i % 4);
            // 4-frame sit: same haunches-down pose with the tail wagging
            // up-and-down (high / mid / horizontal-out / mid).
            case "sit":        return tile("Dog/Sit", i % 4);
            case "stretch":    return tile("Dog/Idle", 1);   // tail up = alert/stretch
            case "look":       return tile("Dog/Idle", 1 + (i % 2));
            case "sleep":      return tile("Dog/Idle", 2);
            // Lay-down pose (see Cat/lay-down comment).
            case "lay-down":   return tile("Dog/LayDown", 0);
            // Play-bow pose (see Cat/play-bow comment).
            case "play-bow":       return tile("Dog/PlayBow", 0);
            case "play-bow-right": return tile("Dog/PlayBow", 0);
            case "play-bow-left":  return tile("Dog/PlayBow", 1);
            // 3-frame derived scratch (sit base + rocking tilt frames; see
            // tools/gen-dog-scratch-frames.ps1). Bare "scratch" maps to
            // tile0 for back-compat with any caller that doesn't index.
            case "scratch":    return tile("Dog/Scratch", i % 3);
            // PEE_TREE pose (see Cat/pee).
            case "pee":        return "Sprites/Dog/Pee/pee.svg";
            // 6-frame dance (see Cat/dance comment).
            case "dance":      return tile("Dog/Dance", Math.floorMod(i, 6));
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
            // No dedicated run art — reuse walk so the engine can still call run-left/right.
            case "run-left":   return tile("Ducky/Walk/Left",  i % 4);
            case "run-right":  return tile("Ducky/Walk/Right", i % 4);
            case "sit":        return "Sprites/Ducky/Crouch/crouch.svg";
            case "stretch":    return tile("Ducky/LeftRightCombo", 2);
            case "look":       return tile("Ducky/Idle", 1 + (i % 2));
            case "sleep":      return "Sprites/Ducky/Crouch/crouch.svg";
            // Lay-down pose (see Cat/lay-down comment).
            case "lay-down":   return tile("Ducky/LayDown", 0);
            // Dedicated pixel-art scratch pose (raised orange foot + motion lines).
            case "scratch":    return "Sprites/Ducky/Scratch/scratch.svg";
            // PEE_TREE pose (see Cat/pee).
            case "pee":        return "Sprites/Ducky/Pee/pee.svg";
            // 6-frame dance (see Cat/dance comment).
            case "dance":      return tile("Ducky/Dance", Math.floorMod(i, 6));
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
            // Birds fly when running — their runAlongFloor override goes through
            // walkAlongFloor (flight) and these keys are a defensive fallback.
            case "run-left":   return tileFrom("Bird/Walk/Left",  1, 6, i);
            case "run-right":  return tileFrom("Bird/Walk/Right", 1, 6, i);
            case "sit":        return tile("Bird/Idle", 1);
            case "stretch":    return tile("Bird/Walk/Right", 1);
            case "look":       return tileFrom("Bird/Idle", 1, 4, 1 + i);
            case "sleep":      return tile("Bird/Idle", 1);
            // Lay-down pose (see Cat/lay-down comment).
            case "lay-down":   return tile("Bird/LayDown", 0);
            // Dedicated pixel-art scratch pose (raised wing + motion lines).
            case "scratch":    return "Sprites/Bird/Scratch/scratch.svg";
            // Knocked-out pose (belly-up, legs in air, impact stars). Used
            // by Pet.fallOutAndExit when a Bird visitor is hit by a Ball.
            case "hit":        return "Sprites/Bird/Hit/hit.svg";
            // 6-frame dance (see Cat/dance comment).
            case "dance":      return tile("Bird/Dance", Math.floorMod(i, 6));
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
            case "water":return "Sprites/Props/water.svg";
            case "zzz":  return "Sprites/Props/zzz.svg";
            case "tray": return "Sprites/Props/tray.svg";
            case "bone": return "Sprites/Props/bone.svg";
            case "fish": return "Sprites/Props/fish.svg";
            case "seed": return "Sprites/Props/seed.svg";
            case "gift": return "Sprites/Props/gift.svg";
            case "tree": return "Sprites/Props/tree.svg";
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

    private record Key(String key, int size, int hueQuant) { }
}
