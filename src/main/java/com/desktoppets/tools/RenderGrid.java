package com.desktoppets.tools;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.attributes.ViewBox;
import com.github.weisj.jsvg.parser.SVGLoader;

/**
 * Renders a multi-row sprite sheet from a manifest file.
 *
 * Manifest format (one row per line):
 *   label|svgpath1,svgpath2,svgpath3,...
 * Blank lines and lines starting with # are ignored.
 *
 * Usage: RenderGrid <manifest> <out.png> <cellSize>
 */
public final class RenderGrid {
    private RenderGrid() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: RenderGrid <manifest> <out.png> <cellSize>");
            System.exit(2);
        }
        Path manifest = Path.of(args[0]);
        String out = args[1];
        int cell = Integer.parseInt(args[2]);

        List<String[]> rows = new ArrayList<>();
        int maxCols = 0;
        for (String raw : Files.readAllLines(manifest)) {
            String line = raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
            if (line.isBlank() || line.startsWith("#")) continue;
            int pipe = line.indexOf('|');
            String label = pipe < 0 ? "" : line.substring(0, pipe);
            String list = pipe < 0 ? line : line.substring(pipe + 1);
            String[] paths = list.split(",");
            String[] row = new String[paths.length + 1];
            row[0] = label;
            System.arraycopy(paths, 0, row, 1, paths.length);
            rows.add(row);
            maxCols = Math.max(maxCols, paths.length);
        }

        int gap = 8;
        int labelW = 220;
        int width = labelW + maxCols * cell + (maxCols - 1) * gap + gap * 2;
        int height = rows.size() * cell + (rows.size() - 1) * gap + gap * 2;

        BufferedImage out_img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out_img.createGraphics();
        g.setColor(new Color(40, 40, 50));
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(12, cell / 8)));
        g.setColor(Color.WHITE);

        SVGLoader loader = new SVGLoader();
        for (int r = 0; r < rows.size(); r++) {
            String[] row = rows.get(r);
            int y = gap + r * (cell + gap);
            g.drawString(row[0], gap, y + cell / 2);
            for (int c = 1; c < row.length; c++) {
                String svgPath = row[c].trim();
                if (svgPath.isEmpty()) continue;
                URL url = RenderGrid.class.getResource("/" + svgPath);
                if (url == null) {
                    System.err.println("not found: " + svgPath);
                    continue;
                }
                SVGDocument doc = loader.load(url);
                BufferedImage frame = new BufferedImage(cell, cell, BufferedImage.TYPE_INT_ARGB);
                Graphics2D fg = frame.createGraphics();
                fg.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_OFF);
                doc.render(null, fg, new ViewBox(0, 0, cell, cell));
                fg.dispose();
                int x = labelW + (c - 1) * (cell + gap);
                g.drawImage(frame, x, y, null);
            }
        }
        g.dispose();
        ImageIO.write(out_img, "PNG", new File(out));
        System.out.println("Wrote " + out + " (" + width + "x" + height + ")");
    }
}
