package qupath.ext.quiet.export;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Shared text rendering for every element QuIET draws on an image: outlined text, the
 * readable-text style ({@link TextBackground}) and the box behind a block.
 * <p>
 * The generated scripts carry a Groovy copy of this logic in
 * {@code text-overlay-helpers.groovy}; {@code TextOverlayParityTest} checks that both draw
 * the same pixels. Change them together.
 */
public final class TextRenderUtils {

    /** Minimum allowed font size (pixels). */
    static final int MIN_FONT_SIZE = 4;
    /** Maximum allowed font size (pixels). */
    static final int MAX_FONT_SIZE = 200;

    /** Opacity of the box behind a text block. */
    public static final float BOX_ALPHA = 0.65f;
    /** WCAG AA contrast for normal text. */
    public static final double MIN_CONTRAST = 4.5;
    /** Mean relative luminance above which Auto text is black: equal contrast with black and white. */
    static final double AUTO_DARK_TEXT_ABOVE = 0.179;
    /** Most pixels read from the image to decide an Auto style. */
    static final int MAX_SAMPLES = 16384;

    private static final double[] LINEAR = new double[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            LINEAR[i] = c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
    }

    /**
     * The image under the text, for Auto styles.
     *
     * @param image the image being drawn on, as composited so far
     * @param dx    added to a drawing x coordinate to get the image x coordinate
     * @param dy    added to a drawing y coordinate to get the image y coordinate
     */
    public record Backdrop(BufferedImage image, int dx, int dy) {
        /** @return a backdrop for drawing straight onto {@code image}, or null */
        public static Backdrop of(BufferedImage image) {
            return image == null ? null : new Backdrop(image, 0, 0);
        }

        /** @return this backdrop for a graphics context translated by (x, y) */
        public Backdrop shifted(int x, int y) {
            return new Backdrop(image, dx + x, dy + y);
        }
    }

    /**
     * The colors one text block is drawn in.
     *
     * @param text    the text color
     * @param outline the outline around the text, contrasting with it
     * @param box     the box behind the block, or null for none
     */
    public record Ink(Color text, Color outline, Color box) {
        /** Plain text with an outline in the contrasting color and no box. */
        public static Ink outlined(Color text) {
            Color primary = text != null ? text : Color.WHITE;
            return new Ink(primary, computeOutlineColor(primary), null);
        }
    }

    private TextRenderUtils() {
        // Utility class
    }

    /**
     * Resolve the effective font size. If the requested size is 0 (auto),
     * compute from image dimensions. Otherwise clamp to the allowed range.
     *
     * @param requested   the requested font size (0 = auto)
     * @param minImageDim the smaller of the image's width and height
     * @return the resolved font size in pixels
     */
    public static int resolveFontSize(int requested, int minImageDim) {
        if (requested <= 0) {
            return Math.max(12, minImageDim / 50);
        }
        return Math.max(MIN_FONT_SIZE, Math.min(requested, MAX_FONT_SIZE));
    }

    /**
     * Compute the outline/contrast color from luminance of the primary color.
     * Uses the standard luminance formula: (0.299*R + 0.587*G + 0.114*B) / 255.
     * Returns BLACK for bright colors, WHITE for dark colors.
     *
     * @param primary the primary text color
     * @return a contrasting outline color (BLACK or WHITE)
     */
    public static Color computeOutlineColor(Color primary) {
        double luminance = (0.299 * primary.getRed()
                + 0.587 * primary.getGreen()
                + 0.114 * primary.getBlue()) / 255.0;
        return luminance > 0.5 ? Color.BLACK : Color.WHITE;
    }

    /**
     * WCAG relative luminance of an sRGB color.
     *
     * @param rgb packed RGB; alpha is ignored
     * @return 0 (black) to 1 (white)
     */
    public static double relativeLuminance(int rgb) {
        return 0.2126 * LINEAR[(rgb >> 16) & 0xFF]
                + 0.7152 * LINEAR[(rgb >> 8) & 0xFF]
                + 0.0722 * LINEAR[rgb & 0xFF];
    }

