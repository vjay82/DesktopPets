package com.desktoppets.tools;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.attributes.ViewBox;
import com.github.weisj.jsvg.parser.SVGLoader;

/**
 * Renders every SVG sprite found on the classpath under
 * {@code Sprites/<Species>/} into a single labelled grid PNG per species
 * and writes the result to {@code target/sprite-check/<species>.png}.
 *
 * <p>Each cell shows the sprite at a fixed size with horizontal guide
 * lines at y=0 (top), y=1 (baseline), and the detected feet-Y (red), so
 * sprites that sit too high, too low, or off-centre are visible at a
 * glance.
 *
 * <p>Also prints a textual report flagging sprites whose feet-Y or
 * body-centre-X deviate from the species median by more than a tolerance,
 * which makes it easy to spot "outlier" tiles that need touching up.
 */
public final class SpriteSheetCheck {

    private static final int CELL = 96;
    private static final int LABEL_H = 32;
    private static final int COLS = 8;
    private static final int PAD = 4;
    private static final SVGLoader LOADER = new SVGLoader();

    /** Tolerance for outlier detection (fraction of viewBox). */
    private static final double FEET_TOL = 0.08;   // ~8 px of 96
    private static final double CENTER_TOL = 0.12; // ~12 px of 96

    private SpriteSheetCheck() {}

    public static void main(String[] args) throws IOException {
        Path outDir = Paths.get("target", "sprite-check");
        Files.createDirectories(outDir);

        String[] species = { "Cat", "Dog", "Ducky", "Bird" };
        for (String sp : species) {
            List<SpriteEntry> entries = scan(sp);
            if (entries.isEmpty()) {
                System.out.println("[" + sp + "] no sprites found");
                continue;
            }
            BufferedImage grid = renderGrid(sp, entries);
            Path out = outDir.resolve(sp.toLowerCase(Locale.ROOT) + ".png");
            ImageIO.write(grid, "png", out.toFile());
            System.out.println("[" + sp + "] wrote " + out + " (" + entries.size() + " sprites)");
            reportOutliers(sp, entries);
        }
    }

