package qupath.ext.quiet.export;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.concurrent.Task;
import qupath.lib.analysis.heatmaps.DensityMaps.DensityMapBuilder;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.display.settings.ImageDisplaySettings;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Background JavaFX Task that exports selected project images based on
 * the chosen export category.
 * <p>
 * Reports progress (N of M images) and handles errors per-image,
 * continuing to process remaining images if one fails.
 */
public class BatchExportTask extends Task<ExportResult> {

    private static final Logger logger = LoggerFactory.getLogger(BatchExportTask.class);

    private final List<ProjectImageEntry<BufferedImage>> entries;
    private final ExportCategory category;
    private final RenderedExportConfig renderedConfig;
    private final MaskExportConfig maskConfig;
    private final RawExportConfig rawConfig;
    private final TiledExportConfig tiledConfig;
    private final ObjectCropConfig objectCropConfig;
    private final PixelClassifier classifier;
    private final DensityMapBuilder densityBuilder;
    private final String workflowScript;
    private final boolean exportGeoJson;
    private final File outputDirectory;
    private final String filenamePrefix;
    private final String filenameSuffix;
    private final boolean channelsConsistent;

    /** Effective config after optional GLOBAL_MATCHED pre-scan. */
    private RenderedExportConfig effectiveConfig;

    /**
     * Create a batch export task for rendered exports.
     */
    public static BatchExportTask forRendered(List<ProjectImageEntry<BufferedImage>> entries,
                                              RenderedExportConfig config,
                                              PixelClassifier classifier,
                                              DensityMapBuilder densityBuilder,
                                              String workflowScript,
                                              boolean exportGeoJson,
                                              String filenamePrefix,
                                              String filenameSuffix,
                                              boolean channelsConsistent) {
        return new BatchExportTask(entries, ExportCategory.RENDERED,
                config, null, null, null, null, classifier, densityBuilder, workflowScript,
                exportGeoJson, config.getOutputDirectory(), filenamePrefix, filenameSuffix,
                channelsConsistent);
    }

    /**
     * Create a batch export task for mask exports.
     */
    public static BatchExportTask forMask(List<ProjectImageEntry<BufferedImage>> entries,
                                          MaskExportConfig config,
                                          String workflowScript,
                                          boolean exportGeoJson,
                                          String filenamePrefix,
                                          String filenameSuffix) {
        return new BatchExportTask(entries, ExportCategory.MASK,
                null, config, null, null, null, null, null, workflowScript,
                exportGeoJson, config.getOutputDirectory(), filenamePrefix, filenameSuffix, true);
    }

    /**
     * Create a batch export task for raw exports.
     */
    public static BatchExportTask forRaw(List<ProjectImageEntry<BufferedImage>> entries,
                                         RawExportConfig config,
                                         String workflowScript,
                                         boolean exportGeoJson,
                                         String filenamePrefix,
                                         String filenameSuffix,
                                         boolean channelsConsistent) {
        return new BatchExportTask(entries, ExportCategory.RAW,
                null, null, config, null, null, null, null, workflowScript,
                exportGeoJson, config.getOutputDirectory(), filenamePrefix, filenameSuffix,
                channelsConsistent);
    }

    /**
     * Create a batch export task for tiled exports.
     */
    public static BatchExportTask forTiled(List<ProjectImageEntry<BufferedImage>> entries,
                                           TiledExportConfig config,
                                           String workflowScript,
                                           boolean exportGeoJson,
                                           String filenamePrefix,
                                           String filenameSuffix) {
        return new BatchExportTask(entries, ExportCategory.TILED,
                null, null, null, config, null, null, null, workflowScript,
                exportGeoJson, config.getOutputDirectory(), filenamePrefix, filenameSuffix, true);
    }

    /**
     * Create a batch export task for object crop exports.
     */
    public static BatchExportTask forObjectCrops(List<ProjectImageEntry<BufferedImage>> entries,
                                                  ObjectCropConfig config,
                                                  String workflowScript,
                                                  boolean exportGeoJson,
                                                  String filenamePrefix,
                                                  String filenameSuffix,
                                                  boolean channelsConsistent) {
        return new BatchExportTask(entries, ExportCategory.OBJECT_CROPS,
                null, null, null, null, config, null, null, workflowScript,
                exportGeoJson, config.getOutputDirectory(), filenamePrefix, filenameSuffix,
                channelsConsistent);
    }

