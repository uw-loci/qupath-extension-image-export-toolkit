package qupath.ext.quiet.export;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composes a list of rendered cell images into a single rows-by-columns
 * panel / montage figure.
 * <p>
 * Cells are uniform-sized: the cell dimensions are the largest rendered image
 * dimensions across the batch, so every panel occupies an equally sized slot.
 * Each cell image is placed according to a {@link CellFitMode}, which always
 * preserves the image aspect ratio. Cells are filled row-major in input order;
 * trailing empty cells are background-filled. An optional caption band is
 * reserved above or below the image area of every row.
 * <p>
 * Conventions (grid + single gutter width + uniform cell sizing + per-cell
 * caption) follow ImageJ's "Make Montage", BioVoxxel Figure Tools and
 * QuickFigures; QuIET deliberately exposes independent X/Y pixel gutters for
 * finer control.
 */
public final class PanelComposer {

    private static final Logger logger = LoggerFactory.getLogger(PanelComposer.class);

    private PanelComposer() {
        // Utility class
    }

    /**
     * One panel cell: its rendered image plus its resolved caption lines.
     */
    public static final class Cell {
        private final BufferedImage image;
        private final List<String> captionLines;
        private final double micronsPerPixel;

        /**
         * @param image        the rendered cell image (never null)
         * @param captionLines caption lines for this cell (may be empty/null)
         */
        public Cell(BufferedImage image, List<String> captionLines) {
            this(image, captionLines, Double.NaN);
        }

        /**
         * @param micronsPerPixel microns per pixel of {@code image}; NaN if unknown
         */
        public Cell(BufferedImage image, List<String> captionLines, double micronsPerPixel) {
            this.image = image;
            this.captionLines = captionLines != null ? List.copyOf(captionLines) : List.of();
            this.micronsPerPixel = micronsPerPixel;
        }

        public double getMicronsPerPixel() {
            return micronsPerPixel;
        }

        public BufferedImage getImage() {
            return image;
        }

        public List<String> getCaptionLines() {
            return captionLines;
        }
    }

    /**
     * Compute the composed-figure pixel dimensions for a grid, without
     * composing. Used by the live size-feedback readout.
     *
     * @param config        the panel configuration (grid, gutters, captions)
     * @param cellWidth     the uniform cell image width in pixels
     * @param cellHeight    the uniform cell image height in pixels
     * @param maxCaptionLines the largest caption line count across the batch
     * @return {@code [width, height]} of the composed figure
     */
    public static long[] computeFigureSize(PanelExportConfig config,
                                           int cellWidth, int cellHeight,
                                           int maxCaptionLines) {
        int cols = config.getCols();
        int rows = config.getRows();
        int gx = config.getGutterX();
        int gy = config.getGutterY();
        int captionBand = config.hasCaption()
                ? CaptionRenderer.computeBandHeight(maxCaptionLines, config.getCaptionFontSize())
                : 0;
        long width = (long) cols * cellWidth + (long) (cols + 1) * gx;
        long slotHeight = (long) cellHeight + captionBand;
        long height = (long) rows * slotHeight + (long) (rows + 1) * gy;
        return new long[]{width, height};
    }

