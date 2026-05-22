package qupath.ext.quiet.export;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.LabeledImageServer;
import qupath.lib.images.writers.ImageWriterTools;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.RegionRequest;

/**
 * Exports label/mask images using QuPath's {@link LabeledImageServer}.
 * <p>
 * Supports binary masks, grayscale labels, class-colored, instance IDs,
 * and multi-channel mask types.
 * <p>
 * Label integrity: LabeledImageServer renders from vector geometries (object ROIs)
 * at the requested downsample, producing exact label values at any resolution.
 * No raster interpolation occurs, so label values are never blurred or mixed.
 * JPEG format is blocked at the config level to prevent lossy compression
 * from destroying label values.
 */
public class MaskImageExporter {

    private static final Logger logger = LoggerFactory.getLogger(MaskImageExporter.class);

    private MaskImageExporter() {
        // Utility class
    }

    /**
     * Export a mask/label image for a single image entry.
     *
     * @param imageData the image data containing the object hierarchy
     * @param config    mask export configuration
     * @param entryName the image entry name (used for filename generation)
     * @throws IOException if the export fails
     */
    public static void exportMask(ImageData<BufferedImage> imageData,
                                  MaskExportConfig config,
                                  String entryName) throws IOException {

        ImageServer<BufferedImage> labelServer = null;

        try {
            labelServer = buildLabelServer(imageData, config);

            double downsample = config.getDownsample();
            RegionRequest request = RegionRequest.createInstance(
                    labelServer.getPath(), downsample,
                    0, 0, labelServer.getWidth(), labelServer.getHeight());

            logger.debug("Reading mask at downsample {} for: {}", downsample, entryName);
            BufferedImage maskImage = labelServer.readRegion(request);

            String filename = config.buildOutputFilename(entryName);
            File outputFile = new File(config.getOutputDirectory(), filename);
            ImageWriterTools.writeImage(maskImage, outputFile.getAbsolutePath());

            logger.info("Exported mask: {}", outputFile.getAbsolutePath());

        } catch (Exception e) {
            throw new IOException("Failed to export mask for: " + entryName, e);
        } finally {
            if (labelServer != null) {
                try {
                    labelServer.close();
                } catch (Exception e) {
                    logger.warn("Error closing label server for: {}", entryName, e);
                }
            }
        }
    }

    /**
     * Render the whole-image label mask to an in-memory {@link BufferedImage},
     * without writing any file. Used by the Panel / Montage export to obtain a
     * single composed cell image for a mask recipe.
     *
     * @param imageData the image data containing the object hierarchy
     * @param config    mask export configuration
     * @param entryName the image entry name (for error messages)
     * @return the whole-image mask BufferedImage at the configured downsample
     * @throws IOException if rendering fails
     */
    public static BufferedImage renderToImage(ImageData<BufferedImage> imageData,
                                              MaskExportConfig config,
                                              String entryName) throws IOException {
        ImageServer<BufferedImage> labelServer = null;
        try {
            labelServer = buildLabelServer(imageData, config);
            double downsample = config.getDownsample();
            RegionRequest request = RegionRequest.createInstance(
                    labelServer.getPath(), downsample,
                    0, 0, labelServer.getWidth(), labelServer.getHeight());
            BufferedImage maskImage = labelServer.readRegion(request);
            if (maskImage == null) {
                throw new IOException("Mask read returned no image for: " + entryName);
            }
            return maskImage;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to render mask for: " + entryName, e);
        } finally {
            if (labelServer != null) {
                try {
                    labelServer.close();
                } catch (Exception e) {
                    logger.warn("Error closing label server for: {}", entryName, e);
                }
            }
        }
    }

    /**
     * Build a LabeledImageServer based on the mask configuration.
     */
    private static ImageServer<BufferedImage> buildLabelServer(
            ImageData<BufferedImage> imageData, MaskExportConfig config) throws IOException {

        var builder = new LabeledImageServer.Builder(imageData);

        // Set background label
        builder.backgroundLabel(config.getBackgroundLabel());

        // Set downsample
        builder.downsample(config.getDownsample());

        // Set boundary label if enabled
        if (config.isEnableBoundary()) {
            if (config.getBoundaryThickness() > 1) {
                builder.setBoundaryLabel("Boundary", config.getBoundaryLabel(),
                        Integer.valueOf(config.getBoundaryThickness()));
            } else {
                builder.setBoundaryLabel("Boundary", config.getBoundaryLabel());
            }
        }

        // Set object source
        switch (config.getObjectSource()) {
            case ANNOTATIONS -> builder.useAnnotations();
            case DETECTIONS -> builder.useDetections();
            case CELLS -> builder.useCells();
        }

        // Configure based on mask type
        switch (config.getMaskType()) {
            case BINARY -> {
                // Single class: all selected objects get label 1
                if (!config.getSelectedClassifications().isEmpty()) {
                    for (String className : config.getSelectedClassifications()) {
                        builder.addLabel(PathClass.fromString(className), 1);
                    }
                } else {
                    // No specific class - label all objects as 1
                    builder.addUnclassifiedLabel(1);
                }
            }
            case GRAYSCALE_LABELS -> {
                builder.grayscale(true);
                var classes = config.getSelectedClassifications();
                for (int i = 0; i < classes.size(); i++) {
                    builder.addLabel(PathClass.fromString(classes.get(i)), i + 1);
                }
                if (config.isGrayscaleLut()) {
                    builder.grayscale(true);
                }
            }
            case COLORED -> {
                // Assign sequential labels; colors come from PathClass
                var colorClasses = config.getSelectedClassifications();
                for (int i = 0; i < colorClasses.size(); i++) {
                    builder.addLabel(PathClass.fromString(colorClasses.get(i)), i + 1);
                }
            }
            case INSTANCE -> {
                builder.useInstanceLabels();
                if (config.isShuffleInstanceLabels()) {
                    builder.shuffleInstanceLabels(true);
                }
            }
            case MULTICHANNEL -> {
                builder.multichannelOutput(true);
                var mcClasses = config.getSelectedClassifications();
                for (int i = 0; i < mcClasses.size(); i++) {
                    builder.addLabel(PathClass.fromString(mcClasses.get(i)), i + 1);
                }
            }
        }

        return builder.build();
    }
}
