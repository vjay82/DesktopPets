package com.desktoppets;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;

/** Dev-only preview: renders every cat state at 64 px into a single PNG. */
public final class DoodlePreview {
    public static void main(String[] args) throws Exception {
        String[] kinds = {"ducky", "cat", "dog", "bird"};
        String[] states = {"idle/0", "idle/1", "idle/2",
                "walk-right/0", "walk-right/1", "walk-right/2",
                "walk-left/0", "sit", "stretch", "look/0", "look/1", "sleep"};
        int size = 96;
        int cols = states.length;
        int rows = kinds.length;
        BufferedImage sheet = new BufferedImage(cols * size, rows * size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(new Color(0xEEEEEE));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        // alternating checker
        g.setColor(new Color(0xDDDDDD));
        int cs = 8;
        for (int y = 0; y < sheet.getHeight(); y += cs)
            for (int x = 0; x < sheet.getWidth(); x += cs)
                if (((x / cs + y / cs) & 1) == 0) g.fillRect(x, y, cs, cs);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                ImageIcon icon = Doodle.icon(kinds[r] + "/" + states[c], size);
                if (icon != null) {
                    g.drawImage(icon.getImage(), c * size, r * size, null);
                }
            }
        }
        g.dispose();
        File out = new File("doodle-preview.png");
        ImageIO.write(sheet, "png", out);
        System.out.println("wrote " + out.getAbsolutePath());
    }
}
