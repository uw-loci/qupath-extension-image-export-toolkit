package qupath.ext.quiet.export;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jfree.svg.SVGGraphics2D;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.analysis.heatmaps.DensityMaps.DensityMapBuilder;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.images.writers.ImageWriterTools;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Renders each selected project image through a single-image export recipe,
 * composes the results into one panel / montage figure via
 * {@link PanelComposer}, and writes the composed figure once.
 * <p>
 * This is the panel-mode analogue of the other categories' {@code *ImageExporter}
 * classes, but it writes a single output file rather than one per image.
 */
public final class PanelImageExporter {

    private static final Logger logger = LoggerFactory.getLogger(PanelImageExporter.class);

    private PanelImageExporter() {
        // Utility class
    }

    /**
     * Progress callback for the two-phase panel export.
     */
    public interface ProgressListener {
        /**
         * @param current 1-based item index for the current phase
         * @param total   total items in the current phase
         * @param message human-readable status (ASCII-only)
         */
        void onProgress(int current, int total, String message);
    }

    /**
     * Cancellation probe so the caller's {@code Task.isCancelled()} can abort a
     * long render loop.
     */
    public interface CancelCheck {
        boolean isCancelled();
    }

    /**
     * Result of a panel export.
     */
    public static final class PanelResult {
        private final File outputFile;
        private final int composedCells;
        private final int skipped;
        private final List<String> errors;
        private final int figureWidth;
        private final int figureHeight;

        PanelResult(File outputFile, int composedCells, int skipped,
                    List<String> errors, int figureWidth, int figureHeight) {
            this.outputFile = outputFile;
            this.composedCells = composedCells;
            this.skipped = skipped;
            this.errors = List.copyOf(errors);
            this.figureWidth = figureWidth;
            this.figureHeight = figureHeight;
        }

        public File getOutputFile() {
            return outputFile;
        }

        public int getComposedCells() {
            return composedCells;
        }

        public int getSkipped() {
            return skipped;
        }

        public List<String> getErrors() {
            return errors;
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public int getFigureWidth() {
            return figureWidth;
        }

        public int getFigureHeight() {
            return figureHeight;
        }

        /** A short ASCII summary line for the status label. */
        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("Composed ").append(composedCells).append(" panels into ")
              .append(figureWidth).append(" x ").append(figureHeight).append(" px figure");
            if (skipped > 0) {
                sb.append("; ").append(skipped).append(" skipped");
            }
            return sb.toString();
        }
    }

    /**
     * Export a panel figure from a list of project image entries.
     *
     * @param entries        the selected image entries, in panel placement order
     * @param config         the panel export configuration
     * @param classifier     the pixel classifier for a CLASSIFIER_OVERLAY
     *                       rendered recipe (null otherwise)
     * @param densityBuilder the density-map builder for a DENSITY_MAP_OVERLAY
     *                       rendered recipe (null otherwise)
     * @param progress       progress callback (may be null)
     * @param cancel         cancellation probe (may be null)
     * @return the export result
     * @throws IOException       if no cell could be rendered or the write fails
     * @throws OutOfMemoryError  if the composed figure is too large for memory
     */
    public static PanelResult exportPanel(List<ProjectImageEntry<BufferedImage>> entries,
                                          PanelExportConfig config,
                                          PixelClassifier classifier,
                                          DensityMapBuilder densityBuilder,
                                          ProgressListener progress,
                                          CancelCheck cancel) throws IOException {
        int total = entries.size();
        List<RenderedCell> rendered = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int skipped = 0;

        // Phase 1 -- render each image to a BufferedImage via the recipe.
        for (int i = 0; i < total; i++) {
            if (cancel != null && cancel.isCancelled()) {
                logger.info("Panel export cancelled during render phase after {} of {}",
                        i, total);
                throw new IOException("cancelled");
            }
            var entry = entries.get(i);
            String name = entry.getImageName();
            if (progress != null) {
                progress.onProgress(i + 1, total,
                        "Rendering panel " + (i + 1) + " of " + total);
            }
            ImageData<BufferedImage> imageData = null;
            try {
                imageData = entry.readImageData();
                BufferedImage cellImage = renderCell(imageData, config,
                        classifier, densityBuilder, name);
                Map<String, String> metadata = readMetadata(entry, imageData);
                rendered.add(new RenderedCell(name, cellImage, metadata));
            } catch (IOException e) {
                skipped++;
                errors.add(name + ": " + e.getMessage());
                logger.warn("Skipping panel cell {}: {}", name, e.getMessage());
            } catch (Exception e) {
                skipped++;
                errors.add(name + ": " + e.getMessage());
                logger.error("Failed to render panel cell: {}", name, e);
            } finally {
                if (imageData != null) {
                    try {
                        imageData.getServer().close();
                    } catch (Exception e) {
                        logger.warn("Error closing image server for {}: {}",
                                name, e.getMessage());
                    }
                }
            }
        }

        if (rendered.isEmpty()) {
            throw new IOException(
                    "No images could be rendered for the panel. See the error list.");
        }

        // Phase 2 -- compose.
        if (cancel != null && cancel.isCancelled()) {
            throw new IOException("cancelled");
        }
        if (progress != null) {
            progress.onProgress(1, 2, "Composing figure");
        }

        int cellWidth = 0;
        int cellHeight = 0;
        int maxCaptionLines = 0;
        List<PanelComposer.Cell> cells = new ArrayList<>();
        for (RenderedCell rc : rendered) {
            cellWidth = Math.max(cellWidth, rc.image.getWidth());
            cellHeight = Math.max(cellHeight, rc.image.getHeight());
            List<String> lines = CaptionRenderer.resolveLines(config, rc.name, rc.metadata);
            maxCaptionLines = Math.max(maxCaptionLines, lines.size());
            cells.add(new PanelComposer.Cell(rc.image, lines));
        }

        int composedCellCount = cells.size();
        BufferedImage figure;
        try {
            figure = PanelComposer.compose(config, cells, cellWidth, cellHeight,
                    maxCaptionLines);
        } catch (OutOfMemoryError oom) {
            // Free the rendered cells before rethrowing so the caller's handler
            // has headroom to show a dialog.
            rendered.clear();
            cells.clear();
            throw oom;
        }
        // Source cells are no longer needed once composed.
        rendered.clear();
        cells.clear();

        // Phase 2 -- write.
        if (cancel != null && cancel.isCancelled()) {
            throw new IOException("cancelled");
        }
        if (progress != null) {
            progress.onProgress(2, 2, "Writing figure");
        }

        File outDir = config.getOutputDirectory();
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IOException("Could not create output directory: "
                    + outDir.getAbsolutePath());
        }
        File outputFile = new File(outDir, config.buildOutputFilename());
        writeFigure(figure, config, outputFile);
        logger.info("Wrote panel figure: {}", outputFile.getAbsolutePath());

