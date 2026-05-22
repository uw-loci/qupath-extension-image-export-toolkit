package qupath.ext.quiet.export;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws a multi-line text <em>caption</em> band for one panel cell.
 * <p>
 * A caption is distinct from QuIET's existing Info Labels
 * ({@link InfoLabelRenderer} -- text in an image corner) and Panel Labels
 * ({@link PanelLabelRenderer} -- A/B/C letters in a corner). A caption is a
 * separate horizontal text band drawn above or below a cell, against the panel
 * background colour, with a filename line and one line per metadata field.
 * <p>
 * The renderer is plain Java2D and stateless. The composer
 * ({@link PanelComposer}) reserves the band height via
 * {@link #computeBandHeight(int, int)} and paints the band via
 * {@link #drawCaption(Graphics2D, int, int, int, int, List, Color)}.
 */
public final class CaptionRenderer {

    /** Vertical padding above and below the caption text block, in pixels. */
    private static final int VERTICAL_PADDING = 4;

    private CaptionRenderer() {
        // Utility class
    }

    /**
     * Compute the pixel height of a caption band for a given number of text
     * lines and font size. Returns 0 when there are no lines.
     *
     * @param lineCount the number of caption lines
     * @param fontSize  the caption font size in points
     * @return the band height in pixels
     */
    public static int computeBandHeight(int lineCount, int fontSize) {
        if (lineCount <= 0) {
            return 0;
        }
        int lineHeight = Math.round(fontSize * 1.35f);
        return lineCount * lineHeight + 2 * VERTICAL_PADDING;
    }

    /**
     * Resolve the caption lines for one image: the filename line (if enabled)
     * followed by one line per configured metadata field that has a value.
     * Metadata lines are rendered as {@code key: value}.
     *
     * @param config       the panel export configuration
     * @param imageName    the image entry name
     * @param metadata     resolved metadata key/value pairs for the image
     * @return the ordered caption lines (may be empty)
     */
    public static List<String> resolveLines(PanelExportConfig config,
                                            String imageName,
                                            java.util.Map<String, String> metadata) {
        List<String> lines = new ArrayList<>();
        if (config.isShowFilenameCaption() && imageName != null) {
            lines.add(imageName);
        }
        for (String key : config.getMetadataFields()) {
            String value = metadata != null ? metadata.get(key) : null;
            if (value != null && !value.isBlank()) {
                lines.add(key + ": " + value);
            } else {
                lines.add(key + ": -");
            }
        }
        return lines;
    }

    /**
     * Draw a caption band.
     * <p>
     * The band background is NOT painted here -- the composer has already
     * filled the whole figure with the background colour, so the band sits on
     * that backdrop. Each line is centred horizontally within the band width.
     *
     * @param g2d       the graphics context
     * @param bandX     left pixel of the band
     * @param bandY     top pixel of the band
     * @param bandWidth band width in pixels
     * @param fontSize  caption font size in points
     * @param lines     the caption lines
     * @param textColor the caption text colour
     */
    public static void drawCaption(Graphics2D g2d,
                                   int bandX, int bandY,
                                   int bandWidth, int fontSize,
                                   List<String> lines, Color textColor) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);
        g2d.setFont(font);
        FontRenderContext frc = g2d.getFontRenderContext();
        int lineHeight = Math.round(fontSize * 1.35f);
        int ascent = g2d.getFontMetrics().getAscent();
        int y = bandY + VERTICAL_PADDING;
        Color outline = TextRenderUtils.computeOutlineColor(textColor);
        for (String line : lines) {
            String text = line != null ? line : "";
            double textWidth = font.getStringBounds(text, frc).getWidth();
            int x = bandX + (int) Math.round((bandWidth - textWidth) / 2.0);
            if (x < bandX) {
                x = bandX;
            }
            TextRenderUtils.drawOutlinedText(g2d, text, x, y + ascent,
                    textColor, outline);
            y += lineHeight;
        }
    }
}