    /**
     * Compose the panel figure.
     *
     * @param config          the panel configuration
     * @param cells           the cell list, in row-major placement order
     * @param cellWidth       the uniform cell image width in pixels
     * @param cellHeight      the uniform cell image height in pixels
     * @param maxCaptionLines the largest caption line count across the batch
     * @return the composed figure image
     * @throws OutOfMemoryError if the figure raster cannot be allocated
     */
    public static BufferedImage compose(PanelExportConfig config,
                                        List<Cell> cells,
                                        int cellWidth, int cellHeight,
                                        int maxCaptionLines) {
        int cols = config.getCols();
        int rows = config.getRows();
        int gx = config.getGutterX();
        int gy = config.getGutterY();
        int fontSize = config.getCaptionFontSize();
        int captionBand = config.hasCaption()
                ? CaptionRenderer.computeBandHeight(maxCaptionLines, fontSize)
                : 0;
        boolean captionAbove =
                config.getCaptionPosition() == PanelExportConfig.CaptionPosition.ABOVE;

        long[] size = computeFigureSize(config, cellWidth, cellHeight, maxCaptionLines);
        int figW = (int) Math.min(size[0], Integer.MAX_VALUE);
        int figH = (int) Math.min(size[1], Integer.MAX_VALUE);

        BufferedImage figure = new BufferedImage(figW, figH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = figure.createGraphics();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            // Background fills the whole figure (and therefore the gutters).
            g2d.setColor(config.getBackgroundColor());
            g2d.fillRect(0, 0, figW, figH);

            int slotHeight = cellHeight + captionBand;
            int cellCount = Math.min(cells.size(), rows * cols);
            double common = commonMicronsPerPixel(config, cells, cellWidth, cellHeight);
            // One bar length for the whole figure when every cell shares a scale
            double sharedBarLength = common > 0
                    ? ScaleBarRenderer.resolveLengthMicrons(cellWidth, cellHeight, common,
                            config.getScaleBarLengthMicrons())
                    : config.getScaleBarLengthMicrons();
            for (int idx = 0; idx < cellCount; idx++) {
                int row = idx / cols;
                int col = idx % cols;
                if (row >= rows) {
                    break;  // grid smaller than selection -- trailing cells dropped
                }
                int slotX = gx + col * (cellWidth + gx);
                int slotY = gy + row * (slotHeight + gy);

                int imageAreaY = captionAbove ? slotY + captionBand : slotY;
                int captionY = captionAbove ? slotY : slotY + cellHeight;

                Cell cell = cells.get(idx);
                BufferedImage img = cell.getImage();
                if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
                    continue;
                }
                var placement = PanelCellGeometry.place(config.getCellFitMode(),
                        cellWidth, cellHeight, img.getWidth(), img.getHeight(),
                        cell.getMicronsPerPixel(), common);
                drawCellImage(g2d, img, slotX, imageAreaY, cellWidth, cellHeight, placement);
                if (showsScaleBar(config, idx, cellCount)) {
                    drawCellScaleBar(g2d, slotX, imageAreaY, cellWidth, cellHeight,
                            placement, sharedBarLength, config);
                }
                if (captionBand > 0) {
                    CaptionRenderer.drawCaption(g2d, slotX, captionY,
                            cellWidth, fontSize, cell.getCaptionLines(),
                            config.getCaptionColor());
                }
                if (config.hasPanelLabel()) {
                    String label = PanelLabelRenderer.labelForIndex(
                            idx, config.getPanelLabelStyle());
                    Graphics2D labelG2d = (Graphics2D) g2d.create();
                    try {
                        labelG2d.translate(slotX, imageAreaY);
                        PanelLabelRenderer.drawPanelLabel(labelG2d,
                                cellWidth, cellHeight, label,
                                config.getPanelLabelPosition(),
                                config.getPanelLabelFontSize(),
                                config.isPanelLabelBold(),
                                config.getPanelLabelColor());
                    } finally {
                        labelG2d.dispose();
                    }
                }
            }
        } finally {
            g2d.dispose();
        }
        logger.info("Composed panel figure: {} x {} px ({} cells, {} x {} grid)",
                figW, figH, cells.size(), rows, cols);
        return figure;
    }

    /**
     * The microns per figure pixel shared by every cell, or NaN when images are fitted
     * individually (same scale off, or an image without a pixel size).
     */
    static double commonMicronsPerPixel(PanelExportConfig config, List<Cell> cells,
                                        int cellWidth, int cellHeight) {
        if (!config.isMatchScale() || cells.isEmpty()) {
            return Double.NaN;
        }
        int n = cells.size();
        double[] w = new double[n];
        double[] h = new double[n];
        double[] um = new double[n];
        for (int i = 0; i < n; i++) {
            var img = cells.get(i).getImage();
            w[i] = img != null ? img.getWidth() : 1;
            h[i] = img != null ? img.getHeight() : 1;
            um[i] = cells.get(i).getMicronsPerPixel();
        }
        return PanelCellGeometry.commonMicronsPerPixel(
                config.getCellFitMode(), cellWidth, cellHeight, w, h, um);
    }

    /** Whether cell {@code idx} of {@code cellCount} placed cells carries a scale bar. */
    public static boolean showsScaleBar(PanelExportConfig config, int idx, int cellCount) {
        return switch (config.getScaleBarMode()) {
            case NONE -> false;
            case EVERY_CELL -> true;
            case LAST_CELL -> idx == cellCount - 1;
        };
    }

    /**
     * Draw one cell image at its placement, clipped to the cell so a FILL_CROP or
     * oversized image cannot bleed into neighbours.
     */
    private static void drawCellImage(Graphics2D g2d, BufferedImage img,
                                      int areaX, int areaY,
                                      int areaW, int areaH,
                                      PanelCellGeometry.Placement placement) {
        Graphics2D cellG = (Graphics2D) g2d.create();
        try {
            cellG.clipRect(areaX, areaY, areaW, areaH);
            int drawW = Math.max(1, (int) Math.round(placement.width()));
            int drawH = Math.max(1, (int) Math.round(placement.height()));
            int dx = areaX + (areaW - drawW) / 2;
            int dy = areaY + (areaH - drawH) / 2;
            cellG.drawImage(img, dx, dy, drawW, drawH, null);
        } finally {
            cellG.dispose();
        }
    }

    /**
     * Draw a scale bar inside the visible part of the image, so it never lands in the
     * letterbox margin or outside a cropped image.
     */
    private static void drawCellScaleBar(Graphics2D g2d, int areaX, int areaY,
                                         int areaW, int areaH,
                                         PanelCellGeometry.Placement placement,
                                         double lengthMicrons, PanelExportConfig config) {
        double um = placement.micronsPerPixel();
        if (!(um > 0)) {
            return;
        }
        int[] v = placement.visible(areaW, areaH);
        if (v[2] <= 0 || v[3] <= 0) {
            return;
        }
        Graphics2D barG = (Graphics2D) g2d.create();
        try {
            barG.translate(areaX + v[0], areaY + v[1]);
            barG.clipRect(0, 0, v[2], v[3]);
            ScaleBarRenderer.drawScaleBar(barG, v[2], v[3], um,
                    config.getScaleBarPosition(), config.getScaleBarColor(),
                    config.getScaleBarFontSize(), config.isScaleBarBold(), false,
                    lengthMicrons > 0 ? lengthMicrons : 0);
        } finally {
            barG.dispose();
        }
    }
}