    /** Walk {@code src/main/resources/Sprites/<species>} for *.svg, recursively. */
    private static List<SpriteEntry> scan(String species) throws IOException {
        Path root = Paths.get("src", "main", "resources", "Sprites", species);
        if (!Files.isDirectory(root)) return List.of();
        List<SpriteEntry> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".svg"))
             .sorted()
             .forEach(p -> {
                 String rel = root.getParent().relativize(p).toString().replace('\\', '/');
                 String cp = "Sprites/" + rel.substring(rel.indexOf('/') + 1);
                 // rel starts with "<Species>/..." already because we used root.getParent()
                 cp = rel; // rel already includes Species/...
                 String classpath = "Sprites/" + rel;
                 out.add(measure(classpath, rel));
             });
        }
        return out;
    }

    private static SpriteEntry measure(String classpath, String rel) {
        BufferedImage img = rasterise(classpath, CELL);
        if (img == null) {
            return new SpriteEntry(classpath, rel, null, 0, 0, 0, 0, 0.5);
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int a = (img.getRGB(x, y) >>> 24) & 0xFF;
                if (a >= 8) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX) {
            return new SpriteEntry(classpath, rel, img, 0, 0, 0, 0, 0.5);
        }
        double feetY = (maxY + 1.0) / CELL;
        double topY = minY / (double) CELL;
        double centerX = ((minX + maxX + 1.0) / 2.0) / CELL;
        return new SpriteEntry(classpath, rel, img, minX, minY, maxX, maxY,
                centerX, feetY, topY);
    }

    private static BufferedImage rasterise(String classpath, int size) {
        URL url = SpriteSheetCheck.class.getClassLoader().getResource(classpath);
        if (url == null) {
            // Fallback: read from source tree (tests run before classes are reprocessed).
            Path p = Paths.get("src", "main", "resources", classpath);
            if (!Files.isRegularFile(p)) return null;
            try (InputStream in = Files.newInputStream(p)) {
                return rasteriseStream(in, size);
            } catch (IOException e) {
                return null;
            }
        }
        try (InputStream in = url.openStream()) {
            return rasteriseStream(in, size);
        } catch (IOException e) {
            return null;
        }
    }

    private static BufferedImage rasteriseStream(InputStream in, int size) {
        SVGDocument doc = LOADER.load(in);
        if (doc == null) return null;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_OFF);
            doc.render((Component) null, g, new ViewBox(0, 0, size, size));
        } finally {
            g.dispose();
        }
        return img;
    }

    private static BufferedImage renderGrid(String species, List<SpriteEntry> entries) {
        int n = entries.size();
        int cols = Math.min(COLS, n);
        int rows = (n + cols - 1) / cols;
        int cellW = CELL + 2 * PAD;
        int cellH = CELL + LABEL_H + 2 * PAD;
        int headerH = 40;
        int w = cols * cellW;
        int h = headerH + rows * cellH;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // Background.
            g.setColor(new Color(245, 245, 248));
            g.fillRect(0, 0, w, h);
            // Header.
            g.setColor(Color.DARK_GRAY);
            g.setFont(new Font("Dialog", Font.BOLD, 18));
            double medFeet = median(entries.stream()
                    .filter(e -> e.img != null).mapToDouble(e -> e.feetY).toArray());
            double medCenter = median(entries.stream()
                    .filter(e -> e.img != null).mapToDouble(e -> e.centerX).toArray());
            g.drawString(species + "  (" + n + " sprites, median feetY=" +
                    String.format("%.2f", medFeet) + " centerX=" +
                    String.format("%.2f", medCenter) + ")", 10, 24);

            int i = 0;
            for (SpriteEntry e : entries) {
                int col = i % cols;
                int row = i / cols;
                int cx = col * cellW + PAD;
                int cy = headerH + row * cellH + PAD;
                drawCell(g, cx, cy, e, medFeet, medCenter);
                i++;
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    private static void drawCell(Graphics2D g, int x, int y, SpriteEntry e,
                                  double medFeet, double medCenter) {
        // Checkerboard background to make alpha visible.
        for (int yy = 0; yy < CELL; yy += 8) {
            for (int xx = 0; xx < CELL; xx += 8) {
                boolean dark = ((xx / 8) + (yy / 8)) % 2 == 0;
                g.setColor(dark ? new Color(220, 220, 225) : new Color(235, 235, 240));
                g.fillRect(x + xx, y + yy, 8, 8);
            }
        }
        // Sprite.
        if (e.img != null) {
            g.drawImage(e.img, x, y, null);
        }
        // Cell border.
        g.setColor(new Color(180, 180, 180));
        g.setStroke(new BasicStroke(1f));
        g.drawRect(x, y, CELL - 1, CELL - 1);
        // Median feet baseline (green dashed).
        g.setColor(new Color(0, 160, 0, 180));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{4f, 3f}, 0f));
        int medFeetY = (int) Math.round(medFeet * CELL);
        g.drawLine(x, y + medFeetY, x + CELL - 1, y + medFeetY);
        // Median centerX (green dashed).
        int medCx = (int) Math.round(medCenter * CELL);
        g.drawLine(x + medCx, y, x + medCx, y + CELL - 1);
        // This sprite's bbox (red if outlier, blue otherwise).
        boolean outlier = e.img != null && (
                Math.abs(e.feetY - medFeet) > FEET_TOL ||
                Math.abs(e.centerX - medCenter) > CENTER_TOL);
        Color box = outlier ? new Color(220, 30, 30) : new Color(60, 100, 220);
        g.setColor(box);
        g.setStroke(new BasicStroke(1f));
        if (e.img != null && e.maxX >= e.minX) {
            g.drawRect(x + e.minX, y + e.minY,
                    e.maxX - e.minX, e.maxY - e.minY);
        }
        // Label.
        g.setFont(new Font("Dialog", Font.PLAIN, 9));
        g.setColor(outlier ? new Color(180, 0, 0) : Color.DARK_GRAY);
        String shortName = e.rel;
        // Trim "<Species>/" prefix from label for readability.
        int slash = shortName.indexOf('/');
        if (slash >= 0) shortName = shortName.substring(slash + 1);
        // Truncate long names.
        if (shortName.length() > 22) shortName = shortName.substring(0, 22) + "\u2026";
        g.drawString(shortName, x, y + CELL + 11);
        g.setFont(new Font("Dialog", Font.PLAIN, 9));
        g.setColor(outlier ? new Color(180, 0, 0) : new Color(100, 100, 100));
        if (e.img != null) {
            g.drawString(String.format(Locale.ROOT, "f=%.2f c=%.2f%s",
                    e.feetY, e.centerX, outlier ? " !" : ""),
                    x, y + CELL + 22);
        } else {
            g.drawString("(no render)", x, y + CELL + 22);
        }
    }

    private static void reportOutliers(String species, List<SpriteEntry> entries) {
        double medFeet = median(entries.stream()
                .filter(e -> e.img != null).mapToDouble(e -> e.feetY).toArray());
        double medCenter = median(entries.stream()
                .filter(e -> e.img != null).mapToDouble(e -> e.centerX).toArray());
        int count = 0;
        for (SpriteEntry e : entries) {
            if (e.img == null) continue;
            double dF = e.feetY - medFeet;
            double dC = e.centerX - medCenter;
            if (Math.abs(dF) > FEET_TOL || Math.abs(dC) > CENTER_TOL) {
                System.out.printf(Locale.ROOT,
                        "  OUTLIER %-40s feetY=%.2f (%+.2f) centerX=%.2f (%+.2f)%n",
                        e.rel, e.feetY, dF, e.centerX, dC);
                count++;
            }
        }
        System.out.println("[" + species + "] outliers=" + count + " of " + entries.size());
    }

    private static double median(double[] v) {
        if (v.length == 0) return 0.5;
        double[] s = v.clone();
        java.util.Arrays.sort(s);
        int m = s.length / 2;
        return s.length % 2 == 1 ? s[m] : 0.5 * (s[m - 1] + s[m]);
    }

    /** Row in the sprite report. */
    private static final class SpriteEntry {
        final String classpath;
        final String rel;        // e.g. "Cat/Idle/tile000.svg"
        final BufferedImage img; // nullable
        final int minX, minY, maxX, maxY;
        final double centerX;
        final double feetY;
        final double topY;

        SpriteEntry(String classpath, String rel, BufferedImage img,
                    int minX, int minY, int maxX, int maxY, double centerX) {
            this(classpath, rel, img, minX, minY, maxX, maxY, centerX, 1.0, 0.0);
        }

        SpriteEntry(String classpath, String rel, BufferedImage img,
                    int minX, int minY, int maxX, int maxY,
                    double centerX, double feetY, double topY) {
            this.classpath = classpath;
            this.rel = rel;
            this.img = img;
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.centerX = centerX;
            this.feetY = feetY;
            this.topY = topY;
        }
    }
}
