package qupath.ext.quiet.export;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jfree.svg.SVGGraphics2D;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.display.settings.ImageDisplaySettings;
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
        private final List<String> recordLines;

        PanelResult(File outputFile, int composedCells, int skipped,
                    List<String> errors, int figureWidth, int figureHeight,
                    List<String> recordLines) {
            this.recordLines = List.copyOf(recordLines);
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

        /**
         * Record lines for the figure's info file: the map overlay and color range, the
         * recipe's text and legend settings, and where the legends went.
         */
        public List<String> getRecordLines() {
            return recordLines;
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
     * @param mapSource      the map overlay source for a DENSITY_MAP_OVERLAY
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
                                          MapOverlaySource mapSource,
                                          ProgressListener progress,
                                          CancelCheck cancel) throws IOException {
        int total = entries.size();
        List<RenderedCell> rendered = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int skipped = 0;
        PanelExportConfig renderConfig = config;
        if (config.hasScaleBar()
                && config.getRecipeConfig() instanceof RenderedExportConfig rc
                && rc.scaleBar().show()) {
            // The montage draws its own bars after fitting; the recipe's would be resized with each image
            logger.info("Panel scale bar is on; the recipe's own scale bar is turned off");
            renderConfig = withRecipe(config, RenderedImageExporter.withoutScaleBar(rc));
        }
        var legendMode = config.getLegendMode();
        if (legendMode != PanelExportConfig.LegendMode.EVERY_CELL
                && renderConfig.getRecipeConfig() instanceof RenderedExportConfig rc) {
            // Cells render without legends; one is drawn beside the grid, or on each cell
            // afterwards for the parts that differ between cells
            renderConfig = withRecipe(renderConfig, RenderedImageExporter.withoutLegends(rc));
        }
        if (renderConfig.getRecipeConfig() instanceof RenderedExportConfig rc
                && rc.mapOverlay().rangeMode() == RenderedExportConfig.MapRangeMode.SHARED) {
            // Every cell is drawn on one map range, computed over all the panel's images
            var resolved = MapRangeScanner.resolve(entries, mapSource, rc,
                    (i, t) -> {
                        if (progress != null) {
                            progress.onProgress(i, t, String.format(
                                    "Scanning map %d of %d for the shared range", i + 1, t));
                        }
                    },
                    cancel == null ? null : cancel::isCancelled);
            renderConfig = withRecipe(renderConfig, resolved);
        }

        // The recipe's legend settings, with the panel's shared map range
        RenderedExportConfig legendConfig = null;
        if (legendMode.isOnce() && config.getRecipeConfig() instanceof RenderedExportConfig orig
                && renderConfig.getRecipeConfig() instanceof RenderedExportConfig resolved) {
            legendConfig = RenderedExportConfig.Builder.from(orig)
                    .mapOverlay(resolved.mapOverlay()).build();
        }

        // Global Matched: one display range per kind of image, over the panel's images
        Map<Integer, ImageDisplaySettings> matchedSettings = new HashMap<>();
        if (renderConfig.getRecipeConfig() instanceof RenderedExportConfig rc
                && rc.getDisplaySettingsMode()
                    == RenderedExportConfig.DisplaySettingsMode.GLOBAL_MATCHED) {
            var groups = GlobalDisplayRangeScanner.computeGroupedRanges(
                    entries, rc.getMatchedDisplayPercentile(), 32.0,
                    (i, t) -> {
                        if (progress != null) {
                            progress.onProgress(i + 1, t, String.format(
                                    "Scanning %d of %d for display ranges", i + 1, t));
                        }
                    });
            for (var group : groups) {
                var settings = GlobalDisplayRangeScanner.buildDisplaySettings(
                        group.ranges(), entries.get(group.indices().get(0)));
                if (settings == null) continue;
                for (int index : group.indices()) {
                    matchedSettings.put(index, settings);
                }
            }
        }

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
                PanelExportConfig cellConfig = renderConfig;
                RenderedExportConfig cellLegendConfig = legendConfig;
                var matched = matchedSettings.get(i);
                if (matched != null
                        && renderConfig.getRecipeConfig() instanceof RenderedExportConfig rc) {
                    cellConfig = withRecipe(renderConfig, RenderedExportConfig.Builder.from(rc)
                            .capturedDisplaySettings(matched).build());
                    if (legendConfig != null) {
                        cellLegendConfig = RenderedExportConfig.Builder.from(legendConfig)
                                .capturedDisplaySettings(matched).build();
                    }
                }
                var cell = renderCell(imageData, cellConfig, cellLegendConfig,
                        classifier, mapSource, name);
                Map<String, String> metadata = readMetadata(entry, imageData);
                rendered.add(new RenderedCell(name, cell.image(), metadata,
                        micronsPerPixel(imageData, config.getRecipeConfig()), cell.legend()));
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

        List<String> legendLines = new ArrayList<>();
        LegendContent sharedLegend = legendConfig == null ? null
                : shareLegend(rendered, legendConfig, legendMode, legendLines);

        // Phase 2 -- compose.
        if (cancel != null && cancel.isCancelled()) {
            throw new IOException("cancelled");
        }
        if (progress != null) {
            progress.onProgress(1, 2, "Composing figure");
        }

        int largestWidth = 0;
        int largestHeight = 0;
        int maxCaptionLines = 0;
        List<PanelComposer.Cell> cells = new ArrayList<>();
        List<String> uncalibrated = new ArrayList<>();
        for (RenderedCell rc : rendered) {
            largestWidth = Math.max(largestWidth, rc.image.getWidth());
            largestHeight = Math.max(largestHeight, rc.image.getHeight());
            List<String> lines = CaptionRenderer.resolveLines(config, rc.name, rc.metadata);
            maxCaptionLines = Math.max(maxCaptionLines, lines.size());
            cells.add(new PanelComposer.Cell(rc.image, lines, rc.micronsPerPixel));
            if (!(rc.micronsPerPixel > 0)) {
                uncalibrated.add(rc.name);
            }
        }
        int[] cellSize = config.resolveCellSize(largestWidth, largestHeight);
        int cellWidth = cellSize[0];
        int cellHeight = cellSize[1];
        if (!uncalibrated.isEmpty() && (config.isMatchScale() || config.hasScaleBar())) {
            String names = String.join(", ", uncalibrated);
            if (config.isMatchScale()) {
                errors.add("Same scale not applied -- no pixel size for: " + names);
            }
            if (config.hasScaleBar()) {
                errors.add("No scale bar -- no pixel size for: " + names);
            }
            logger.warn("Panel images without a pixel size: {}", names);
        }

        int composedCellCount = cells.size();
        BufferedImage figure;
        try {
            figure = PanelComposer.compose(config, cells, cellWidth, cellHeight,
                    maxCaptionLines, sharedLegend);
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

        List<String> recordLines = new ArrayList<>();
        if (renderConfig.getRecipeConfig() instanceof RenderedExportConfig rc) {
            recordLines.addAll(ExportMetadataWriter.mapOverlayLines(rc));
        }
        if (config.getRecipeConfig() instanceof RenderedExportConfig orig) {
            for (String line : ExportMetadataWriter.textOverlayLines(orig)) {
                if (line.startsWith("Scale bar: ") && config.hasScaleBar() && orig.scaleBar().show()) {
                    line = "Scale bar: drawn by the panel after fitting (see Layout); the recipe's is off";
                }
                recordLines.add(line);
            }
        }
        recordLines.addAll(ExportMetadataWriter.panelLegendLines(config, legendLines));
        return new PanelResult(outputFile, composedCellCount, skipped, errors,
                figure.getWidth(), figure.getHeight(), recordLines);
    }

    /**
     * Decide which legends the panel draws once: the channel legend and the color scale bar
     * when every cell's is the same, the object legend pooled over the cells when no class
     * is drawn in two colors. A part that differs between cells is drawn on each cell
     * instead, and said so in {@code lines}.
     *
     * @return the legend to draw beside the grid
     */
    static LegendContent shareLegend(List<RenderedCell> cells, RenderedExportConfig legendConfig,
                                     PanelExportConfig.LegendMode mode, List<String> lines) {
        var channels = new ArrayList<List<LegendRenderer.Entry>>();
        var objects = new ArrayList<List<LegendRenderer.Entry>>();
        var maps = new ArrayList<LegendContent.MapScale>();
        for (var cell : cells) {
            var legend = cell.legend() != null ? cell.legend() : LegendContent.EMPTY;
            channels.add(legend.channels());
            objects.add(legend.objects());
            maps.add(legend.map());
        }
        var sharedChannels = LegendContent.shared(channels);
        var pooledObjects = LegendContent.poolObjects(objects);
        var sharedMap = LegendContent.shared(maps);
        boolean anyMap = maps.stream().anyMatch(m -> m != null);
        String where = mode.getDisplayName().toLowerCase();
        if (RenderedImageExporter.wantChannelLegend(legendConfig)) {
            lines.add(sharedChannels != null
                    ? "Channel legend: " + where + ", the same for every cell"
                    : "Channel legend: on every cell -- the cells show different channels or colors");
        }
        if (legendConfig.isShowObjectLegend()) {
            lines.add(pooledObjects != null
                    ? "Object legend: " + where + ", the classes drawn in any cell"
                    : "Object legend: on every cell -- a class is drawn in different colors in different cells");
        }
        if (anyMap) {
            lines.add(sharedMap != null
                    ? "Color scale bar: " + where + ", the same range for every cell"
                    : "Color scale bar: on every cell -- the cells have different ranges");
        }
        // Parts that differ go back on their cells
        for (var cell : cells) {
            if (cell.legend() == null) {
                continue;
            }
            var own = new LegendContent(
                    sharedChannels == null ? cell.legend().channels() : List.of(),
                    pooledObjects == null ? cell.legend().objects() : List.of(),
                    sharedMap == null ? cell.legend().map() : null);
            if (!own.isEmpty()) {
                var g2d = cell.image().createGraphics();
                try {
                    RenderedImageExporter.drawLegend(g2d, legendConfig, own,
                            cell.image().getWidth(), cell.image().getHeight(),
                            TextRenderUtils.Backdrop.of(cell.image()));
                } finally {
                    g2d.dispose();
                }
            }
        }
        return new LegendContent(
                sharedChannels != null ? sharedChannels : List.of(),
                pooledObjects != null ? pooledObjects : List.of(),
                sharedMap);
    }

    /**
     * Render one image to a cell BufferedImage via the recipe category's
     * exporter.
     * <p>
     * For a RENDERED recipe in CLASSIFIER_OVERLAY or DENSITY_MAP_OVERLAY mode
     * the resolved {@code classifier} / {@code mapSource} must be non-null
     * and applicable -- the panel never silently falls back to a plain
     * object-overlay render that would drop the recipe's headline content.
     */
    private static RenderedImageExporter.CellRender renderCell(
            ImageData<BufferedImage> imageData, PanelExportConfig config,
            RenderedExportConfig legendConfig, PixelClassifier classifier,
            MapOverlaySource mapSource, String entryName) throws IOException {
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
                        && mapSource == null) {
                    throw new IOException("the recipe renders a map overlay but"
                            + " no map source is available");
                }
                yield RenderedImageExporter.renderCell(imageData, classifier,
                        mapSource, rc, legendConfig, entryName);
            }
            case RAW -> {
                if (!(recipe instanceof RawExportConfig rawc)) {
                    throw new IOException("recipe configuration type mismatch (raw)");
                }
                yield new RenderedImageExporter.CellRender(
                        RawImageExporter.renderToImage(imageData, rawc, entryName), null);
            }
            case MASK -> {
                if (!(recipe instanceof MaskExportConfig mc)) {
                    throw new IOException("recipe configuration type mismatch (mask)");
                }
                yield new RenderedImageExporter.CellRender(
                        MaskImageExporter.renderToImage(imageData, mc, entryName), null);
            }
            case OBJECT_CROPS -> {
                if (!(recipe instanceof ObjectCropConfig occ)) {
                    throw new IOException("recipe configuration type mismatch (object crop)");
                }
                yield new RenderedImageExporter.CellRender(
                        ObjectCropExporter.renderToImage(imageData, occ, entryName), null);
            }
            case TILED, PANEL -> throw new IOException(
                    "unsupported recipe category: " + config.getRecipeCategory());
        };
    }

    /**
     * Microns per pixel of the image a recipe renders for this image.
     *
     * @return NaN if the image has no pixel size
     */
    static double micronsPerPixel(ImageData<BufferedImage> imageData, Object recipe) {
        var cal = imageData.getServer().getPixelCalibration();
        if (!cal.hasPixelSizeMicrons()) {
            return Double.NaN;
        }
        double px = cal.getAveragedPixelSizeMicrons();
        return px * recipeDownsample(recipe, px);
    }

    /**
     * The downsample a recipe renders at.
     *
     * @param pixelSizeMicrons the image's pixel size, for a DPI-driven Rendered recipe; NaN if unknown
     */
    public static double recipeDownsample(Object recipe, double pixelSizeMicrons) {
        if (recipe instanceof RenderedExportConfig rc) {
            return rc.getTargetDpi() > 0 && pixelSizeMicrons > 0
                    ? rc.computeEffectiveDownsample(pixelSizeMicrons) : rc.getDownsample();
        }
        if (recipe instanceof RawExportConfig raw) {
            return raw.getDownsample();
        }
        if (recipe instanceof MaskExportConfig mc) {
            return mc.getDownsample();
        }
        if (recipe instanceof ObjectCropConfig occ) {
            return occ.getDownsample();
        }
        return 1.0;
    }

    /** A copy of {@code config} rendering with {@code recipe} instead. */
    private static PanelExportConfig withRecipe(PanelExportConfig config, Object recipe) {
        return config.toBuilder().recipeConfig(recipe).build();
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
                        String.format("%.4f \u00B5m/px", cal.getAveragedPixelSizeMicrons()));
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
    record RenderedCell(String name, BufferedImage image,
                        Map<String, String> metadata, double micronsPerPixel,
                        LegendContent legend) {
    }
}
