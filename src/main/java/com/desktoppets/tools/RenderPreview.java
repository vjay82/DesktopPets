package com.desktoppets.tools;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URL;
import javax.imageio.ImageIO;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.attributes.ViewBox;
import com.github.weisj.jsvg.parser.SVGLoader;

public final class RenderPreview {
    private RenderPreview() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: RenderPreview <svg1> [svg2 ...] <out.png> <size>");
            System.exit(2);
        }
        int size = Integer.parseInt(args[args.length - 1]);
        String out = args[args.length - 2];
        int n = args.length - 2;
        int gap = 8;
        BufferedImage strip = new BufferedImage(size * n + (n - 1) * gap, size,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = strip.createGraphics();
        g.setColor(new Color(40, 40, 50));
        g.fillRect(0, 0, strip.getWidth(), strip.getHeight());
        SVGLoader loader = new SVGLoader();
        for (int i = 0; i < n; i++) {
            URL url = RenderPreview.class.getResource("/" + args[i]);
            if (url == null) {
                System.err.println("not found on classpath: " + args[i]);
                System.exit(3);
            }
            SVGDocument doc = loader.load(url);
            BufferedImage frame = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D fg = frame.createGraphics();
            fg.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_OFF);
            doc.render(null, fg, new ViewBox(0, 0, size, size));
            fg.dispose();
            g.drawImage(frame, i * (size + gap), 0, null);
        }
        g.dispose();
        ImageIO.write(strip, "PNG", new File(out));
        System.out.println("Wrote " + out + " (" + strip.getWidth() + "x" + strip.getHeight() + ")");
    }
}
