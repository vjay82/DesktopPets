package com.desktoppets.tools;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Converts every PNG under a root directory into a pixel-perfect SVG sibling.
 * Each opaque pixel becomes part of a horizontal run-length-encoded &lt;rect&gt;.
 * Transparent pixels (alpha &lt; 8) are skipped. Result scales crisply at any size
 * when rendered with shape-rendering="crispEdges".
 */
public final class PngToSvg {
    private PngToSvg() {}

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args.length > 0 ? args[0] : "src/main/resources/Sprites");
        if (!Files.isDirectory(root)) {
            System.err.println("Not a directory: " + root.toAbsolutePath());
            System.exit(1);
        }
        int[] counts = {0, 0};
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                .forEach(p -> {
                    try {
                        Path out = p.resolveSibling(stripExt(p.getFileName().toString()) + ".svg");
                        convert(p, out);
                        counts[0]++;
                    } catch (Exception ex) {
                        counts[1]++;
                        System.err.println("FAIL " + p + ": " + ex.getMessage());
                    }
                });
        }
        System.out.println("Converted " + counts[0] + " files (" + counts[1] + " failed) under " + root);
    }

    static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    public static void convert(Path png, Path svg) throws IOException {
        BufferedImage img = ImageIO.read(png.toFile());
        if (img == null) throw new IOException("not a readable image");
        int w = img.getWidth(), h = img.getHeight();
        int[] argb = new int[w * h];
        img.getRGB(0, 0, w, h, argb, 0, w);

        StringBuilder sb = new StringBuilder(8192);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
          .append(w).append(' ').append(h)
          .append("\" shape-rendering=\"crispEdges\">\n");

        for (int y = 0; y < h; y++) {
            int x = 0;
            while (x < w) {
                int c = argb[y * w + x];
                int a = (c >>> 24) & 0xff;
                if (a < 8) { x++; continue; }
                int runEnd = x + 1;
                while (runEnd < w && argb[y * w + runEnd] == c) runEnd++;
                int run = runEnd - x;
                appendRect(sb, x, y, run, c, a);
                x = runEnd;
            }
        }
        sb.append("</svg>\n");
        Files.writeString(svg, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void appendRect(StringBuilder sb, int x, int y, int w, int rgba, int a) {
        int r = (rgba >>> 16) & 0xff, g = (rgba >>> 8) & 0xff, b = rgba & 0xff;
        sb.append("<rect x=\"").append(x).append("\" y=\"").append(y)
          .append("\" width=\"").append(w).append("\" height=\"1\" fill=\"#")
          .append(hex2(r)).append(hex2(g)).append(hex2(b)).append('"');
        if (a < 255) sb.append(" fill-opacity=\"").append(String.format(Locale.ROOT, "%.3f", a / 255.0)).append('"');
        sb.append("/>\n");
    }

    private static String hex2(int v) {
        String s = Integer.toHexString(v);
        return s.length() == 1 ? "0" + s : s;
    }
}
