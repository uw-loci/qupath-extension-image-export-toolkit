package qupath.ext.quiet.export;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Measures how readable the text on a rendered export is, from its pixels alone: the WCAG
 * contrast between each text block's text color and the pixels behind the text (its box,
 * or the image where there is no box). Used by the tests, the harness scenario and the
 * acceptance runs; the exporter does not call it.
 * <p>
 * Blocks are found by comparing the export with the same export drawn without text.
 */
public final class TextContrastProbe {

    /** Gap, in pixels, that still joins glyphs into one block. */
    private static final int JOIN = 4;
    /** Pixels this close to a glyph or swatch are not counted as background. */
    private static final int CLEAR = 2;

    /**
     * One block of text.
     *
     * @param x           left of the changed pixels
     * @param y           top
     * @param w           width
     * @param h           height
     * @param text        the text color found
     * @param minContrast the lowest contrast between the text and a background pixel
     * @param p1Contrast  the 1st percentile of that contrast
     * @param samples     background pixels measured
     * @param boxed       true if the text has a box behind it
     */
    public record Block(int x, int y, int w, int h, Color text, double minContrast,
                        double p1Contrast, int samples, boolean boxed) {
        @Override
        public String toString() {
            return String.format("block %d,%d %dx%d text #%06X on %s: contrast min %.2f, 1st pct %.2f (%d px)",
                    x, y, w, h, text.getRGB() & 0xFFFFFF, boxed ? "box" : "image",
                    minContrast, p1Contrast, samples);
        }
    }

    private TextContrastProbe() {
        // Utility class
    }

