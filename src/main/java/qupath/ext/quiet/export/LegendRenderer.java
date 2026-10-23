package qupath.ext.quiet.export;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.util.List;

/**
 * Draws a legend of color swatches and names: channels, stains, object classes and the
 * classes of a class-index map.
 * <p>
 * The generated scripts carry a Groovy copy in {@code text-overlay-helpers.groovy}.
 */
public final class LegendRenderer {

    /** How a swatch shows its entry, matching how the entry is drawn on the image. */
    public enum SwatchKind {
        /** A filled square: channels, stains, filled objects. */
        FILL,
        /** A hollow square: objects drawn as outlines. */
        OUTLINE,
        /** A short line: line and polyline objects. */
        LINE,
        /** A dot: point objects. */
        POINT
    }

    /**
     * One legend row.
     *
     * @param name the text
     * @param rgb  the swatch color, packed RGB
     * @param kind how the swatch is drawn
     */
    public record Entry(String name, int rgb, SwatchKind kind) {}

    private LegendRenderer() {
        // Utility class
    }

    /**
     * Draw a legend in a corner, wrapped into columns and shrunk so it covers at most half
     * the image in each direction.
     *
     * @param entries    the rows; nothing is drawn if empty
     * @param pos        the corner
     * @param w          image width
     * @param h          image height
     * @param background how the text is kept readable; a box covers swatches and text
     * @param fixedText  text color, or null for Auto
     * @param backdrop   the image under the legend, or null
     */
    public static void drawCornerLegend(Graphics2D g2d, List<Entry> entries,
                                        ScaleBarRenderer.Position pos, int w, int h,
                                        TextBackground background, Color fixedText,
                                        TextRenderUtils.Backdrop backdrop) {
        if (entries == null || entries.isEmpty() || w <= 0 || h <= 0) {
            return;
        }
        int minDim = Math.min(w, h);
        int baseSwatch = Math.max(8, minDim / 30);
        int margin = Math.max(6, minDim / 50);
        int baseFont = Math.max(10, minDim / 45);
        int baseLineSpacing = Math.max(2, baseSwatch / 4);
        int n = entries.size();
        int maxLegendH = Math.max(1, (int) (h * 0.5));
        int maxLegendW = Math.max(1, (int) (w * 0.5));

        // Columns so each fits half the height at the base row pitch, at most 4
        int baseRow = baseSwatch + baseLineSpacing;
        int rowsPerColAtBase = Math.max(1, maxLegendH / baseRow);
        int columns = Math.max(1, Math.min(4, (n + rowsPerColAtBase - 1) / rowsPerColAtBase));
        int rowsPerCol = (n + columns - 1) / columns;

        g2d.setFont(new Font(Font.SANS_SERIF, Font.BOLD, baseFont));
        FontMetrics fmProbe = g2d.getFontMetrics();
        int widestText = 0;
        for (var e : entries) {
            widestText = Math.max(widestText, fmProbe.stringWidth(e.name()));
        }
        int legendWAtBase = columns * (baseSwatch + margin + widestText + margin);
        int legendHAtBase = rowsPerCol * baseRow;

        // Shrink proportionally if either axis is over budget
        double scale = Math.min(
                (double) maxLegendW / Math.max(1, legendWAtBase),
                (double) maxLegendH / Math.max(1, legendHAtBase));
        scale = Math.min(1.0, scale);
        int swatchSize = Math.max(6, (int) Math.round(baseSwatch * scale));
        int lineSpacing = Math.max(1, (int) Math.round(baseLineSpacing * scale));
        int fontSize = Math.max(8, (int) Math.round(baseFont * scale));

        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
        FontMetrics fm = g2d.getFontMetrics();
        int actualWidest = 0;
        for (var e : entries) {
            actualWidest = Math.max(actualWidest, fm.stringWidth(e.name()));
        }
        int colWidth = swatchSize + margin / 2 + actualWidest + margin / 2;
        int rowPitch = swatchSize + lineSpacing;

        int legendW = columns * colWidth;
        int legendH = rowsPerCol * rowPitch;
        boolean right = pos == ScaleBarRenderer.Position.UPPER_RIGHT
                || pos == ScaleBarRenderer.Position.LOWER_RIGHT;
        boolean lower = pos == ScaleBarRenderer.Position.LOWER_LEFT
                || pos == ScaleBarRenderer.Position.LOWER_RIGHT;
        int originX = right ? Math.max(margin, w - margin - legendW) : margin;
        int originY = lower ? Math.max(margin, h - margin - legendH) : margin;

        int pad = Math.max(3, margin / 2);
        int boxX = originX - pad;
        int boxY = originY - pad;
        int boxW = legendW + 2 * pad;
        int boxH = legendH - lineSpacing + 2 * pad;
        var ink = TextRenderUtils.resolveInk(background, fixedText, backdrop, boxX, boxY, boxW, boxH);
        TextRenderUtils.fillBox(g2d, ink, boxX, boxY, boxW, boxH, w, h, pad);

        for (int i = 0; i < n; i++) {
            int x0 = originX + (i / rowsPerCol) * colWidth;
            int y0 = originY + (i % rowsPerCol) * rowPitch;
            drawSwatch(g2d, entries.get(i), x0, y0, swatchSize, ink.text());
            TextRenderUtils.drawText(g2d, entries.get(i).name(),
                    x0 + swatchSize + margin / 2, y0 + swatchSize - 2, ink);
        }
    }

    /**
     * Draw one swatch.
     *
     * @param border the edge of a filled swatch: the legend's text color, so it shows on the
     *               legend's background
     */
    public static void drawSwatch(Graphics2D g2d, Entry entry, int x, int y, int size,
                                  Color border) {
        Color color = new Color(entry.rgb() & 0xFFFFFF);
        Stroke savedStroke = g2d.getStroke();
        Object savedAA = g2d.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        try {
            switch (entry.kind()) {
                case OUTLINE -> {
                    int t = Math.max(2, size / 6);
                    g2d.setColor(color);
                    g2d.setStroke(new BasicStroke(t));
                    g2d.drawRect(x + t / 2, y + t / 2, size - t, size - t);
                }
                case LINE -> {
                    int t = Math.max(2, size / 5);
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g2d.setColor(color);
                    g2d.setStroke(new BasicStroke(t, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2d.drawLine(x + t, y + size - t, x + size - t, y + t);
                }
                case POINT -> {
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    int d = Math.max(4, size * 2 / 3);
                    int o = (size - d) / 2;
                    g2d.setColor(color);
                    g2d.fillOval(x + o, y + o, d, d);
                    g2d.setColor(border);
                    g2d.drawOval(x + o, y + o, d, d);
                }
                default -> {
                    g2d.setColor(color);
                    g2d.fillRect(x, y, size, size);
                    g2d.setColor(border);
                    g2d.drawRect(x, y, size, size);
                }
            }
        } finally {
            g2d.setStroke(savedStroke);
            if (savedAA != null) {
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, savedAA);
            }
        }
    }
}