    /** WCAG contrast ratio of two relative luminances, 1 to 21. */
    public static double contrastRatio(double luminanceA, double luminanceB) {
        double hi = Math.max(luminanceA, luminanceB);
        double lo = Math.min(luminanceA, luminanceB);
        return (hi + 0.05) / (lo + 0.05);
    }

    /**
     * Relative luminance of the backdrop pixels under a rectangle, on a grid of at most
     * {@link #MAX_SAMPLES} points.
     *
     * @return the luminances; empty if there is no backdrop or the rectangle misses it
     */
    static double[] sampleLuminance(Backdrop backdrop, int x, int y, int w, int h) {
        if (backdrop == null || backdrop.image() == null) {
            return new double[0];
        }
        BufferedImage img = backdrop.image();
        int x0 = Math.max(0, x + backdrop.dx());
        int y0 = Math.max(0, y + backdrop.dy());
        int x1 = Math.min(img.getWidth(), x + backdrop.dx() + w);
        int y1 = Math.min(img.getHeight(), y + backdrop.dy() + h);
        if (x1 <= x0 || y1 <= y0) {
            return new double[0];
        }
        double area = (double) (x1 - x0) * (y1 - y0);
        int step = (int) Math.max(1, Math.ceil(Math.sqrt(area / MAX_SAMPLES)));
        int nx = (x1 - x0 + step - 1) / step;
        int ny = (y1 - y0 + step - 1) / step;
        double[] lum = new double[nx * ny];
        int k = 0;
        for (int yy = y0; yy < y1; yy += step) {
            for (int xx = x0; xx < x1; xx += step) {
                lum[k++] = relativeLuminance(img.getRGB(xx, yy));
            }
        }
        return lum;
    }

    /**
     * Choose the colors for a text block drawn over a rectangle of the image.
     *
     * @param background how the text is kept readable
     * @param fixedText  the text color, or null for Auto: black on a light area, white on a dark one
     * @param backdrop   the image under the block; null when unknown
     * @param x          block left, in drawing coordinates
     * @param y          block top
     * @param w          block width
     * @param h          block height
     * @return the colors; an Auto style without a backdrop gets white text on a box
     */
    public static Ink resolveInk(TextBackground background, Color fixedText,
                                 Backdrop backdrop, int x, int y, int w, int h) {
        double[] lum = sampleLuminance(backdrop, x, y, w, h);
        Color text = fixedText;
        if (text == null) {
            double mean = 0;
            for (double v : lum) {
                mean += v;
            }
            boolean light = lum.length > 0 && mean / lum.length > AUTO_DARK_TEXT_ABOVE;
            text = light ? Color.BLACK : Color.WHITE;
        }
        Color outline = computeOutlineColor(text);
        boolean box;
        if (background == TextBackground.BOX) {
            box = true;
        } else if (background == TextBackground.OUTLINE) {
            box = false;
        } else {
            box = !readableWithoutBox(text, lum);
        }
        return new Ink(text, outline, box ? outline : null);
    }

    /** True when {@code text} reaches {@link #MIN_CONTRAST} on every sampled pixel. */
    static boolean readableWithoutBox(Color text, double[] lum) {
        if (lum.length == 0) {
            return false;
        }
        double t = relativeLuminance(text.getRGB());
        for (double v : lum) {
            if (contrastRatio(t, v) < MIN_CONTRAST) {
                return false;
            }
        }
        return true;
    }

