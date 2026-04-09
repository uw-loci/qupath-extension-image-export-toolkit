package qupath.ext.quiet.preferences;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Persistent preferences for the QuIET extension.
 * <p>
 * All settings are automatically persisted across QuPath sessions using
 * QuPath's preference system. Values are restored when the extension loads.
 */
public class QuietPreferences {

    private static final String PREFIX = "quiet.";

    // --- Global preferences ---

    private static final StringProperty lastCategory =
            PathPrefs.createPersistentPreference(PREFIX + "lastCategory", "RENDERED");

    private static final BooleanProperty addToWorkflow =
            PathPrefs.createPersistentPreference(PREFIX + "addToWorkflow", true);

    private static final BooleanProperty exportGeoJson =
            PathPrefs.createPersistentPreference(PREFIX + "exportGeoJson", false);

    private static final DoubleProperty wizardWidth =
            PathPrefs.createPersistentPreference(PREFIX + "wizardWidth", 750.0);

    private static final DoubleProperty wizardHeight =
            PathPrefs.createPersistentPreference(PREFIX + "wizardHeight", 700.0);

    private static final StringProperty filenamePrefix =
            PathPrefs.createPersistentPreference(PREFIX + "filenamePrefix", "");

    private static final StringProperty filenameSuffix =
            PathPrefs.createPersistentPreference(PREFIX + "filenameSuffix", "");

    // --- Rendered export preferences ---

    private static final StringProperty renderedMode =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.mode", "CLASSIFIER_OVERLAY");

    private static final StringProperty renderedClassifierName =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.classifierName", "");

    private static final DoubleProperty renderedOpacity =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.opacity", 0.5);

    private static final DoubleProperty renderedDownsample =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.downsample", 4.0);

    private static final StringProperty renderedFormat =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.format", "PNG");

    private static final BooleanProperty renderedIncludeAnnotations =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.includeAnnotations", false);

    private static final BooleanProperty renderedIncludeDetections =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.includeDetections", true);

    private static final BooleanProperty renderedFillAnnotations =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.fillAnnotations", false);

    private static final BooleanProperty renderedShowNames =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.showNames", false);

    private static final StringProperty renderedDisplayMode =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.displayMode", "PER_IMAGE_SAVED");

    private static final StringProperty renderedDisplayPresetName =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.displayPresetName", "");

    private static final BooleanProperty renderedShowScaleBar =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.showScaleBar", false);

    private static final StringProperty renderedScaleBarPosition =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.scaleBarPosition", "LOWER_RIGHT");

    private static final StringProperty renderedScaleBarColor =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.scaleBarColor", "#FFFFFF");

    private static final IntegerProperty renderedScaleBarFontSize =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.scaleBarFontSize", 0);

    private static final BooleanProperty renderedScaleBarBold =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.scaleBarBold", true);

    private static final BooleanProperty renderedScaleBarBackgroundBox =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.scaleBarBackgroundBox", false);

    private static final BooleanProperty renderedShowChannelLegend =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.showChannelLegend", false);

    // --- Rendered region type preferences ---

    private static final StringProperty renderedRegionType =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.regionType", "WHOLE_IMAGE");

    private static final IntegerProperty renderedPadding =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.padding", 0);

    // --- Rendered density map preferences ---

    private static final StringProperty renderedDensityMapName =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.densityMapName", "");

    private static final StringProperty renderedColormapName =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.colormapName", "Viridis");

    private static final BooleanProperty renderedShowColorScaleBar =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.showColorScaleBar", false);

    private static final StringProperty renderedColorScaleBarPosition =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.colorScaleBarPosition", "LOWER_RIGHT");

    private static final IntegerProperty renderedColorScaleBarFontSize =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.colorScaleBarFontSize", 0);

    private static final BooleanProperty renderedColorScaleBarBold =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.colorScaleBarBold", true);

    // --- Rendered panel label preferences ---

    private static final BooleanProperty renderedShowPanelLabel =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.showPanelLabel", false);

    private static final StringProperty renderedPanelLabelText =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.panelLabelText", "");

    private static final StringProperty renderedPanelLabelPosition =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.panelLabelPosition", "UPPER_LEFT");

    private static final IntegerProperty renderedPanelLabelFontSize =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.panelLabelFontSize", 0);

    private static final BooleanProperty renderedPanelLabelBold =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.panelLabelBold", true);

    // --- Rendered split-channel preferences ---

    private static final BooleanProperty renderedSplitChannels =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.splitChannels", false);

    private static final BooleanProperty renderedSplitChannelsGrayscale =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.splitChannelsGrayscale", true);

    private static final BooleanProperty renderedSplitChannelColorBorder =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.splitChannelColorBorder", false);

    private static final BooleanProperty renderedChannelColorLegend =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.channelColorLegend", true);

