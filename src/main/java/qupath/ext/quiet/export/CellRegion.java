package qupath.ext.quiet.export;

import java.io.IOException;
import java.util.Collection;

import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;

/**
 * The part of an image a panel cell shows when the recipe exports annotation regions: the
 * bounding box of every matching annotation, padded and clamped to the image. One cell per
 * image, so the panel layout is the same whatever the region setting.
 */
final class CellRegion {

    private CellRegion() {}

    /**
     * @param annotations the annotations the recipe would export for this image
     * @param padding     pixels added on every side, as for a per-annotation export
     * @param imageWidth  full-resolution image width
     * @param imageHeight full-resolution image height
     * @param entryName   the image name, for the error
     * @return {x, y, width, height} in full-resolution pixels
     * @throws IOException if no annotation has a region inside the image
     */
    static int[] bounds(Collection<? extends PathObject> annotations, int padding,
                        int imageWidth, int imageHeight, String entryName) throws IOException {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (PathObject annotation : annotations) {
            ROI roi = annotation.getROI();
            if (roi == null) continue;
            minX = Math.min(minX, roi.getBoundsX());
            minY = Math.min(minY, roi.getBoundsY());
            maxX = Math.max(maxX, roi.getBoundsX() + roi.getBoundsWidth());
            maxY = Math.max(maxY, roi.getBoundsY() + roi.getBoundsHeight());
        }
        int pad = Math.max(0, padding);
        int x = Math.max(0, (int) Math.floor(minX) - pad);
        int y = Math.max(0, (int) Math.floor(minY) - pad);
        int x2 = Math.min(imageWidth, (int) Math.ceil(maxX) + pad);
        int y2 = Math.min(imageHeight, (int) Math.ceil(maxY) + pad);
        if (Double.isInfinite(minX) || x2 <= x || y2 <= y) {
            throw new IOException("no annotation of the recipe's region was found in " + entryName);
        }
        return new int[]{x, y, x2 - x, y2 - y};
    }
}