    /**
     * Fill the box behind a block, clamped to the image, if the ink has one.
     *
     * @param imageWidth  drawing width the box must stay inside
     * @param imageHeight drawing height the box must stay inside
     * @param arc         corner arc in pixels
     */
    public static void fillBox(Graphics2D g2d, Ink ink, int x, int y, int w, int h,
                               int imageWidth, int imageHeight, int arc) {
        if (ink.box() == null) {
            return;
        }
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int right = Math.min(imageWidth, x + w);
        int bottom = Math.min(imageHeight, y + h);
        if (right <= left || bottom <= top) {
            return;
        }
        Composite prev = g2d.getComposite();
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, BOX_ALPHA));
        g2d.setColor(ink.box());
        g2d.fillRoundRect(left, top, right - left, bottom - top, arc, arc);
        g2d.setComposite(prev);
    }

    /**
     * Draw text with an outline for contrast on any background.
     * Uses 8-direction offset technique to create a visible outline around the text.
     *
     * @param g2d     the graphics context to draw on
     * @param text    the text to draw
     * @param x       the x position for the text baseline
     * @param y       the y position for the text baseline
     * @param primary the primary text color
     * @param outline the outline color (drawn behind the primary text)
     */
    public static void drawOutlinedText(Graphics2D g2d, String text,
                                         int x, int y,
                                         Color primary, Color outline) {
        g2d.setColor(outline);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx != 0 || dy != 0) {
                    g2d.drawString(text, x + dx, y + dy);
                }
            }
        }
        g2d.setColor(primary);
        g2d.drawString(text, x, y);
    }

    /** Draw outlined text in an ink's colors. */
    public static void drawText(Graphics2D g2d, String text, int x, int y, Ink ink) {
        drawOutlinedText(g2d, text, x, y, ink.text(), ink.outline());
    }

    /**
     * Draw one line of text in a corner, with the readable-text style: the info label and
     * the panel label.
     *
     * @param fontSize   resolved font size in pixels
     * @param margin     distance of the text from the image edges
     * @param fixedText  text color, or null for Auto
     * @param background how the text is kept readable
     * @param backdrop   the image under the text, or null
     * @return the ink used, or null if nothing was drawn
     */
    public static Ink drawCornerText(Graphics2D g2d, int imageWidth, int imageHeight,
                                     String text, ScaleBarRenderer.Position position,
                                     int fontSize, int margin, boolean bold,
                                     Color fixedText, TextBackground background,
                                     Backdrop backdrop) {
        if (imageWidth <= 0 || imageHeight <= 0 || text == null || text.isEmpty()) {
            return null;
        }
        Composite savedComposite = g2d.getComposite();
        Object savedAA = g2d.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
        Font savedFont = g2d.getFont();
        Color savedColor = g2d.getColor();
        try {
            g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2d.setFont(new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, fontSize));
            FontMetrics fm = g2d.getFontMetrics();
            int textWidth = fm.stringWidth(text);
            int ascent = fm.getAscent();
            int textX;
            int textY;
            switch (position != null ? position : ScaleBarRenderer.Position.LOWER_RIGHT) {
                case LOWER_LEFT -> {
                    textX = margin;
                    textY = imageHeight - margin;
                }
                case UPPER_RIGHT -> {
                    textX = imageWidth - margin - textWidth;
                    textY = margin + ascent;
                }
                case UPPER_LEFT -> {
                    textX = margin;
                    textY = margin + ascent;
                }
                default -> {
                    textX = imageWidth - margin - textWidth;
                    textY = imageHeight - margin;
                }
            }
            int pad = boxPad(fontSize);
            int boxX = textX - pad;
            int boxY = textY - ascent - pad;
            int boxW = textWidth + 2 * pad;
            int boxH = ascent + fm.getDescent() + 2 * pad;
            Ink ink = resolveInk(background, fixedText, backdrop, boxX, boxY, boxW, boxH);
            fillBox(g2d, ink, boxX, boxY, boxW, boxH, imageWidth, imageHeight, pad);
            drawText(g2d, text, textX, textY, ink);
            return ink;
        } finally {
            g2d.setComposite(savedComposite);
            if (savedAA != null) {
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, savedAA);
            }
            g2d.setFont(savedFont);
            g2d.setColor(savedColor);
        }
    }

    /** Padding between text and the edge of its box. */
    public static int boxPad(int fontSize) {
        return Math.max(3, fontSize / 5);
    }
}
