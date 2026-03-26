package qupath.ext.quiet.export;

import java.awt.Color;
import java.io.File;
import java.util.List;

import qupath.lib.common.GeneralTools;
import qupath.lib.display.settings.ImageDisplaySettings;

/**
 * Immutable configuration for a rendered image export operation.
 * <p>
 * Fields are organized into sub-config records grouped by feature domain.
 * The {@link Builder} retains individual field setters for UI code and adds
 * wholesale sub-config setters for efficient config copying.
 */
public class RenderedExportConfig {

    /**
     * Which region(s) of the image to export.
     */
    public enum RegionType {
        /** Export the entire image. */
        WHOLE_IMAGE,
        /** Export each annotation as a separate cropped image. */
        ALL_ANNOTATIONS
    }

    /**
     * The render mode for the exported image.
     */
    public enum RenderMode {
        /** Export with a pixel classifier overlay rendered on top. */
        CLASSIFIER_OVERLAY,
        /** Export with object overlays (annotations/detections) only. */
        OBJECT_OVERLAY,
        /** Export with a density map overlay colorized by a LUT. */
        DENSITY_MAP_OVERLAY
    }

    /**
     * How display settings (brightness/contrast, channel visibility, LUTs)
     * are applied to the base image before overlay compositing.
     */
    public enum DisplaySettingsMode {
        /** Each image uses its own saved display settings from ImageData properties. */
        PER_IMAGE_SAVED,
        /** Capture the current viewer's display settings and apply to all images. */
        CURRENT_VIEWER,
        /** Load a named preset from the project's saved display settings. */
        SAVED_PRESET,
        /** Compute global min/max across all selected images per channel (batch). */
        GLOBAL_MATCHED,
        /** No display adjustments -- export raw pixel data (original behavior). */
        RAW
    }

    // -----------------------------------------------------------------------
    //  Sub-config records
    // -----------------------------------------------------------------------

    /**
     * Scale bar overlay configuration.
     */
    public record ScaleBarConfig(
            boolean show,
            ScaleBarRenderer.Position position,
            String colorHex,
            int fontSize,
            boolean bold,
            boolean backgroundBox
    ) {
        /** Converts the hex color string to an AWT Color, falling back to white. */
        public Color colorAsAwt() {
            try {
                return Color.decode(colorHex);
            } catch (NumberFormatException e) {
                return Color.WHITE;
            }
        }
    }

    /**
     * Color scale bar (density map legend) configuration.
     */
    public record ColorScaleBarConfig(
            boolean show,
            ScaleBarRenderer.Position position,
            int fontSize,
            boolean bold
    ) {}

    /**
     * Reusable text label configuration, used for both panel labels
     * and info labels.
     */
    public record TextLabelConfig(
            boolean show,
            String text,
            ScaleBarRenderer.Position position,
            int fontSize,
            boolean bold
    ) {}

    /**
     * Object overlay configuration (classifiers, density maps, annotations/detections).
     */
    public record ObjectOverlayConfig(
            String classifierName,
            String densityMapName,
            String colormapName,
            boolean includeAnnotations,
            boolean includeDetections,
            boolean fillAnnotations,
            boolean showNames
    ) {}

    /**
     * Split-channel export configuration.
     */
    public record SplitChannelConfig(
            boolean enabled,
            boolean grayscale,
            boolean colorBorder,
            boolean colorLegend
    ) {}