    private static final DoubleProperty renderedMatchedDisplayPercentile =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.matchedDisplayPercentile", 0.1);

    // --- Rendered info label preferences ---

    private static final BooleanProperty renderedShowInfoLabel =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.showInfoLabel", false);

    private static final StringProperty renderedInfoLabelTemplate =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.infoLabelTemplate", "");

    private static final StringProperty renderedInfoLabelPosition =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.infoLabelPosition", "LOWER_LEFT");

    private static final IntegerProperty renderedInfoLabelFontSize =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.infoLabelFontSize", 0);

    private static final BooleanProperty renderedInfoLabelBold =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.infoLabelBold", false);

    // --- Rendered DPI control preferences ---

    private static final IntegerProperty renderedTargetDpi =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.targetDpi", 0);

    private static final StringProperty renderedResolutionMode =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.resolutionMode", "DOWNSAMPLE");

    // --- Rendered inset/zoom preferences ---

    private static final BooleanProperty renderedShowInset =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.showInset", false);

    private static final DoubleProperty renderedInsetSourceX =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.insetSourceX", 0.4);

    private static final DoubleProperty renderedInsetSourceY =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.insetSourceY", 0.4);

    private static final DoubleProperty renderedInsetSourceW =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.insetSourceW", 0.15);

    private static final DoubleProperty renderedInsetSourceH =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.insetSourceH", 0.15);

    private static final IntegerProperty renderedInsetMagnification =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.insetMagnification", 4);

    private static final StringProperty renderedInsetPosition =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.insetPosition", "UPPER_RIGHT");

    private static final StringProperty renderedInsetFrameColor =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.insetFrameColor", "#FFFF00");

    private static final IntegerProperty renderedInsetFrameWidth =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.insetFrameWidth", 0);

    private static final BooleanProperty renderedInsetConnectingLines =
            PathPrefs.createPersistentPreference(PREFIX + "rendered.insetConnectingLines", true);

    // --- Mask export preferences ---

    private static final StringProperty maskType =
            PathPrefs.createPersistentPreference(PREFIX + "mask.maskType", "BINARY");

    private static final IntegerProperty maskBackgroundLabel =
            PathPrefs.createPersistentPreference(PREFIX + "mask.backgroundLabel", 0);

    private static final IntegerProperty maskBoundaryLabel =
            PathPrefs.createPersistentPreference(PREFIX + "mask.boundaryLabel", -1);

    private static final BooleanProperty maskEnableBoundary =
            PathPrefs.createPersistentPreference(PREFIX + "mask.enableBoundary", false);

    private static final StringProperty maskObjectSource =
            PathPrefs.createPersistentPreference(PREFIX + "mask.objectSource", "ANNOTATIONS");

    private static final DoubleProperty maskDownsample =
            PathPrefs.createPersistentPreference(PREFIX + "mask.downsample", 4.0);

    private static final StringProperty maskFormat =
            PathPrefs.createPersistentPreference(PREFIX + "mask.format", "PNG");

    private static final BooleanProperty maskGrayscaleLut =
            PathPrefs.createPersistentPreference(PREFIX + "mask.grayscaleLut", false);

    private static final BooleanProperty maskShuffleInstanceLabels =
            PathPrefs.createPersistentPreference(PREFIX + "mask.shuffleInstanceLabels", false);

    private static final IntegerProperty maskBoundaryThickness =
            PathPrefs.createPersistentPreference(PREFIX + "mask.boundaryThickness", 1);

    private static final BooleanProperty maskSkipEmptyImages =
            PathPrefs.createPersistentPreference(PREFIX + "mask.skipEmptyImages", false);

    // --- Raw export preferences ---

    private static final StringProperty rawRegionType =
            PathPrefs.createPersistentPreference(PREFIX + "raw.regionType", "WHOLE_IMAGE");

    private static final DoubleProperty rawDownsample =
            PathPrefs.createPersistentPreference(PREFIX + "raw.downsample", 4.0);

    private static final StringProperty rawFormat =
            PathPrefs.createPersistentPreference(PREFIX + "raw.format", "TIFF");

    private static final IntegerProperty rawPadding =
            PathPrefs.createPersistentPreference(PREFIX + "raw.padding", 0);

    private static final IntegerProperty rawPyramidLevels =
            PathPrefs.createPersistentPreference(PREFIX + "raw.pyramidLevels", 4);

    private static final IntegerProperty rawTileSize =
            PathPrefs.createPersistentPreference(PREFIX + "raw.tileSize", 512);

    private static final StringProperty rawCompression =
            PathPrefs.createPersistentPreference(PREFIX + "raw.compression", "DEFAULT");

    // --- Tiled export preferences ---