    private BatchExportTask(List<ProjectImageEntry<BufferedImage>> entries,
                            ExportCategory category,
                            RenderedExportConfig renderedConfig,
                            MaskExportConfig maskConfig,
                            RawExportConfig rawConfig,
                            TiledExportConfig tiledConfig,
                            ObjectCropConfig objectCropConfig,
                            PixelClassifier classifier,
                            DensityMapBuilder densityBuilder,
                            String workflowScript,
                            boolean exportGeoJson,
                            File outputDirectory,
                            String filenamePrefix,
                            String filenameSuffix,
                            boolean channelsConsistent) {
        this.entries = List.copyOf(entries);
        this.category = category;
        this.renderedConfig = renderedConfig;
        this.maskConfig = maskConfig;
        this.rawConfig = rawConfig;
        this.tiledConfig = tiledConfig;
        this.objectCropConfig = objectCropConfig;
        this.classifier = classifier;
        this.densityBuilder = densityBuilder;
        this.workflowScript = workflowScript;
        this.exportGeoJson = exportGeoJson;
        this.outputDirectory = outputDirectory;
        this.filenamePrefix = filenamePrefix != null ? filenamePrefix : "";
        this.filenameSuffix = filenameSuffix != null ? filenameSuffix : "";
        this.channelsConsistent = channelsConsistent;
    }

    @Override
    protected ExportResult call() throws Exception {
        int total = entries.size();
        int succeeded = 0;
        int failed = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        // Resolve effective config (may pre-scan for GLOBAL_MATCHED)
        effectiveConfig = renderedConfig;
        if (renderedConfig != null && category == ExportCategory.RENDERED
                && renderedConfig.getDisplaySettingsMode()
                    == RenderedExportConfig.DisplaySettingsMode.GLOBAL_MATCHED) {
            updateMessage("Scanning images for display range matching...");
            var ranges = GlobalDisplayRangeScanner.computeGlobalRanges(
                    entries, renderedConfig.getMatchedDisplayPercentile(), 32.0,
                    (i, t) -> {
                        updateMessage(String.format(
                                "Scanning %d of %d for display ranges...", i + 1, t));
                        updateProgress(i, t + total);
                    });
            if (ranges != null && !ranges.isEmpty()) {
                var settings = GlobalDisplayRangeScanner.buildDisplaySettings(
                        ranges, entries.get(0));
                if (settings != null) {
                    effectiveConfig = rebuildWithMatchedSettings(renderedConfig, settings);
                    logger.info("Global matched display ranges computed for {} channels",
                            ranges.size());
                }
            }
        }

        // Metadata tracking
        Map<String, ExportMetadataWriter.ChannelGroup> channelGroups = new LinkedHashMap<>();
        PixelCalibration firstCalibration = null;

        for (int i = 0; i < total; i++) {
            if (isCancelled()) {
                logger.info("Export cancelled by user after {} of {} images", i, total);
                break;
            }

            var entry = entries.get(i);
            String rawName = entry.getImageName();
            String entryName = filenamePrefix + rawName + filenameSuffix;

            updateMessage(String.format("Exporting %d of %d: %s", i + 1, total, entryName));
            updateProgress(i, total);

            ImageData<BufferedImage> imageData = null;
            try {
                imageData = entry.readImageData();

                // Skip images without matching classifications (mask export)
                if (category == ExportCategory.MASK && maskConfig != null
                        && maskConfig.isSkipEmptyImages()
                        && !maskConfig.getSelectedClassifications().isEmpty()) {
                    if (!hasMatchingClassifications(imageData, maskConfig)) {
                        skipped++;
                        logger.info("Skipping {}: no objects with selected classifications", entryName);
                        continue;
                    }
                }

                switch (category) {
                    case RENDERED -> exportRendered(imageData, entryName, i);
                    case MASK -> MaskImageExporter.exportMask(imageData, maskConfig, entryName);
                    case RAW -> RawImageExporter.exportRaw(imageData, rawConfig, entryName);
                    case TILED -> TiledImageExporter.exportTiled(imageData, tiledConfig, entryName);
                    case OBJECT_CROPS -> ObjectCropExporter.exportObjectCrops(
                            imageData, objectCropConfig, entryName);
                }

                // GeoJSON export (orthogonal to image export)
                if (exportGeoJson && outputDirectory != null) {
                    try {
                        GeoJsonExporter.exportGeoJson(imageData, outputDirectory, entryName);
                    } catch (Exception ge) {
                        logger.warn("GeoJSON export failed for {}: {}", entryName, ge.getMessage());
                    }
                }
                succeeded++;

                // Track channel info for metadata sidecar
                trackChannelGroup(channelGroups, imageData, entryName);
                if (firstCalibration == null) {
                    firstCalibration = imageData.getServer().getPixelCalibration();
                }

                // Add workflow step if configured
                if (shouldAddWorkflow() && workflowScript != null) {
                    addWorkflowStep(imageData, entry);
                }

            } catch (IllegalArgumentException e) {
                // Classifier doesn't support image - skip
                skipped++;
                String msg = entryName + ": " + e.getMessage();
                errors.add(msg);
                logger.warn("Skipping {}: {}", entryName, e.getMessage());
            } catch (Exception e) {
                failed++;
                String errorMsg = entryName + ": " + e.getMessage();
                errors.add(errorMsg);
                logger.error("Failed to export image: {}", entryName, e);
            } finally {
                if (imageData != null) {
                    try {
                        imageData.getServer().close();
                    } catch (Exception e) {
                        logger.warn("Error closing image server for: {}", entryName, e);
                    }
                }
            }
        }

        // Write metadata sidecar file
        if (succeeded > 0) {
            writeMetadataSidecar(channelGroups, firstCalibration);
        }

        updateProgress(total, total);
        updateMessage("Export complete");

        return new ExportResult(succeeded, failed, skipped, errors);
    }

