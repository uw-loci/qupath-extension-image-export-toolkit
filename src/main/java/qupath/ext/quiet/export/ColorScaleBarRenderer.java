package qupath.ext.quiet.export;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import qupath.lib.color.ColorMaps;

/**
 * Standalone Java2D utility for rendering color scale bars onto exported images.
 * <p>
 * Draws a vertical gradient bar showing the value-to-color mapping of a
 * {@link ColorMaps.ColorMap}, with labeled tick marks for min, max, and
 * intermediate values.
 * <p>
 * No JavaFX or QuPath viewer dependency -- works with any {@link Graphics2D}.
 */
public class ColorScaleBarRenderer {

    private static final int TICK_COUNT = 5;

    private ColorScaleBarRenderer() {
        // Utility class
    }

    /**
     * Draw a color scale bar with tick labels onto the given graphics context.
     * <p>
     * Saves and restores the graphics state (composite, hints, font, color)
     * so the caller's state is not affected.
     *
     * @param g2d         the graphics context to draw on
     * @param imageWidth  width of the output image in pixels
     * @param imageHeight height of the output image in pixels
     * @param colorMap    the color map to render as a gradient
     * @param minValue    the minimum data value (bottom of bar)
     * @param maxValue    the maximum data value (top of bar)
     * @param position    which corner to place the bar in
     * @param fontSize    font size in pixels; 0 = auto-compute from image dimensions
     * @param boldText    true for bold text, false for plain
     */
    public static void drawColorScaleBar(Graphics2D g2d, int imageWidth, int imageHeight,
                                          ColorMaps.ColorMap colorMap,
                                          double minValue, double maxValue,
                                          ScaleBarRenderer.Position position,
                                          int fontSize, boolean boldText) {
        drawColorScaleBar(g2d, imageWidth, imageHeight, colorMap, minValue, maxValue,
                position, fontSize, boldText, null);
    }

    /**
     * Draw a color scale bar with tick labels and an optional title.
     *
     * @param title text drawn beside the bar's far end (above for a lower corner,
     *              below for an upper corner); null or blank for none
     * @see #drawColorScaleBar(Graphics2D, int, int, ColorMaps.ColorMap, double, double,
     *      ScaleBarRenderer.Position, int, boolean)
     */
    public static void drawColorScaleBar(Graphics2D g2d, int imageWidth, int imageHeight,
                                          ColorMaps.ColorMap colorMap,
                                          double minValue, double maxValue,
                                          ScaleBarRenderer.Position position,
                                          int fontSize, boolean boldText,
                                          String title) {
        drawColorScaleBar(g2d, imageWidth, imageHeight, colorMap, minValue, maxValue,
                position, fontSize, boldText, title, TextBackground.OUTLINE, Color.WHITE, null);
    }