        return new PanelResult(outputFile, composedCellCount, skipped, errors,
                figure.getWidth(), figure.getHeight());
    }

    /**
     * Render one image to a cell BufferedImage via the recipe category's
     * exporter.
     * <p>
     * For a RENDERED recipe in CLASSIFIER_OVERLAY or DENSITY_MAP_OVERLAY mode
     * the resolved {@code classifier} / {@code densityBuilder} must be non-null
     * and applicable -- the panel never silently falls back to a plain
     * object-overlay render that would drop the recipe's headline content.
     */
    private static BufferedImage renderCell(ImageData<BufferedImage> imageData,
                                            PanelExportConfig config,
                                            PixelClassifier classifier,
                                            DensityMapBuilder densityBuilder,
                                            String entryName) throws IOException {
        Object recipe = config.getRecipeConfig();
        return switch (config.getRecipeCategory()) {
            case RENDERED -> {
                if (!(recipe instanceof RenderedExportConfig rc)) {
                    throw new IOException("recipe configuration type mismatch (rendered)");
                }
                if (rc.getRenderMode()
                        == RenderedExportConfig.RenderMode.CLASSIFIER_OVERLAY) {
                    if (classifier == null) {
                        throw new IOException("the recipe renders a pixel"
                                + " classification but no classifier is available");
                    }
                    if (!classifier.supportsImage(imageData)) {
                        throw new IOException("the recipe's pixel classifier does"
                                + " not support this image");
                    }
                } else if (rc.getRenderMode()
                        == RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY
                        && densityBuilder == null) {
                    throw new IOException("the recipe renders a density map but"
                            + " no density map is available");
                }
                yield RenderedImageExporter.renderToImage(imageData, classifier,
                        densityBuilder, rc, entryName);
            }
            case RAW -> {
                if (!(recipe instanceof RawExportConfig rawc)) {
                    throw new IOException("recipe configuration type mismatch (raw)");
                }
                yield RawImageExporter.renderToImage(imageData, rawc, entryName);
            }
            case MASK -> {
                if (!(recipe instanceof MaskExportConfig mc)) {
                    throw new IOException("recipe configuration type mismatch (mask)");
                }
                yield MaskImageExporter.renderToImage(imageData, mc, entryName);
            }
            case OBJECT_CROPS -> {
                if (!(recipe instanceof ObjectCropConfig occ)) {
                    throw new IOException("recipe configuration type mismatch (object crop)");
                }
                yield ObjectCropExporter.renderToImage(imageData, occ, entryName);
            }
            case TILED, PANEL -> throw new IOException(
                    "unsupported recipe category: " + config.getRecipeCategory());
        };
    }

    /**
     * Read a flat metadata map for an image: the project entry's user metadata
     * key/value pairs plus a few intrinsic image fields used as caption values.
     */
    private static Map<String, String> readMetadata(
            ProjectImageEntry<BufferedImage> entry,
            ImageData<BufferedImage> imageData) {
        Map<String, String> map = new LinkedHashMap<>();
        // Project-entry user metadata (the primary caption source).
        try {
            var entryMeta = entry.getMetadata();
            if (entryMeta != null) {
                for (var e : entryMeta.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        map.put(e.getKey(), e.getValue());
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to read entry metadata for {}: {}",
                    entry.getImageName(), e.getMessage());
        }
        // Intrinsic image fields, only added when not already present.
        try {
            var server = imageData.getServer();
            map.putIfAbsent("width", String.valueOf(server.getWidth()));
            map.putIfAbsent("height", String.valueOf(server.getHeight()));
            var cal = server.getPixelCalibration();
            if (cal.hasPixelSizeMicrons()) {
                map.putIfAbsent("pixelSize",
                        String.format("%.4f um/px", cal.getAveragedPixelSizeMicrons()));
            }
            if (imageData.getImageType() != null) {
                map.putIfAbsent("imageType", imageData.getImageType().name());
            }
        } catch (Exception e) {
            logger.debug("Failed to read intrinsic metadata: {}", e.getMessage());
        }
        return map;
    }

    /**
     * Resolve the caption metadata keys for one already-open image. Used by the
     * panel layout pane to populate the caption field picker in the same image
     * scan it uses for cell sizing, so each image is opened only once.
     *
     * @param entry     the project image entry
     * @param imageData the open image data for {@code entry}
     * @return an ordered list of distinct metadata keys
     */
    public static List<String> readMetadataKeys(
            ProjectImageEntry<BufferedImage> entry,
            ImageData<BufferedImage> imageData) {
        return new ArrayList<>(readMetadata(entry, imageData).keySet());
    }

    /**
     * Write the composed figure in the configured output format.
     */
    private static void writeFigure(BufferedImage figure, PanelExportConfig config,
                                    File outputFile) throws IOException {
        OutputFormat format = config.getFormat();
        switch (format) {
            case SVG -> writeSvg(figure, outputFile);
            case OME_TIFF, OME_TIFF_PYRAMID -> writeOmeTiff(figure, outputFile, format);
            default -> ImageWriterTools.writeImage(figure, outputFile.getAbsolutePath());
        }
    }

    /**
     * Write the composed figure as an SVG document with the raster embedded
     * as a PNG, via the bundled JFreeSVG library.
     */
    private static void writeSvg(BufferedImage figure, File outputFile) throws IOException {
        SVGGraphics2D svg = new SVGGraphics2D(figure.getWidth(), figure.getHeight());
        try {
            svg.drawImage(figure, 0, 0, null);
            Files.writeString(outputFile.toPath(), svg.getSVGDocument(),
                    StandardCharsets.UTF_8);
        } finally {
            svg.dispose();
        }
    }

    /**
     * Write the composed figure as an OME-TIFF (pyramid) using the bioformats
     * extension's OMEPyramidWriter via reflection. Falls back to a flat write
     * if bioformats is not installed.
     */
    private static void writeOmeTiff(BufferedImage figure, File outputFile,
                                     OutputFormat format) throws IOException {
        ImageServer<BufferedImage> server =
                new WrappedBufferedImageServer(outputFile.getName(), figure);
        try {
            Class<?> writerClass = Class.forName(
                    "qupath.lib.images.writers.ome.OMEPyramidWriter");
            Class<?> builderClass = Class.forName(
                    "qupath.lib.images.writers.ome.OMEPyramidWriter$Builder");
            var builderCtor = builderClass.getConstructor(ImageServer.class);
            Object builder = builderCtor.newInstance(server);
            if (format == OutputFormat.OME_TIFF_PYRAMID) {
                var scaled = builderClass.getMethod("scaledDownsampling",
                        double.class, int.class);
                builder = scaled.invoke(builder, 1.0, 4);
            }
            var tileSize = builderClass.getMethod("tileSize", int.class);
            builder = tileSize.invoke(builder, 512);
            var writeMethod = builderClass.getMethod("writePyramid", String.class);
            writeMethod.invoke(builder, outputFile.getAbsolutePath());
        } catch (ClassNotFoundException e) {
            logger.warn("OMEPyramidWriter not available; writing flat OME-TIFF");
            ImageWriterTools.writeImage(figure, outputFile.getAbsolutePath());
        } catch (Exception e) {
            throw new IOException("Failed to write OME-TIFF panel figure", e);
        } finally {
            try {
                server.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    /**
     * One image rendered to a cell, with resolved metadata.
     */
    private record RenderedCell(String name, BufferedImage image,
                                Map<String, String> metadata) {
    }
}