    private static final IntegerProperty tiledTileSize =
            PathPrefs.createPersistentPreference(PREFIX + "tiled.tileSize", 512);

    private static final IntegerProperty tiledOverlap =
            PathPrefs.createPersistentPreference(PREFIX + "tiled.overlap", 0);

    private static final DoubleProperty tiledDownsample =
            PathPrefs.createPersistentPreference(PREFIX + "tiled.downsample", 1.0);

    private static final StringProperty tiledImageFormat =
            PathPrefs.createPersistentPreference(PREFIX + "tiled.imageFormat", "TIFF");

    private static final BooleanProperty tiledAnnotatedOnly =
            PathPrefs.createPersistentPreference(PREFIX + "tiled.annotatedOnly", true);

    private static final BooleanProperty tiledExportJson =
            PathPrefs.createPersistentPreference(PREFIX + "tiled.exportJson", false);

    private static final BooleanProperty tiledEnableLabels =
            PathPrefs.createPersistentPreference(PREFIX + "tiled.enableLabels", true);

    private static final StringProperty tiledLabelFormat =
            PathPrefs.createPersistentPreference(PREFIX + "tiled.labelFormat", "PNG");

    // --- Object Crops export preferences ---

    private static final StringProperty objectCropType =
            PathPrefs.createPersistentPreference(PREFIX + "objectCrops.objectType", "DETECTIONS");

    private static final IntegerProperty objectCropSize =
            PathPrefs.createPersistentPreference(PREFIX + "objectCrops.cropSize", 64);

    private static final IntegerProperty objectCropPadding =
            PathPrefs.createPersistentPreference(PREFIX + "objectCrops.padding", 0);

    private static final DoubleProperty objectCropDownsample =
            PathPrefs.createPersistentPreference(PREFIX + "objectCrops.downsample", 1.0);

    private static final StringProperty objectCropLabelFormat =
            PathPrefs.createPersistentPreference(PREFIX + "objectCrops.labelFormat", "SUBDIRECTORY");

    private static final StringProperty objectCropFormat =
            PathPrefs.createPersistentPreference(PREFIX + "objectCrops.format", "PNG");

    private QuietPreferences() {
        // Utility class
    }

    // ==================== Global ====================

    public static StringProperty lastCategoryProperty() { return lastCategory; }
    public static String getLastCategory() { return lastCategory.get(); }
    public static void setLastCategory(String value) { lastCategory.set(value != null ? value : "RENDERED"); }

    public static BooleanProperty addToWorkflowProperty() { return addToWorkflow; }
    public static boolean isAddToWorkflow() { return addToWorkflow.get(); }
    public static void setAddToWorkflow(boolean value) { addToWorkflow.set(value); }

    public static BooleanProperty exportGeoJsonProperty() { return exportGeoJson; }
    public static boolean isExportGeoJson() { return exportGeoJson.get(); }
    public static void setExportGeoJson(boolean value) { exportGeoJson.set(value); }

    public static DoubleProperty wizardWidthProperty() { return wizardWidth; }
    public static double getWizardWidth() { return wizardWidth.get(); }
    public static void setWizardWidth(double value) { wizardWidth.set(value); }

    public static DoubleProperty wizardHeightProperty() { return wizardHeight; }
    public static double getWizardHeight() { return wizardHeight.get(); }
    public static void setWizardHeight(double value) { wizardHeight.set(value); }

    public static StringProperty filenamePrefixProperty() { return filenamePrefix; }
    public static String getFilenamePrefix() { return filenamePrefix.get(); }
    public static void setFilenamePrefix(String value) { filenamePrefix.set(value != null ? value : ""); }

    public static StringProperty filenameSuffixProperty() { return filenameSuffix; }
    public static String getFilenameSuffix() { return filenameSuffix.get(); }
    public static void setFilenameSuffix(String value) { filenameSuffix.set(value != null ? value : ""); }

    // ==================== Rendered ====================

    public static StringProperty renderedModeProperty() { return renderedMode; }
    public static String getRenderedMode() { return renderedMode.get(); }
    public static void setRenderedMode(String value) { renderedMode.set(value != null ? value : "CLASSIFIER_OVERLAY"); }

    public static StringProperty renderedClassifierNameProperty() { return renderedClassifierName; }
    public static String getRenderedClassifierName() { return renderedClassifierName.get(); }
    public static void setRenderedClassifierName(String value) { renderedClassifierName.set(value != null ? value : ""); }

    public static DoubleProperty renderedOpacityProperty() { return renderedOpacity; }
    public static double getRenderedOpacity() { return renderedOpacity.get(); }
    public static void setRenderedOpacity(double value) { renderedOpacity.set(value); }

    public static DoubleProperty renderedDownsampleProperty() { return renderedDownsample; }
    public static double getRenderedDownsample() { return renderedDownsample.get(); }
    public static void setRenderedDownsample(double value) { renderedDownsample.set(value); }

