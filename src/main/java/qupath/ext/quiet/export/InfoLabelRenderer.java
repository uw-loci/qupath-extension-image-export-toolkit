package qupath.ext.quiet.export;

import java.awt.Color;
import java.awt.Graphics2D;

/**
 * Standalone Java2D utility for rendering per-image metadata text stamps
 * onto exported images (e.g., image name, pixel size, date).
 * <p>
 * Follows the same pattern as {@link PanelLabelRenderer} and {@link ScaleBarRenderer}.
 * No JavaFX or QuPath viewer dependency -- works with any {@link Graphics2D}.
 */
public class InfoLabelRenderer {

    private InfoLabelRenderer() {
        // Utility class
    }

    /**
     * Draw an info label with a plain outline, in the given color.
     *
     * @param g2d         the graphics context to draw on
     * @param imageWidth  width of the output image in pixels
     * @param imageHeight height of the output image in pixels
     * @param text        the resolved text to draw
     * @param position    which corner to place the label in
     * @param fontSize    font size in pixels; 0 = auto-compute from image dimensions
     * @param bold        true for bold text, false for plain
     * @param color       primary color of the label text
     */
    public static void drawInfoLabel(Graphics2D g2d,
                                      int imageWidth, int imageHeight,
                                      String text,
                                      ScaleBarRenderer.Position position,
                                      int fontSize, boolean bold,
                                      Color color) {
        drawInfoLabel(g2d, imageWidth, imageHeight, text, position, fontSize, bold,
                color != null ? color : Color.WHITE, TextBackground.OUTLINE, null);
    }

    /**
     * Draw an info label in the readable-text style.
     *
     * @param color      text color, or null for Auto
     * @param background how the text is kept readable
     * @param backdrop   the image under the label, or null
     */
    public static void drawInfoLabel(Graphics2D g2d,
                                      int imageWidth, int imageHeight,
                                      String text,
                                      ScaleBarRenderer.Position position,
                                      int fontSize, boolean bold,
                                      Color color, TextBackground background,
                                      TextRenderUtils.Backdrop backdrop) {
        int minDim = Math.min(imageWidth, imageHeight);
        int effectiveFontSize = fontSize <= 0
                ? Math.max(12, minDim / 40)
                : TextRenderUtils.resolveFontSize(fontSize, minDim);
        int margin = Math.max(10, minDim / 40);
        TextRenderUtils.drawCornerText(g2d, imageWidth, imageHeight, text, position,
                effectiveFontSize, margin, bold, color, background, backdrop);
    }
}