    /**
     * Inset/zoom panel configuration.
     */
    public record InsetConfig(
            boolean show,
            double sourceX,
            double sourceY,
            double sourceW,
            double sourceH,
            int magnification,
            ScaleBarRenderer.Position position,
            String frameColorHex,
            int frameWidth,
            boolean connectingLines
    ) {
        /** Converts the frame hex color to an AWT Color, falling back to yellow. */
        public Color frameColorAsAwt() {
            try {
                return Color.decode(frameColorHex);
            } catch (NumberFormatException e) {
                return Color.YELLOW;
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Core fields (14)
    // -----------------------------------------------------------------------

    private final RegionType regionType;
    private final List<String> selectedClassifications;
    private final int paddingPixels;
    private final RenderMode renderMode;
    private final DisplaySettingsMode displaySettingsMode;
    private final ImageDisplaySettings capturedDisplaySettings;
    private final String displayPresetName;
    private final double overlayOpacity;
    private final double downsample;
    private final int targetDpi;
    private final OutputFormat format;
    private final File outputDirectory;
    private final boolean addToWorkflow;
    private final double matchedDisplayPercentile;
    private final boolean showChannelLegend;

    // -----------------------------------------------------------------------
    //  Sub-config record instances
    // -----------------------------------------------------------------------

    private final ObjectOverlayConfig overlays;
    private final ScaleBarConfig scaleBar;
    private final ColorScaleBarConfig colorScaleBar;
    private final TextLabelConfig panelLabel;
    private final TextLabelConfig infoLabel;
    private final SplitChannelConfig splitChannel;
    private final InsetConfig inset;

    // -----------------------------------------------------------------------
    //  Constructor (from Builder)
    // -----------------------------------------------------------------------

    private RenderedExportConfig(Builder builder) {
        // Core fields
        this.regionType = builder.regionType;
        this.selectedClassifications = builder.selectedClassifications == null
                ? null : List.copyOf(builder.selectedClassifications);
        this.paddingPixels = builder.paddingPixels;
        this.renderMode = builder.renderMode;
        this.displaySettingsMode = builder.displaySettingsMode;
        this.capturedDisplaySettings = builder.capturedDisplaySettings;
        this.displayPresetName = builder.displayPresetName;
        this.overlayOpacity = builder.overlayOpacity;
        this.downsample = builder.downsample;
        this.targetDpi = builder.targetDpi;
        this.format = builder.format;
        this.outputDirectory = builder.outputDirectory;
        this.addToWorkflow = builder.addToWorkflow;
        this.matchedDisplayPercentile = builder.matchedDisplayPercentile;
        this.showChannelLegend = builder.showChannelLegend;

        // Sub-configs assembled from individual builder fields
        this.overlays = new ObjectOverlayConfig(
                builder.classifierName,
                builder.densityMapName,
                builder.colormapName,
                builder.includeAnnotations,
                builder.includeDetections,
                builder.fillAnnotations,
                builder.showNames);
        this.scaleBar = new ScaleBarConfig(
                builder.showScaleBar,
                builder.scaleBarPosition,
                builder.scaleBarColorHex,
                builder.scaleBarFontSize,
                builder.scaleBarBoldText,
                builder.scaleBarBackgroundBox);
        this.colorScaleBar = new ColorScaleBarConfig(
                builder.showColorScaleBar,
                builder.colorScaleBarPosition,
                builder.colorScaleBarFontSize,
                builder.colorScaleBarBoldText);
        this.panelLabel = new TextLabelConfig(
                builder.showPanelLabel,
                builder.panelLabelText,
                builder.panelLabelPosition,
                builder.panelLabelFontSize,
                builder.panelLabelBold);
        this.infoLabel = new TextLabelConfig(
                builder.showInfoLabel,
                builder.infoLabelTemplate,
                builder.infoLabelPosition,
                builder.infoLabelFontSize,
                builder.infoLabelBold);
        this.splitChannel = new SplitChannelConfig(
                builder.splitChannels,
                builder.splitChannelsGrayscale,
                builder.splitChannelColorBorder,
                builder.channelColorLegend);
        this.inset = new InsetConfig(
                builder.showInset,
                builder.insetSourceX,
                builder.insetSourceY,
                builder.insetSourceW,
                builder.insetSourceH,
                builder.insetMagnification,
                builder.insetPosition,
                builder.insetFrameColorHex,
                builder.insetFrameWidth,
                builder.insetConnectingLines);
    }

    // -----------------------------------------------------------------------
    //  Core field getters
    // -----------------------------------------------------------------------

    public RegionType getRegionType() {
        return regionType;
    }

    /**
     * Returns the classification names to filter annotations by.
     * Null means export all annotations regardless of classification.
     */
    public List<String> getSelectedClassifications() {
        return selectedClassifications;
    }

    /**
     * Padding in pixels to add around each annotation's bounding box.
     * Only applies when region type is ALL_ANNOTATIONS.
     */
    public int getPaddingPixels() {
        return paddingPixels;
    }

    public RenderMode getRenderMode() {
        return renderMode;
    }

    public DisplaySettingsMode getDisplaySettingsMode() {
        return displaySettingsMode;
    }

    /**
     * Returns the captured display settings for CURRENT_VIEWER mode.
     * Null for other modes.
     */
    public ImageDisplaySettings getCapturedDisplaySettings() {
        return capturedDisplaySettings;
    }

    /**
     * Returns the preset name for SAVED_PRESET mode.
     * Null for other modes.
     */
    public String getDisplayPresetName() {
        return displayPresetName;
    }

    public double getOverlayOpacity() {
        return overlayOpacity;
    }

    public double getDownsample() {
        return downsample;
    }

    /**
     * Returns the target DPI for resolution control.
     * 0 means disabled (use downsample directly).
     */
    public int getTargetDpi() {
        return targetDpi;
    }

    public OutputFormat getFormat() {
        return format;
    }

    public File getOutputDirectory() {
        return outputDirectory;
    }

    public boolean isAddToWorkflow() {
        return addToWorkflow;
    }

    /**
     * Saturation percentile for GLOBAL_MATCHED mode (0.0-5.0).
     * Clips this percentage of extreme pixel values at each end.
     */
    public double getMatchedDisplayPercentile() {
        return matchedDisplayPercentile;
    }

    /**
     * Whether to draw a channel/stain legend on the exported image.
     * For fluorescence images, shows channel names and colors.
     * For brightfield images, shows stain names and colors from color deconvolution.
     */
    public boolean isShowChannelLegend() {
        return showChannelLegend;
    }

    // -----------------------------------------------------------------------
    //  Sub-config accessors
    // -----------------------------------------------------------------------

    public ObjectOverlayConfig overlays() {
        return overlays;
    }

    public ScaleBarConfig scaleBar() {
        return scaleBar;
    }

    public ColorScaleBarConfig colorScaleBar() {
        return colorScaleBar;
    }

    public TextLabelConfig panelLabel() {
        return panelLabel;
    }

    public TextLabelConfig infoLabel() {
        return infoLabel;
    }

    public SplitChannelConfig splitChannel() {
        return splitChannel;
    }

    public InsetConfig inset() {
        return inset;
    }

    // -----------------------------------------------------------------------
    //  Computed helpers
    // -----------------------------------------------------------------------

    /**
     * Compute the effective downsample factor from target DPI and pixel size.
     * Falls back to the configured downsample if DPI is disabled or pixel size
     * is unavailable.
     *
     * @param pixelSizeMicrons the averaged pixel size in microns
     * @return the effective downsample factor
     */
    public double computeEffectiveDownsample(double pixelSizeMicrons) {
        if (targetDpi <= 0 || pixelSizeMicrons <= 0 || Double.isNaN(pixelSizeMicrons))
            return downsample;
        double targetPixelSize = 25400.0 / targetDpi; // microns per inch
        return Math.max(1.0, targetPixelSize / pixelSizeMicrons);
    }

    // -----------------------------------------------------------------------
    //  Filename utilities
    // -----------------------------------------------------------------------

    /**
     * Generates a sanitized output filename for a given image entry name.
     *
     * @param entryName the project image entry name
     * @return sanitized filename with appropriate extension
     */
    public String buildOutputFilename(String entryName) {
        String sanitized = GeneralTools.stripInvalidFilenameChars(entryName);
        if (sanitized == null || sanitized.isBlank()) {
            sanitized = "unnamed";
        }
        return sanitized + "." + format.getExtension();
    }

    /**
     * Generates a sanitized output filename with a suffix (e.g., for annotation regions).
     *
     * @param entryName the project image entry name
     * @param suffix    additional suffix (e.g., "_Tumor_0")
     * @return sanitized filename with suffix and appropriate extension
     */
    public String buildOutputFilename(String entryName, String suffix) {
        String sanitized = GeneralTools.stripInvalidFilenameChars(entryName);
        if (sanitized == null || sanitized.isBlank()) {
            sanitized = "unnamed";
        }
        return sanitized + suffix + "." + format.getExtension();
    }

    /**
     * Generates a filename for a single split-channel panel.
     *
     * @param entryName    the project image entry name
     * @param channelIndex zero-based channel index
     * @param channelName  the channel display name
     * @return filename like "imageName_Ch1_DAPI.png"
     */
    public String buildSplitChannelFilename(String entryName, int channelIndex, String channelName) {
        String sanitized = GeneralTools.stripInvalidFilenameChars(entryName);
        if (sanitized == null || sanitized.isBlank()) sanitized = "unnamed";
        String safeCh = GeneralTools.stripInvalidFilenameChars(channelName);
        if (safeCh == null || safeCh.isBlank()) safeCh = "ch" + channelIndex;
        return sanitized + "_Ch" + (channelIndex + 1) + "_" + safeCh + "." + format.getExtension();
    }

    /**
     * Generates a filename for the merged composite in split-channel export.
     *
     * @param entryName the project image entry name
     * @return filename like "imageName_merge.png"
     */
    public String buildMergeFilename(String entryName) {
        String sanitized = GeneralTools.stripInvalidFilenameChars(entryName);
        if (sanitized == null || sanitized.isBlank()) sanitized = "unnamed";
        return sanitized + "_merge." + format.getExtension();
    }

    // -----------------------------------------------------------------------
    //  Builder
    // -----------------------------------------------------------------------

    /**
     * Builder for creating {@link RenderedExportConfig} instances.
     * <p>
     * Provides both individual field setters (for UI controls that set one
     * field at a time) and wholesale sub-config setters (for efficient
     * config copying in buildPreviewConfig / rebuildWithMatchedSettings).
     */
    public static class Builder {

        // -- Core fields --
        private RegionType regionType = RegionType.WHOLE_IMAGE;
        private List<String> selectedClassifications = null;
        private int paddingPixels = 0;
        private RenderMode renderMode = RenderMode.CLASSIFIER_OVERLAY;
        private DisplaySettingsMode displaySettingsMode = DisplaySettingsMode.PER_IMAGE_SAVED;
        private ImageDisplaySettings capturedDisplaySettings;
        private String displayPresetName;
        private double overlayOpacity = 0.5;
        private double downsample = 4.0;
        private int targetDpi = 0;
        private OutputFormat format = OutputFormat.PNG;
        private File outputDirectory;
        private boolean addToWorkflow = true;
        private double matchedDisplayPercentile = 0.1;
        private boolean showChannelLegend = false;

        // -- Object overlay fields --
        private String classifierName;
        private String densityMapName;
        private String colormapName = "Viridis";
        private boolean includeAnnotations = false;
        private boolean includeDetections = true;
        private boolean fillAnnotations = false;
        private boolean showNames = false;

        // -- Scale bar fields --
        private boolean showScaleBar = false;
        private ScaleBarRenderer.Position scaleBarPosition = ScaleBarRenderer.Position.LOWER_RIGHT;
        private String scaleBarColorHex = "#FFFFFF";
        private int scaleBarFontSize = 0;
        private boolean scaleBarBoldText = true;
        private boolean scaleBarBackgroundBox = false;

        // -- Color scale bar fields --
        private boolean showColorScaleBar = false;
        private ScaleBarRenderer.Position colorScaleBarPosition = ScaleBarRenderer.Position.LOWER_RIGHT;
        private int colorScaleBarFontSize = 0;
        private boolean colorScaleBarBoldText = true;

        // -- Panel label fields --
        private boolean showPanelLabel = false;
        private String panelLabelText = null;
        private ScaleBarRenderer.Position panelLabelPosition = ScaleBarRenderer.Position.UPPER_LEFT;
        private int panelLabelFontSize = 0;
        private boolean panelLabelBold = true;

        // -- Info label fields --
        private boolean showInfoLabel = false;
        private String infoLabelTemplate = null;
        private ScaleBarRenderer.Position infoLabelPosition = ScaleBarRenderer.Position.LOWER_LEFT;
        private int infoLabelFontSize = 0;
        private boolean infoLabelBold = false;

        // -- Split channel fields --
        private boolean splitChannels = false;
        private boolean splitChannelsGrayscale = true;
        private boolean splitChannelColorBorder = false;
        private boolean channelColorLegend = true;

        // -- Inset fields --
        private boolean showInset = false;
        private double insetSourceX = 0.4;
        private double insetSourceY = 0.4;
        private double insetSourceW = 0.15;
        private double insetSourceH = 0.15;
        private int insetMagnification = 4;
        private ScaleBarRenderer.Position insetPosition = ScaleBarRenderer.Position.UPPER_RIGHT;
        private String insetFrameColorHex = "#FFFF00";
        private int insetFrameWidth = 0;
        private boolean insetConnectingLines = true;

        // =============================================================
        //  Core field setters
        // =============================================================

        public Builder regionType(RegionType type) {
            this.regionType = type;
            return this;
        }

        public Builder selectedClassifications(List<String> classifications) {
            this.selectedClassifications = classifications;
            return this;
        }

        public Builder paddingPixels(int padding) {
            this.paddingPixels = padding;
            return this;
        }

        public Builder renderMode(RenderMode mode) {
            this.renderMode = mode;
            return this;
        }

        public Builder displaySettingsMode(DisplaySettingsMode mode) {
            this.displaySettingsMode = mode;
            return this;
        }

        public Builder capturedDisplaySettings(ImageDisplaySettings settings) {
            this.capturedDisplaySettings = settings;
            return this;
        }

        public Builder displayPresetName(String name) {
            this.displayPresetName = name;
            return this;
        }

        public Builder overlayOpacity(double opacity) {
            this.overlayOpacity = GeneralTools.clipValue(opacity, 0.0, 1.0);
            return this;
        }

        public Builder downsample(double ds) {
            this.downsample = ds;
            return this;
        }

        public Builder targetDpi(int dpi) {
            this.targetDpi = dpi;
            return this;
        }

        public Builder format(OutputFormat fmt) {
            this.format = fmt;
            return this;
        }

        public Builder outputDirectory(File dir) {
            this.outputDirectory = dir;
            return this;
        }

        public Builder addToWorkflow(boolean add) {
            this.addToWorkflow = add;
            return this;
        }

        public Builder matchedDisplayPercentile(double percentile) {
            this.matchedDisplayPercentile = GeneralTools.clipValue(percentile, 0.0, 5.0);
            return this;
        }

        public Builder showChannelLegend(boolean show) {
            this.showChannelLegend = show;
            return this;
        }

        // =============================================================
        //  Object overlay -- individual setters
        // =============================================================

        public Builder classifierName(String name) {
            this.classifierName = name;
            return this;
        }

        public Builder densityMapName(String name) {
            this.densityMapName = name;
            return this;
        }

        public Builder colormapName(String name) {
            this.colormapName = name != null ? name : "Viridis";
            return this;
        }

        public Builder includeAnnotations(boolean include) {
            this.includeAnnotations = include;
            return this;
        }

        public Builder includeDetections(boolean include) {
            this.includeDetections = include;
            return this;
        }

        public Builder fillAnnotations(boolean fill) {
            this.fillAnnotations = fill;
            return this;
        }

        public Builder showNames(boolean show) {
            this.showNames = show;
            return this;
        }

        /** Sets all object overlay fields from a sub-config record. */
        public Builder overlays(ObjectOverlayConfig cfg) {
            this.classifierName = cfg.classifierName();
            this.densityMapName = cfg.densityMapName();
            this.colormapName = cfg.colormapName();
            this.includeAnnotations = cfg.includeAnnotations();
            this.includeDetections = cfg.includeDetections();
            this.fillAnnotations = cfg.fillAnnotations();
            this.showNames = cfg.showNames();
            return this;
        }

        // =============================================================
        //  Scale bar -- individual setters
        // =============================================================

        public Builder showScaleBar(boolean show) {
            this.showScaleBar = show;
            return this;
        }

        public Builder scaleBarPosition(ScaleBarRenderer.Position position) {
            this.scaleBarPosition = position;
            return this;
        }

        public Builder scaleBarColorHex(String hex) {
            this.scaleBarColorHex = hex != null ? hex : "#FFFFFF";
            return this;
        }

        public Builder scaleBarFontSize(int size) {
            this.scaleBarFontSize = size;
            return this;
        }

        public Builder scaleBarBoldText(boolean bold) {
            this.scaleBarBoldText = bold;
            return this;
        }

        public Builder scaleBarBackgroundBox(boolean backgroundBox) {
            this.scaleBarBackgroundBox = backgroundBox;
            return this;
        }

        /** Sets all scale bar fields from a sub-config record. */
        public Builder scaleBar(ScaleBarConfig cfg) {
            this.showScaleBar = cfg.show();
            this.scaleBarPosition = cfg.position();
            this.scaleBarColorHex = cfg.colorHex();
            this.scaleBarFontSize = cfg.fontSize();
            this.scaleBarBoldText = cfg.bold();
            this.scaleBarBackgroundBox = cfg.backgroundBox();
            return this;
        }

        // =============================================================
        //  Color scale bar -- individual setters
        // =============================================================

        public Builder showColorScaleBar(boolean show) {
            this.showColorScaleBar = show;
            return this;
        }

        public Builder colorScaleBarPosition(ScaleBarRenderer.Position position) {
            this.colorScaleBarPosition = position;
            return this;
        }

        public Builder colorScaleBarFontSize(int size) {
            this.colorScaleBarFontSize = size;
            return this;
        }

        public Builder colorScaleBarBoldText(boolean bold) {
            this.colorScaleBarBoldText = bold;
            return this;
        }

        /** Sets all color scale bar fields from a sub-config record. */
        public Builder colorScaleBar(ColorScaleBarConfig cfg) {
            this.showColorScaleBar = cfg.show();
            this.colorScaleBarPosition = cfg.position();
            this.colorScaleBarFontSize = cfg.fontSize();
            this.colorScaleBarBoldText = cfg.bold();
            return this;
        }

        // =============================================================
        //  Panel label -- individual setters
        // =============================================================

        public Builder showPanelLabel(boolean show) {
            this.showPanelLabel = show;
            return this;
        }

        public Builder panelLabelText(String text) {
            this.panelLabelText = text;
            return this;
        }

        public Builder panelLabelPosition(ScaleBarRenderer.Position position) {
            this.panelLabelPosition = position;
            return this;
        }

        public Builder panelLabelFontSize(int size) {
            this.panelLabelFontSize = size;
            return this;
        }

        public Builder panelLabelBold(boolean bold) {
            this.panelLabelBold = bold;
            return this;
        }

        /** Sets all panel label fields from a sub-config record. */
        public Builder panelLabel(TextLabelConfig cfg) {
            this.showPanelLabel = cfg.show();
            this.panelLabelText = cfg.text();
            this.panelLabelPosition = cfg.position();
            this.panelLabelFontSize = cfg.fontSize();
            this.panelLabelBold = cfg.bold();
            return this;
        }

        // =============================================================
        //  Info label -- individual setters
        // =============================================================

        public Builder showInfoLabel(boolean show) {
            this.showInfoLabel = show;
            return this;
        }

        public Builder infoLabelTemplate(String template) {
            this.infoLabelTemplate = template;
            return this;
        }

        public Builder infoLabelPosition(ScaleBarRenderer.Position position) {
            this.infoLabelPosition = position;
            return this;
        }

        public Builder infoLabelFontSize(int size) {
            this.infoLabelFontSize = size;
            return this;
        }

        public Builder infoLabelBold(boolean bold) {
            this.infoLabelBold = bold;
            return this;
        }

        /** Sets all info label fields from a sub-config record. */
        public Builder infoLabel(TextLabelConfig cfg) {
            this.showInfoLabel = cfg.show();
            this.infoLabelTemplate = cfg.text();
            this.infoLabelPosition = cfg.position();
            this.infoLabelFontSize = cfg.fontSize();
            this.infoLabelBold = cfg.bold();
            return this;
        }

        // =============================================================
        //  Split channel -- individual setters
        // =============================================================

        public Builder splitChannels(boolean split) {
            this.splitChannels = split;
            return this;
        }

        public Builder splitChannelsGrayscale(boolean grayscale) {
            this.splitChannelsGrayscale = grayscale;
            return this;
        }

        public Builder splitChannelColorBorder(boolean border) {
            this.splitChannelColorBorder = border;
            return this;
        }

        public Builder channelColorLegend(boolean legend) {
            this.channelColorLegend = legend;
            return this;
        }

        /** Sets all split channel fields from a sub-config record. */
        public Builder splitChannel(SplitChannelConfig cfg) {
            this.splitChannels = cfg.enabled();
            this.splitChannelsGrayscale = cfg.grayscale();
            this.splitChannelColorBorder = cfg.colorBorder();
            this.channelColorLegend = cfg.colorLegend();
            return this;
        }

        // =============================================================
        //  Inset -- individual setters
        // =============================================================

        public Builder showInset(boolean show) {
            this.showInset = show;
            return this;
        }

        public Builder insetSourceX(double x) {
            this.insetSourceX = GeneralTools.clipValue(x, 0.0, 1.0);
            return this;
        }

        public Builder insetSourceY(double y) {
            this.insetSourceY = GeneralTools.clipValue(y, 0.0, 1.0);
            return this;
        }

        public Builder insetSourceW(double w) {
            this.insetSourceW = GeneralTools.clipValue(w, 0.01, 1.0);
            return this;
        }

        public Builder insetSourceH(double h) {
            this.insetSourceH = GeneralTools.clipValue(h, 0.01, 1.0);
            return this;
        }

        public Builder insetMagnification(int mag) {
            this.insetMagnification = (int) GeneralTools.clipValue(mag, 2, 16);
            return this;
        }

        public Builder insetPosition(ScaleBarRenderer.Position position) {
            this.insetPosition = position;
            return this;
        }

        public Builder insetFrameColorHex(String hex) {
            this.insetFrameColorHex = hex != null ? hex : "#FFFF00";
            return this;
        }

        public Builder insetFrameWidth(int width) {
            this.insetFrameWidth = width;
            return this;
        }

        public Builder insetConnectingLines(boolean lines) {
            this.insetConnectingLines = lines;
            return this;
        }

        /** Sets all inset fields from a sub-config record. */
        public Builder inset(InsetConfig cfg) {
            this.showInset = cfg.show();
            this.insetSourceX = cfg.sourceX();
            this.insetSourceY = cfg.sourceY();
            this.insetSourceW = cfg.sourceW();
            this.insetSourceH = cfg.sourceH();
            this.insetMagnification = cfg.magnification();
            this.insetPosition = cfg.position();
            this.insetFrameColorHex = cfg.frameColorHex();
            this.insetFrameWidth = cfg.frameWidth();
            this.insetConnectingLines = cfg.connectingLines();
            return this;
        }

        // =============================================================
        //  Build
        // =============================================================

        /**
         * Build the export configuration, validating required fields.
         *
         * @return a new RenderedExportConfig
         * @throws IllegalArgumentException if required fields are missing or invalid
         */
        public RenderedExportConfig build() {
            if (renderMode == RenderMode.CLASSIFIER_OVERLAY) {
                if (classifierName == null || classifierName.isBlank()) {
                    throw new IllegalArgumentException("Classifier name is required for classifier overlay mode");
                }
            } else if (renderMode == RenderMode.OBJECT_OVERLAY) {
                if (!includeAnnotations && !includeDetections) {
                    throw new IllegalArgumentException(
                            "At least one object type (annotations or detections) must be selected");
                }
            } else if (renderMode == RenderMode.DENSITY_MAP_OVERLAY) {
                if (densityMapName == null || densityMapName.isBlank()) {
                    throw new IllegalArgumentException(
                            "Density map name is required for density map overlay mode");
                }
            }
            if (outputDirectory == null) {
                throw new IllegalArgumentException("Output directory is required");
            }
            if (downsample < 1.0) {
                throw new IllegalArgumentException("Downsample must be >= 1.0");
            }
            if (format == null) {
                throw new IllegalArgumentException("Output format is required");
            }
            return new RenderedExportConfig(this);
        }
    }
}
