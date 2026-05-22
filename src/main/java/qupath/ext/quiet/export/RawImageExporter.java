package qupath.ext.quiet.export;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.TransformedServerBuilder;
import qupath.lib.images.writers.ImageWriterTools;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

/**
 * Exports raw pixel data at a configurable downsample.
 * <p>
 * Supports exporting the whole image, selected annotations, or all annotations.
 * Supports padding around annotation regions, channel selection, and OME-TIFF pyramid output.
 */
public class RawImageExporter {

    private static final Logger logger = LoggerFactory.getLogger(RawImageExporter.class);

    private RawImageExporter() {
        // Utility class
    }

    /**
     * Export raw pixel data for a single image entry.
     *
     * @param imageData the image data to export
     * @param config    raw export configuration
     * @param entryName the image entry name (used for filename generation)
     * @throws IOException if the export fails
     */
    public static void exportRaw(ImageData<BufferedImage> imageData,
                                 RawExportConfig config,
                                 String entryName) throws IOException {

        switch (config.getRegionType()) {
            case WHOLE_IMAGE -> exportWholeImage(imageData, config, entryName);
            case SELECTED_ANNOTATIONS -> exportAnnotations(imageData, config, entryName, true);
            case ALL_ANNOTATIONS -> exportAnnotations(imageData, config, entryName, false);
        }
    }

    /**
     * Render the whole image as raw pixel data to an in-memory
     * {@link BufferedImage}, without writing any file. Used by the
     * Panel / Montage export to obtain a single composed cell image.
     * <p>
     * Channel selection is applied; region type is ignored (panel cells are
     * always whole-image). Pyramid output is not relevant for an in-memory
     * render and is treated as a flat whole-image read.
     *
     * @param imageData the image data (caller is responsible for closing)
     * @param config    raw export configuration
     * @param entryName the image entry name (for error messages)
     * @return the whole-image BufferedImage at the configured downsample
     * @throws IOException if reading fails
     */
    public static BufferedImage renderToImage(ImageData<BufferedImage> imageData,
                                              RawExportConfig config,
                                              String entryName) throws IOException {
        ImageServer<BufferedImage> server = imageData.getServer();
        ImageServer<BufferedImage> exportServer = null;
        try {
            exportServer = maybeExtractChannels(server, config);
            double downsample = config.getDownsample();
            RegionRequest request = RegionRequest.createInstance(
                    exportServer.getPath(), downsample,
                    0, 0, exportServer.getWidth(), exportServer.getHeight());
            BufferedImage image = exportServer.readRegion(request);
            if (image == null) {
                throw new IOException("Raw read returned no image for: " + entryName);
            }
            return image;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to render raw image: " + entryName, e);
        } finally {
            closeIfDifferent(exportServer, server);
        }
    }

    /**
     * Wraps the server with channel extraction if channels are selected.
     */
    private static ImageServer<BufferedImage> maybeExtractChannels(
            ImageServer<BufferedImage> server, RawExportConfig config) throws IOException {
        var channels = config.getSelectedChannels();
        if (channels == null || channels.isEmpty()) {
            return server;
        }
        int[] channelArray = channels.stream().mapToInt(Integer::intValue).toArray();
        return new TransformedServerBuilder(server)
                .extractChannels(channelArray)
                .build();
    }

    /**
     * Export the entire image at the configured downsample.
     */
    private static void exportWholeImage(ImageData<BufferedImage> imageData,
                                         RawExportConfig config,
                                         String entryName) throws IOException {
        ImageServer<BufferedImage> server = imageData.getServer();
        ImageServer<BufferedImage> exportServer = null;

        try {
            exportServer = maybeExtractChannels(server, config);
            double downsample = config.getDownsample();

            // Use OMEPyramidWriter for pyramid format
            if (config.getFormat() == OutputFormat.OME_TIFF_PYRAMID) {
                String filename = config.buildOutputFilename(entryName);
                String outputPath = new File(config.getOutputDirectory(), filename).getAbsolutePath();
                writePyramid(exportServer, downsample, config, outputPath);
                logger.info("Exported OME-TIFF pyramid: {}", outputPath);
                return;
            }

            RegionRequest request = RegionRequest.createInstance(
                    exportServer.getPath(), downsample,
                    0, 0, exportServer.getWidth(), exportServer.getHeight());

            logger.debug("Reading raw image at downsample {} for: {}", downsample, entryName);
            BufferedImage image = exportServer.readRegion(request);

            String filename = config.buildOutputFilename(entryName);
            File outputFile = new File(config.getOutputDirectory(), filename);
            ImageWriterTools.writeImage(image, outputFile.getAbsolutePath());

            logger.info("Exported raw: {}", outputFile.getAbsolutePath());

        } catch (Exception e) {
            throw new IOException("Failed to export raw image: " + entryName, e);
        } finally {
            closeIfDifferent(exportServer, server);
        }
    }