    private void exportRendered(ImageData<BufferedImage> imageData, String entryName,
                                int panelIndex) throws Exception {
        var config = effectiveConfig != null ? effectiveConfig : renderedConfig;

        // Split-channel dispatch
        if (config.splitChannel().enabled()) {
            RenderedImageExporter.exportSplitChannels(
                    imageData, classifier, densityBuilder, config, entryName, panelIndex);
            return;
        }

        if (config.getRegionType() == RenderedExportConfig.RegionType.ALL_ANNOTATIONS) {
            RenderedImageExporter.exportPerAnnotation(
                    imageData, classifier, densityBuilder, config, entryName, panelIndex);
        } else {
            switch (config.getRenderMode()) {
                case OBJECT_OVERLAY ->
                    RenderedImageExporter.exportWithObjectOverlay(
                            imageData, config, entryName, panelIndex);
                case DENSITY_MAP_OVERLAY ->
                    RenderedImageExporter.exportWithDensityMap(
                            imageData, densityBuilder, config, entryName, panelIndex);
                case CLASSIFIER_OVERLAY -> {
                    if (!classifier.supportsImage(imageData)) {
                        throw new IllegalArgumentException(
                                "Classifier does not support image: " + entryName);
                    }
                    RenderedImageExporter.exportWithClassifier(
                            imageData, classifier, config, entryName, panelIndex);
                }
            }
        }
    }

    /**
     * Compute a signature string for an image's channel configuration.
     * Used for channel consistency validation across images.
     */
    public static String channelSignature(ImageServer<?> server) {
        var channels = server.getMetadata().getChannels();
        var sb = new StringBuilder();
        for (var ch : channels) {
            sb.append(ch.getName()).append('|')
              .append(Integer.toHexString(ch.getColor())).append(',');
        }
        return sb.toString();
    }

    /**
     * Track channel group information for the metadata sidecar.
     */
    private void trackChannelGroup(Map<String, ExportMetadataWriter.ChannelGroup> groups,
                                    ImageData<BufferedImage> imageData,
                                    String entryName) {
        try {
            var server = imageData.getServer();
            String sig = channelSignature(server);
            var group = groups.get(sig);
            if (group == null) {
                var channels = List.copyOf(server.getMetadata().getChannels());
                var stains = imageData.getColorDeconvolutionStains();
                var cal = server.getPixelCalibration();
                var filenames = new ArrayList<String>();
                // Compute the exported filename using the appropriate config
                String exportedName = switch (category) {
                    case RENDERED -> renderedConfig.buildOutputFilename(entryName);
                    case MASK -> maskConfig.buildOutputFilename(entryName);
                    case RAW -> rawConfig.buildOutputFilename(entryName);
                    case TILED -> tiledConfig.buildOutputFilename(entryName);
                    case OBJECT_CROPS -> objectCropConfig.buildOutputFilename(entryName);
                };
                filenames.add(exportedName);
                groups.put(sig, new ExportMetadataWriter.ChannelGroup(
                        channels, imageData.getImageType(), stains, cal, filenames));
            } else {
                String exportedName = switch (category) {
                    case RENDERED -> renderedConfig.buildOutputFilename(entryName);
                    case MASK -> maskConfig.buildOutputFilename(entryName);
                    case RAW -> rawConfig.buildOutputFilename(entryName);
                    case TILED -> tiledConfig.buildOutputFilename(entryName);
                    case OBJECT_CROPS -> objectCropConfig.buildOutputFilename(entryName);
                };
                group.filenames().add(exportedName);
            }
        } catch (Exception e) {
            logger.debug("Failed to track channel group for {}: {}", entryName, e.getMessage());
        }
    }

