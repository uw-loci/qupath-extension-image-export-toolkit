package qupath.ext.quiet.export;

import java.awt.Color;
import java.awt.Graphics2D;

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
     * Draw a panel label with a plain outline, in the given color.
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
        drawPanelLabel(g2d, imageWidth, imageHeight, label, position, fontSize, bold,
                color != null ? color : Color.WHITE, TextBackground.OUTLINE, null);
    }

    /**
     * Draw a panel label in the readable-text style.
     *
     * @param color      text color, or null for Auto
     * @param background how the text is kept readable
     * @param backdrop   the image under the label, or null
     */
    public static void drawPanelLabel(Graphics2D g2d,
                                       int imageWidth, int imageHeight,
                                       String label,
                                       ScaleBarRenderer.Position position,
                                       int fontSize, boolean bold,
                                       Color color, TextBackground background,
                                       TextRenderUtils.Backdrop backdrop) {
        int minDim = Math.min(imageWidth, imageHeight);
        int effectiveFontSize = fontSize <= 0
                ? Math.max(14, minDim / 25)
                : TextRenderUtils.resolveFontSize(fontSize, minDim);
        int margin = Math.max(10, minDim / 40);
        TextRenderUtils.drawCornerText(g2d, imageWidth, imageHeight, label, position,
                effectiveFontSize, margin, bold, color, background, backdrop);
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
