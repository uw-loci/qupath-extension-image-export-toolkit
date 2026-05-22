package qupath.ext.quiet.export;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.common.ColorTools;
import qupath.lib.display.settings.ImageDisplaySettings;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Writes human-readable metadata sidecar {@code .txt} files alongside exported images.
 * <p>
 * Two entry points:
 * <ul>
 *   <li>{@link #writeMaskLegend} -- label-to-class mapping for mask exports</li>
 *   <li>{@link #writeExportInfo} -- channel, display, and pixel size info for
 *       rendered, raw, and tiled exports</li>
 * </ul>
 * Failures are logged but never thrown -- metadata is supplementary.
 */
public class ExportMetadataWriter {

    private static final Logger logger = LoggerFactory.getLogger(ExportMetadataWriter.class);

    private ExportMetadataWriter() {
        // Utility class
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Write a {@code mask_legend.txt} sidecar describing the mask label mapping.
     *
     * @param config      the mask export configuration
     * @param calibration pixel calibration from the first exported image (may be null)
     * @param outputDir   the export output directory
     */
    public static void writeMaskLegend(MaskExportConfig config,
                                        PixelCalibration calibration,
                                        double downsample,
                                        File outputDir) {
        File file = new File(outputDir, "mask_legend.txt");
        try (var pw = new PrintWriter(file, StandardCharsets.UTF_8)) {
            pw.println("Mask Export Legend");
            pw.println("=================");
            pw.println();
            pw.println("Mask Type: " + config.getMaskType().name());
            pw.println("Object Source: " + config.getObjectSource().name());
            pw.println();

            switch (config.getMaskType()) {
                case GRAYSCALE_LABELS -> writeLabelMapping(pw, config);
                case BINARY -> writeBinaryMapping(pw);
                case INSTANCE -> writeInstanceInfo(pw, config);
                case COLORED -> writeColoredMapping(pw, config);
                case MULTICHANNEL -> writeMultichannelMapping(pw, config);
            }

            if (config.isEnableBoundary()) {
                pw.println();
                pw.println("Boundary: enabled (thickness = "
                        + config.getBoundaryThickness() + " px)");
                pw.println("Boundary Label: " + config.getBoundaryLabel());
            }

            pw.println();
            writePixelSizeInfo(pw, calibration, downsample);

            logger.info("Wrote mask legend: {}", file.getAbsolutePath());
        } catch (IOException e) {
            logger.warn("Failed to write mask legend: {}", e.getMessage());
        }
    }

    /**
     * Container for grouping images with the same channel configuration.
     *
     * @param channels   the image channels
     * @param imageType  the QuPath image type
     * @param stains     stain vectors (brightfield only, may be null)
     * @param calibration pixel calibration (may be null)
     * @param filenames  exported filenames in this group
     */
    public record ChannelGroup(
            List<ImageChannel> channels,
            ImageData.ImageType imageType,
            qupath.lib.color.ColorDeconvolutionStains stains,
            PixelCalibration calibration,
            List<String> filenames
    ) {}

    /**
     * Write an {@code export_info.txt} sidecar describing channels, display
     * settings, and pixel size for rendered, raw, tiled, or object crop exports.
     *
     * @param channelGroups     image groups with the same channel signature
     * @param downsample        the export downsample factor
     * @param category          the export category
     * @param renderedConfig    rendered config (null for non-rendered exports)
     * @param tiledConfig       tiled config (null for non-tiled exports)
     * @param outputDir         the export output directory
     * @param channelsConsistent true if all images have the same channel configuration
     */
    public static void writeExportInfo(List<ChannelGroup> channelGroups,
                                        double downsample,
                                        ExportCategory category,
                                        RenderedExportConfig renderedConfig,
                                        TiledExportConfig tiledConfig,
                                        File outputDir,
                                        boolean channelsConsistent) {
        File file = new File(outputDir, "export_info.txt");
        try (var pw = new PrintWriter(file, StandardCharsets.UTF_8)) {
            pw.println("Export Info");
            pw.println("===========");
            pw.println();

            if (!channelsConsistent) {
                pw.println("WARNING: Channel names/order are not consistent across exported images.");
                pw.println("Color legend and unified channel info may not be accurate.");
                pw.println();
            }

            pw.println("Export Category: " + category.getDisplayName());
            pw.println("Downsample: " + formatDouble(downsample) + "x");
            pw.println();

            for (var group : channelGroups) {
                writeChannelGroup(pw, group, renderedConfig, channelsConsistent);
            }

            // Tiled export parameters
            if (category == ExportCategory.TILED && tiledConfig != null) {
                pw.println("Tiled Export Parameters");
                pw.println("-----------------------");
                pw.println("Tile Size: " + tiledConfig.getTileSize() + " px");
                pw.println("Overlap: " + tiledConfig.getOverlap() + " px");
                pw.println("Downsample: " + formatDouble(tiledConfig.getDownsample()) + "x");
                pw.println();
            }

            // Pixel size from the first group that has calibration
            PixelCalibration cal = null;
            for (var group : channelGroups) {
                if (group.calibration() != null
                        && group.calibration().hasPixelSizeMicrons()) {
                    cal = group.calibration();
                    break;
                }
            }
            writePixelSizeInfo(pw, cal, downsample);

            logger.info("Wrote export info: {}", file.getAbsolutePath());
        } catch (IOException e) {
            logger.warn("Failed to write export info: {}", e.getMessage());
        }
    }

    /**
     * Write a {@code panel_figure_info.txt} provenance sidecar next to a
     * composed panel / montage figure.
     * <p>
     * Records the ordered source-image list, the recipe (category + settings),
     * the grid / gutter / background / cell-fit layout, the caption
     * configuration, the composed dimensions, the QuIET version and an export
     * timestamp -- so the figure can be traced back to its inputs and recipe.
     *
     * @param config        the panel export configuration
     * @param sourceEntries the source image entries, in placement order
     * @param figureFile    the composed-figure output file
     * @param figureWidth   the composed-figure width in pixels
     * @param figureHeight  the composed-figure height in pixels
     * @param composedCells the number of cells actually composed
     * @param skipped       the number of images skipped during render
     */
    public static void writePanelInfo(PanelExportConfig config,
                                       List<ProjectImageEntry<BufferedImage>> sourceEntries,
                                       File figureFile,
                                       int figureWidth,
                                       int figureHeight,
                                       int composedCells,
                                       int skipped) {
        if (figureFile == null) {
            return;
        }
        File parent = figureFile.getParentFile();
        File file = new File(parent != null ? parent : new File("."),
                "panel_figure_info.txt");
        try (var pw = new PrintWriter(file, StandardCharsets.UTF_8)) {
            pw.println("Panel / Montage Figure Info");
            pw.println("===========================");
            pw.println();
            pw.println("Generated by QuIET (QuPath Image Export Toolkit) version "
                    + qupath.ext.quiet.QuietExtension.QUIET_VERSION);
            pw.println("Export timestamp: "
                    + ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            pw.println();

            pw.println("Composed Figure");
            pw.println("---------------");
            pw.println("File: " + figureFile.getName());
            pw.println("Format: " + config.getFormat().name());
            pw.println("Dimensions: " + figureWidth + " x " + figureHeight + " px");
            pw.println("Cells composed: " + composedCells
                    + (skipped > 0 ? " (" + skipped + " image(s) skipped)" : ""));
            pw.println();

            pw.println("Layout");
            pw.println("------");
            pw.println("Grid: " + config.getRows() + " rows x "
                    + config.getCols() + " columns");
            pw.println("Gutters: " + config.getGutterX() + " x "
                    + config.getGutterY() + " px");
            pw.println("Background: " + toHex(config.getBackgroundColor()));
            pw.println("Cell fit: " + config.getCellFitMode().name());
            pw.println();

            pw.println("Captions");
            pw.println("--------");
            pw.println("Filename caption: "
                    + (config.isShowFilenameCaption() ? "shown" : "off"));
            var fields = config.getMetadataFields();
            pw.println("Metadata fields: "
                    + (fields.isEmpty() ? "(none)" : String.join(", ", fields)));
            if (config.hasCaption()) {
                pw.println("Caption position: " + config.getCaptionPosition().name());
                pw.println("Caption font size: " + config.getCaptionFontSize() + " pt");
                pw.println("Caption colour: " + toHex(config.getCaptionColor()));
            }
            pw.println();

            pw.println("Recipe");
            pw.println("------");
            pw.println("Category: " + config.getRecipeCategory().getDisplayName());
            writeRecipeSettings(pw, config);
            pw.println();

            pw.println("Source Images (" + sourceEntries.size()
                    + ", in placement order)");
            pw.println("-------------------------------------------");
            int idx = 1;
            for (var entry : sourceEntries) {
                pw.println("  " + idx++ + ". " + entry.getImageName());
            }

            logger.info("Wrote panel figure info: {}", file.getAbsolutePath());
        } catch (IOException e) {
            logger.warn("Failed to write panel figure info: {}", e.getMessage());
        }
    }

    /**
     * Write the recipe's key single-image settings for the panel sidecar.
     */
    private static void writeRecipeSettings(PrintWriter pw, PanelExportConfig config) {
        Object recipe = config.getRecipeConfig();
        if (recipe instanceof RenderedExportConfig rc) {
            pw.println("Render mode: " + rc.getRenderMode().name());
            pw.println("Downsample: " + formatDouble(rc.getDownsample()) + "x");
            if (rc.getTargetDpi() > 0) {
                pw.println("Target DPI: " + rc.getTargetDpi());
            }
            pw.println("Display settings mode: "
                    + rc.getDisplaySettingsMode().name());
        } else if (recipe instanceof RawExportConfig raw) {
            pw.println("Region type: " + raw.getRegionType().name());
            pw.println("Downsample: " + formatDouble(raw.getDownsample()) + "x");
        } else if (recipe instanceof MaskExportConfig mc) {
            pw.println("Mask type: " + mc.getMaskType().name());
            pw.println("Object source: " + mc.getObjectSource().name());
            pw.println("Downsample: " + formatDouble(mc.getDownsample()) + "x");
        } else if (recipe instanceof ObjectCropConfig occ) {
            pw.println("Object type: " + occ.getObjectType().name());
            pw.println("Crop size: " + occ.getCropSize() + " px");
            pw.println("Downsample: " + formatDouble(occ.getDownsample()) + "x");
        }
    }

    /**
     * Convert an AWT colour to a {@code #RRGGBB} hex string.
     */
    private static String toHex(java.awt.Color c) {
        if (c == null) {
            return "#FFFFFF";
        }
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    // ------------------------------------------------------------------
    // Mask legend helpers
    // ------------------------------------------------------------------

    private static void writeLabelMapping(PrintWriter pw, MaskExportConfig config) {
        pw.println("Label Mapping:");
        pw.println("  " + config.getBackgroundLabel() + " = Background");
        var classes = config.getSelectedClassifications();
        for (int i = 0; i < classes.size(); i++) {
            pw.println("  " + (i + 1) + " = " + classes.get(i));
        }
    }

    private static void writeBinaryMapping(PrintWriter pw) {
        pw.println("Label Mapping:");
        pw.println("  0 = Background");
        pw.println("  1 = Foreground (any classified object)");
    }

    private static void writeInstanceInfo(PrintWriter pw, MaskExportConfig config) {
        pw.println("Each unique non-zero pixel value represents a distinct object instance.");
        pw.println("Background = 0");
        if (config.isShuffleInstanceLabels()) {
            pw.println("Instance label IDs were shuffled for visual distinction.");
        }
    }

    private static void writeColoredMapping(PrintWriter pw, MaskExportConfig config) {
        pw.println("Class -> Color Mapping:");
        pw.println("  Background = RGB(0, 0, 0)");
        var classes = config.getSelectedClassifications();
        for (var className : classes) {
            PathClass pc = PathClass.fromString(className);
            int color = pc.getColor();
            int r = ColorTools.red(color);
            int g = ColorTools.green(color);
            int b = ColorTools.blue(color);
            pw.println("  " + className + " = RGB(" + r + ", " + g + ", " + b + ")");
        }
    }

    private static void writeMultichannelMapping(PrintWriter pw, MaskExportConfig config) {
        pw.println("Channel -> Class Mapping:");
        var classes = config.getSelectedClassifications();
        for (int i = 0; i < classes.size(); i++) {
            pw.println("  Channel " + i + ": " + classes.get(i));
        }
    }

    // ------------------------------------------------------------------
    // Export info helpers
    // ------------------------------------------------------------------

    private static void writeChannelGroup(PrintWriter pw, ChannelGroup group,
                                           RenderedExportConfig renderedConfig,
                                           boolean channelsConsistent) {
        int count = group.filenames().size();
        pw.println("Image Group (" + count + " image" + (count != 1 ? "s" : "") + "):");

        // List filenames (truncate if too many)
        if (count <= 10) {
            pw.println("  Files: " + String.join(", ", group.filenames()));
        } else {
            var first5 = group.filenames().subList(0, 5);
            pw.println("  Files: " + String.join(", ", first5)
                    + " ... and " + (count - 5) + " more");
        }

        pw.println("  Image Type: " + group.imageType());
        pw.println();

        // Channels -- always write per-group info (useful for debugging inconsistencies)
        if (group.imageType() == ImageData.ImageType.BRIGHTFIELD_H_E
                || group.imageType() == ImageData.ImageType.BRIGHTFIELD_H_DAB
                || group.imageType() == ImageData.ImageType.BRIGHTFIELD_OTHER) {
            writeBrightfieldInfo(pw, group);
        } else if (channelsConsistent) {
            // Only write the full color legend when channels are consistent
            writeChannelList(pw, group);
        } else {
            // Just list channel names without color info
            pw.println("  Channels: " + group.channels().stream()
                    .map(ch -> ch.getName())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("(none)"));
        }

        // Display settings for rendered exports
        if (renderedConfig != null) {
            writeDisplaySettingsInfo(pw, renderedConfig);
        }

        pw.println();
    }

    private static void writeChannelList(PrintWriter pw, ChannelGroup group) {
        pw.println("  Channels:");
        var channels = group.channels();
        for (int i = 0; i < channels.size(); i++) {
            var ch = channels.get(i);
            int color = ch.getColor();
            int r = ColorTools.red(color);
            int g = ColorTools.green(color);
            int b = ColorTools.blue(color);
            pw.println("    Channel " + i + ": " + ch.getName()
                    + " (color: RGB(" + r + ", " + g + ", " + b + "))");
        }
    }

    private static void writeBrightfieldInfo(PrintWriter pw, ChannelGroup group) {
        if (group.stains() == null) {
            pw.println("  Brightfield image (no stain vectors available)");
            return;
        }
        var stains = group.stains();
        for (int i = 1; i <= 3; i++) {
            var stain = stains.getStain(i);
            if (stain != null && !stain.isResidual()) {
                pw.println("  Stain " + i + ": " + stain.getName());
                pw.println("    Vector: ("
                        + String.format("%.4f", stain.getRed()) + ", "
                        + String.format("%.4f", stain.getGreen()) + ", "
                        + String.format("%.4f", stain.getBlue()) + ")");
            }
        }
    }

    private static void writeDisplaySettingsInfo(PrintWriter pw,
                                                  RenderedExportConfig config) {
        var mode = config.getDisplaySettingsMode();
        switch (mode) {
            case RAW -> pw.println("  Display Settings: None (raw pixel values)");
            case PER_IMAGE_SAVED -> pw.println("  Display Settings: Per-image (varies -- see QuPath project)");
            case CURRENT_VIEWER, SAVED_PRESET -> {
                ImageDisplaySettings settings = config.getCapturedDisplaySettings();
                if (settings == null) {
                    pw.println("  Display Settings: " + mode.name() + " (not captured)");
                    return;
                }
                pw.println("  Display Settings (applied uniformly):");
                var channels = settings.getChannels();
                if (channels != null) {
                    for (var ch : channels) {
                        pw.println("    " + ch.getName()
                                + ": min=" + formatDouble(ch.getMinDisplay())
                                + ", max=" + formatDouble(ch.getMaxDisplay()));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /**
     * Write pixel size information line.
     */
    private static void writePixelSizeInfo(PrintWriter pw,
                                            PixelCalibration calibration,
                                            double downsample) {
        if (calibration == null || !calibration.hasPixelSizeMicrons()) {
            pw.println("Pixel Size: Not calibrated");
            return;
        }
        double original = calibration.getAveragedPixelSizeMicrons();
        double effective = original * downsample;
        pw.println("Pixel Size: " + formatDouble(effective) + " um/px"
                + " (downsample " + formatDouble(downsample) + "x"
                + " from " + formatDouble(original) + " um/px)");
    }

    /**
     * Format a double, removing trailing zeros for clean display.
     */
    private static String formatDouble(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        String s = String.format("%.4f", value);
        // Strip trailing zeros after decimal point
        s = s.replaceAll("0+$", "");
        s = s.replaceAll("\\.$", "");
        return s;
    }
}
