package qupath.ext.quiet.export;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.images.ImageData;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.RegionRequest;

/**
 * Exports small image patches (crops) centered on detection or cell objects,
 * organized by classification label.
 * <p>
 * The output structure is compatible with PyTorch ImageFolder when using
 * {@link ObjectCropConfig.LabelFormat#SUBDIRECTORY}.
 */
public class ObjectCropExporter {

    private static final Logger logger = LoggerFactory.getLogger(ObjectCropExporter.class);

    private ObjectCropExporter() {
        // Utility class
    }

    /**
     * Export object crops for a single image.
     *
     * @param imageData the image data (caller is responsible for closing)
     * @param config    the crop configuration
     * @param entryName the image entry name (used for filename generation)
     * @return the number of crops successfully exported
     * @throws Exception if a critical error occurs
     */
    public static int exportObjectCrops(ImageData<BufferedImage> imageData,
                                         ObjectCropConfig config,
                                         String entryName) throws Exception {

        var server = imageData.getServer();
        var hierarchy = imageData.getHierarchy();
        double downsample = config.getDownsample();

        // Filter objects by type
        Collection<PathObject> objects = getFilteredObjects(hierarchy, config);

        // Filter by selected classes (if any)
        if (!config.getSelectedClasses().isEmpty()) {
            objects = objects.stream()
                    .filter(o -> o.getPathClass() != null
                            && config.getSelectedClasses().contains(o.getPathClass().getName()))
                    .collect(Collectors.toList());
        }

        if (objects.isEmpty()) {
            logger.info("No matching objects found for: {}", entryName);
            return 0;
        }

        int serverW = server.getWidth();
        int serverH = server.getHeight();
        int halfCrop = (int) Math.round((config.getCropSize() / 2.0) * downsample);
        int paddingScaled = (int) Math.round(config.getPadding() * downsample);

        int exported = 0;
        Map<String, Integer> classCounters = new HashMap<>();

        for (var obj : objects) {
            var roi = obj.getROI();
            if (roi == null) continue;

            double cx = roi.getCentroidX();
            double cy = roi.getCentroidY();

            // Compute region in full-resolution coordinates
            int regionSize = (halfCrop + paddingScaled) * 2;
            int x = (int) Math.round(cx) - halfCrop - paddingScaled;
            int y = (int) Math.round(cy) - halfCrop - paddingScaled;

            // Clamp to image bounds
            x = Math.max(0, x);
            y = Math.max(0, y);
            int w = Math.min(regionSize, serverW - x);
            int h = Math.min(regionSize, serverH - y);

            if (w <= 0 || h <= 0) continue;

            try {
                var region = RegionRequest.createInstance(
                        server.getPath(), downsample, x, y, w, h);
                BufferedImage crop = server.readRegion(region);

                String className = (obj.getPathClass() != null)
                        ? obj.getPathClass().getName() : "Unclassified";
                int idx = classCounters.merge(className, 1, Integer::sum);

                File outputFile = config.resolveOutputFile(entryName, className, idx);
                outputFile.getParentFile().mkdirs();

                String formatName = config.getFormat().getExtension();
                if ("tif".equals(formatName)) formatName = "tiff";
                ImageIO.write(crop, formatName, outputFile);
                exported++;

            } catch (Exception e) {
                logger.warn("Failed to export crop for object in {}: {}",
                        entryName, e.getMessage());
            }
        }

        logger.info("Exported {} object crops for: {}", exported, entryName);
        return exported;
    }

    /**
     * Render a single representative object crop to an in-memory
     * {@link BufferedImage}, without writing any file. Used by the
     * Panel / Montage export, where one image contributes one panel cell.
     * <p>
     * The first object matching the crop configuration (object type and class
     * filter) is used. If the image contains no matching object, an
     * {@link IOException} is thrown so the caller can skip that panel cell.
     *
     * @param imageData the image data (caller is responsible for closing)
     * @param config    the crop configuration
     * @param entryName the image entry name (for error messages)
     * @return the first matching object crop
     * @throws IOException if no matching object exists or reading fails
     */
    public static BufferedImage renderToImage(ImageData<BufferedImage> imageData,
                                              ObjectCropConfig config,
                                              String entryName) throws IOException {
        var server = imageData.getServer();
        var hierarchy = imageData.getHierarchy();
        double downsample = config.getDownsample();

        Collection<PathObject> objects = getFilteredObjects(hierarchy, config);
        if (!config.getSelectedClasses().isEmpty()) {
            objects = objects.stream()
                    .filter(o -> o.getPathClass() != null
                            && config.getSelectedClasses().contains(o.getPathClass().getName()))
                    .collect(Collectors.toList());
        }
        if (objects.isEmpty()) {
            throw new IOException("no matching objects to crop in: " + entryName);
        }

        int serverW = server.getWidth();
        int serverH = server.getHeight();
        int halfCrop = (int) Math.round((config.getCropSize() / 2.0) * downsample);
        int paddingScaled = (int) Math.round(config.getPadding() * downsample);

        for (var obj : objects) {
            var roi = obj.getROI();
            if (roi == null) {
                continue;
            }
            double cx = roi.getCentroidX();
            double cy = roi.getCentroidY();
            int regionSize = (halfCrop + paddingScaled) * 2;
            int x = Math.max(0, (int) Math.round(cx) - halfCrop - paddingScaled);
            int y = Math.max(0, (int) Math.round(cy) - halfCrop - paddingScaled);
            int w = Math.min(regionSize, serverW - x);
            int h = Math.min(regionSize, serverH - y);
            if (w <= 0 || h <= 0) {
                continue;
            }
            try {
                var region = RegionRequest.createInstance(
                        server.getPath(), downsample, x, y, w, h);
                BufferedImage crop = server.readRegion(region);
                if (crop != null) {
                    return crop;
                }
            } catch (Exception e) {
                logger.warn("Failed to read object crop in {}: {}",
                        entryName, e.getMessage());
            }
        }
        throw new IOException("could not read an object crop in: " + entryName);
    }

    /**
     * Get objects from the hierarchy filtered by the configured object type.
     */
    private static Collection<PathObject> getFilteredObjects(
            PathObjectHierarchy hierarchy, ObjectCropConfig config) {
        return switch (config.getObjectType()) {
            case DETECTIONS -> hierarchy.getDetectionObjects().stream()
                    .map(o -> (PathObject) o)
                    .collect(Collectors.toList());
            case CELLS -> hierarchy.getCellObjects().stream()
                    .map(o -> (PathObject) o)
                    .collect(Collectors.toList());
            case ANNOTATIONS -> hierarchy.getAnnotationObjects().stream()
                    .map(o -> (PathObject) o)
                    .collect(Collectors.toList());
            case ALL -> {
                var all = new java.util.ArrayList<PathObject>();
                all.addAll(hierarchy.getDetectionObjects());
                all.addAll(hierarchy.getCellObjects().stream()
                        .filter(o -> !(o instanceof PathDetectionObject))
                        .toList());
                yield all;
            }
        };
    }
}
