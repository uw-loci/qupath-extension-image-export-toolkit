package qupath.ext.quiet.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import org.controlsfx.control.CheckComboBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import qupath.ext.quiet.export.OutputFormat;
import qupath.ext.quiet.export.RenderedExportConfig;
import qupath.ext.quiet.export.RenderedExportConfig.DisplaySettingsMode;
import qupath.ext.quiet.export.RenderedImageExporter;
import qupath.ext.quiet.export.ScaleBarRenderer;
import qupath.ext.quiet.preferences.QuietPreferences;
import qupath.fx.utils.FXUtils;
import qupath.lib.analysis.heatmaps.DensityMaps;
import qupath.lib.analysis.heatmaps.DensityMaps.DensityMapBuilder;
import qupath.lib.classifiers.pixel.PixelClassificationImageServer;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.color.ColorMaps;
import qupath.lib.display.settings.DisplaySettingUtils;
import qupath.lib.display.settings.ImageDisplaySettings;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.overlays.PixelClassificationOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.classes.PathClass;

/**
 * Step 2a of the export wizard: Configure rendered image export.
 */
public class RenderedConfigPane extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(RenderedConfigPane.class);
    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.quiet.ui.strings");

    /** Sentinel value stored in config classifierName to indicate "use the active overlay". */
    static final String ACTIVE_OVERLAY_SENTINEL = "\u0000__ACTIVE_OVERLAY__";

    /** Display label shown in the classifier combo for the active overlay option. */
    static final String ACTIVE_OVERLAY_DISPLAY_LABEL = "(Active Overlay)";

    private final QuPathGUI qupath;

    private ComboBox<RenderedExportConfig.RegionType> regionTypeCombo;
    private CheckComboBox<String> classificationCombo;
    private Label classificationFilterLabel;
    private HBox classificationFilterBox;
    private Label paddingLabel;
    private Spinner<Integer> paddingSpinner;

    private ComboBox<RenderedExportConfig.RenderMode> modeCombo;
    private ComboBox<DisplaySettingsMode> displaySettingsCombo;
    private ComboBox<String> presetNameCombo;
    private ComboBox<String> classifierCombo;
    private Slider opacitySlider;
    private Label opacityValueLabel;
    private ComboBox<Double> downsampleCombo;
    private ComboBox<OutputFormat> formatCombo;
    private CheckBox includeAnnotationsCheck;
    private CheckBox includeDetectionsCheck;
    private CheckBox fillAnnotationsCheck;
    private CheckBox showNamesCheck;
    private CheckBox showScaleBarCheck;
    private ComboBox<ScaleBarRenderer.Position> scaleBarPositionCombo;
    private ColorPicker scaleBarColorPicker;
    private Spinner<Integer> scaleBarFontSizeSpinner;
    private CheckBox scaleBarBoldCheck;
    private CheckBox scaleBarBackgroundBoxCheck;
    private Label scaleBarColorHintLabel;
    private Button previewButton;

    // Density map controls
    private ComboBox<String> densityMapCombo;
    private Label densityMapLabel;
    private HBox densityMapBox;
    private ComboBox<String> colormapCombo;
    private Label colormapLabel;

    // Color scale bar controls
    private CheckBox showColorScaleBarCheck;
    private ComboBox<ScaleBarRenderer.Position> colorScaleBarPositionCombo;
    private Label colorScaleBarPositionLabel;
    private Spinner<Integer> colorScaleBarFontSizeSpinner;
    private Label colorScaleBarFontSizeLabel;
    private CheckBox colorScaleBarBoldCheck;

    // Panel label controls
    private CheckBox showPanelLabelCheck;
    private TextField panelLabelTextField;
    private Label panelLabelTextLabel;
    private ComboBox<ScaleBarRenderer.Position> panelLabelPositionCombo;
    private Label panelLabelPositionLabel;
    private Spinner<Integer> panelLabelFontSizeSpinner;
    private Label panelLabelFontSizeLabel;
    private CheckBox panelLabelBoldCheck;

    // Split-channel controls
    private CheckBox splitChannelsCheck;
    private CheckBox splitChannelsGrayscaleCheck;
    private CheckBox splitChannelColorBorderCheck;
    private CheckBox channelColorLegendCheck;
    private Label splitChannelNoteLabel;

    // Global matched controls
    private Label matchedPercentileLabel;
    private Spinner<Double> matchedPercentileSpinner;

    // DPI resolution controls
    private ComboBox<String> resolutionModeCombo;
    private Label resolutionModeLabel;
    private Label downsampleLabel;
    private Spinner<Integer> dpiSpinner;
    private Label dpiLabel;
    private Label dpiNoteLabel;

    // Info label controls
    private CheckBox showInfoLabelCheck;
    private TextField infoLabelTemplateField;
    private Label infoLabelTemplateLabel;
    private Label infoLabelPlaceholderRef;
    private Label infoLabelPreviewLabel;
    private ComboBox<ScaleBarRenderer.Position> infoLabelPositionCombo;
    private Label infoLabelPositionLabel;
    private Spinner<Integer> infoLabelFontSizeSpinner;
    private Label infoLabelFontSizeLabel;
    private CheckBox infoLabelBoldCheck;

    // Format info label
    private Label formatInfoLabel;

    // Controls needing visibility toggling
    private Label classifierLabel;
    private HBox classifierBox;
    private Label presetLabel;
    private HBox presetBox;
    private Label scaleBarPositionLabel;
    private Label scaleBarColorLabel;
    private Label scaleBarFontSizeLabel;

    // Section TitledPanes needing visibility toggling
    private TitledPane imageSettingsSection;
    private TitledPane overlaySourceSection;
    private TitledPane splitChannelSection;
    private TitledPane scaleBarSection;
    private TitledPane colorScaleBarSection;
    private TitledPane infoLabelSection;

    public RenderedConfigPane(QuPathGUI qupath) {
        this.qupath = qupath;
        setSpacing(10);
        setPadding(new Insets(10));
        buildUI();
        populateClassifiers();
        populatePresets();
        populateDensityMaps();
        restorePreferences();
    }

    private void buildUI() {
        var header = new Label(resources.getString("wizard.step2.title") + " - Rendered Image");
        header.setFont(Font.font(null, FontWeight.BOLD, 14));

        imageSettingsSection = buildImageSettingsSection();
        overlaySourceSection = buildOverlaySourceSection();
        var objectOverlaysSection = buildObjectOverlaysSection();
        splitChannelSection = buildSplitChannelSection();
        scaleBarSection = buildScaleBarSection();
        colorScaleBarSection = buildColorScaleBarSection();
        infoLabelSection = buildInfoLabelSection();

        previewButton = new Button(resources.getString("rendered.label.previewImage"));
        previewButton.setOnAction(e -> handlePreview());
        previewButton.setMaxWidth(Double.MAX_VALUE);

        getChildren().addAll(header, imageSettingsSection, overlaySourceSection,
                objectOverlaysSection, splitChannelSection, scaleBarSection,
                colorScaleBarSection, infoLabelSection,
                previewButton);

        // Scale bar visibility toggling + SVG auto-default
        showScaleBarCheck.selectedProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal) maybeSwitchToSvg();
                    updateScaleBarVisibility(newVal);
                });
        updateScaleBarVisibility(false);

        // Color scale bar visibility toggling + SVG auto-default
        showColorScaleBarCheck.selectedProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal) maybeSwitchToSvg();
                    updateColorScaleBarVisibility(newVal);
                });
        updateColorScaleBarVisibility(false);

        // Panel label visibility toggling + SVG auto-default
        showPanelLabelCheck.selectedProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal) maybeSwitchToSvg();
                    updatePanelLabelVisibility(newVal);
                });
        updatePanelLabelVisibility(false);

        // Info label visibility toggling + SVG auto-default
        showInfoLabelCheck.selectedProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal) maybeSwitchToSvg();
                    updateInfoLabelVisibility(newVal);
                });
        updateInfoLabelVisibility(false);

        // DPI/downsample mode toggling
        resolutionModeCombo.valueProperty().addListener(
                (obs, oldVal, newVal) -> updateResolutionModeVisibility(newVal));
        updateResolutionModeVisibility("By Downsample");

        // Split-channel sub-option visibility
        splitChannelsCheck.selectedProperty().addListener(
                (obs, oldVal, newVal) -> updateSplitChannelVisibility(newVal));
        updateSplitChannelVisibility(false);

        // Object overlay SVG auto-default
        includeAnnotationsCheck.selectedProperty().addListener(
                (obs, oldVal, newVal) -> { if (newVal) maybeSwitchToSvg(); });
        includeDetectionsCheck.selectedProperty().addListener(
                (obs, oldVal, newVal) -> { if (newVal) maybeSwitchToSvg(); });

        // Preview button enabled state depends on image being open
        updatePreviewButtonState();
        qupath.imageDataProperty().addListener((obs, oldVal, newVal) -> updatePreviewButtonState());

        // Mode switching
        modeCombo.valueProperty().addListener((obs, oldMode, newMode) -> updateModeVisibility(newMode));
        updateModeVisibility(RenderedExportConfig.RenderMode.CLASSIFIER_OVERLAY);

        // Region type visibility (default hidden)
        updateRegionTypeVisibility(RenderedExportConfig.RegionType.WHOLE_IMAGE);

        wireTooltips();
    }

    private TitledPane buildImageSettingsSection() {
        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        int row = 0;

        // Render mode selection
        grid.add(new Label(resources.getString("rendered.label.mode")), 0, row);
        modeCombo = new ComboBox<>(FXCollections.observableArrayList(
                RenderedExportConfig.RenderMode.values()));
        modeCombo.setValue(RenderedExportConfig.RenderMode.CLASSIFIER_OVERLAY);
        modeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(RenderedExportConfig.RenderMode mode) {
                if (mode == null) return "";
                return switch (mode) {
                    case CLASSIFIER_OVERLAY -> resources.getString("rendered.mode.classifier");
                    case OBJECT_OVERLAY -> resources.getString("rendered.mode.object");
                    case DENSITY_MAP_OVERLAY -> resources.getString("rendered.mode.densityMap");
                };
            }
            @Override
            public RenderedExportConfig.RenderMode fromString(String s) {
                return RenderedExportConfig.RenderMode.CLASSIFIER_OVERLAY;
            }
        });
        grid.add(modeCombo, 1, row);
        row++;

        // Region type selection
        grid.add(new Label(resources.getString("rendered.label.regionType")), 0, row);
        regionTypeCombo = new ComboBox<>(FXCollections.observableArrayList(
                RenderedExportConfig.RegionType.values()));
        regionTypeCombo.setValue(RenderedExportConfig.RegionType.WHOLE_IMAGE);
        regionTypeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(RenderedExportConfig.RegionType type) {
                if (type == null) return "";
                return switch (type) {
                    case WHOLE_IMAGE -> resources.getString("rendered.region.wholeImage");
                    case ALL_ANNOTATIONS -> resources.getString("rendered.region.allAnnotations");
                };
            }
            @Override
            public RenderedExportConfig.RegionType fromString(String s) {
                return RenderedExportConfig.RegionType.WHOLE_IMAGE;
            }
        });
        regionTypeCombo.valueProperty().addListener(
                (obs, oldVal, newVal) -> updateRegionTypeVisibility(newVal));
        grid.add(regionTypeCombo, 1, row);
        row++;

        // Classification filter (ALL_ANNOTATIONS mode only)
        classificationFilterLabel = new Label(resources.getString("rendered.label.classificationFilter"));
        grid.add(classificationFilterLabel, 0, row);
        classificationCombo = new CheckComboBox<>();
        classificationCombo.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(classificationCombo, Priority.ALWAYS);
        classificationCombo.setTitle("All selected");
        FXUtils.installSelectAllOrNoneMenu(classificationCombo);
        var classRefreshButton = new Button(resources.getString("button.refresh"));
        classRefreshButton.setTooltip(createTooltip("tooltip.rendered.refreshClassifications"));
        classRefreshButton.setOnAction(e -> populateAnnotationClassifications());
        classificationFilterBox = new HBox(5, classificationCombo, classRefreshButton);
        HBox.setHgrow(classificationCombo, Priority.ALWAYS);
        grid.add(classificationFilterBox, 1, row);
        row++;

        // Padding spinner (ALL_ANNOTATIONS mode only)
        paddingLabel = new Label(resources.getString("rendered.label.padding"));
        grid.add(paddingLabel, 0, row);
        paddingSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 10000, 0, 10));
        paddingSpinner.setEditable(true);
        paddingSpinner.setPrefWidth(100);
        grid.add(paddingSpinner, 1, row);
        row++;

        // Display settings mode
        grid.add(new Label(resources.getString("rendered.label.displaySettings")), 0, row);
        displaySettingsCombo = new ComboBox<>(FXCollections.observableArrayList(
                DisplaySettingsMode.values()));
        displaySettingsCombo.setValue(DisplaySettingsMode.PER_IMAGE_SAVED);
        displaySettingsCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(DisplaySettingsMode mode) {
                if (mode == null) return "";
                return switch (mode) {
                    case PER_IMAGE_SAVED -> resources.getString("rendered.display.perImage");
                    case CURRENT_VIEWER -> resources.getString("rendered.display.currentViewer");
                    case SAVED_PRESET -> resources.getString("rendered.display.savedPreset");
                    case GLOBAL_MATCHED -> resources.getString("rendered.display.globalMatched");
                    case RAW -> resources.getString("rendered.display.raw");
                };
            }
            @Override
            public DisplaySettingsMode fromString(String s) {
                return DisplaySettingsMode.PER_IMAGE_SAVED;
            }
        });
        // Disable CURRENT_VIEWER when no image is open in the viewer
        displaySettingsCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(DisplaySettingsMode item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setDisable(false);
                } else {
                    setText(displaySettingsCombo.getConverter().toString(item));
                    if (item == DisplaySettingsMode.CURRENT_VIEWER) {
                        boolean noViewer = qupath.getViewer() == null
                                || qupath.getViewer().getImageData() == null;
                        setDisable(noViewer);
                        if (noViewer) {
                            setTooltip(new Tooltip(
                                    resources.getString("rendered.display.viewerRequired")));
                        }
                    } else {
                        setDisable(false);
                        setTooltip(null);
                    }
                }
            }
        });
        displaySettingsCombo.valueProperty().addListener(
                (obs, oldVal, newVal) -> updateDisplaySettingsVisibility(newVal));
        grid.add(displaySettingsCombo, 1, row);
        row++;

        // Preset name selection (SAVED_PRESET mode only)
        presetLabel = new Label(resources.getString("rendered.label.presetName"));
        grid.add(presetLabel, 0, row);
        presetNameCombo = new ComboBox<>();
        presetNameCombo.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(presetNameCombo, Priority.ALWAYS);
        presetNameCombo.setPromptText(resources.getString("rendered.display.noPresets"));
        var presetRefreshButton = new Button(resources.getString("button.refresh"));
        presetRefreshButton.setTooltip(createTooltip("tooltip.rendered.refreshPresets"));
        presetRefreshButton.setOnAction(e -> populatePresets());
        presetBox = new HBox(5, presetNameCombo, presetRefreshButton);
        HBox.setHgrow(presetNameCombo, Priority.ALWAYS);
        grid.add(presetBox, 1, row);
        row++;

        // Matched percentile spinner (GLOBAL_MATCHED mode only)
        matchedPercentileLabel = new Label(resources.getString("rendered.label.matchedPercentile"));
        grid.add(matchedPercentileLabel, 0, row);
        matchedPercentileSpinner = new Spinner<>(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(0.0, 5.0, 0.1, 0.05));
        matchedPercentileSpinner.setEditable(true);
        matchedPercentileSpinner.setPrefWidth(100);
        grid.add(matchedPercentileSpinner, 1, row);
        row++;

        // Opacity slider
        grid.add(new Label(resources.getString("rendered.label.opacity")), 0, row);
        opacitySlider = new Slider(0.0, 1.0, 0.5);
        opacitySlider.setShowTickMarks(true);
        opacitySlider.setShowTickLabels(true);
        opacitySlider.setMajorTickUnit(0.25);
        opacitySlider.setBlockIncrement(0.05);
        opacityValueLabel = new Label("0.50");
        opacityValueLabel.setMinWidth(35);
        opacitySlider.valueProperty().addListener((obs, oldVal, newVal) ->
                opacityValueLabel.setText(String.format("%.2f", newVal.doubleValue())));
        var opacityBox = new HBox(5, opacitySlider, opacityValueLabel);
        HBox.setHgrow(opacitySlider, Priority.ALWAYS);
        grid.add(opacityBox, 1, row);
        row++;

        // Resolution mode toggle
        resolutionModeLabel = new Label(resources.getString("rendered.label.resolutionMode"));
        grid.add(resolutionModeLabel, 0, row);
        resolutionModeCombo = new ComboBox<>(FXCollections.observableArrayList(
                "By Downsample", "By Target DPI"));
        resolutionModeCombo.setValue("By Downsample");
        grid.add(resolutionModeCombo, 1, row);
        row++;

        // Downsample combo
        downsampleLabel = new Label(resources.getString("rendered.label.downsample"));
        grid.add(downsampleLabel, 0, row);
        downsampleCombo = new ComboBox<>(FXCollections.observableArrayList(
                1.0, 2.0, 4.0, 8.0, 16.0, 32.0));
        downsampleCombo.setEditable(true);
        downsampleCombo.setValue(4.0);
        downsampleCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Double value) {
                if (value == null) return "";
                return value == Math.floor(value) ?
                        String.valueOf(value.intValue()) : String.valueOf(value);
            }
            @Override
            public Double fromString(String string) {
                try { return Double.parseDouble(string); }
                catch (NumberFormatException e) { return 4.0; }
            }
        });
        grid.add(downsampleCombo, 1, row);
        row++;

        // DPI spinner
        dpiLabel = new Label(resources.getString("rendered.label.targetDpi"));
        grid.add(dpiLabel, 0, row);
        dpiSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(72, 1200, 300, 50));
        dpiSpinner.setEditable(true);
        dpiSpinner.setPrefWidth(100);
        grid.add(dpiSpinner, 1, row);
        row++;

        // DPI note
        dpiNoteLabel = new Label(resources.getString("rendered.label.dpiNote"));
        dpiNoteLabel.setWrapText(true);
        dpiNoteLabel.setMaxWidth(400);
        dpiNoteLabel.setStyle("-fx-font-size: 11; -fx-text-fill: #666666; -fx-font-style: italic;");
        grid.add(dpiNoteLabel, 1, row);
        row++;

        // Format combo
        grid.add(new Label(resources.getString("rendered.label.format")), 0, row);
        formatCombo = new ComboBox<>(FXCollections.observableArrayList(OutputFormat.values()));
        formatCombo.setValue(OutputFormat.PNG);
        grid.add(formatCombo, 1, row);
        row++;

        // Format info label
        formatInfoLabel = new Label();
        formatInfoLabel.setWrapText(true);
        formatInfoLabel.setMaxWidth(400);
        formatInfoLabel.setStyle("-fx-font-size: 11; -fx-text-fill: #666666;");
        grid.add(formatInfoLabel, 1, row);
        row++;

        formatCombo.valueProperty().addListener((obs, old, val) -> updateFormatInfo(val));
        updateFormatInfo(OutputFormat.PNG);

        return SectionBuilder.createSection(
                resources.getString("rendered.section.imageSettings"), true, grid);
    }

    private TitledPane buildOverlaySourceSection() {
        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        int row = 0;

        // Classifier selection (classifier mode only)
        classifierLabel = new Label(resources.getString("rendered.label.classifier"));
        grid.add(classifierLabel, 0, row);
        classifierCombo = new ComboBox<>();
        classifierCombo.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(classifierCombo, Priority.ALWAYS);
        var refreshButton = new Button(resources.getString("button.refresh"));
        refreshButton.setTooltip(createTooltip("tooltip.rendered.refreshClassifiers"));
        refreshButton.setOnAction(e -> populateClassifiers());
        classifierBox = new HBox(5, classifierCombo, refreshButton);
        HBox.setHgrow(classifierCombo, Priority.ALWAYS);
        grid.add(classifierBox, 1, row);
        row++;

        // Density map selector (density map mode only)
        densityMapLabel = new Label(resources.getString("rendered.label.densityMap"));
        grid.add(densityMapLabel, 0, row);
        densityMapCombo = new ComboBox<>();
        densityMapCombo.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(densityMapCombo, Priority.ALWAYS);
        var dmRefreshButton = new Button(resources.getString("button.refresh"));
        dmRefreshButton.setTooltip(createTooltip("tooltip.rendered.refreshDensityMaps"));
        dmRefreshButton.setOnAction(e -> populateDensityMaps());
        densityMapBox = new HBox(5, densityMapCombo, dmRefreshButton);
        HBox.setHgrow(densityMapCombo, Priority.ALWAYS);
        grid.add(densityMapBox, 1, row);
        row++;

        // Colormap/LUT selector (density map mode only)
        colormapLabel = new Label(resources.getString("rendered.label.colormap"));
        grid.add(colormapLabel, 0, row);
        colormapCombo = new ComboBox<>();
        colormapCombo.getItems().addAll(ColorMaps.getColorMaps().keySet());
        colormapCombo.setValue("Viridis");
        // Custom cell factory to show colormap gradient swatches
        colormapCombo.setCellFactory(lv -> createColormapCell());
        colormapCombo.setButtonCell(createColormapCell());
        grid.add(colormapCombo, 1, row);
        row++;

        return SectionBuilder.createSection(
                resources.getString("rendered.section.overlaySource"), true, grid);
    }

    private TitledPane buildObjectOverlaysSection() {
        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        int row = 0;

        includeAnnotationsCheck = new CheckBox(resources.getString("rendered.label.includeAnnotations"));
        grid.add(includeAnnotationsCheck, 0, row, 2, 1);
        row++;

        includeDetectionsCheck = new CheckBox(resources.getString("rendered.label.includeDetections"));
        includeDetectionsCheck.setSelected(true);
        grid.add(includeDetectionsCheck, 0, row, 2, 1);
        row++;

        fillAnnotationsCheck = new CheckBox(resources.getString("rendered.label.fillAnnotations"));
        grid.add(fillAnnotationsCheck, 0, row, 2, 1);
        row++;

        showNamesCheck = new CheckBox(resources.getString("rendered.label.showNames"));
        grid.add(showNamesCheck, 0, row, 2, 1);
        row++;

        return SectionBuilder.createSection(
                resources.getString("rendered.section.objectOverlays"), false, grid);
    }

    private TitledPane buildScaleBarSection() {
        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        int row = 0;

        showScaleBarCheck = new CheckBox(resources.getString("rendered.label.showScaleBar"));
        grid.add(showScaleBarCheck, 0, row, 2, 1);
        row++;

        scaleBarPositionLabel = new Label(resources.getString("rendered.label.scaleBarPosition"));
        grid.add(scaleBarPositionLabel, 0, row);
        scaleBarPositionCombo = new ComboBox<>(FXCollections.observableArrayList(
                ScaleBarRenderer.Position.values()));
        scaleBarPositionCombo.setValue(ScaleBarRenderer.Position.LOWER_RIGHT);
        scaleBarPositionCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ScaleBarRenderer.Position pos) {
                if (pos == null) return "";
                return switch (pos) {
                    case LOWER_RIGHT -> resources.getString("rendered.scaleBar.lowerRight");
                    case LOWER_LEFT -> resources.getString("rendered.scaleBar.lowerLeft");
                    case UPPER_RIGHT -> resources.getString("rendered.scaleBar.upperRight");
                    case UPPER_LEFT -> resources.getString("rendered.scaleBar.upperLeft");
                };
            }
            @Override
            public ScaleBarRenderer.Position fromString(String s) {
                return ScaleBarRenderer.Position.LOWER_RIGHT;
            }
        });
        grid.add(scaleBarPositionCombo, 1, row);
        row++;

        scaleBarColorLabel = new Label(resources.getString("rendered.label.scaleBarColor"));
        grid.add(scaleBarColorLabel, 0, row);
        scaleBarColorPicker = new ColorPicker(javafx.scene.paint.Color.WHITE);
        grid.add(scaleBarColorPicker, 1, row);
        row++;

        scaleBarFontSizeLabel = new Label(resources.getString("rendered.label.scaleBarFontSize"));
        grid.add(scaleBarFontSizeLabel, 0, row);
        var fontSizeFactory = new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 72, 0);
        fontSizeFactory.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer value) {
                if (value == null || value == 0)
                    return resources.getString("rendered.scaleBar.fontSizeAuto");
                return String.valueOf(value);
            }
            @Override
            public Integer fromString(String string) {
                if (string == null || string.isBlank()
                        || string.equalsIgnoreCase(resources.getString("rendered.scaleBar.fontSizeAuto"))) {
                    return 0;
                }
                try { return Integer.parseInt(string); }
                catch (NumberFormatException e) { return 0; }
            }
        });
        scaleBarFontSizeSpinner = new Spinner<>(fontSizeFactory);
        scaleBarFontSizeSpinner.setEditable(true);
        grid.add(scaleBarFontSizeSpinner, 1, row);
        row++;

        scaleBarBoldCheck = new CheckBox(resources.getString("rendered.label.scaleBarBold"));
        scaleBarBoldCheck.setSelected(true);
        grid.add(scaleBarBoldCheck, 0, row, 2, 1);
        row++;

        scaleBarBackgroundBoxCheck = new CheckBox(resources.getString("rendered.label.scaleBarBackgroundBox"));
        grid.add(scaleBarBackgroundBoxCheck, 0, row, 2, 1);
        row++;

        scaleBarColorHintLabel = new Label(resources.getString("rendered.scaleBar.colorHint"));
        scaleBarColorHintLabel.setWrapText(true);
        scaleBarColorHintLabel.setStyle("-fx-font-size: 0.85em; -fx-text-fill: #666666; -fx-font-style: italic;");
        grid.add(scaleBarColorHintLabel, 0, row, 2, 1);
        row++;

        return SectionBuilder.createSection(
                resources.getString("rendered.section.scaleBar"), false, grid);
    }

    private TitledPane buildColorScaleBarSection() {
        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        int row = 0;

        showColorScaleBarCheck = new CheckBox(resources.getString("rendered.label.showColorScaleBar"));
        grid.add(showColorScaleBarCheck, 0, row, 2, 1);
        row++;

        colorScaleBarPositionLabel = new Label(resources.getString("rendered.label.colorScaleBarPosition"));
        grid.add(colorScaleBarPositionLabel, 0, row);
        colorScaleBarPositionCombo = new ComboBox<>(FXCollections.observableArrayList(
                ScaleBarRenderer.Position.values()));
        colorScaleBarPositionCombo.setValue(ScaleBarRenderer.Position.LOWER_RIGHT);
        colorScaleBarPositionCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ScaleBarRenderer.Position pos) {
                if (pos == null) return "";
                return switch (pos) {
                    case LOWER_RIGHT -> resources.getString("rendered.scaleBar.lowerRight");
                    case LOWER_LEFT -> resources.getString("rendered.scaleBar.lowerLeft");
                    case UPPER_RIGHT -> resources.getString("rendered.scaleBar.upperRight");
                    case UPPER_LEFT -> resources.getString("rendered.scaleBar.upperLeft");
                };
            }
            @Override
            public ScaleBarRenderer.Position fromString(String s) {
                return ScaleBarRenderer.Position.LOWER_RIGHT;
            }
        });
        grid.add(colorScaleBarPositionCombo, 1, row);
        row++;

        colorScaleBarFontSizeLabel = new Label(resources.getString("rendered.label.colorScaleBarFontSize"));
        grid.add(colorScaleBarFontSizeLabel, 0, row);
        var csFontSizeFactory = new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 72, 0);
        csFontSizeFactory.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer value) {
                if (value == null || value == 0)
                    return resources.getString("rendered.scaleBar.fontSizeAuto");
                return String.valueOf(value);
            }
            @Override
            public Integer fromString(String string) {
                if (string == null || string.isBlank()
                        || string.equalsIgnoreCase(resources.getString("rendered.scaleBar.fontSizeAuto"))) {
                    return 0;
                }
                try { return Integer.parseInt(string); }
                catch (NumberFormatException e) { return 0; }
            }
        });
        colorScaleBarFontSizeSpinner = new Spinner<>(csFontSizeFactory);
        colorScaleBarFontSizeSpinner.setEditable(true);
        grid.add(colorScaleBarFontSizeSpinner, 1, row);
        row++;

        colorScaleBarBoldCheck = new CheckBox(resources.getString("rendered.label.colorScaleBarBold"));
        colorScaleBarBoldCheck.setSelected(true);
        grid.add(colorScaleBarBoldCheck, 0, row, 2, 1);
        row++;

        return SectionBuilder.createSection(
                resources.getString("rendered.section.colorScaleBar"), false, grid);
    }

    // Panel label controls are now part of buildSplitChannelSection()

    private TitledPane buildInfoLabelSection() {
        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        int row = 0;

        showInfoLabelCheck = new CheckBox(resources.getString("rendered.label.showInfoLabel"));
        grid.add(showInfoLabelCheck, 0, row, 2, 1);
        row++;

        infoLabelTemplateLabel = new Label(resources.getString("rendered.label.infoLabelTemplate"));
        grid.add(infoLabelTemplateLabel, 0, row);
        infoLabelTemplateField = new TextField();
        infoLabelTemplateField.setPromptText("{imageName} - {pixelSize}");
        infoLabelTemplateField.textProperty().addListener((obs, oldVal, newVal) ->
                updateInfoLabelPreview());
        grid.add(infoLabelTemplateField, 1, row);
        row++;

        // Inline placeholder reference
        infoLabelPlaceholderRef = new Label(resources.getString("rendered.infoLabel.placeholderRef"));
        infoLabelPlaceholderRef.setWrapText(true);
        infoLabelPlaceholderRef.setStyle("-fx-font-size: 0.85em; -fx-text-fill: #555555; -fx-font-family: monospace;");
        grid.add(infoLabelPlaceholderRef, 0, row, 2, 1);
        row++;

        // Live preview
        infoLabelPreviewLabel = new Label();
        infoLabelPreviewLabel.setWrapText(true);
        infoLabelPreviewLabel.setStyle("-fx-font-size: 0.9em; -fx-background-color: #e8eef4; "
                + "-fx-padding: 6; -fx-background-radius: 4;");
        grid.add(infoLabelPreviewLabel, 0, row, 2, 1);
        row++;

        infoLabelPositionLabel = new Label(resources.getString("rendered.label.infoLabelPosition"));
        grid.add(infoLabelPositionLabel, 0, row);
        infoLabelPositionCombo = new ComboBox<>(FXCollections.observableArrayList(
                ScaleBarRenderer.Position.values()));
        infoLabelPositionCombo.setValue(ScaleBarRenderer.Position.LOWER_LEFT);
        infoLabelPositionCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ScaleBarRenderer.Position pos) {
                if (pos == null) return "";
                return switch (pos) {
                    case LOWER_RIGHT -> resources.getString("rendered.scaleBar.lowerRight");
                    case LOWER_LEFT -> resources.getString("rendered.scaleBar.lowerLeft");
                    case UPPER_RIGHT -> resources.getString("rendered.scaleBar.upperRight");
                    case UPPER_LEFT -> resources.getString("rendered.scaleBar.upperLeft");
                };
            }
            @Override
            public ScaleBarRenderer.Position fromString(String s) {
                return ScaleBarRenderer.Position.LOWER_LEFT;
            }
        });
        grid.add(infoLabelPositionCombo, 1, row);
        row++;

        infoLabelFontSizeLabel = new Label(resources.getString("rendered.label.infoLabelFontSize"));
        grid.add(infoLabelFontSizeLabel, 0, row);
        var ilFontSizeFactory = new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 72, 0);
        ilFontSizeFactory.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer value) {
                if (value == null || value == 0)
                    return resources.getString("rendered.scaleBar.fontSizeAuto");
                return String.valueOf(value);
            }
            @Override
            public Integer fromString(String string) {
                if (string == null || string.isBlank()
                        || string.equalsIgnoreCase(resources.getString("rendered.scaleBar.fontSizeAuto"))) {
                    return 0;
                }
                try { return Integer.parseInt(string); }
                catch (NumberFormatException e) { return 0; }
            }
        });
        infoLabelFontSizeSpinner = new Spinner<>(ilFontSizeFactory);
        infoLabelFontSizeSpinner.setEditable(true);
        grid.add(infoLabelFontSizeSpinner, 1, row);
        row++;

        infoLabelBoldCheck = new CheckBox(resources.getString("rendered.label.infoLabelBold"));
        grid.add(infoLabelBoldCheck, 0, row, 2, 1);
        row++;

        return SectionBuilder.createSection(
                resources.getString("rendered.section.infoLabel"), false, grid);
    }

    private TitledPane buildSplitChannelSection() {
        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        int row = 0;

        splitChannelsCheck = new CheckBox(resources.getString("rendered.label.splitChannels"));
        grid.add(splitChannelsCheck, 0, row, 2, 1);
        row++;

        splitChannelsGrayscaleCheck = new CheckBox(resources.getString("rendered.label.splitGrayscale"));
        splitChannelsGrayscaleCheck.setSelected(true);
        grid.add(splitChannelsGrayscaleCheck, 0, row, 2, 1);
        row++;

        splitChannelColorBorderCheck = new CheckBox(
                resources.getString("rendered.label.splitChannelColorBorder"));
        grid.add(splitChannelColorBorderCheck, 0, row, 2, 1);
        row++;

        channelColorLegendCheck = new CheckBox(
                resources.getString("rendered.label.channelColorLegend"));
        channelColorLegendCheck.setSelected(true);
        grid.add(channelColorLegendCheck, 0, row, 2, 1);
        row++;

        // Informational note about grayscale vs color
        splitChannelNoteLabel = new Label(resources.getString("rendered.label.splitChannelNote"));
        splitChannelNoteLabel.setWrapText(true);
        splitChannelNoteLabel.setMaxWidth(400);
        splitChannelNoteLabel.setStyle("-fx-font-size: 11; -fx-text-fill: #666666; -fx-font-style: italic;");
        grid.add(splitChannelNoteLabel, 0, row, 2, 1);
        row++;

        // Panel label (A, B, C...) for split-channel panels
        showPanelLabelCheck = new CheckBox(resources.getString("rendered.label.showPanelLabel"));
        grid.add(showPanelLabelCheck, 0, row, 2, 1);
        row++;

        panelLabelTextLabel = new Label(resources.getString("rendered.label.panelLabelText"));
        grid.add(panelLabelTextLabel, 0, row);
        panelLabelTextField = new TextField();
        panelLabelTextField.setPromptText("Auto (A, B, C...)");
        grid.add(panelLabelTextField, 1, row);
        row++;

        panelLabelPositionLabel = new Label(resources.getString("rendered.label.panelLabelPosition"));
        grid.add(panelLabelPositionLabel, 0, row);
        panelLabelPositionCombo = new ComboBox<>(FXCollections.observableArrayList(
                ScaleBarRenderer.Position.values()));
        panelLabelPositionCombo.setValue(ScaleBarRenderer.Position.UPPER_LEFT);
        panelLabelPositionCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ScaleBarRenderer.Position pos) {
                if (pos == null) return "";
                return switch (pos) {
                    case LOWER_RIGHT -> resources.getString("rendered.scaleBar.lowerRight");
                    case LOWER_LEFT -> resources.getString("rendered.scaleBar.lowerLeft");
                    case UPPER_RIGHT -> resources.getString("rendered.scaleBar.upperRight");
                    case UPPER_LEFT -> resources.getString("rendered.scaleBar.upperLeft");
                };
            }
            @Override
            public ScaleBarRenderer.Position fromString(String s) {
                return ScaleBarRenderer.Position.UPPER_LEFT;
            }
        });
        grid.add(panelLabelPositionCombo, 1, row);
        row++;

        panelLabelFontSizeLabel = new Label(resources.getString("rendered.label.panelLabelFontSize"));
        grid.add(panelLabelFontSizeLabel, 0, row);
        var plFontSizeFactory = new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 72, 0);
        plFontSizeFactory.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer value) {
                if (value == null || value == 0)
                    return resources.getString("rendered.scaleBar.fontSizeAuto");
                return String.valueOf(value);
            }
            @Override
            public Integer fromString(String string) {
                if (string == null || string.isBlank()
                        || string.equalsIgnoreCase(resources.getString("rendered.scaleBar.fontSizeAuto"))) {
                    return 0;
                }
                try { return Integer.parseInt(string); }
                catch (NumberFormatException e) { return 0; }
            }
        });
        panelLabelFontSizeSpinner = new Spinner<>(plFontSizeFactory);
        panelLabelFontSizeSpinner.setEditable(true);
        grid.add(panelLabelFontSizeSpinner, 1, row);
        row++;

        panelLabelBoldCheck = new CheckBox(resources.getString("rendered.label.panelLabelBold"));
        panelLabelBoldCheck.setSelected(true);
        grid.add(panelLabelBoldCheck, 0, row, 2, 1);
        row++;

        return SectionBuilder.createSection(
                resources.getString("rendered.section.splitChannel"), false, grid);
    }

    private void updateSplitChannelVisibility(boolean splitEnabled) {
        splitChannelsGrayscaleCheck.setVisible(splitEnabled);
        splitChannelsGrayscaleCheck.setManaged(splitEnabled);
        splitChannelColorBorderCheck.setVisible(splitEnabled);
        splitChannelColorBorderCheck.setManaged(splitEnabled);
        channelColorLegendCheck.setVisible(splitEnabled);
        channelColorLegendCheck.setManaged(splitEnabled);
        splitChannelNoteLabel.setVisible(splitEnabled);
        splitChannelNoteLabel.setManaged(splitEnabled);
        // Panel label only relevant when split channels is enabled
        showPanelLabelCheck.setVisible(splitEnabled);
        showPanelLabelCheck.setManaged(splitEnabled);
        if (!splitEnabled) {
            updatePanelLabelVisibility(false);
        } else {
            updatePanelLabelVisibility(showPanelLabelCheck.isSelected());
        }
    }

    private void updateModeVisibility(RenderedExportConfig.RenderMode mode) {
        boolean isClassifier = (mode == RenderedExportConfig.RenderMode.CLASSIFIER_OVERLAY);
        boolean isDensityMap = (mode == RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY);
        boolean isObjectOverlay = (mode == RenderedExportConfig.RenderMode.OBJECT_OVERLAY);

        // Individual controls within overlay source section
        classifierLabel.setVisible(isClassifier);
        classifierLabel.setManaged(isClassifier);
        classifierBox.setVisible(isClassifier);
        classifierBox.setManaged(isClassifier);

        densityMapLabel.setVisible(isDensityMap);
        densityMapLabel.setManaged(isDensityMap);
        densityMapBox.setVisible(isDensityMap);
        densityMapBox.setManaged(isDensityMap);
        colormapLabel.setVisible(isDensityMap);
        colormapLabel.setManaged(isDensityMap);
        colormapCombo.setVisible(isDensityMap);
        colormapCombo.setManaged(isDensityMap);

        // Toggle entire overlay source section
        overlaySourceSection.setVisible(!isObjectOverlay);
        overlaySourceSection.setManaged(!isObjectOverlay);

        // Deselect object overlays when switching to classifier/density map modes
        // (the overlay is the classifier/density map itself, not objects)
        if (isClassifier || isDensityMap) {
            includeAnnotationsCheck.setSelected(false);
            includeDetectionsCheck.setSelected(false);
        }

        // Color scale bar section only for density map mode
        colorScaleBarSection.setVisible(isDensityMap);
        colorScaleBarSection.setManaged(isDensityMap);
        if (!isDensityMap) {
            updateColorScaleBarVisibility(false);
        } else {
            updateColorScaleBarVisibility(showColorScaleBarCheck.isSelected());
        }
    }

    private void updateDisplaySettingsVisibility(DisplaySettingsMode mode) {
        boolean isPreset = (mode == DisplaySettingsMode.SAVED_PRESET);
        presetLabel.setVisible(isPreset);
        presetLabel.setManaged(isPreset);
        presetBox.setVisible(isPreset);
        presetBox.setManaged(isPreset);

        boolean isMatched = (mode == DisplaySettingsMode.GLOBAL_MATCHED);
        matchedPercentileLabel.setVisible(isMatched);
        matchedPercentileLabel.setManaged(isMatched);
        matchedPercentileSpinner.setVisible(isMatched);
        matchedPercentileSpinner.setManaged(isMatched);
    }

    private void updateScaleBarVisibility(boolean showScaleBar) {
        scaleBarPositionLabel.setVisible(showScaleBar);
        scaleBarPositionLabel.setManaged(showScaleBar);
        scaleBarPositionCombo.setVisible(showScaleBar);
        scaleBarPositionCombo.setManaged(showScaleBar);
        scaleBarColorLabel.setVisible(showScaleBar);
        scaleBarColorLabel.setManaged(showScaleBar);
        scaleBarColorPicker.setVisible(showScaleBar);
        scaleBarColorPicker.setManaged(showScaleBar);
        scaleBarFontSizeLabel.setVisible(showScaleBar);
        scaleBarFontSizeLabel.setManaged(showScaleBar);
        scaleBarFontSizeSpinner.setVisible(showScaleBar);
        scaleBarFontSizeSpinner.setManaged(showScaleBar);
        scaleBarBoldCheck.setVisible(showScaleBar);
        scaleBarBoldCheck.setManaged(showScaleBar);
        scaleBarBackgroundBoxCheck.setVisible(showScaleBar);
        scaleBarBackgroundBoxCheck.setManaged(showScaleBar);
        scaleBarColorHintLabel.setVisible(showScaleBar);
        scaleBarColorHintLabel.setManaged(showScaleBar);
    }

    private void updateColorScaleBarVisibility(boolean show) {
        colorScaleBarPositionLabel.setVisible(show);
        colorScaleBarPositionLabel.setManaged(show);
        colorScaleBarPositionCombo.setVisible(show);
        colorScaleBarPositionCombo.setManaged(show);
        colorScaleBarFontSizeLabel.setVisible(show);
        colorScaleBarFontSizeLabel.setManaged(show);
        colorScaleBarFontSizeSpinner.setVisible(show);
        colorScaleBarFontSizeSpinner.setManaged(show);
        colorScaleBarBoldCheck.setVisible(show);
        colorScaleBarBoldCheck.setManaged(show);
    }

    private void updatePanelLabelVisibility(boolean show) {
        panelLabelTextLabel.setVisible(show);
        panelLabelTextLabel.setManaged(show);
        panelLabelTextField.setVisible(show);
        panelLabelTextField.setManaged(show);
        panelLabelPositionLabel.setVisible(show);
        panelLabelPositionLabel.setManaged(show);
        panelLabelPositionCombo.setVisible(show);
        panelLabelPositionCombo.setManaged(show);
        panelLabelFontSizeLabel.setVisible(show);
        panelLabelFontSizeLabel.setManaged(show);
        panelLabelFontSizeSpinner.setVisible(show);
        panelLabelFontSizeSpinner.setManaged(show);
        panelLabelBoldCheck.setVisible(show);
        panelLabelBoldCheck.setManaged(show);
    }

    private void updateInfoLabelVisibility(boolean show) {
        infoLabelTemplateLabel.setVisible(show);
        infoLabelTemplateLabel.setManaged(show);
        infoLabelTemplateField.setVisible(show);
        infoLabelTemplateField.setManaged(show);
        infoLabelPlaceholderRef.setVisible(show);
        infoLabelPlaceholderRef.setManaged(show);
        infoLabelPreviewLabel.setVisible(show);
        infoLabelPreviewLabel.setManaged(show);
        infoLabelPositionLabel.setVisible(show);
        infoLabelPositionLabel.setManaged(show);
        infoLabelPositionCombo.setVisible(show);
        infoLabelPositionCombo.setManaged(show);
        infoLabelFontSizeLabel.setVisible(show);
        infoLabelFontSizeLabel.setManaged(show);
        infoLabelFontSizeSpinner.setVisible(show);
        infoLabelFontSizeSpinner.setManaged(show);
        infoLabelBoldCheck.setVisible(show);
        infoLabelBoldCheck.setManaged(show);
        if (show) updateInfoLabelPreview();
    }

    /**
     * Resolve the info label template against the current viewer image (if any)
     * and display a live preview. Highlights empty placeholders so the user
     * knows which variables have no value.
     */
    private void updateInfoLabelPreview() {
        String template = infoLabelTemplateField.getText();
        if (template == null || template.isBlank()) {
            infoLabelPreviewLabel.setText(resources.getString("rendered.infoLabel.preview.empty"));
            infoLabelPreviewLabel.setStyle("-fx-font-size: 0.9em; -fx-background-color: #e8eef4; "
                    + "-fx-padding: 6; -fx-background-radius: 4; -fx-text-fill: #888888;");
            return;
        }

        // Resolve against current image if available
        var imageData = qupath.getImageData();
        String resolved = template;
        var warnings = new java.util.ArrayList<String>();

        if (imageData != null) {
            var entry = qupath.getProject() != null ? qupath.getProject().getEntry(imageData) : null;
            String entryName = entry != null ? entry.getImageName() : "(current image)";
            resolved = resolved.replace("{imageName}", entryName);

            var server = imageData.getServer();
            var cal = server.getPixelCalibration();
            if (cal.hasPixelSizeMicrons()) {
                resolved = resolved.replace("{pixelSize}",
                        String.format("%.3f um/px", cal.getAveragedPixelSizeMicrons()));
            } else {
                resolved = resolved.replace("{pixelSize}", "uncalibrated");
                warnings.add("{pixelSize} -> uncalibrated");
            }
            resolved = resolved.replace("{width}", String.valueOf(server.getWidth()));
            resolved = resolved.replace("{height}", String.valueOf(server.getHeight()));
        } else {
            // No image open -- show example values
            resolved = resolved.replace("{imageName}", "(no image open)");
            resolved = resolved.replace("{pixelSize}", "0.500 um/px");
            resolved = resolved.replace("{width}", "1920");
            resolved = resolved.replace("{height}", "1080");
            warnings.add("No image open -- showing example values");
        }

        resolved = resolved.replace("{date}",
                java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
        resolved = resolved.replace("{time}",
                java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));

        // Classifier: check if one is actually set
        if (template.contains("{classifier}")) {
            // No easy way to get classifier name at config time; warn user
            resolved = resolved.replace("{classifier}", "");
            String trimmed = resolved.trim();
            if (!trimmed.equals(resolved.replace("{classifier}", "").trim())) {
                // placeholder was there but resolved to empty
            }
            warnings.add("{classifier} resolves at export time (empty if no classifier overlay selected)");
        }

        // Check for any unresolved placeholders
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\w+\\}").matcher(resolved);
        while (m.find()) {
            warnings.add(m.group() + " is not a recognized placeholder");
        }

        var sb = new StringBuilder();
        sb.append(resources.getString("rendered.infoLabel.preview.prefix")).append(" ");
        sb.append(resolved.trim().isEmpty() ? "(empty -- all placeholders resolved to blank)" : resolved);
        if (!warnings.isEmpty()) {
            sb.append("\n");
            for (String w : warnings) {
                sb.append("  * ").append(w).append("\n");
            }
        }

        infoLabelPreviewLabel.setText(sb.toString().trim());
        boolean hasWarnings = !warnings.isEmpty();
        infoLabelPreviewLabel.setStyle("-fx-font-size: 0.9em; -fx-background-color: "
                + (hasWarnings ? "#fff3cd" : "#e8eef4")
                + "; -fx-padding: 6; -fx-background-radius: 4;");
    }

    private void updateResolutionModeVisibility(String mode) {
        boolean isDpi = "By Target DPI".equals(mode);
        downsampleLabel.setVisible(!isDpi);
        downsampleLabel.setManaged(!isDpi);
        downsampleCombo.setVisible(!isDpi);
        downsampleCombo.setManaged(!isDpi);
        dpiLabel.setVisible(isDpi);
        dpiLabel.setManaged(isDpi);
        dpiSpinner.setVisible(isDpi);
        dpiSpinner.setManaged(isDpi);
        dpiNoteLabel.setVisible(isDpi);
        dpiNoteLabel.setManaged(isDpi);
    }

    private void updateFormatInfo(OutputFormat format) {
        if (format == null) {
            formatInfoLabel.setText("");
            formatInfoLabel.setVisible(false);
            formatInfoLabel.setManaged(false);
            return;
        }
        if (format == OutputFormat.SVG) {
            formatInfoLabel.setText(resources.getString("rendered.format.info.svg"));
            formatInfoLabel.setVisible(true);
            formatInfoLabel.setManaged(true);
        } else {
            formatInfoLabel.setText("");
            formatInfoLabel.setVisible(false);
            formatInfoLabel.setManaged(false);
        }
    }

    /**
     * When an overlay is enabled and format is the default PNG,
     * auto-switch to SVG for vector-sharp overlays.
     */
    private void maybeSwitchToSvg() {
        if (formatCombo.getValue() == OutputFormat.PNG) {
            formatCombo.setValue(OutputFormat.SVG);
        }
    }

    private void updateRegionTypeVisibility(RenderedExportConfig.RegionType regionType) {
        boolean isPerAnnotation = (regionType == RenderedExportConfig.RegionType.ALL_ANNOTATIONS);
        classificationFilterLabel.setVisible(isPerAnnotation);
        classificationFilterLabel.setManaged(isPerAnnotation);
        classificationFilterBox.setVisible(isPerAnnotation);
        classificationFilterBox.setManaged(isPerAnnotation);
        paddingLabel.setVisible(isPerAnnotation);
        paddingLabel.setManaged(isPerAnnotation);
        paddingSpinner.setVisible(isPerAnnotation);
        paddingSpinner.setManaged(isPerAnnotation);
        if (isPerAnnotation) {
            populateAnnotationClassifications();
        }
    }

    private void populateAnnotationClassifications() {
        // Clear checks BEFORE clearing items to avoid ControlsFX
        // IndexOutOfBoundsException when clearChecks fires change events
        // that reference items by index
        classificationCombo.getCheckModel().clearChecks();
        classificationCombo.getItems().clear();
        var project = qupath.getProject();
        if (project == null) return;

        // Scan all project images to collect actual annotation classes
        var classNames = new java.util.TreeSet<String>();
        boolean hasUnclassified = false;

        for (var entry : project.getImageList()) {
            try {
                var hierarchy = entry.readHierarchy();
                if (hierarchy == null) continue;
                for (var annotation : hierarchy.getAnnotationObjects()) {
                    var pathClass = annotation.getPathClass();
                    if (pathClass == null || pathClass == PathClass.NULL_CLASS) {
                        hasUnclassified = true;
                    } else {
                        classNames.add(pathClass.toString());
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not read hierarchy for {}: {}",
                        entry.getImageName(), e.getMessage());
            }
        }

        // Fall back to project class list if no annotations found
        if (classNames.isEmpty() && !hasUnclassified) {
            for (PathClass pc : project.getPathClasses()) {
                if (pc == null || pc == PathClass.NULL_CLASS) continue;
                classNames.add(pc.toString());
            }
            // Include Unclassified in fallback so the filter is not empty
            hasUnclassified = true;
        }

        if (hasUnclassified) {
            classificationCombo.getItems().add("Unclassified");
        }
        classificationCombo.getItems().addAll(classNames);

        // Check all items by default
        for (int i = 0; i < classificationCombo.getItems().size(); i++) {
            classificationCombo.getCheckModel().check(i);
        }
    }

    private void updatePreviewButtonState() {
        boolean hasImage = qupath.getViewer() != null
                && qupath.getViewer().getImageData() != null;
        previewButton.setDisable(!hasImage);
    }

    private void wireTooltips() {
        regionTypeCombo.setTooltip(createTooltip("tooltip.rendered.regionType"));
        classificationCombo.setTooltip(createTooltip("tooltip.rendered.classificationFilter"));
        paddingSpinner.setTooltip(createTooltip("tooltip.rendered.padding"));
        modeCombo.setTooltip(createTooltip("tooltip.rendered.mode"));
        displaySettingsCombo.setTooltip(createTooltip("tooltip.rendered.displaySettings"));
        presetNameCombo.setTooltip(createTooltip("tooltip.rendered.preset"));
        classifierCombo.setTooltip(createTooltip("tooltip.rendered.classifier"));
        opacitySlider.setTooltip(createTooltip("tooltip.rendered.opacity"));
        downsampleCombo.setTooltip(createTooltip("tooltip.rendered.downsample"));
        formatCombo.setTooltip(createTooltip("tooltip.rendered.format"));
        includeAnnotationsCheck.setTooltip(createTooltip("tooltip.rendered.includeAnnotations"));
        includeDetectionsCheck.setTooltip(createTooltip("tooltip.rendered.includeDetections"));
        fillAnnotationsCheck.setTooltip(createTooltip("tooltip.rendered.fillAnnotations"));
        showNamesCheck.setTooltip(createTooltip("tooltip.rendered.showNames"));
        showScaleBarCheck.setTooltip(createTooltip("tooltip.rendered.showScaleBar"));
        scaleBarPositionCombo.setTooltip(createTooltip("tooltip.rendered.scaleBarPosition"));
        scaleBarColorPicker.setTooltip(createTooltip("tooltip.rendered.scaleBarColor"));
        scaleBarFontSizeSpinner.setTooltip(createTooltip("tooltip.rendered.scaleBarFontSize"));
        scaleBarBoldCheck.setTooltip(createTooltip("tooltip.rendered.scaleBarBold"));
        scaleBarBackgroundBoxCheck.setTooltip(createTooltip("tooltip.rendered.scaleBarBackgroundBox"));
        previewButton.setTooltip(createTooltip("tooltip.rendered.previewImage"));
        densityMapCombo.setTooltip(createTooltip("tooltip.rendered.densityMap"));
        colormapCombo.setTooltip(createTooltip("tooltip.rendered.colormap"));
        showColorScaleBarCheck.setTooltip(createTooltip("tooltip.rendered.showColorScaleBar"));
        colorScaleBarPositionCombo.setTooltip(createTooltip("tooltip.rendered.colorScaleBarPosition"));
        colorScaleBarFontSizeSpinner.setTooltip(createTooltip("tooltip.rendered.colorScaleBarFontSize"));
        colorScaleBarBoldCheck.setTooltip(createTooltip("tooltip.rendered.colorScaleBarBold"));
        showPanelLabelCheck.setTooltip(createTooltip("tooltip.rendered.showPanelLabel"));
        panelLabelTextField.setTooltip(createTooltip("tooltip.rendered.panelLabelText"));
        panelLabelPositionCombo.setTooltip(createTooltip("tooltip.rendered.panelLabelPosition"));
        panelLabelFontSizeSpinner.setTooltip(createTooltip("tooltip.rendered.panelLabelFontSize"));
        panelLabelBoldCheck.setTooltip(createTooltip("tooltip.rendered.panelLabelBold"));
        splitChannelsCheck.setTooltip(createTooltip("tooltip.rendered.splitChannels"));
        splitChannelsGrayscaleCheck.setTooltip(createTooltip("tooltip.rendered.splitGrayscale"));
        splitChannelColorBorderCheck.setTooltip(createTooltip("tooltip.rendered.splitChannelColorBorder"));
        channelColorLegendCheck.setTooltip(createTooltip("tooltip.rendered.channelColorLegend"));
        matchedPercentileSpinner.setTooltip(createTooltip("tooltip.rendered.matchedPercentile"));
        resolutionModeCombo.setTooltip(createTooltip("tooltip.rendered.resolutionMode"));
        dpiSpinner.setTooltip(createTooltip("tooltip.rendered.targetDpi"));
        showInfoLabelCheck.setTooltip(createTooltip("tooltip.rendered.showInfoLabel"));
        infoLabelTemplateField.setTooltip(createTooltip("tooltip.rendered.infoLabelTemplate"));
        infoLabelPositionCombo.setTooltip(createTooltip("tooltip.rendered.infoLabelPosition"));
        infoLabelFontSizeSpinner.setTooltip(createTooltip("tooltip.rendered.infoLabelFontSize"));
        infoLabelBoldCheck.setTooltip(createTooltip("tooltip.rendered.infoLabelBold"));
    }

    private static Tooltip createTooltip(String key) {
        var tip = new Tooltip(resources.getString(key));
        tip.setWrapText(true);
        tip.setMaxWidth(400);
        tip.setShowDuration(javafx.util.Duration.seconds(30));
        return tip;
    }

    private void populateClassifiers() {
        classifierCombo.getItems().clear();

        // Check for active pixel classification overlay on the current viewer
        if (hasActivePixelOverlay()) {
            classifierCombo.getItems().add(ACTIVE_OVERLAY_DISPLAY_LABEL);
        }

        // List project-saved classifiers
        var project = qupath.getProject();
        if (project != null) {
            try {
                var names = project.getPixelClassifiers().getNames();
                classifierCombo.getItems().addAll(names);
            } catch (IOException e) {
                logger.error("Failed to load pixel classifier names", e);
            }
        }

        // Selection: prefer active overlay if present, else saved preference, else first
        if (classifierCombo.getItems().contains(ACTIVE_OVERLAY_DISPLAY_LABEL)) {
            classifierCombo.setValue(ACTIVE_OVERLAY_DISPLAY_LABEL);
        } else if (!classifierCombo.getItems().isEmpty()) {
            String saved = QuietPreferences.getRenderedClassifierName();
            if (saved != null && classifierCombo.getItems().contains(saved)) {
                classifierCombo.setValue(saved);
            } else {
                classifierCombo.getSelectionModel().selectFirst();
            }
        }
    }

    /**
     * Check whether the current viewer has an active pixel classification overlay.
     */
    private boolean hasActivePixelOverlay() {
        var viewer = qupath.getViewer();
        if (viewer == null) return false;
        var overlay = viewer.getCustomPixelLayerOverlay();
        return overlay instanceof PixelClassificationOverlay;
    }

    /**
     * Extract the {@link PixelClassifier} from the viewer's active pixel classification overlay.
     * Returns null if no suitable overlay is active.
     */
    @SuppressWarnings("unchecked")
    static PixelClassifier getActiveOverlayClassifier(QuPathGUI qupath) {
        var viewer = qupath.getViewer();
        if (viewer == null) return null;
        var overlay = viewer.getCustomPixelLayerOverlay();
        if (!(overlay instanceof PixelClassificationOverlay pco)) return null;
        var imageData = (ImageData<BufferedImage>) viewer.getImageData();
        if (imageData == null) return null;
        ImageServer<BufferedImage> server = pco.getPixelClassificationServer(imageData);
        if (server instanceof PixelClassificationImageServer pcis) {
            return pcis.getClassifier();
        }
        return null;
    }

    private void populatePresets() {
        presetNameCombo.getItems().clear();
        var project = qupath.getProject();
        if (project == null) return;
        try {
            var manager = DisplaySettingUtils.getResourcesForProject(project);
            var names = manager.getNames();
            presetNameCombo.getItems().addAll(names);
            if (!names.isEmpty()) {
                String saved = QuietPreferences.getRenderedDisplayPresetName();
                if (saved != null && names.contains(saved)) {
                    presetNameCombo.setValue(saved);
                } else {
                    presetNameCombo.getSelectionModel().selectFirst();
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load display setting presets", e);
        }
    }

    private void populateDensityMaps() {
        densityMapCombo.getItems().clear();
        var project = qupath.getProject();
        if (project == null) return;
        try {
            var resources = project.getResources(
                    DensityMaps.PROJECT_LOCATION, DensityMapBuilder.class, "json");
            var names = resources.getNames();
            densityMapCombo.getItems().addAll(names);
            if (!names.isEmpty()) {
                String saved = QuietPreferences.getRenderedDensityMapName();
                if (saved != null && names.contains(saved)) {
                    densityMapCombo.setValue(saved);
                } else {
                    densityMapCombo.getSelectionModel().selectFirst();
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load density map names", e);
        }
    }

    private void restorePreferences() {
        String savedRegionType = QuietPreferences.getRenderedRegionType();
        try {
            regionTypeCombo.setValue(RenderedExportConfig.RegionType.valueOf(savedRegionType));
        } catch (IllegalArgumentException e) { /* keep default */ }
        updateRegionTypeVisibility(regionTypeCombo.getValue());

        paddingSpinner.getValueFactory().setValue(QuietPreferences.getRenderedPadding());

        String savedMode = QuietPreferences.getRenderedMode();
        try {
            modeCombo.setValue(RenderedExportConfig.RenderMode.valueOf(savedMode));
        } catch (IllegalArgumentException e) { /* keep default */ }

        String savedDisplayMode = QuietPreferences.getRenderedDisplayMode();
        try {
            displaySettingsCombo.setValue(DisplaySettingsMode.valueOf(savedDisplayMode));
        } catch (IllegalArgumentException e) { /* keep default */ }
        updateDisplaySettingsVisibility(displaySettingsCombo.getValue());

        opacitySlider.setValue(QuietPreferences.getRenderedOpacity());
        opacityValueLabel.setText(String.format("%.2f", QuietPreferences.getRenderedOpacity()));

        double savedDs = QuietPreferences.getRenderedDownsample();
        if (savedDs >= 1.0) downsampleCombo.setValue(savedDs);

        String savedFormat = QuietPreferences.getRenderedFormat();
        try { formatCombo.setValue(OutputFormat.valueOf(savedFormat)); }
        catch (IllegalArgumentException e) { /* keep default */ }

        includeAnnotationsCheck.setSelected(QuietPreferences.isRenderedIncludeAnnotations());
        includeDetectionsCheck.setSelected(QuietPreferences.isRenderedIncludeDetections());
        fillAnnotationsCheck.setSelected(QuietPreferences.isRenderedFillAnnotations());
        showNamesCheck.setSelected(QuietPreferences.isRenderedShowNames());

        showScaleBarCheck.setSelected(QuietPreferences.isRenderedShowScaleBar());
        try {
            scaleBarPositionCombo.setValue(
                    ScaleBarRenderer.Position.valueOf(QuietPreferences.getRenderedScaleBarPosition()));
        } catch (IllegalArgumentException e) { /* keep default */ }

        // Restore scale bar color from hex string (handle legacy enum names gracefully)
        String savedColor = QuietPreferences.getRenderedScaleBarColor();
        scaleBarColorPicker.setValue(hexToFxColor(savedColor));

        scaleBarFontSizeSpinner.getValueFactory().setValue(QuietPreferences.getRenderedScaleBarFontSize());
        scaleBarBoldCheck.setSelected(QuietPreferences.isRenderedScaleBarBold());
        scaleBarBackgroundBoxCheck.setSelected(QuietPreferences.isRenderedScaleBarBackgroundBox());

        // Smart default: auto-detect scale bar color from project image types
        // Only applies when the color is still the default white (#FFFFFF)
        applySmartScaleBarColorDefault(savedColor);

        updateScaleBarVisibility(showScaleBarCheck.isSelected());

        // Density map preferences
        String savedColormap = QuietPreferences.getRenderedColormapName();
        if (savedColormap != null && !savedColormap.isBlank()) {
            colormapCombo.setValue(savedColormap);
        }

        // Color scale bar preferences
        showColorScaleBarCheck.setSelected(QuietPreferences.isRenderedShowColorScaleBar());
        try {
            colorScaleBarPositionCombo.setValue(
                    ScaleBarRenderer.Position.valueOf(QuietPreferences.getRenderedColorScaleBarPosition()));
        } catch (IllegalArgumentException e) { /* keep default */ }
        colorScaleBarFontSizeSpinner.getValueFactory().setValue(QuietPreferences.getRenderedColorScaleBarFontSize());
        colorScaleBarBoldCheck.setSelected(QuietPreferences.isRenderedColorScaleBarBold());
        updateColorScaleBarVisibility(showColorScaleBarCheck.isSelected());

        // Panel label preferences
        showPanelLabelCheck.setSelected(QuietPreferences.isRenderedShowPanelLabel());
        String savedPanelText = QuietPreferences.getRenderedPanelLabelText();
        if (savedPanelText != null && !savedPanelText.isBlank()) {
            panelLabelTextField.setText(savedPanelText);
        }
        try {
            panelLabelPositionCombo.setValue(
                    ScaleBarRenderer.Position.valueOf(QuietPreferences.getRenderedPanelLabelPosition()));
        } catch (IllegalArgumentException e) { /* keep default */ }
        panelLabelFontSizeSpinner.getValueFactory().setValue(QuietPreferences.getRenderedPanelLabelFontSize());
        panelLabelBoldCheck.setSelected(QuietPreferences.isRenderedPanelLabelBold());
        updatePanelLabelVisibility(showPanelLabelCheck.isSelected());

        // Split-channel preferences
        splitChannelsCheck.setSelected(QuietPreferences.isRenderedSplitChannels());
        splitChannelsGrayscaleCheck.setSelected(QuietPreferences.isRenderedSplitChannelsGrayscale());
        splitChannelColorBorderCheck.setSelected(QuietPreferences.isRenderedSplitChannelColorBorder());
        channelColorLegendCheck.setSelected(QuietPreferences.isRenderedChannelColorLegend());
        updateSplitChannelVisibility(splitChannelsCheck.isSelected());

        // Global matched preferences
        matchedPercentileSpinner.getValueFactory().setValue(
                QuietPreferences.getRenderedMatchedDisplayPercentile());

        // DPI preferences
        String savedResMode = QuietPreferences.getRenderedResolutionMode();
        if (savedResMode != null && !savedResMode.isBlank()) {
            resolutionModeCombo.setValue(savedResMode);
        }
        dpiSpinner.getValueFactory().setValue(QuietPreferences.getRenderedTargetDpi());
        updateResolutionModeVisibility(resolutionModeCombo.getValue());

        // Info label preferences
        showInfoLabelCheck.setSelected(QuietPreferences.isRenderedShowInfoLabel());
        String savedInfoTemplate = QuietPreferences.getRenderedInfoLabelTemplate();
        if (savedInfoTemplate != null && !savedInfoTemplate.isBlank()) {
            infoLabelTemplateField.setText(savedInfoTemplate);
        }
        try {
            infoLabelPositionCombo.setValue(
                    ScaleBarRenderer.Position.valueOf(QuietPreferences.getRenderedInfoLabelPosition()));
        } catch (IllegalArgumentException e) { /* keep default */ }
        infoLabelFontSizeSpinner.getValueFactory().setValue(QuietPreferences.getRenderedInfoLabelFontSize());
        infoLabelBoldCheck.setSelected(QuietPreferences.isRenderedInfoLabelBold());
        updateInfoLabelVisibility(showInfoLabelCheck.isSelected());

    }

    /**
     * Save current UI state to persistent preferences.
     */
    public void savePreferences() {
        var regionType = regionTypeCombo.getValue();
        if (regionType != null) QuietPreferences.setRenderedRegionType(regionType.name());
        QuietPreferences.setRenderedPadding(
                paddingSpinner.getValue() != null ? paddingSpinner.getValue() : 0);
        var mode = modeCombo.getValue();
        if (mode != null) QuietPreferences.setRenderedMode(mode.name());
        var dsMode = displaySettingsCombo.getValue();
        if (dsMode != null) QuietPreferences.setRenderedDisplayMode(dsMode.name());
        var preset = presetNameCombo.getValue();
        if (preset != null) QuietPreferences.setRenderedDisplayPresetName(preset);
        var classifier = classifierCombo.getValue();
        if (classifier != null) QuietPreferences.setRenderedClassifierName(classifier);
        QuietPreferences.setRenderedOpacity(opacitySlider.getValue());
        Double ds = downsampleCombo.getValue();
        if (ds != null) QuietPreferences.setRenderedDownsample(ds);
        var fmt = formatCombo.getValue();
        if (fmt != null) QuietPreferences.setRenderedFormat(fmt.name());
        QuietPreferences.setRenderedIncludeAnnotations(includeAnnotationsCheck.isSelected());
        QuietPreferences.setRenderedIncludeDetections(includeDetectionsCheck.isSelected());
        QuietPreferences.setRenderedFillAnnotations(fillAnnotationsCheck.isSelected());
        QuietPreferences.setRenderedShowNames(showNamesCheck.isSelected());
        QuietPreferences.setRenderedShowScaleBar(showScaleBarCheck.isSelected());
        var sbPos = scaleBarPositionCombo.getValue();
        if (sbPos != null) QuietPreferences.setRenderedScaleBarPosition(sbPos.name());
        QuietPreferences.setRenderedScaleBarColor(fxColorToHex(scaleBarColorPicker.getValue()));
        QuietPreferences.setRenderedScaleBarFontSize(
                scaleBarFontSizeSpinner.getValue() != null ? scaleBarFontSizeSpinner.getValue() : 0);
        QuietPreferences.setRenderedScaleBarBold(scaleBarBoldCheck.isSelected());
        QuietPreferences.setRenderedScaleBarBackgroundBox(scaleBarBackgroundBoxCheck.isSelected());
        var densityMap = densityMapCombo.getValue();
        if (densityMap != null) QuietPreferences.setRenderedDensityMapName(densityMap);
        var colormap = colormapCombo.getValue();
        if (colormap != null) QuietPreferences.setRenderedColormapName(colormap);
        QuietPreferences.setRenderedShowColorScaleBar(showColorScaleBarCheck.isSelected());
        var csPos = colorScaleBarPositionCombo.getValue();
        if (csPos != null) QuietPreferences.setRenderedColorScaleBarPosition(csPos.name());
        QuietPreferences.setRenderedColorScaleBarFontSize(
                colorScaleBarFontSizeSpinner.getValue() != null ? colorScaleBarFontSizeSpinner.getValue() : 0);
        QuietPreferences.setRenderedColorScaleBarBold(colorScaleBarBoldCheck.isSelected());
        QuietPreferences.setRenderedShowPanelLabel(showPanelLabelCheck.isSelected());
        QuietPreferences.setRenderedPanelLabelText(
                panelLabelTextField.getText() != null ? panelLabelTextField.getText() : "");
        var plPos = panelLabelPositionCombo.getValue();
        if (plPos != null) QuietPreferences.setRenderedPanelLabelPosition(plPos.name());
        QuietPreferences.setRenderedPanelLabelFontSize(
                panelLabelFontSizeSpinner.getValue() != null ? panelLabelFontSizeSpinner.getValue() : 0);
        QuietPreferences.setRenderedPanelLabelBold(panelLabelBoldCheck.isSelected());
        QuietPreferences.setRenderedSplitChannels(splitChannelsCheck.isSelected());
        QuietPreferences.setRenderedSplitChannelsGrayscale(splitChannelsGrayscaleCheck.isSelected());
        QuietPreferences.setRenderedSplitChannelColorBorder(splitChannelColorBorderCheck.isSelected());
        QuietPreferences.setRenderedChannelColorLegend(channelColorLegendCheck.isSelected());
        QuietPreferences.setRenderedMatchedDisplayPercentile(
                matchedPercentileSpinner.getValue() != null ? matchedPercentileSpinner.getValue() : 0.1);

        // DPI preferences
        String resMode = resolutionModeCombo.getValue();
        if (resMode != null) QuietPreferences.setRenderedResolutionMode(resMode);
        QuietPreferences.setRenderedTargetDpi(
                dpiSpinner.getValue() != null ? dpiSpinner.getValue() : 300);

        // Info label preferences
        QuietPreferences.setRenderedShowInfoLabel(showInfoLabelCheck.isSelected());
        QuietPreferences.setRenderedInfoLabelTemplate(
                infoLabelTemplateField.getText() != null ? infoLabelTemplateField.getText() : "");
        var ilPos = infoLabelPositionCombo.getValue();
        if (ilPos != null) QuietPreferences.setRenderedInfoLabelPosition(ilPos.name());
        QuietPreferences.setRenderedInfoLabelFontSize(
                infoLabelFontSizeSpinner.getValue() != null ? infoLabelFontSizeSpinner.getValue() : 0);
        QuietPreferences.setRenderedInfoLabelBold(infoLabelBoldCheck.isSelected());

    }

    /**
     * Build a RenderedExportConfig from current UI state.
     *
     * @param outputDir the output directory
     * @return the config, or null if validation fails
     */
    public RenderedExportConfig buildConfig(File outputDir) {
        var dsMode = displaySettingsCombo.getValue() != null
                ? displaySettingsCombo.getValue()
                : DisplaySettingsMode.PER_IMAGE_SAVED;

        // Collect selected classifications from CheckComboBox
        List<String> selectedClassifications = null;
        var currentRegionType = regionTypeCombo.getValue() != null
                ? regionTypeCombo.getValue()
                : RenderedExportConfig.RegionType.WHOLE_IMAGE;
        if (currentRegionType == RenderedExportConfig.RegionType.ALL_ANNOTATIONS) {
            selectedClassifications = new ArrayList<>(
                    classificationCombo.getCheckModel().getCheckedItems());
        }

        var builder = new RenderedExportConfig.Builder()
                .regionType(currentRegionType)
                .selectedClassifications(selectedClassifications)
                .paddingPixels(paddingSpinner.getValue() != null ? paddingSpinner.getValue() : 0)
                .renderMode(modeCombo.getValue())
                .displaySettingsMode(dsMode)
                .overlayOpacity(opacitySlider.getValue())
                .downsample(downsampleCombo.getValue() != null ? downsampleCombo.getValue() : 4.0)
                .format(formatCombo.getValue())
                .outputDirectory(outputDir)
                .includeAnnotations(includeAnnotationsCheck.isSelected())
                .includeDetections(includeDetectionsCheck.isSelected())
                .fillAnnotations(fillAnnotationsCheck.isSelected())
                .showNames(showNamesCheck.isSelected())
                .showScaleBar(showScaleBarCheck.isSelected())
                .scaleBarPosition(scaleBarPositionCombo.getValue() != null
                        ? scaleBarPositionCombo.getValue()
                        : ScaleBarRenderer.Position.LOWER_RIGHT)
                .scaleBarColorHex(fxColorToHex(scaleBarColorPicker.getValue()))
                .scaleBarFontSize(scaleBarFontSizeSpinner.getValue() != null
                        ? scaleBarFontSizeSpinner.getValue() : 0)
                .scaleBarBoldText(scaleBarBoldCheck.isSelected())
                .scaleBarBackgroundBox(scaleBarBackgroundBoxCheck.isSelected());

        // Panel label options
        String panelText = panelLabelTextField.getText();
        builder.showPanelLabel(showPanelLabelCheck.isSelected())
                .panelLabelText(panelText != null && !panelText.isBlank() ? panelText : null)
                .panelLabelPosition(panelLabelPositionCombo.getValue() != null
                        ? panelLabelPositionCombo.getValue()
                        : ScaleBarRenderer.Position.UPPER_LEFT)
                .panelLabelFontSize(panelLabelFontSizeSpinner.getValue() != null
                        ? panelLabelFontSizeSpinner.getValue() : 0)
                .panelLabelBold(panelLabelBoldCheck.isSelected());

        builder.densityMapName(densityMapCombo.getValue())
                .colormapName(colormapCombo.getValue())
                .showColorScaleBar(showColorScaleBarCheck.isSelected())
                .colorScaleBarPosition(colorScaleBarPositionCombo.getValue() != null
                        ? colorScaleBarPositionCombo.getValue()
                        : ScaleBarRenderer.Position.LOWER_RIGHT)
                .colorScaleBarFontSize(colorScaleBarFontSizeSpinner.getValue() != null
                        ? colorScaleBarFontSizeSpinner.getValue() : 0)
                .colorScaleBarBoldText(colorScaleBarBoldCheck.isSelected());

        // Split-channel options
        builder.splitChannels(splitChannelsCheck.isSelected())
                .splitChannelsGrayscale(splitChannelsGrayscaleCheck.isSelected())
                .splitChannelColorBorder(splitChannelColorBorderCheck.isSelected())
                .channelColorLegend(channelColorLegendCheck.isSelected())
                .matchedDisplayPercentile(
                        matchedPercentileSpinner.getValue() != null
                                ? matchedPercentileSpinner.getValue() : 0.1);

        // DPI control
        boolean isDpiMode = "By Target DPI".equals(resolutionModeCombo.getValue());
        builder.targetDpi(isDpiMode && dpiSpinner.getValue() != null ? dpiSpinner.getValue() : 0);

        // Info label options
        String infoTemplate = infoLabelTemplateField.getText();
        builder.showInfoLabel(showInfoLabelCheck.isSelected())
                .infoLabelTemplate(infoTemplate != null && !infoTemplate.isBlank() ? infoTemplate : null)
                .infoLabelPosition(infoLabelPositionCombo.getValue() != null
                        ? infoLabelPositionCombo.getValue()
                        : ScaleBarRenderer.Position.LOWER_LEFT)
                .infoLabelFontSize(infoLabelFontSizeSpinner.getValue() != null
                        ? infoLabelFontSizeSpinner.getValue() : 0)
                .infoLabelBold(infoLabelBoldCheck.isSelected());

        // Inset/zoom not exposed in UI (defaults to off)

        if (modeCombo.getValue() == RenderedExportConfig.RenderMode.CLASSIFIER_OVERLAY) {
            String selected = classifierCombo.getValue();
            if (ACTIVE_OVERLAY_DISPLAY_LABEL.equals(selected)) {
                builder.classifierName(ACTIVE_OVERLAY_SENTINEL);
            } else {
                builder.classifierName(selected);
            }
        }

        // Capture display settings based on selected mode
        if (dsMode == DisplaySettingsMode.CURRENT_VIEWER) {
            var viewer = qupath.getViewer();
            if (viewer != null && viewer.getImageDisplay() != null) {
                var settings = DisplaySettingUtils.displayToSettings(
                        viewer.getImageDisplay(), "export");
                builder.capturedDisplaySettings(settings);
            }
        } else if (dsMode == DisplaySettingsMode.SAVED_PRESET) {
            String presetName = presetNameCombo.getValue();
            builder.displayPresetName(presetName);
            if (presetName != null && !presetName.isBlank()) {
                resolvePresetSettings(presetName, builder);
            }
        }

        return builder.build();
    }

    private void resolvePresetSettings(String presetName, RenderedExportConfig.Builder builder) {
        var project = qupath.getProject();
        if (project == null) return;
        try {
            var manager = DisplaySettingUtils.getResourcesForProject(project);
            var settings = manager.get(presetName);
            if (settings != null) {
                builder.capturedDisplaySettings(settings);
            } else {
                logger.warn("Display preset not found: {}", presetName);
            }
        } catch (IOException e) {
            logger.error("Failed to load display preset: {}", presetName, e);
        }
    }

    /**
     * Handle the preview button click.
     * Renders the current image with current settings in a background thread
     * and shows the result in a popup window.
     */
    private void handlePreview() {
        var viewer = qupath.getViewer();
        if (viewer == null || viewer.getImageData() == null) {
            return;
        }

        @SuppressWarnings("unchecked")
        ImageData<BufferedImage> imageData = (ImageData<BufferedImage>) viewer.getImageData();

        // Build config with a temp dir placeholder (not used for preview)
        File tempDir = new File(System.getProperty("java.io.tmpdir"));
        RenderedExportConfig config;
        try {
            config = buildConfig(tempDir);
        } catch (Exception e) {
            logger.error("Failed to build config for preview", e);
            return;
        }

        // Load classifier or density map builder if needed
        PixelClassifier classifier = null;
        DensityMapBuilder densityBuilder = null;
        if (config.getRenderMode() == RenderedExportConfig.RenderMode.CLASSIFIER_OVERLAY) {
            String classifierName = config.overlays().classifierName();
            if (ACTIVE_OVERLAY_SENTINEL.equals(classifierName)) {
                classifier = getActiveOverlayClassifier(qupath);
                if (classifier == null) {
                    logger.warn("No active pixel classification overlay found for preview");
                    return;
                }
            } else if (classifierName != null && !classifierName.isBlank()) {
                var project = qupath.getProject();
                if (project != null) {
                    try {
                        classifier = project.getPixelClassifiers().get(classifierName);
                    } catch (Exception e) {
                        logger.error("Failed to load classifier for preview: {}", classifierName, e);
                        return;
                    }
                }
            } else {
                logger.warn("No classifier selected for preview");
                return;
            }
        } else if (config.getRenderMode() == RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY) {
            String dmName = config.overlays().densityMapName();
            if (dmName == null || dmName.isBlank()) {
                logger.warn("No density map selected for preview");
                return;
            }
            var project = qupath.getProject();
            if (project != null) {
                try {
                    var dmResources = project.getResources(
                            DensityMaps.PROJECT_LOCATION, DensityMapBuilder.class, "json");
                    densityBuilder = dmResources.get(dmName);
                } catch (Exception e) {
                    logger.error("Failed to load density map for preview: {}", dmName, e);
                    return;
                }
            }
        }

        // Show progress indicator
        var progressStage = new Stage();
        progressStage.setTitle("Rendering Preview...");
        var progressIndicator = new ProgressIndicator(-1);
        progressIndicator.setPrefSize(80, 80);
        var progressPane = new StackPane(progressIndicator);
        progressPane.setPadding(new Insets(20));
        progressStage.setScene(new Scene(progressPane));
        progressStage.setResizable(false);
        progressStage.show();

        final PixelClassifier finalClassifier = classifier;
        final DensityMapBuilder finalDensityBuilder = densityBuilder;

        Thread previewThread = new Thread(() -> {
            try {
                BufferedImage preview = RenderedImageExporter.renderPreview(
                        imageData, finalClassifier, finalDensityBuilder, config, 800);

                Platform.runLater(() -> {
                    progressStage.close();
                    showPreviewWindow(preview);
                });
            } catch (Exception e) {
                logger.error("Preview rendering failed", e);
                Platform.runLater(progressStage::close);
            }
        });
        previewThread.setDaemon(true);
        previewThread.setName("quiet-preview");
        previewThread.start();
    }

    private void showPreviewWindow(BufferedImage preview) {
        var fxImage = SwingFXUtils.toFXImage(preview, null);
        var imageView = new ImageView(fxImage);
        imageView.setPreserveRatio(true);
        imageView.setFitWidth(800);
        imageView.setFitHeight(600);

        var pane = new StackPane(imageView);
        pane.setPadding(new Insets(5));

        var stage = new Stage();
        stage.setTitle("Export Preview");
        stage.setScene(new Scene(pane));
        stage.setResizable(true);
        stage.show();
    }

    public String getClassifierName() {
        return classifierCombo.getValue();
    }

    public RenderedExportConfig.RenderMode getRenderMode() {
        return modeCombo.getValue();
    }

    public String getDensityMapName() {
        return densityMapCombo.getValue();
    }

    /**
     * Create a ListCell that shows a colormap gradient swatch next to the name.
     */
    private static ListCell<String> createColormapCell() {
        return new ListCell<>() {
            private final ImageView iv = new ImageView();
            @Override
            protected void updateItem(String name, boolean empty) {
                super.updateItem(name, empty);
                if (empty || name == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(name);
                var cm = ColorMaps.getColorMaps().get(name);
                if (cm != null) {
                    WritableImage swatch = new WritableImage(60, 12);
                    var pw = swatch.getPixelWriter();
                    for (int x = 0; x < 60; x++) {
                        int rgb = cm.getColor((double) x / 59, 0.0, 1.0);
                        int r = (rgb >> 16) & 0xFF;
                        int g = (rgb >> 8) & 0xFF;
                        int b = rgb & 0xFF;
                        var fxColor = javafx.scene.paint.Color.rgb(r, g, b);
                        for (int y = 0; y < 12; y++) {
                            pw.setColor(x, y, fxColor);
                        }
                    }
                    iv.setImage(swatch);
                    setGraphic(iv);
                } else {
                    setGraphic(null);
                }
            }
        };
    }

    /**
     * Convert a JavaFX Color to a hex string like "#RRGGBB".
     */
    private static String fxColorToHex(javafx.scene.paint.Color color) {
        if (color == null) return "#FFFFFF";
        int r = (int) (color.getRed() * 255);
        int g = (int) (color.getGreen() * 255);
        int b = (int) (color.getBlue() * 255);
        return String.format("#%02X%02X%02X", r, g, b);
    }

    /**
     * Convert a hex string to a JavaFX Color.
     * Handles legacy enum names ("WHITE", "BLACK") gracefully.
     */
    /**
     * Highlight config sections that have associated publication advice items.
     * Call this when navigating back to Step 2 from Step 3.
     *
     * @param items the current advice items (from ImageSelectionPane)
     */
    void highlightAdviceSections(List<qupath.ext.quiet.advice.AdviceItem> items) {
        // Clear all highlights first
        clearSectionHighlight(imageSettingsSection);
        clearSectionHighlight(scaleBarSection);
        clearSectionHighlight(splitChannelSection);
        clearSectionHighlight(colorScaleBarSection);
        clearSectionHighlight(infoLabelSection);

        if (items == null || items.isEmpty()) return;

        // Map config sections to TitledPanes
        var sectionMap = java.util.Map.of(
                "scaleBar", scaleBarSection,
                "format", imageSettingsSection,
                "displaySettings", imageSettingsSection,
                "splitChannel", splitChannelSection
        );

        // Find the worst severity per section
        var worstSeverity = new java.util.HashMap<TitledPane, qupath.ext.quiet.advice.AdviceSeverity>();
        for (var item : items) {
            if (item.configSection() == null) continue;
            var pane = sectionMap.get(item.configSection());
            if (pane == null) continue;

            var current = worstSeverity.get(pane);
            if (current == null || item.severity().ordinal() < current.ordinal()) {
                worstSeverity.put(pane, item.severity());
            }
        }

        // Apply colored left border to flagged sections
        for (var entry : worstSeverity.entrySet()) {
            String color = switch (entry.getValue()) {
                case ERROR -> "#e74c3c";
                case WARNING -> "#e67e22";
                case INFO -> "#3498db";
            };
            entry.getKey().setStyle(
                    "-fx-border-color: " + color + " transparent transparent transparent; "
                    + "-fx-border-width: 3 0 0 0;");
        }
    }

    private static void clearSectionHighlight(TitledPane pane) {
        if (pane != null) pane.setStyle("");
    }

    private static javafx.scene.paint.Color hexToFxColor(String hex) {
        if (hex == null || hex.isBlank()) return javafx.scene.paint.Color.WHITE;
        // Handle legacy enum names from old preferences
        if ("WHITE".equalsIgnoreCase(hex)) return javafx.scene.paint.Color.WHITE;
        if ("BLACK".equalsIgnoreCase(hex)) return javafx.scene.paint.Color.BLACK;
        try {
            return javafx.scene.paint.Color.web(hex);
        } catch (IllegalArgumentException e) {
            return javafx.scene.paint.Color.WHITE;
        }
    }

    /**
     * Auto-detect the best default scale bar color from project image types.
     * Only applies when the saved color is still the hardcoded default (#FFFFFF),
     * indicating the user has not explicitly chosen a color.
     * <p>
     * Brightfield images (white/light background) -> black scale bar.
     * Fluorescence images (dark background) -> white scale bar (keep default).
     */
    private void applySmartScaleBarColorDefault(String savedColor) {
        // Only override when at the default value -- respect explicit user choices
        if (savedColor != null && !savedColor.isBlank()
                && !"#FFFFFF".equalsIgnoreCase(savedColor)
                && !"WHITE".equalsIgnoreCase(savedColor)) {
            return;
        }

        var project = qupath.getProject();
        if (project == null) return;

        var entries = project.getImageList();
        if (entries.isEmpty()) return;

        // Sample up to 10 images to determine the dominant type
        int brightfieldCount = 0;
        int fluorescenceCount = 0;
        int sampled = 0;
        int maxSample = Math.min(entries.size(), 10);

        for (int i = 0; i < maxSample; i++) {
            try {
                var imageData = entries.get(i).readImageData();
                if (imageData == null) continue;
                var type = imageData.getImageType();
                if (type == null) continue;
                sampled++;
                String typeName = type.name();
                if (typeName.startsWith("BRIGHTFIELD")) {
                    brightfieldCount++;
                } else if ("FLUORESCENCE".equals(typeName)) {
                    fluorescenceCount++;
                }
            } catch (Exception e) {
                logger.debug("Could not read image type for smart default: {}",
                        e.getMessage());
            }
        }

        if (sampled == 0) return;

        // If majority is brightfield, default to black for visibility on white backgrounds
        if (brightfieldCount > fluorescenceCount) {
            scaleBarColorPicker.setValue(javafx.scene.paint.Color.BLACK);
            logger.debug("Smart default: set scale bar color to black (brightfield-dominant project)");
        }
        // Fluorescence-dominant or mixed: keep white (the default)
    }
}