    public static StringProperty renderedFormatProperty() { return renderedFormat; }
    public static String getRenderedFormat() { return renderedFormat.get(); }
    public static void setRenderedFormat(String value) { renderedFormat.set(value != null ? value : "PNG"); }

    public static BooleanProperty renderedIncludeAnnotationsProperty() { return renderedIncludeAnnotations; }
    public static boolean isRenderedIncludeAnnotations() { return renderedIncludeAnnotations.get(); }
    public static void setRenderedIncludeAnnotations(boolean value) { renderedIncludeAnnotations.set(value); }

    public static BooleanProperty renderedIncludeDetectionsProperty() { return renderedIncludeDetections; }
    public static boolean isRenderedIncludeDetections() { return renderedIncludeDetections.get(); }
    public static void setRenderedIncludeDetections(boolean value) { renderedIncludeDetections.set(value); }

    public static BooleanProperty renderedFillAnnotationsProperty() { return renderedFillAnnotations; }
    public static boolean isRenderedFillAnnotations() { return renderedFillAnnotations.get(); }
    public static void setRenderedFillAnnotations(boolean value) { renderedFillAnnotations.set(value); }

    public static BooleanProperty renderedShowNamesProperty() { return renderedShowNames; }
    public static boolean isRenderedShowNames() { return renderedShowNames.get(); }
    public static void setRenderedShowNames(boolean value) { renderedShowNames.set(value); }

    public static StringProperty renderedDisplayModeProperty() { return renderedDisplayMode; }
    public static String getRenderedDisplayMode() { return renderedDisplayMode.get(); }
    public static void setRenderedDisplayMode(String value) { renderedDisplayMode.set(value != null ? value : "PER_IMAGE_SAVED"); }

    public static StringProperty renderedDisplayPresetNameProperty() { return renderedDisplayPresetName; }
    public static String getRenderedDisplayPresetName() { return renderedDisplayPresetName.get(); }
    public static void setRenderedDisplayPresetName(String value) { renderedDisplayPresetName.set(value != null ? value : ""); }

    public static BooleanProperty renderedShowScaleBarProperty() { return renderedShowScaleBar; }
    public static boolean isRenderedShowScaleBar() { return renderedShowScaleBar.get(); }
    public static void setRenderedShowScaleBar(boolean value) { renderedShowScaleBar.set(value); }

    public static StringProperty renderedScaleBarPositionProperty() { return renderedScaleBarPosition; }
    public static String getRenderedScaleBarPosition() { return renderedScaleBarPosition.get(); }
    public static void setRenderedScaleBarPosition(String value) { renderedScaleBarPosition.set(value != null ? value : "LOWER_RIGHT"); }

    public static StringProperty renderedScaleBarColorProperty() { return renderedScaleBarColor; }
    public static String getRenderedScaleBarColor() { return renderedScaleBarColor.get(); }
    public static void setRenderedScaleBarColor(String value) { renderedScaleBarColor.set(value != null ? value : "#FFFFFF"); }

    public static IntegerProperty renderedScaleBarFontSizeProperty() { return renderedScaleBarFontSize; }
    public static int getRenderedScaleBarFontSize() { return renderedScaleBarFontSize.get(); }
    public static void setRenderedScaleBarFontSize(int value) { renderedScaleBarFontSize.set(value); }

    public static BooleanProperty renderedScaleBarBoldProperty() { return renderedScaleBarBold; }
    public static boolean isRenderedScaleBarBold() { return renderedScaleBarBold.get(); }
    public static void setRenderedScaleBarBold(boolean value) { renderedScaleBarBold.set(value); }

    public static BooleanProperty renderedScaleBarBackgroundBoxProperty() { return renderedScaleBarBackgroundBox; }
    public static boolean isRenderedScaleBarBackgroundBox() { return renderedScaleBarBackgroundBox.get(); }
    public static void setRenderedScaleBarBackgroundBox(boolean value) { renderedScaleBarBackgroundBox.set(value); }

    public static BooleanProperty renderedShowChannelLegendProperty() { return renderedShowChannelLegend; }
    public static boolean isRenderedShowChannelLegend() { return renderedShowChannelLegend.get(); }
    public static void setRenderedShowChannelLegend(boolean value) { renderedShowChannelLegend.set(value); }

    // --- Region type ---

    public static StringProperty renderedRegionTypeProperty() { return renderedRegionType; }
    public static String getRenderedRegionType() { return renderedRegionType.get(); }
    public static void setRenderedRegionType(String value) { renderedRegionType.set(value != null ? value : "WHOLE_IMAGE"); }