    /**
     * Write the metadata sidecar file appropriate for the export category.
     */
    private void writeMetadataSidecar(Map<String, ExportMetadataWriter.ChannelGroup> channelGroups,
                                       PixelCalibration firstCalibration) {
        try {
            switch (category) {
                case MASK -> ExportMetadataWriter.writeMaskLegend(
                        maskConfig, firstCalibration,
                        maskConfig.getDownsample(), outputDirectory);
                case RENDERED -> ExportMetadataWriter.writeExportInfo(
                        List.copyOf(channelGroups.values()),
                        renderedConfig.getDownsample(), category,
                        renderedConfig, null, outputDirectory,
                        channelsConsistent);
                case RAW -> ExportMetadataWriter.writeExportInfo(
                        List.copyOf(channelGroups.values()),
                        rawConfig.getDownsample(), category,
                        null, null, outputDirectory,
                        channelsConsistent);
                case TILED -> ExportMetadataWriter.writeExportInfo(
                        List.copyOf(channelGroups.values()),
                        tiledConfig.getDownsample(), category,
                        null, tiledConfig, outputDirectory,
                        channelsConsistent);
                case OBJECT_CROPS -> ExportMetadataWriter.writeExportInfo(
                        List.copyOf(channelGroups.values()),
                        objectCropConfig.getDownsample(), category,
                        null, null, outputDirectory,
                        channelsConsistent);
            }
        } catch (Exception e) {
            logger.warn("Failed to write metadata sidecar file: {}", e.getMessage());
        }
    }

    private boolean shouldAddWorkflow() {
        return switch (category) {
            case RENDERED -> renderedConfig != null && renderedConfig.isAddToWorkflow();
            case MASK -> maskConfig != null && maskConfig.isAddToWorkflow();
            case RAW -> rawConfig != null && rawConfig.isAddToWorkflow();
            case TILED -> tiledConfig != null && tiledConfig.isAddToWorkflow();
            case OBJECT_CROPS -> objectCropConfig != null && objectCropConfig.isAddToWorkflow();
        };
    }

    /**
     * Rebuild a config with computed global-matched display settings injected.
     * All other fields are copied from the original config.
     */
    private static RenderedExportConfig rebuildWithMatchedSettings(
            RenderedExportConfig original, ImageDisplaySettings settings) {
        return new RenderedExportConfig.Builder()
                // Core fields
                .regionType(original.getRegionType())
                .selectedClassifications(original.getSelectedClassifications())
                .paddingPixels(original.getPaddingPixels())
                .renderMode(original.getRenderMode())
                .displaySettingsMode(original.getDisplaySettingsMode())
                .capturedDisplaySettings(settings) // inject computed settings
                .displayPresetName(original.getDisplayPresetName())
                .overlayOpacity(original.getOverlayOpacity())
                .downsample(original.getDownsample())
                .targetDpi(original.getTargetDpi())
                .format(original.getFormat())
                .outputDirectory(original.getOutputDirectory())
                .addToWorkflow(original.isAddToWorkflow())
                .matchedDisplayPercentile(original.getMatchedDisplayPercentile())
                // Sub-configs copied wholesale
                .overlays(original.overlays())
                .scaleBar(original.scaleBar())
                .colorScaleBar(original.colorScaleBar())
                .panelLabel(original.panelLabel())
                .infoLabel(original.infoLabel())
                .splitChannel(original.splitChannel())
                .inset(original.inset())
                .build();
    }

    private void addWorkflowStep(ImageData<BufferedImage> imageData,
                                 ProjectImageEntry<BufferedImage> entry) {
        try {
            String stepName = switch (category) {
                case RENDERED -> switch (renderedConfig.getRenderMode()) {
                    case OBJECT_OVERLAY -> "Object Overlay Export";
                    case DENSITY_MAP_OVERLAY -> "Density Map Export";
                    case CLASSIFIER_OVERLAY -> "Classifier Export";
                };
                case MASK -> "Mask Export";
                case RAW -> "Raw Image Export";
                case TILED -> "Tiled Export";
                case OBJECT_CROPS -> "Object Crop Export";
            };
            imageData.getHistoryWorkflow().addStep(
                    new DefaultScriptableWorkflowStep(stepName, workflowScript));
            entry.saveImageData(imageData);
            logger.debug("Added workflow step for: {}", entry.getImageName());
        } catch (Exception e) {
            logger.warn("Failed to add workflow step for: {}", entry.getImageName(), e);
        }
    }

    /**
     * Check whether an image contains any objects matching the mask config's
     * selected classifications, using the configured object source.
     */
    private static boolean hasMatchingClassifications(ImageData<BufferedImage> imageData,
                                                       MaskExportConfig config) {
        var hierarchy = imageData.getHierarchy();
        Collection<? extends PathObject> objects = switch (config.getObjectSource()) {
            case ANNOTATIONS -> hierarchy.getAnnotationObjects();
            case DETECTIONS -> hierarchy.getDetectionObjects();
            case CELLS -> hierarchy.getCellObjects();
        };
        Set<String> classSet = new HashSet<>(config.getSelectedClassifications());
        return objects.stream().anyMatch(obj ->
                obj.getPathClass() != null && classSet.contains(obj.getPathClass().toString()));
    }
}