    /**
     * Measure every text block.
     *
     * @param withText    the export
     * @param withoutText the same export with every text element turned off
     * @param textColors  text colors known to be in use, such as the scale bar's; a block
     *                    with 20 or more pixels of one is measured for it. Leave Auto's
     *                    black and white out: a block without a known color is measured for
     *                    whichever of black and white contrasts more with it
     * @param ignore      swatch colors, whose pixels are not background
     * @return the blocks, top to bottom
     */
    public static List<Block> measure(BufferedImage withText, BufferedImage withoutText,
                                      Collection<Color> textColors, Collection<Color> ignore) {
        int w = withText.getWidth();
        int h = withText.getHeight();
        boolean[] changed = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                changed[y * w + x] = (withText.getRGB(x, y) & 0xFFFFFF)
                        != (withoutText.getRGB(x, y) & 0xFFFFFF);
            }
        }
        boolean[] joined = dilate(changed, w, h, JOIN);
        int[] label = new int[w * h];
        List<Block> blocks = new ArrayList<>();
        int next = 0;
        for (int start = 0; start < w * h; start++) {
            if (!joined[start] || label[start] != 0) {
                continue;
            }
            next++;
            int x0 = w, y0 = h, x1 = -1, y1 = -1;
            var queue = new ArrayDeque<Integer>();
            queue.add(start);
            label[start] = next;
            while (!queue.isEmpty()) {
                int p = queue.poll();
                int px = p % w;
                int py = p / w;
                if (changed[p]) {
                    x0 = Math.min(x0, px);
                    y0 = Math.min(y0, py);
                    x1 = Math.max(x1, px);
                    y1 = Math.max(y1, py);
                }
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = px + d[0];
                    int ny = py + d[1];
                    if (nx >= 0 && ny >= 0 && nx < w && ny < h) {
                        int q = ny * w + nx;
                        if (joined[q] && label[q] == 0) {
                            label[q] = next;
                            queue.add(q);
                        }
                    }
                }
            }
            if (x1 >= x0) {
                var block = measureBlock(withText, changed, w, x0, y0, x1 - x0 + 1, y1 - y0 + 1,
                        textColors, ignore);
                if (block != null) {
                    blocks.add(block);
                }
            }
        }
        blocks.sort((a, b) -> a.y() != b.y() ? Integer.compare(a.y(), b.y()) : Integer.compare(a.x(), b.x()));
        return blocks;
    }

    private static Block measureBlock(BufferedImage img, boolean[] changed, int imageWidth,
                                      int bx, int by, int bw, int bh,
                                      Collection<Color> textColors, Collection<Color> ignore) {
        int[] rgb = img.getRGB(bx, by, bw, bh, null, 0, bw);
        for (int i = 0; i < rgb.length; i++) {
            rgb[i] &= 0xFFFFFF;
        }
        // A known color with enough exact pixels is the text; otherwise the Auto
        // extreme farther from the block's median
        Color text = null;
        for (Color c : textColors) {
            int target = c.getRGB() & 0xFFFFFF;
            int n = 0;
            for (int v : rgb) {
                if (v == target) {
                    n++;
                }
            }
            if (n >= 20) {
                text = c;
                break;
            }
        }
        if (text == null) {
            double[] lum = new double[rgb.length];
            for (int i = 0; i < rgb.length; i++) {
                lum[i] = TextRenderUtils.relativeLuminance(rgb[i]);
            }
            Arrays.sort(lum);
            double median = lum[lum.length / 2];
            text = TextRenderUtils.contrastRatio(median, 0) > TextRenderUtils.contrastRatio(median, 1)
                    ? Color.BLACK : Color.WHITE;
        }
        int t = text.getRGB() & 0xFFFFFF;
        int outline = TextRenderUtils.computeOutlineColor(text).getRGB() & 0xFFFFFF;
        var ignored = new java.util.HashSet<Integer>();
        for (Color c : ignore) {
            ignored.add(c.getRGB() & 0xFFFFFF);
        }
        boolean[] glyph = new boolean[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            int v = rgb[i];
            glyph[i] = v == t || v == outline || ignored.contains(v);
        }
        boolean[] clear = dilate(glyph, bw, bh, CLEAR);
        // With a box most of the block changed: then only the box is behind the text (the
        // image shows through its rounded corners); without one, the image is
        int open = 0;
        int openChanged = 0;
        for (int i = 0; i < rgb.length; i++) {
            if (!clear[i]) {
                open++;
                if (changed[(by + i / bw) * imageWidth + bx + i % bw]) {
                    openChanged++;
                }
            }
        }
        boolean boxed = openChanged * 2 > open;
        double tl = TextRenderUtils.relativeLuminance(t);
        var contrasts = new ArrayList<Double>();
        for (int i = 0; i < rgb.length; i++) {
            boolean inBox = changed[(by + i / bw) * imageWidth + bx + i % bw];
            if (!clear[i] && (!boxed || inBox)) {
                contrasts.add(TextRenderUtils.contrastRatio(tl, TextRenderUtils.relativeLuminance(rgb[i])));
            }
        }
        if (contrasts.isEmpty()) {
            return null;
        }
        contrasts.sort(Double::compare);
        return new Block(bx, by, bw, bh, text, contrasts.get(0),
                contrasts.get((int) (contrasts.size() * 0.01)), contrasts.size(), boxed);
    }

    /** Square dilation by {@code r} pixels. */
    private static boolean[] dilate(boolean[] mask, int w, int h, int r) {
        boolean[] rows = new boolean[mask.length];
        for (int y = 0; y < h; y++) {
            int last = -r - 1;
            for (int x = 0; x < w; x++) {
                if (mask[y * w + x]) {
                    last = x;
                }
                if (x - last <= r) {
                    rows[y * w + x] = true;
                }
            }
            last = w + r + 1;
            for (int x = w - 1; x >= 0; x--) {
                if (mask[y * w + x]) {
                    last = x;
                }
                if (last - x <= r) {
                    rows[y * w + x] = true;
                }
            }
        }
        boolean[] out = new boolean[mask.length];
        for (int x = 0; x < w; x++) {
            int last = -r - 1;
            for (int y = 0; y < h; y++) {
                if (rows[y * w + x]) {
                    last = y;
                }
                if (y - last <= r) {
                    out[y * w + x] = true;
                }
            }
            last = h + r + 1;
            for (int y = h - 1; y >= 0; y--) {
                if (rows[y * w + x]) {
                    last = y;
                }
                if (last - y <= r) {
                    out[y * w + x] = true;
                }
            }
        }
        return out;
    }
}