    public static IntegerProperty renderedPaddingProperty() { return renderedPadding; }
    public static int getRenderedPadding() { return renderedPadding.get(); }
    public static void setRenderedPadding(int value) { renderedPadding.set(value); }

    // --- Density map ---

    public static StringProperty renderedDensityMapNameProperty() { return renderedDensityMapName; }
    public static String getRenderedDensityMapName() { return renderedDensityMapName.get(); }
    public static void setRenderedDensityMapName(String value) { renderedDensityMapName.set(value != null ? value : ""); }

    public static StringProperty renderedColormapNameProperty() { return renderedColormapName; }
    public static String getRenderedColormapName() { return renderedColormapName.get(); }
    public static void setRenderedColormapName(String value) { renderedColormapName.set(value != null ? value : "Viridis"); }

    public static BooleanProperty renderedShowColorScaleBarProperty() { return renderedShowColorScaleBar; }
    public static boolean isRenderedShowColorScaleBar() { return renderedShowColorScaleBar.get(); }
    public static void setRenderedShowColorScaleBar(boolean value) { renderedShowColorScaleBar.set(value); }

    public static StringProperty renderedColorScaleBarPositionProperty() { return renderedColorScaleBarPosition; }
    public static String getRenderedColorScaleBarPosition() { return renderedColorScaleBarPosition.get(); }
    public static void setRenderedColorScaleBarPosition(String value) { renderedColorScaleBarPosition.set(value != null ? value : "LOWER_RIGHT"); }

    public static IntegerProperty renderedColorScaleBarFontSizeProperty() { return renderedColorScaleBarFontSize; }
    public static int getRenderedColorScaleBarFontSize() { return renderedColorScaleBarFontSize.get(); }
    public static void setRenderedColorScaleBarFontSize(int value) { renderedColorScaleBarFontSize.set(value); }

    public static BooleanProperty renderedColorScaleBarBoldProperty() { return renderedColorScaleBarBold; }
    public static boolean isRenderedColorScaleBarBold() { return renderedColorScaleBarBold.get(); }
    public static void setRenderedColorScaleBarBold(boolean value) { renderedColorScaleBarBold.set(value); }

    // --- Panel label ---

    public static BooleanProperty renderedShowPanelLabelProperty() { return renderedShowPanelLabel; }
    public static boolean isRenderedShowPanelLabel() { return renderedShowPanelLabel.get(); }
    public static void setRenderedShowPanelLabel(boolean value) { renderedShowPanelLabel.set(value); }

    public static StringProperty renderedPanelLabelTextProperty() { return renderedPanelLabelText; }
    public static String getRenderedPanelLabelText() { return renderedPanelLabelText.get(); }
    public static void setRenderedPanelLabelText(String value) { renderedPanelLabelText.set(value != null ? value : ""); }

    public static StringProperty renderedPanelLabelPositionProperty() { return renderedPanelLabelPosition; }
    public static String getRenderedPanelLabelPosition() { return renderedPanelLabelPosition.get(); }
    public static void setRenderedPanelLabelPosition(String value) { renderedPanelLabelPosition.set(value != null ? value : "UPPER_LEFT"); }

    public static IntegerProperty renderedPanelLabelFontSizeProperty() { return renderedPanelLabelFontSize; }
    public static int getRenderedPanelLabelFontSize() { return renderedPanelLabelFontSize.get(); }
    public static void setRenderedPanelLabelFontSize(int value) { renderedPanelLabelFontSize.set(value); }

    public static BooleanProperty renderedPanelLabelBoldProperty() { return renderedPanelLabelBold; }
    public static boolean isRenderedPanelLabelBold() { return renderedPanelLabelBold.get(); }
    public static void setRenderedPanelLabelBold(boolean value) { renderedPanelLabelBold.set(value); }

    // --- Split-channel ---

    public static BooleanProperty renderedSplitChannelsProperty() { return renderedSplitChannels; }
    public static boolean isRenderedSplitChannels() { return renderedSplitChannels.get(); }
    public static void setRenderedSplitChannels(boolean value) { renderedSplitChannels.set(value); }

    public static BooleanProperty renderedSplitChannelsGrayscaleProperty() { return renderedSplitChannelsGrayscale; }
    public static boolean isRenderedSplitChannelsGrayscale() { return renderedSplitChannelsGrayscale.get(); }
    public static void setRenderedSplitChannelsGrayscale(boolean value) { renderedSplitChannelsGrayscale.set(value); }

    public static BooleanProperty renderedSplitChannelColorBorderProperty() { return renderedSplitChannelColorBorder; }
    public static boolean isRenderedSplitChannelColorBorder() { return renderedSplitChannelColorBorder.get(); }
    public static void setRenderedSplitChannelColorBorder(boolean value) { renderedSplitChannelColorBorder.set(value); }

