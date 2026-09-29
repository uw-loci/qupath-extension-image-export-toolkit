package qupath.ext.quiet.export;

/**
 * Where each image lands in its panel cell. Shared by {@link PanelComposer} and the
 * layout preview so the preview shows exactly what the export draws.
 */
public final class PanelCellGeometry {

    private PanelCellGeometry() {
        // Utility class
    }

    /**
     * An image's rectangle relative to its cell's top-left corner, in figure pixels.
     *
     * @param micronsPerPixel microns per figure pixel of the drawn image; NaN if the
     *                        image has no pixel size
     */
    public record Placement(double x, double y, double width, double height,
                            double micronsPerPixel) {

        /** The part of the image inside a cell of the given size, as {x, y, w, h}. */
        public int[] visible(int cellWidth, int cellHeight) {
            int x0 = (int) Math.round(Math.max(0, x));
            int y0 = (int) Math.round(Math.max(0, y));
            int x1 = (int) Math.round(Math.min(cellWidth, x + width));
            int y1 = (int) Math.round(Math.min(cellHeight, y + height));
            return new int[] {x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0)};
        }
    }

    /**
     * The one microns-per-pixel shared by every cell when images are shown at the same
     * scale: FIT so the physically largest image fits, FILL so the smallest still covers
     * its cell, ACTUAL_SIZE at the finest pixel size.
     *
     * @param widths          image widths in pixels
     * @param heights         image heights in pixels
     * @param micronsPerPixel microns per image pixel, per image
     * @return the shared value, or NaN if any image has no pixel size
     */
    public static double commonMicronsPerPixel(CellFitMode mode, int cellWidth, int cellHeight,
                                               double[] widths, double[] heights,
                                               double[] micronsPerPixel) {
        if (widths.length == 0) {
            return Double.NaN;
        }
        double result = mode == CellFitMode.FILL_CROP || mode == CellFitMode.ACTUAL_SIZE
                ? Double.POSITIVE_INFINITY : 0;
        for (int i = 0; i < widths.length; i++) {
            double um = micronsPerPixel[i];
            if (!(um > 0) || Double.isInfinite(um)) {
                return Double.NaN;
            }
            double perW = widths[i] * um / cellWidth;
            double perH = heights[i] * um / cellHeight;
            result = switch (mode) {
                case FIT_LETTERBOX -> Math.max(result, Math.max(perW, perH));
                case FILL_CROP -> Math.min(result, Math.min(perW, perH));
                case ACTUAL_SIZE -> Math.min(result, um);
            };
        }
        return result;
    }

    /**
     * Place one image in its cell, centred.
     *
     * @param micronsPerPixel microns per image pixel; NaN if unknown
     * @param commonMicrons   the shared value from {@link #commonMicronsPerPixel}, or NaN
     *                        to fit each image on its own
     */
    public static Placement place(CellFitMode mode, int cellWidth, int cellHeight,
                                  double imageWidth, double imageHeight,
                                  double micronsPerPixel, double commonMicrons) {
        double scale;
        if (commonMicrons > 0 && micronsPerPixel > 0) {
            scale = micronsPerPixel / commonMicrons;
        } else {
            scale = switch (mode) {
                case FIT_LETTERBOX -> Math.min(cellWidth / imageWidth, cellHeight / imageHeight);
                case FILL_CROP -> Math.max(cellWidth / imageWidth, cellHeight / imageHeight);
                case ACTUAL_SIZE -> 1.0;
            };
        }
        double w = imageWidth * scale;
        double h = imageHeight * scale;
        double um = micronsPerPixel > 0 ? micronsPerPixel / scale : Double.NaN;
        return new Placement((cellWidth - w) / 2.0, (cellHeight - h) / 2.0, w, h, um);
    }
}
