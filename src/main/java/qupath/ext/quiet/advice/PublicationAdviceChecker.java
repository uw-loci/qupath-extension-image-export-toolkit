package qupath.ext.quiet.advice;

import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import qupath.ext.quiet.export.ExportCategory;
import qupath.ext.quiet.export.MaskExportConfig;
import qupath.ext.quiet.export.OutputFormat;
import qupath.ext.quiet.export.RenderedExportConfig;
import qupath.ext.quiet.export.TiledExportConfig;
import qupath.lib.common.ColorTools;

/**
 * Checks export configurations against QUAREP-LiMi publication guidelines
 * and returns advisory items.
 * <p>
 * This class is stateless -- all checks are pure functions of the inputs.
 * It never blocks an export; it only provides informational advice.
 *
 * @see <a href="https://doi.org/10.1038/s41592-023-01987-9">
 *      Schmied et al., 2023, Nature Methods</a>
 */
public class PublicationAdviceChecker {

    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.quiet.ui.strings");

    private PublicationAdviceChecker() {
        // Utility class
    }

    /**
     * Run all applicable checks for the given export category and configuration.
     *
     * @param category the export category
     * @param config   the category-specific config object (RenderedExportConfig,
     *                 MaskExportConfig, TiledExportConfig, etc.), or null
     * @param images   metadata for selected images (may be empty)
     * @return list of advice items (may be empty if no issues found)
     */
    public static List<AdviceItem> check(ExportCategory category, Object config,
                                         List<ImageContext> images) {
        var items = new ArrayList<AdviceItem>();

        if (category == ExportCategory.RENDERED && config instanceof RenderedExportConfig rc) {
            checkRendered(rc, images, items);
        } else if (category == ExportCategory.MASK && config instanceof MaskExportConfig mc) {
            checkMask(mc, items);
        } else if (category == ExportCategory.TILED && config instanceof TiledExportConfig tc) {
            checkTiled(tc, items);
        }

        // Cross-category checks
        checkCalibrationAcrossAll(images, items);

        return items;
    }

    // ---- Rendered checks ----

    private static void checkRendered(RenderedExportConfig config,
                                       List<ImageContext> images,
                                       List<AdviceItem> items) {
        boolean anyCalibrated = images.stream().anyMatch(ImageContext::hasPixelCalibration);
        boolean anyUncalibrated = images.stream().anyMatch(i -> !i.hasPixelCalibration());

        // Check 1: No scale bar when image has calibration
        if (!config.scaleBar().show() && anyCalibrated) {
            items.add(new AdviceItem(
                    AdviceSeverity.WARNING,
                    resources.getString("advice.scaleBar.title"),
                    resources.getString("advice.scaleBar.description"),
                    "IA-1",
                    resources.getString("advice.scaleBar.action")));
        }

        // Check 2: No pixel calibration (scale bar impossible)
        if (config.scaleBar().show() && anyUncalibrated) {
            items.add(new AdviceItem(
                    AdviceSeverity.INFO,
                    resources.getString("advice.noCalibration.title"),
                    resources.getString("advice.noCalibration.description"),
                    "IA-1",
                    resources.getString("advice.noCalibration.action")));
        }

        // Check 3 & 4: Scale bar color contrast
        if (config.scaleBar().show()) {
            checkScaleBarColor(config.scaleBar().colorHex(), images, items);
        }

        // Check 5: Display settings = RAW
        if (config.getDisplaySettingsMode() == RenderedExportConfig.DisplaySettingsMode.RAW) {
            items.add(new AdviceItem(
                    AdviceSeverity.WARNING,
                    resources.getString("advice.rawDisplay.title"),
                    resources.getString("advice.rawDisplay.description"),
                    "IC-2",
                    resources.getString("advice.rawDisplay.action")));
        }

        // Check 6: PER_IMAGE_SAVED with multiple images
        if (config.getDisplaySettingsMode()
                == RenderedExportConfig.DisplaySettingsMode.PER_IMAGE_SAVED
                && images.size() > 1) {
            items.add(new AdviceItem(
                    AdviceSeverity.INFO,
                    resources.getString("advice.perImageDisplay.title"),
                    resources.getString("advice.perImageDisplay.description"),
                    "IC-3",
                    resources.getString("advice.perImageDisplay.action")));
        }

        // Check 7: JPEG format
        if (config.getFormat() == OutputFormat.JPEG) {
            items.add(new AdviceItem(
                    AdviceSeverity.WARNING,
                    resources.getString("advice.jpegRendered.title"),
                    resources.getString("advice.jpegRendered.description"),
                    "ID-1",
                    resources.getString("advice.jpegRendered.action")));
        }

        // Check 8: Red-green channel combination
        checkRedGreenChannels(images, items);

        // Check 9: Multi-channel without split-channel export
        boolean anyMultiChannel = images.stream()
                .anyMatch(i -> i.isFluorescence() && i.nChannels() > 1);
        if (anyMultiChannel && !config.splitChannel().enabled()) {
            items.add(new AdviceItem(
                    AdviceSeverity.INFO,
                    resources.getString("advice.noSplitChannel.title"),
                    resources.getString("advice.noSplitChannel.description"),
                    "IC-5",
                    resources.getString("advice.noSplitChannel.action")));
        }

        // Check 10: Split channels in pseudocolor (not grayscale)
        if (config.splitChannel().enabled() && !config.splitChannel().grayscale()) {
            items.add(new AdviceItem(
                    AdviceSeverity.INFO,
                    resources.getString("advice.splitPseudocolor.title"),
                    resources.getString("advice.splitPseudocolor.description"),
                    "IC-4",
                    resources.getString("advice.splitPseudocolor.action")));
        }
    }

