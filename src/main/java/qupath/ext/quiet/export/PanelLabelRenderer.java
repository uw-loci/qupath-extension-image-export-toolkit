package qupath.ext.quiet.export;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Standalone Java2D utility for rendering panel letter labels (A, B, C...)
 * onto exported images for publication figures.
 * <p>
 * No JavaFX or QuPath viewer dependency -- works with any {@link Graphics2D}.
 * Follows the same pattern as {@link ScaleBarRenderer} and {@link ColorScaleBarRenderer}.
 */
public class PanelLabelRenderer {

    /**
     * Style of per-cell label used on a composed panel / montage figure.
     * <p>
     * Publication figures conventionally use {@link #UPPER} (A, B, C...);
     * {@link #LOWER} (a, b, c...) is used by some journals; {@link #NUMERIC}
     * (1, 2, 3...) is intended for slides, posters, and other non-publication
     * presentations. {@link #NONE} disables per-cell labels entirely.
     */
    public enum PanelLabelStyle {
        NONE,
        UPPER,
        LOWER,
        NUMERIC
    }

    private PanelLabelRenderer() {
        // Utility class
    }

    /**
     * Draw a panel label onto the given graphics context.
     * <p>
     * Saves and restores the graphics state (composite, hints, font, color)
     * so the caller's state is not affected.
     *
     * @param g2d         the graphics context to draw on
     * @param imageWidth  width of the output image in pixels
     * @param imageHeight height of the output image in pixels
     * @param label       the text to draw (e.g., "A", "B", "AA")
     * @param position    which corner to place the label in
     * @param fontSize    font size in pixels; 0 = auto-compute from image dimensions
     * @param bold        true for bold text, false for plain
     * @param color       primary color of the label text
     */
    public static void drawPanelLabel(Graphics2D g2d,
                                       int imageWidth, int imageHeight,
                                       String label,
                                       ScaleBarRenderer.Position position,
                                       int fontSize, boolean bold,
                                       Color color) {
        if (imageWidth <= 0 || imageHeight <= 0 || label == null || label.isEmpty()) {
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

            // Auto font size: minDim / 25 for larger text than scale bar
            int effectiveFontSize;
            if (fontSize <= 0) {
                effectiveFontSize = Math.max(14, minDim / 25);
            } else {
                effectiveFontSize = TextRenderUtils.resolveFontSize(fontSize, minDim);
            }

            int margin = Math.max(10, minDim / 40);

            // Font setup
            int fontStyle = bold ? Font.BOLD : Font.PLAIN;
            Font font = new Font(Font.SANS_SERIF, fontStyle, effectiveFontSize);
            g2d.setFont(font);
            FontMetrics fm = g2d.getFontMetrics();
            int textWidth = fm.stringWidth(label);
            int textAscent = fm.getAscent();

            // Compute text position based on corner
            int textX, textY;
            switch (position) {
                case LOWER_LEFT:
                    textX = margin;
                    textY = imageHeight - margin;
                    break;
                case UPPER_RIGHT:
                    textX = imageWidth - margin - textWidth;
                    textY = margin + textAscent;
                    break;
                case UPPER_LEFT:
                    textX = margin;
                    textY = margin + textAscent;
                    break;
                case LOWER_RIGHT:
                default:
                    textX = imageWidth - margin - textWidth;
                    textY = imageHeight - margin;
                    break;
            }

            Color primary = color != null ? color : Color.WHITE;
            Color outline = TextRenderUtils.computeOutlineColor(primary);

            TextRenderUtils.drawOutlinedText(g2d, label, textX, textY, primary, outline);

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
     * Convert a zero-based index to an uppercase panel label string.
     * <p>
     * 0 -> "A", 1 -> "B", ..., 25 -> "Z", 26 -> "AA", 27 -> "AB", etc.
     *
     * @param index zero-based index
     * @return the corresponding uppercase letter label
     */
    public static String labelForIndex(int index) {
        return labelForIndex(index, PanelLabelStyle.UPPER);
    }

    /**
     * Convert a zero-based index to a panel label string in the requested style.
     * <p>
     * Letter styles overflow Excel-column-style after Z ("AA", "AB", ...).
     * {@link PanelLabelStyle#NUMERIC} is one-based (0 -> "1"). {@link
     * PanelLabelStyle#NONE} returns the empty string.
     *
     * @param index zero-based index
     * @param style which style to format in
     * @return the formatted label
     */
    public static String labelForIndex(int index, PanelLabelStyle style) {
        if (style == null || style == PanelLabelStyle.NONE) {
            return "";
        }
        if (style == PanelLabelStyle.NUMERIC) {
            return Integer.toString(Math.max(1, index + 1));
        }
        int safeIndex = Math.max(0, index);
        char base = style == PanelLabelStyle.LOWER ? 'a' : 'A';
        StringBuilder sb = new StringBuilder();
        int n = safeIndex;
        do {
            sb.insert(0, (char) (base + (n % 26)));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }
}