    /**
     * Draw a color scale bar in the readable-text style; a box covers bar, labels and title.
     *
     * @param background how the labels are kept readable
     * @param fixedText  label color, or null for Auto
     * @param backdrop   the image under the bar, or null
     */
    public static void drawColorScaleBar(Graphics2D g2d, int imageWidth, int imageHeight,
                                          ColorMaps.ColorMap colorMap,
                                          double minValue, double maxValue,
                                          ScaleBarRenderer.Position position,
                                          int fontSize, boolean boldText,
                                          String title, TextBackground background,
                                          Color fixedText, TextRenderUtils.Backdrop backdrop) {
        if (imageWidth <= 0 || imageHeight <= 0 || colorMap == null) {
            return;
        }

        // Save graphics state
        Composite savedComposite = g2d.getComposite();
        Object savedAA = g2d.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
        Font savedFont = g2d.getFont();
        Color savedColor = g2d.getColor();

        try {
            g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int minDim = Math.min(imageWidth, imageHeight);
            int effectiveFontSize = TextRenderUtils.resolveFontSize(fontSize, minDim);
            int margin = Math.max(10, minDim / 40);

            // Bar dimensions: height ~25% of image height (capped), width ~barHeight/6
            int barHeight = Math.min(imageHeight / 4, Math.max(60, imageHeight / 4));
            int barWidth = Math.max(10, barHeight / 6);

            // Font setup
            int fontStyle = boldText ? Font.BOLD : Font.PLAIN;
            Font font = new Font(Font.SANS_SERIF, fontStyle, effectiveFontSize);
            g2d.setFont(font);
            FontMetrics fm = g2d.getFontMetrics();

            // Tick labels: min, 25%, 50%, 75%, max
            String[] tickLabels = tickLabels(minValue, maxValue);
            int maxLabelWidth = 0;
            for (String label : tickLabels) {
                maxLabelWidth = Math.max(maxLabelWidth, fm.stringWidth(label));
            }

            int tickLength = 4;
            int labelGap = 4;

            // Total width of bar + ticks + labels
            int totalWidth = barWidth + tickLength + labelGap + maxLabelWidth;
            int textHeight = fm.getAscent();

            // Compute bar position; labels go on the right of the bar
            int barX;
            int barY;
            switch (position != null ? position : ScaleBarRenderer.Position.LOWER_RIGHT) {
                case LOWER_LEFT -> {
                    barX = margin;
                    barY = imageHeight - margin - barHeight;
                }
                case UPPER_RIGHT -> {
                    barX = imageWidth - margin - totalWidth;
                    barY = margin;
                }
                case UPPER_LEFT -> {
                    barX = margin;
                    barY = margin;
                }
                default -> {
                    barX = imageWidth - margin - totalWidth;
                    barY = imageHeight - margin - barHeight;
                }
            }

            // Title: above a bar in a lower corner, below one in an upper corner
            boolean hasTitle = title != null && !title.isBlank();
            int titleY = 0;
            int titleX = 0;
            if (hasTitle) {
                int titleGap = Math.max(4, textHeight / 2);
                int titleWidth = fm.stringWidth(title);
                boolean upper = position == ScaleBarRenderer.Position.UPPER_LEFT
                        || position == ScaleBarRenderer.Position.UPPER_RIGHT;
                boolean right = position == ScaleBarRenderer.Position.UPPER_RIGHT
                        || position == ScaleBarRenderer.Position.LOWER_RIGHT
                        || position == null;
                if (upper) {
                    titleY = barY + barHeight + titleGap + textHeight;
                } else {
                    barY -= textHeight + titleGap;
                    titleY = barY - titleGap;
                }
                titleX = right ? barX + totalWidth - titleWidth : barX;
                titleX = Math.max(margin, Math.min(titleX, imageWidth - margin - titleWidth));
            }

            int pad = TextRenderUtils.boxPad(effectiveFontSize);
            int boxLeft = barX - 1;
            int boxRight = barX + totalWidth;
            int boxTop = barY - 1;
            int boxBottom = barY + barHeight + 1 + fm.getDescent();
            if (hasTitle) {
                boxLeft = Math.min(boxLeft, titleX);
                boxRight = Math.max(boxRight, titleX + fm.stringWidth(title));
                boxTop = Math.min(boxTop, titleY - textHeight);
                boxBottom = Math.max(boxBottom, titleY + fm.getDescent());
            }
            boxLeft -= pad;
            boxTop -= pad;
            boxRight += pad;
            boxBottom += pad;
            TextRenderUtils.Ink ink = TextRenderUtils.resolveInk(background, fixedText, backdrop,
                    boxLeft, boxTop, boxRight - boxLeft, boxBottom - boxTop);
            TextRenderUtils.fillBox(g2d, ink, boxLeft, boxTop, boxRight - boxLeft,
                    boxBottom - boxTop, imageWidth, imageHeight, pad);

            paintBar(g2d, barX, barY, barWidth, barHeight, colorMap, minValue, maxValue,
                    tickLabels, tickLength, labelGap, textHeight, ink);

            if (hasTitle) {
                TextRenderUtils.drawText(g2d, title, titleX, titleY, ink);
            }

        } finally {
            // Restore graphics state
            g2d.setComposite(savedComposite);
            if (savedAA != null) {
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, savedAA);
            }
            g2d.setFont(savedFont);
            g2d.setColor(savedColor);
        }
    }

    /** Labels for the five ticks, min to max. */
    static String[] tickLabels(double minValue, double maxValue) {
        String[] labels = new String[TICK_COUNT];
        for (int i = 0; i < TICK_COUNT; i++) {
            double t = (double) i / (TICK_COUNT - 1);
            labels[i] = formatValue(minValue + t * (maxValue - minValue));
        }
        return labels;
    }

    /** Draw the gradient (top = max), its border, the ticks and their labels. */
    private static void paintBar(Graphics2D g2d, int barX, int barY, int barWidth, int barHeight,
                                 ColorMaps.ColorMap colorMap, double minValue, double maxValue,
                                 String[] labels, int tickLength, int labelGap, int textHeight,
                                 TextRenderUtils.Ink ink) {
        double range = maxValue - minValue;
        for (int row = 0; row < barHeight; row++) {
            double t = 1.0 - (double) row / Math.max(1, barHeight - 1);
            double value = minValue + t * range;
            g2d.setColor(new Color(colorMap.getColor(value, minValue, maxValue)));
            g2d.fillRect(barX, barY + row, barWidth, 1);
        }
        g2d.setColor(ink.text());
        g2d.drawRect(barX - 1, barY - 1, barWidth + 1, barHeight + 1);
        for (int i = 0; i < TICK_COUNT; i++) {
            double t = (double) i / (TICK_COUNT - 1);
            int tickY = barY + barHeight - 1 - (int) Math.round(t * (barHeight - 1));
            int tickStartX = barX + barWidth;
            g2d.setColor(ink.text());
            g2d.drawLine(tickStartX, tickY, tickStartX + tickLength, tickY);
            int labelX = tickStartX + tickLength + labelGap;
            int labelY = tickY + textHeight / 2 - 1;
            labelY = Math.max(barY + textHeight, Math.min(labelY, barY + barHeight));
            TextRenderUtils.drawText(g2d, labels[i], labelX, labelY, ink);
        }
    }

    /**
     * Draw a color scale bar in a figure margin: title above, bar of
     * {@code barHeight} below, labels to the right, plain text.
     *
     * @param measureOnly true to return the size without drawing
     * @return {width, height} in pixels
     */
    public static int[] drawInBand(Graphics2D g2d, int x, int y, ColorMaps.ColorMap colorMap,
                                   double minValue, double maxValue, String title, Font font,
                                   int barHeight, Color text, Color background,
                                   boolean measureOnly) {
        g2d.setFont(font);
        FontMetrics fm = g2d.getFontMetrics();
        String[] labels = tickLabels(minValue, maxValue);
        int widest = 0;
        for (String label : labels) {
            widest = Math.max(widest, fm.stringWidth(label));
        }
        int barWidth = Math.max(8, font.getSize());
        int tickLength = Math.max(4, font.getSize() / 3);
        int labelGap = 4;
        int ascent = fm.getAscent();
        boolean hasTitle = title != null && !title.isBlank();
        int titleH = hasTitle ? fm.getHeight() + Math.max(4, ascent / 2) : 0;
        int width = Math.max(barWidth + tickLength + labelGap + widest,
                hasTitle ? fm.stringWidth(title) : 0);
        int height = titleH + ascent / 2 + barHeight + ascent / 2 + fm.getDescent();
        if (measureOnly || colorMap == null) {
            return new int[]{width, height};
        }
        if (hasTitle) {
            g2d.setColor(text);
            g2d.drawString(title, x, y + ascent);
        }
        var ink = new TextRenderUtils.Ink(text, background, null);
        int barY = y + titleH + ascent / 2;
        double range = maxValue - minValue;
        for (int row = 0; row < barHeight; row++) {
            double t = 1.0 - (double) row / Math.max(1, barHeight - 1);
            g2d.setColor(new Color(colorMap.getColor(minValue + t * range, minValue, maxValue)));
            g2d.fillRect(x, barY + row, barWidth, 1);
        }
        g2d.setColor(text);
        g2d.drawRect(x - 1, barY - 1, barWidth + 1, barHeight + 1);
        for (int i = 0; i < TICK_COUNT; i++) {
            double t = (double) i / (TICK_COUNT - 1);
            int tickY = barY + barHeight - 1 - (int) Math.round(t * (barHeight - 1));
            g2d.setColor(text);
            g2d.drawLine(x + barWidth, tickY, x + barWidth + tickLength, tickY);
            g2d.drawString(labels[i], x + barWidth + tickLength + labelGap, tickY + ascent / 2 - 1);
        }
        return new int[]{width, height};
    }

    /**
     * Format a numeric value for display on the color scale bar.
     * Uses integer format for whole numbers, scientific notation for
     * very small values, and 2 decimal places otherwise. ASCII-only.
     */
    static String formatValue(double value) {
        if (value == 0) return "0";
        double abs = Math.abs(value);
        if (abs >= 1 && value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.format("%d", (long) value);
        }
        if (abs < 0.01 && abs > 0) {
            return String.format("%.1e", value);
        }
        return String.format("%.2f", value);
    }

}