    /**
     * Export annotation bounding box regions with optional padding.
     *
     * @param selectedOnly if true, only export selected annotations;
     *                     if false, export all annotations
     */
    private static void exportAnnotations(ImageData<BufferedImage> imageData,
                                          RawExportConfig config,
                                          String entryName,
                                          boolean selectedOnly) throws IOException {
        ImageServer<BufferedImage> server = imageData.getServer();
        ImageServer<BufferedImage> exportServer = null;
        Collection<PathObject> annotations;

        if (selectedOnly) {
            annotations = imageData.getHierarchy().getSelectionModel()
                    .getSelectedObjects().stream()
                    .filter(p -> p instanceof PathAnnotationObject)
                    .toList();
        } else {
            annotations = imageData.getHierarchy().getAnnotationObjects().stream()
                    .map(p -> (PathObject) p)
                    .toList();
        }

        if (annotations.isEmpty()) {
            logger.warn("No annotations found for: {}", entryName);
            return;
        }

        try {
            exportServer = maybeExtractChannels(server, config);
        } catch (Exception e) {
            throw new IOException("Failed to apply channel extraction for: " + entryName, e);
        }

        int padding = config.getPaddingPixels();
        int index = 0;
        for (PathObject annotation : annotations) {
            ROI roi = annotation.getROI();
            if (roi == null) continue;

            try {
                double downsample = config.getDownsample();
                int x = (int) roi.getBoundsX();
                int y = (int) roi.getBoundsY();
                int w = (int) Math.ceil(roi.getBoundsWidth());
                int h = (int) Math.ceil(roi.getBoundsHeight());

                // Apply padding
                if (padding > 0) {
                    x -= padding;
                    y -= padding;
                    w += 2 * padding;
                    h += 2 * padding;
                }

                // Clamp to image bounds
                x = Math.max(0, x);
                y = Math.max(0, y);
                w = Math.min(w, exportServer.getWidth() - x);
                h = Math.min(h, exportServer.getHeight() - y);

                if (w <= 0 || h <= 0) continue;

                RegionRequest request = RegionRequest.createInstance(
                        exportServer.getPath(), downsample, x, y, w, h);

                BufferedImage region = exportServer.readRegion(request);

                String suffix = "_region_" + index;
                String filename = config.buildOutputFilename(entryName, suffix);
                File outputFile = new File(config.getOutputDirectory(), filename);
                ImageWriterTools.writeImage(region, outputFile.getAbsolutePath());

                logger.debug("Exported annotation region {}: {}", index, outputFile.getAbsolutePath());
                index++;

            } catch (Exception e) {
                logger.warn("Failed to export annotation {} for: {}", index, entryName, e);
            }
        }

        closeIfDifferent(exportServer, server);
        logger.info("Exported {} annotation regions for: {}", index, entryName);
    }

    /**
     * Write an OME-TIFF pyramid using reflection to access OMEPyramidWriter from
     * the bioformats extension. Falls back to flat OME-TIFF if bioformats is unavailable.
     */
    private static void writePyramid(ImageServer<BufferedImage> server,
                                     double downsample,
                                     RawExportConfig config,
                                     String outputPath) throws IOException {
        try {
            // Try to use OMEPyramidWriter via reflection (requires qupath-extension-bioformats)
            Class<?> writerClass = Class.forName("qupath.lib.images.writers.ome.OMEPyramidWriter");
            Class<?> builderClass = Class.forName("qupath.lib.images.writers.ome.OMEPyramidWriter$Builder");

            var builderCtor = builderClass.getConstructor(ImageServer.class);
            var builder = builderCtor.newInstance(server);

            // .scaledDownsampling(downsample, pyramidLevels)
            var scaledMethod = builderClass.getMethod("scaledDownsampling", double.class, int.class);
            builder = scaledMethod.invoke(builder, downsample, config.getPyramidLevels());

            // .tileSize(tileSize)
            var tileSizeMethod = builderClass.getMethod("tileSize", int.class);
            builder = tileSizeMethod.invoke(builder, config.getTileSize());

            // .parallelize()
            var parallelizeMethod = builderClass.getMethod("parallelize");
            builder = parallelizeMethod.invoke(builder);

            // .compression(CompressionType) if specified
            String compression = config.getCompressionType();
            if (compression != null && !compression.isEmpty()) {
                Class<?> compressionClass = Class.forName(
                        "qupath.lib.images.writers.ome.OMEPyramidWriter$CompressionType");
                Object compressionType = Enum.valueOf(
                        compressionClass.asSubclass(Enum.class), compression);
                var compressionMethod = builderClass.getMethod("compression", compressionClass);
                builder = compressionMethod.invoke(builder, compressionType);
            }

            // .writePyramid(outputPath)
            var writeMethod = builderClass.getMethod("writePyramid", String.class);
            writeMethod.invoke(builder, outputPath);

        } catch (ClassNotFoundException e) {
            // Bioformats extension not available - fall back to flat OME-TIFF
            logger.warn("OMEPyramidWriter not available (bioformats extension missing). "
                    + "Falling back to flat OME-TIFF export.");
            RegionRequest request = RegionRequest.createInstance(
                    server.getPath(), downsample,
                    0, 0, server.getWidth(), server.getHeight());
            BufferedImage image = server.readRegion(request);
            ImageWriterTools.writeImage(image, outputPath);
        } catch (Exception e) {
            throw new IOException("Failed to write OME-TIFF pyramid: " + outputPath, e);
        }
    }

    /**
     * Close the export server if it is different from the original server.
     */
    private static void closeIfDifferent(ImageServer<BufferedImage> exportServer,
                                         ImageServer<BufferedImage> originalServer) {
        if (exportServer != null && exportServer != originalServer) {
            try {
                exportServer.close();
            } catch (Exception e) {
                logger.warn("Error closing transformed server", e);
            }
        }
    }
}