    // ---- Mask checks ----

    private static void checkMask(MaskExportConfig config, List<AdviceItem> items) {
        // Check 11: JPEG format for masks
        if (config.getFormat() == OutputFormat.JPEG) {
            items.add(new AdviceItem(
                    AdviceSeverity.ERROR,
                    resources.getString("advice.jpegMask.title"),
                    resources.getString("advice.jpegMask.description"),
                    null,
                    resources.getString("advice.jpegMask.action")));
        }
    }

    // ---- Tiled checks ----

    private static void checkTiled(TiledExportConfig config, List<AdviceItem> items) {
        // Check 12: No label masks for tiled ML export
        if (config.getLabeledServerConfig() == null) {
            items.add(new AdviceItem(
                    AdviceSeverity.INFO,
                    resources.getString("advice.noTiledLabels.title"),
                    resources.getString("advice.noTiledLabels.description"),
                    null,
                    resources.getString("advice.noTiledLabels.action")));
        }
    }

    // ---- Cross-category checks ----

    /** Check 13: No pixel calibration across any category. */
    private static void checkCalibrationAcrossAll(List<ImageContext> images,
                                                   List<AdviceItem> items) {
        if (!images.isEmpty() && images.stream().noneMatch(ImageContext::hasPixelCalibration)) {
            items.add(new AdviceItem(
                    AdviceSeverity.INFO,
                    resources.getString("advice.allUncalibrated.title"),
                    resources.getString("advice.allUncalibrated.description"),
                    "IA-1",
                    resources.getString("advice.allUncalibrated.action")));
        }
    }

    // ---- Helper methods ----

    /**
     * Check scale bar color visibility against likely image backgrounds.
     * Brightfield images have light backgrounds; fluorescence images have dark backgrounds.
     */
    private static void checkScaleBarColor(String colorHex, List<ImageContext> images,
                                            List<AdviceItem> items) {
        double luminance = hexLuminance(colorHex);
        boolean isLight = luminance > 180;
        boolean isDark = luminance <= 180;

        boolean anyBrightfield = images.stream().anyMatch(ImageContext::isBrightfield);
        boolean anyFluorescence = images.stream().anyMatch(ImageContext::isFluorescence);

        // Check 3: Light scale bar color on brightfield (white background)
        if (isLight && anyBrightfield) {
            items.add(new AdviceItem(
                    AdviceSeverity.WARNING,
                    resources.getString("advice.scaleBarLightOnBF.title"),
                    resources.getString("advice.scaleBarLightOnBF.description"),
                    "IA-1",
                    resources.getString("advice.scaleBarLightOnBF.action")));
        }

        // Check 4: Dark scale bar color on fluorescence (black background)
        if (isDark && anyFluorescence) {
            items.add(new AdviceItem(
                    AdviceSeverity.WARNING,
                    resources.getString("advice.scaleBarDarkOnFL.title"),
                    resources.getString("advice.scaleBarDarkOnFL.description"),
                    "IA-1",
                    resources.getString("advice.scaleBarDarkOnFL.action")));
        }
    }

    /**
     * Check for red-green channel combinations that are not colorblind-accessible.
     * Only applies to fluorescence images with 2+ channels.
     */
    private static void checkRedGreenChannels(List<ImageContext> images,
                                               List<AdviceItem> items) {
        for (var img : images) {
            if (!img.isFluorescence() || img.nChannels() < 2) continue;
            if (img.channelColors() == null) continue;

            boolean hasRed = false;
            boolean hasGreen = false;

            for (int color : img.channelColors()) {
                int r = ColorTools.red(color);
                int g = ColorTools.green(color);
                int b = ColorTools.blue(color);

                if (r > 180 && g < 80 && b < 80) hasRed = true;
                if (r < 80 && g > 180 && b < 80) hasGreen = true;
            }

            if (hasRed && hasGreen) {
                items.add(new AdviceItem(
                        AdviceSeverity.WARNING,
                        resources.getString("advice.redGreen.title"),
                        resources.getString("advice.redGreen.description"),
                        "IC-6",
                        resources.getString("advice.redGreen.action")));
                return; // Only warn once
            }
        }
    }

    /**
     * Compute ITU-R BT.709 luminance from a hex color string.
     *
     * @param hex color in "#RRGGBB" format
     * @return luminance in 0-255 range, or 128 if parsing fails
     */
    static double hexLuminance(String hex) {
        if (hex == null || hex.length() < 7) return 128;
        try {
            java.awt.Color c = java.awt.Color.decode(hex);
            return 0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue();
        } catch (NumberFormatException e) {
            return 128;
        }
    }
}