    public static BooleanProperty renderedChannelColorLegendProperty() { return renderedChannelColorLegend; }
    public static boolean isRenderedChannelColorLegend() { return renderedChannelColorLegend.get(); }
    public static void setRenderedChannelColorLegend(boolean value) { renderedChannelColorLegend.set(value); }

    public static DoubleProperty renderedMatchedDisplayPercentileProperty() { return renderedMatchedDisplayPercentile; }
    public static double getRenderedMatchedDisplayPercentile() { return renderedMatchedDisplayPercentile.get(); }
    public static void setRenderedMatchedDisplayPercentile(double value) { renderedMatchedDisplayPercentile.set(value); }

    // --- Info label ---

    public static BooleanProperty renderedShowInfoLabelProperty() { return renderedShowInfoLabel; }
    public static boolean isRenderedShowInfoLabel() { return renderedShowInfoLabel.get(); }
    public static void setRenderedShowInfoLabel(boolean value) { renderedShowInfoLabel.set(value); }

    public static StringProperty renderedInfoLabelTemplateProperty() { return renderedInfoLabelTemplate; }
    public static String getRenderedInfoLabelTemplate() { return renderedInfoLabelTemplate.get(); }
    public static void setRenderedInfoLabelTemplate(String value) { renderedInfoLabelTemplate.set(value != null ? value : ""); }

    public static StringProperty renderedInfoLabelPositionProperty() { return renderedInfoLabelPosition; }
    public static String getRenderedInfoLabelPosition() { return renderedInfoLabelPosition.get(); }
    public static void setRenderedInfoLabelPosition(String value) { renderedInfoLabelPosition.set(value != null ? value : "LOWER_LEFT"); }

    public static IntegerProperty renderedInfoLabelFontSizeProperty() { return renderedInfoLabelFontSize; }
    public static int getRenderedInfoLabelFontSize() { return renderedInfoLabelFontSize.get(); }
    public static void setRenderedInfoLabelFontSize(int value) { renderedInfoLabelFontSize.set(value); }

    public static BooleanProperty renderedInfoLabelBoldProperty() { return renderedInfoLabelBold; }
    public static boolean isRenderedInfoLabelBold() { return renderedInfoLabelBold.get(); }
    public static void setRenderedInfoLabelBold(boolean value) { renderedInfoLabelBold.set(value); }

    // --- DPI control ---

    public static IntegerProperty renderedTargetDpiProperty() { return renderedTargetDpi; }
    public static int getRenderedTargetDpi() { return renderedTargetDpi.get(); }
    public static void setRenderedTargetDpi(int value) { renderedTargetDpi.set(value); }

    public static StringProperty renderedResolutionModeProperty() { return renderedResolutionMode; }
    public static String getRenderedResolutionMode() { return renderedResolutionMode.get(); }
    public static void setRenderedResolutionMode(String value) { renderedResolutionMode.set(value != null ? value : "DOWNSAMPLE"); }

    // --- Inset/zoom ---

    public static BooleanProperty renderedShowInsetProperty() { return renderedShowInset; }
    public static boolean isRenderedShowInset() { return renderedShowInset.get(); }
    public static void setRenderedShowInset(boolean value) { renderedShowInset.set(value); }

    public static DoubleProperty renderedInsetSourceXProperty() { return renderedInsetSourceX; }
    public static double getRenderedInsetSourceX() { return renderedInsetSourceX.get(); }
    public static void setRenderedInsetSourceX(double value) { renderedInsetSourceX.set(value); }

    public static DoubleProperty renderedInsetSourceYProperty() { return renderedInsetSourceY; }
    public static double getRenderedInsetSourceY() { return renderedInsetSourceY.get(); }
    public static void setRenderedInsetSourceY(double value) { renderedInsetSourceY.set(value); }

    public static DoubleProperty renderedInsetSourceWProperty() { return renderedInsetSourceW; }
    public static double getRenderedInsetSourceW() { return renderedInsetSourceW.get(); }
    public static void setRenderedInsetSourceW(double value) { renderedInsetSourceW.set(value); }

    public static DoubleProperty renderedInsetSourceHProperty() { return renderedInsetSourceH; }
    public static double getRenderedInsetSourceH() { return renderedInsetSourceH.get(); }
    public static void setRenderedInsetSourceH(double value) { renderedInsetSourceH.set(value); }

    public static IntegerProperty renderedInsetMagnificationProperty() { return renderedInsetMagnification; }
    public static int getRenderedInsetMagnification() { return renderedInsetMagnification.get(); }
    public static void setRenderedInsetMagnification(int value) { renderedInsetMagnification.set(value); }

    public static StringProperty renderedInsetPositionProperty() { return renderedInsetPosition; }
    public static String getRenderedInsetPosition() { return renderedInsetPosition.get(); }
    public static void setRenderedInsetPosition(String value) { renderedInsetPosition.set(value != null ? value : "UPPER_RIGHT"); }

