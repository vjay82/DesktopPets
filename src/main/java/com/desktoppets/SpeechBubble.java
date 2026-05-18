package com.desktoppets;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.JComponent;

/**
 * Comic-style speech bubble drawn directly in Swing (no SVG): a white
 * rounded rectangle with a 1 px black outline plus a small triangular tail
 * pointing toward whoever is being addressed (LEFT for another pet to the
 * left, RIGHT for the cursor on the right, etc.). Text is rendered in a
 * bold sans-serif and shrunk on the fly so long words ("Quack quack")
 * still fit inside the bubble at any pet size.
 *
 * <p>Used by {@link Pet#speak(String, long, int)} for the per-species
 * sounds ("Woof!", "Meow", "Tweet!", "Quack!"). Lifecycle is the same as
 * the existing {@code emoteLabel}: invisible by default, made visible
 * for a few hundred ms, then hidden again on the EDT.
 */
final class SpeechBubble extends JComponent {

    private static final long serialVersionUID = 1L;

    enum Tail { LEFT, RIGHT, CENTER }

    private String text = "";
    private Tail tail = Tail.CENTER;

    void setSpeech(String t, Tail tailSide) {
        this.text = t == null ? "" : t;
        this.tail = tailSide == null ? Tail.CENTER : tailSide;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        if (text.isEmpty()) {
            return;
        }
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int tailH = Math.max(3, h / 5);
            int bodyH = h - tailH;
            int arc = Math.max(6, bodyH / 2);

            // Body fill + tail fill (filled in two passes so the outline
            // can be drawn over the body without painting over the tail).
            g.setColor(Color.WHITE);
            g.fillRoundRect(0, 0, w - 1, bodyH - 1, arc, arc);

            int tailHalf = Math.max(3, tailH);
            int tailCx;
            switch (tail) {
                case LEFT:   tailCx = Math.max(arc + tailHalf, w / 4); break;
                case RIGHT:  tailCx = Math.min(w - arc - tailHalf - 1, 3 * w / 4); break;
                case CENTER:
                default:     tailCx = w / 2; break;
            }
            int[] xs = { tailCx - tailHalf, tailCx + tailHalf, tailCx };
            int[] ys = { bodyH - 1,         bodyH - 1,         h - 1   };
            g.fillPolygon(xs, ys, 3);

            // Outline: bubble body + two slanted tail sides. We then erase
            // the bubble-bottom segment that sits across the tail base so
            // the tail visually merges into the bubble.
            g.setColor(Color.BLACK);
            g.drawRoundRect(0, 0, w - 1, bodyH - 1, arc, arc);
            g.drawLine(tailCx - tailHalf, bodyH - 1, tailCx, h - 1);
            g.drawLine(tailCx + tailHalf, bodyH - 1, tailCx, h - 1);
            g.setColor(Color.WHITE);
            g.drawLine(tailCx - tailHalf + 1, bodyH - 1,
                       tailCx + tailHalf - 1, bodyH - 1);

            // Text, shrunk on the fly until it fits inside the body.
            g.setColor(Color.BLACK);
            int padding = Math.max(4, w / 16);
            int maxTextW = Math.max(8, w - padding * 2);
            float size = Math.max(9f, bodyH * 0.6f);
            Font f = new Font(Font.SANS_SERIF, Font.BOLD, Math.round(size));
            FontMetrics fm = g.getFontMetrics(f);
            while (fm.stringWidth(text) > maxTextW && size > 8f) {
                size -= 1f;
                f = f.deriveFont(size);
                fm = g.getFontMetrics(f);
            }
            g.setFont(f);
            int tx = (w - fm.stringWidth(text)) / 2;
            int ty = (bodyH - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(text, tx, ty);
        } finally {
            g.dispose();
        }
    }
}
