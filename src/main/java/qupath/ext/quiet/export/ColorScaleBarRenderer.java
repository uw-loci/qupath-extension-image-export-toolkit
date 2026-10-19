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
            int tickCount = 5;
            String[] tickLabels = new String[tickCount];
            double[] tickValues = new double[tickCount];
            int maxLabelWidth = 0;
            for (int i = 0; i < tickCount; i++) {
                double t = (double) i / (tickCount - 1);
                tickValues[i] = minValue + t * (maxValue - minValue);
                tickLabels[i] = formatValue(tickValues[i]);
                maxLabelWidth = Math.max(maxLabelWidth, fm.stringWidth(tickLabels[i]));
            }

            int tickLength = 4;
            int labelGap = 4;

            // Total width of bar + ticks + labels
            int totalWidth = barWidth + tickLength + labelGap + maxLabelWidth;
            int textHeight = fm.getAscent();

            // Compute bar position
            int barX, barY;
            boolean labelsOnRight;
            switch (position) {
                case LOWER_LEFT:
                    barX = margin;
                    barY = imageHeight - margin - barHeight;
                    labelsOnRight = true;
                    break;
                case UPPER_RIGHT:
                    barX = imageWidth - margin - totalWidth;
                    barY = margin;
                    labelsOnRight = true;
                    break;
                case UPPER_LEFT:
                    barX = margin;
                    barY = margin;
                    labelsOnRight = true;
                    break;
                case LOWER_RIGHT:
                default:
                    barX = imageWidth - margin - totalWidth;
                    barY = imageHeight - margin - barHeight;
                    labelsOnRight = true;
                    break;
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

            // Draw gradient bar line by line (top = max, bottom = min)
            double range = maxValue - minValue;
            for (int row = 0; row < barHeight; row++) {
                double t = 1.0 - (double) row / Math.max(1, barHeight - 1);
                double value = minValue + t * range;
                int packedRgb = colorMap.getColor(value, minValue, maxValue);
                g2d.setColor(new Color(packedRgb));
                g2d.fillRect(barX, barY + row, barWidth, 1);
            }

            // Outline around bar
            Color outlineColor = TextRenderUtils.computeOutlineColor(new Color(colorMap.getColor(
                    (minValue + maxValue) / 2.0, minValue, maxValue)));
            g2d.setColor(outlineColor);
            g2d.drawRect(barX - 1, barY - 1, barWidth + 1, barHeight + 1);

            // Draw tick marks and labels
            Color labelPrimary = Color.WHITE;
            Color labelOutline = TextRenderUtils.computeOutlineColor(labelPrimary);

            for (int i = 0; i < tickCount; i++) {
                double t = (double) i / (tickCount - 1);
                int tickY = barY + barHeight - 1 - (int) Math.round(t * (barHeight - 1));

                // Tick mark
                g2d.setColor(outlineColor);
                int tickStartX = barX + barWidth;
                g2d.drawLine(tickStartX, tickY, tickStartX + tickLength, tickY);

                // Label
                int labelX = tickStartX + tickLength + labelGap;
                int labelY = tickY + textHeight / 2 - 1;

                // Clamp label Y to stay within bar bounds
                labelY = Math.max(barY + textHeight, Math.min(labelY, barY + barHeight));

                TextRenderUtils.drawOutlinedText(g2d, tickLabels[i], labelX, labelY,
                        labelPrimary, labelOutline);
            }

            if (hasTitle) {
                TextRenderUtils.drawOutlinedText(g2d, title, titleX, titleY,
                        labelPrimary, labelOutline);
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