    public static StringProperty renderedInsetFrameColorProperty() { return renderedInsetFrameColor; }
    public static String getRenderedInsetFrameColor() { return renderedInsetFrameColor.get(); }
    public static void setRenderedInsetFrameColor(String value) { renderedInsetFrameColor.set(value != null ? value : "#FFFF00"); }

    public static IntegerProperty renderedInsetFrameWidthProperty() { return renderedInsetFrameWidth; }
    public static int getRenderedInsetFrameWidth() { return renderedInsetFrameWidth.get(); }
    public static void setRenderedInsetFrameWidth(int value) { renderedInsetFrameWidth.set(value); }

    public static BooleanProperty renderedInsetConnectingLinesProperty() { return renderedInsetConnectingLines; }
    public static boolean isRenderedInsetConnectingLines() { return renderedInsetConnectingLines.get(); }
    public static void setRenderedInsetConnectingLines(boolean value) { renderedInsetConnectingLines.set(value); }

    // ==================== Mask ====================

    public static StringProperty maskTypeProperty() { return maskType; }
    public static String getMaskType() { return maskType.get(); }
    public static void setMaskType(String value) { maskType.set(value != null ? value : "BINARY"); }

    public static IntegerProperty maskBackgroundLabelProperty() { return maskBackgroundLabel; }
    public static int getMaskBackgroundLabel() { return maskBackgroundLabel.get(); }
    public static void setMaskBackgroundLabel(int value) { maskBackgroundLabel.set(value); }

    public static IntegerProperty maskBoundaryLabelProperty() { return maskBoundaryLabel; }
    public static int getMaskBoundaryLabel() { return maskBoundaryLabel.get(); }
    public static void setMaskBoundaryLabel(int value) { maskBoundaryLabel.set(value); }

    public static BooleanProperty maskEnableBoundaryProperty() { return maskEnableBoundary; }
    public static boolean isMaskEnableBoundary() { return maskEnableBoundary.get(); }
    public static void setMaskEnableBoundary(boolean value) { maskEnableBoundary.set(value); }

    public static StringProperty maskObjectSourceProperty() { return maskObjectSource; }
    public static String getMaskObjectSource() { return maskObjectSource.get(); }
    public static void setMaskObjectSource(String value) { maskObjectSource.set(value != null ? value : "ANNOTATIONS"); }

    public static DoubleProperty maskDownsampleProperty() { return maskDownsample; }
    public static double getMaskDownsample() { return maskDownsample.get(); }
    public static void setMaskDownsample(double value) { maskDownsample.set(value); }

    public static StringProperty maskFormatProperty() { return maskFormat; }
    public static String getMaskFormat() { return maskFormat.get(); }
    public static void setMaskFormat(String value) { maskFormat.set(value != null ? value : "PNG"); }

    public static BooleanProperty maskGrayscaleLutProperty() { return maskGrayscaleLut; }
    public static boolean isMaskGrayscaleLut() { return maskGrayscaleLut.get(); }
    public static void setMaskGrayscaleLut(boolean value) { maskGrayscaleLut.set(value); }

    public static BooleanProperty maskShuffleInstanceLabelsProperty() { return maskShuffleInstanceLabels; }
    public static boolean isMaskShuffleInstanceLabels() { return maskShuffleInstanceLabels.get(); }
    public static void setMaskShuffleInstanceLabels(boolean value) { maskShuffleInstanceLabels.set(value); }

    public static IntegerProperty maskBoundaryThicknessProperty() { return maskBoundaryThickness; }
    public static int getMaskBoundaryThickness() { return maskBoundaryThickness.get(); }
    public static void setMaskBoundaryThickness(int value) { maskBoundaryThickness.set(value); }

    public static BooleanProperty maskSkipEmptyImagesProperty() { return maskSkipEmptyImages; }
    public static boolean isMaskSkipEmptyImages() { return maskSkipEmptyImages.get(); }
    public static void setMaskSkipEmptyImages(boolean value) { maskSkipEmptyImages.set(value); }

    // ==================== Raw ====================

    public static StringProperty rawRegionTypeProperty() { return rawRegionType; }
    public static String getRawRegionType() { return rawRegionType.get(); }
    public static void setRawRegionType(String value) { rawRegionType.set(value != null ? value : "WHOLE_IMAGE"); }

    public static DoubleProperty rawDownsampleProperty() { return rawDownsample; }
    public static double getRawDownsample() { return rawDownsample.get(); }
    public static void setRawDownsample(double value) { rawDownsample.set(value); }

    public static StringProperty rawFormatProperty() { return rawFormat; }
    public static String getRawFormat() { return rawFormat.get(); }
    public static void setRawFormat(String value) { rawFormat.set(value != null ? value : "TIFF"); }

    public static IntegerProperty rawPaddingProperty() { return rawPadding; }
    public static int getRawPadding() { return rawPadding.get(); }
    public static void setRawPadding(int value) { rawPadding.set(value); }

    public static IntegerProperty rawPyramidLevelsProperty() { return rawPyramidLevels; }
    public static int getRawPyramidLevels() { return rawPyramidLevels.get(); }
    public static void setRawPyramidLevels(int value) { rawPyramidLevels.set(value); }

    public static IntegerProperty rawTileSizeProperty() { return rawTileSize; }
    public static int getRawTileSize() { return rawTileSize.get(); }
    public static void setRawTileSize(int value) { rawTileSize.set(value); }

    public static StringProperty rawCompressionProperty() { return rawCompression; }
    public static String getRawCompression() { return rawCompression.get(); }
    public static void setRawCompression(String value) { rawCompression.set(value != null ? value : "DEFAULT"); }

    // ==================== Tiled ====================

    public static IntegerProperty tiledTileSizeProperty() { return tiledTileSize; }
    public static int getTiledTileSize() { return tiledTileSize.get(); }
    public static void setTiledTileSize(int value) { tiledTileSize.set(value); }

    public static IntegerProperty tiledOverlapProperty() { return tiledOverlap; }
    public static int getTiledOverlap() { return tiledOverlap.get(); }
    public static void setTiledOverlap(int value) { tiledOverlap.set(value); }

    public static DoubleProperty tiledDownsampleProperty() { return tiledDownsample; }
    public static double getTiledDownsample() { return tiledDownsample.get(); }
    public static void setTiledDownsample(double value) { tiledDownsample.set(value); }

    public static StringProperty tiledImageFormatProperty() { return tiledImageFormat; }
    public static String getTiledImageFormat() { return tiledImageFormat.get(); }
    public static void setTiledImageFormat(String value) { tiledImageFormat.set(value != null ? value : "TIFF"); }

    public static BooleanProperty tiledAnnotatedOnlyProperty() { return tiledAnnotatedOnly; }
    public static boolean isTiledAnnotatedOnly() { return tiledAnnotatedOnly.get(); }
    public static void setTiledAnnotatedOnly(boolean value) { tiledAnnotatedOnly.set(value); }

    public static BooleanProperty tiledExportJsonProperty() { return tiledExportJson; }
    public static boolean isTiledExportJson() { return tiledExportJson.get(); }
    public static void setTiledExportJson(boolean value) { tiledExportJson.set(value); }

    public static BooleanProperty tiledEnableLabelsProperty() { return tiledEnableLabels; }
    public static boolean isTiledEnableLabels() { return tiledEnableLabels.get(); }
    public static void setTiledEnableLabels(boolean value) { tiledEnableLabels.set(value); }

    public static StringProperty tiledLabelFormatProperty() { return tiledLabelFormat; }
    public static String getTiledLabelFormat() { return tiledLabelFormat.get(); }
    public static void setTiledLabelFormat(String value) { tiledLabelFormat.set(value != null ? value : "PNG"); }

    // ==================== Object Crops ====================

    public static StringProperty objectCropTypeProperty() { return objectCropType; }
    public static String getObjectCropType() { return objectCropType.get(); }
    public static void setObjectCropType(String value) { objectCropType.set(value != null ? value : "DETECTIONS"); }

    public static IntegerProperty objectCropSizeProperty() { return objectCropSize; }
    public static int getObjectCropSize() { return objectCropSize.get(); }
    public static void setObjectCropSize(int value) { objectCropSize.set(value); }

    public static IntegerProperty objectCropPaddingProperty() { return objectCropPadding; }
    public static int getObjectCropPadding() { return objectCropPadding.get(); }
    public static void setObjectCropPadding(int value) { objectCropPadding.set(value); }

    public static DoubleProperty objectCropDownsampleProperty() { return objectCropDownsample; }
    public static double getObjectCropDownsample() { return objectCropDownsample.get(); }
    public static void setObjectCropDownsample(double value) { objectCropDownsample.set(value); }

    public static StringProperty objectCropLabelFormatProperty() { return objectCropLabelFormat; }
    public static String getObjectCropLabelFormat() { return objectCropLabelFormat.get(); }
    public static void setObjectCropLabelFormat(String value) { objectCropLabelFormat.set(value != null ? value : "SUBDIRECTORY"); }

    public static StringProperty objectCropFormatProperty() { return objectCropFormat; }
    public static String getObjectCropFormat() { return objectCropFormat.get(); }
    public static void setObjectCropFormat(String value) { objectCropFormat.set(value != null ? value : "PNG"); }
}
