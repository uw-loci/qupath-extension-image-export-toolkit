package qupath.ext.quiet.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.StringConverter;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import qupath.ext.quiet.export.MaskExportConfig;
import qupath.ext.quiet.export.OutputFormat;
import qupath.ext.quiet.preferences.QuietPreferences;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.classes.PathClass;

/**
 * Step 2b of the export wizard: Configure mask/label export.
 */
public class MaskConfigPane extends VBox {

    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.quiet.ui.strings");

    private final QuPathGUI qupath;

    private ComboBox<MaskExportConfig.MaskType> maskTypeCombo;
    private ListView<ClassificationItem> classificationList;
    private Spinner<Integer> backgroundLabelSpinner;
    private CheckBox enableBoundaryCheck;
    private Spinner<Integer> boundaryLabelSpinner;
    private Spinner<Integer> boundaryThicknessSpinner;
    private ComboBox<MaskExportConfig.ObjectSource> objectSourceCombo;
    private ComboBox<Double> downsampleCombo;
    private ComboBox<OutputFormat> formatCombo;
    private CheckBox grayscaleLutCheck;
    private CheckBox shuffleInstanceLabelsCheck;
    private CheckBox skipEmptyImagesCheck;

    // Controls needing visibility toggling
    private Label classificationsLabel;
    private VBox classificationsBox;
    private TitledPane classificationsSection;
    private Label grayscaleLutLabel;
    private Label boundaryThicknessLabel;

    public MaskConfigPane(QuPathGUI qupath) {
        this.qupath = qupath;
        setSpacing(10);
        setPadding(new Insets(10));
        buildUI();
        populateClassifications();
        restorePreferences();
    }

    private void buildUI() {
        var header = new Label(resources.getString("wizard.step2.title") + " - Label / Mask");
        header.setFont(Font.font(null, FontWeight.BOLD, 14));

        // --- Section 1: Mask Settings ---
        var settingsGrid = new GridPane();
        settingsGrid.setHgap(10);
        settingsGrid.setVgap(10);

        int row = 0;

        // Mask type
        settingsGrid.add(new Label(resources.getString("mask.label.type")), 0, row);
        maskTypeCombo = new ComboBox<>(FXCollections.observableArrayList(
                MaskExportConfig.MaskType.values()));
        maskTypeCombo.setValue(MaskExportConfig.MaskType.BINARY);
        maskTypeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(MaskExportConfig.MaskType type) {
                if (type == null) return "";
                return switch (type) {
                    case BINARY -> resources.getString("mask.type.binary");
                    case GRAYSCALE_LABELS -> resources.getString("mask.type.grayscaleLabels");
                    case COLORED -> resources.getString("mask.type.colored");
                    case INSTANCE -> resources.getString("mask.type.instance");
                    case MULTICHANNEL -> resources.getString("mask.type.multichannel");
                };
            }
            @Override
            public MaskExportConfig.MaskType fromString(String s) {
                return MaskExportConfig.MaskType.BINARY;
            }
        });
        settingsGrid.add(maskTypeCombo, 1, row);
        row++;

        // Object source
        settingsGrid.add(new Label(resources.getString("mask.label.objectSource")), 0, row);
        objectSourceCombo = new ComboBox<>(FXCollections.observableArrayList(
                MaskExportConfig.ObjectSource.values()));
        objectSourceCombo.setValue(MaskExportConfig.ObjectSource.ANNOTATIONS);
        objectSourceCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(MaskExportConfig.ObjectSource source) {
                if (source == null) return "";
                return switch (source) {
                    case ANNOTATIONS -> resources.getString("mask.source.annotations");
                    case DETECTIONS -> resources.getString("mask.source.detections");
                    case CELLS -> resources.getString("mask.source.cells");
                };
            }
            @Override
            public MaskExportConfig.ObjectSource fromString(String s) {
                return MaskExportConfig.ObjectSource.ANNOTATIONS;
            }
        });
        settingsGrid.add(objectSourceCombo, 1, row);
        row++;

        // Downsample
        settingsGrid.add(new Label(resources.getString("mask.label.downsample")), 0, row);
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
        settingsGrid.add(downsampleCombo, 1, row);
        row++;

        // Format
        settingsGrid.add(new Label(resources.getString("mask.label.format")), 0, row);
        // Exclude SVG (not applicable) and JPEG (lossy compression destroys label values)
        formatCombo = new ComboBox<>(FXCollections.observableArrayList(
                java.util.Arrays.stream(OutputFormat.values())
                        .filter(f -> f != OutputFormat.SVG && f != OutputFormat.JPEG)
                        .toList()));
        formatCombo.setValue(OutputFormat.PNG);
        settingsGrid.add(formatCombo, 1, row);

        var maskSettingsSection = SectionBuilder.createSection(
                resources.getString("mask.section.maskSettings"), true, settingsGrid);

        // --- Section 2: Label Options ---
        var labelGrid = new GridPane();
        labelGrid.setHgap(10);
        labelGrid.setVgap(10);

        int lRow = 0;

        // Background label
        labelGrid.add(new Label(resources.getString("mask.label.backgroundLabel")), 0, lRow);
        backgroundLabelSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                -1, 255, 0));
        backgroundLabelSpinner.setEditable(true);
        backgroundLabelSpinner.setPrefWidth(100);
        labelGrid.add(backgroundLabelSpinner, 1, lRow);
        lRow++;

        // Boundary label
        enableBoundaryCheck = new CheckBox(resources.getString("mask.label.enableBoundary"));
        labelGrid.add(enableBoundaryCheck, 0, lRow);
        boundaryLabelSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                -1, 255, -1));
        boundaryLabelSpinner.setEditable(true);
        boundaryLabelSpinner.setPrefWidth(100);
        boundaryLabelSpinner.disableProperty().bind(enableBoundaryCheck.selectedProperty().not());
        labelGrid.add(boundaryLabelSpinner, 1, lRow);
        lRow++;

        // Boundary thickness
        boundaryThicknessLabel = new Label(resources.getString("mask.label.boundaryThickness"));
        labelGrid.add(boundaryThicknessLabel, 0, lRow);
        boundaryThicknessSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                1, 20, 1));
        boundaryThicknessSpinner.setEditable(true);
        boundaryThicknessSpinner.setPrefWidth(100);
        boundaryThicknessSpinner.disableProperty().bind(enableBoundaryCheck.selectedProperty().not());
        labelGrid.add(boundaryThicknessSpinner, 1, lRow);
        lRow++;

        // Grayscale LUT
        grayscaleLutLabel = new Label();
        grayscaleLutCheck = new CheckBox(resources.getString("mask.label.grayscaleLut"));
        labelGrid.add(grayscaleLutCheck, 1, lRow);
        lRow++;

        // Shuffle instance labels
        shuffleInstanceLabelsCheck = new CheckBox(resources.getString("mask.label.shuffleInstance"));
        labelGrid.add(shuffleInstanceLabelsCheck, 1, lRow);
        lRow++;

        // Skip images without selected classes
        skipEmptyImagesCheck = new CheckBox(resources.getString("mask.label.skipEmpty"));
        labelGrid.add(skipEmptyImagesCheck, 1, lRow);

        var labelOptionsSection = SectionBuilder.createSection(
                resources.getString("mask.section.labelOptions"), true, labelGrid);

        // --- Section 3: Classifications ---
        classificationsLabel = new Label(resources.getString("mask.label.classifications"));
        classificationList = new ListView<>();
        classificationList.setPrefHeight(150);
        classificationList.setCellFactory(lv ->
                new CheckBoxListCell<>(ClassificationItem::selectedProperty));

        var selectAllBtn = new Button(resources.getString("button.selectAll"));
        selectAllBtn.setOnAction(e -> classificationList.getItems().forEach(i -> i.setSelected(true)));
        var selectNoneBtn = new Button(resources.getString("button.deselectAll"));
        selectNoneBtn.setOnAction(e -> classificationList.getItems().forEach(i -> i.setSelected(false)));
        var selectionBtns = new HBox(5, selectAllBtn, selectNoneBtn);

        classificationsBox = new VBox(5, classificationsLabel, classificationList, selectionBtns);
        VBox.setVgrow(classificationList, Priority.ALWAYS);

        classificationsSection = SectionBuilder.createSection(
                resources.getString("mask.section.classifications"), true, classificationsBox);

        // Dynamic visibility based on mask type
        maskTypeCombo.valueProperty().addListener((obs, old, newType) -> updateMaskTypeVisibility(newType));
        updateMaskTypeVisibility(MaskExportConfig.MaskType.BINARY);

        getChildren().addAll(header, maskSettingsSection, labelOptionsSection, classificationsSection);
        VBox.setVgrow(classificationsSection, Priority.ALWAYS);
        wireTooltips();
    }

    private void updateMaskTypeVisibility(MaskExportConfig.MaskType type) {
        boolean needsClassifications = (type != MaskExportConfig.MaskType.INSTANCE);
        classificationsSection.setVisible(needsClassifications);
        classificationsSection.setManaged(needsClassifications);

        boolean showGrayscale = (type == MaskExportConfig.MaskType.GRAYSCALE_LABELS);
        grayscaleLutCheck.setVisible(showGrayscale);
        grayscaleLutCheck.setManaged(showGrayscale);

        boolean showShuffle = (type == MaskExportConfig.MaskType.INSTANCE);
        shuffleInstanceLabelsCheck.setVisible(showShuffle);
        shuffleInstanceLabelsCheck.setManaged(showShuffle);

        skipEmptyImagesCheck.setVisible(needsClassifications);
        skipEmptyImagesCheck.setManaged(needsClassifications);
    }

    private void wireTooltips() {
        maskTypeCombo.setTooltip(createTooltip("tooltip.mask.type"));
        objectSourceCombo.setTooltip(createTooltip("tooltip.mask.objectSource"));
        backgroundLabelSpinner.setTooltip(createTooltip("tooltip.mask.backgroundLabel"));
        enableBoundaryCheck.setTooltip(createTooltip("tooltip.mask.enableBoundary"));
        boundaryLabelSpinner.setTooltip(createTooltip("tooltip.mask.boundaryLabel"));
        boundaryThicknessSpinner.setTooltip(createTooltip("tooltip.mask.boundaryThickness"));
        downsampleCombo.setTooltip(createTooltip("tooltip.mask.downsample"));
        formatCombo.setTooltip(createTooltip("tooltip.mask.format"));
        grayscaleLutCheck.setTooltip(createTooltip("tooltip.mask.grayscaleLut"));
        shuffleInstanceLabelsCheck.setTooltip(createTooltip("tooltip.mask.shuffleInstance"));
        skipEmptyImagesCheck.setTooltip(createTooltip("tooltip.mask.skipEmpty"));
        classificationList.setTooltip(createTooltip("tooltip.mask.classifications"));
    }

    private static Tooltip createTooltip(String key) {
        var tip = new Tooltip(resources.getString(key));
        tip.setWrapText(true);
        tip.setMaxWidth(400);
        tip.setShowDuration(javafx.util.Duration.seconds(30));
        return tip;
    }

    private void populateClassifications() {
        classificationList.getItems().clear();
        var project = qupath.getProject();
        if (project == null) return;

        var classes = project.getPathClasses();
        for (PathClass pc : classes) {
            if (pc == null || pc == PathClass.NULL_CLASS) continue;
            classificationList.getItems().add(new ClassificationItem(pc.toString(), true));
        }
    }

    private void restorePreferences() {
        String savedType = QuietPreferences.getMaskType();
        try { maskTypeCombo.setValue(MaskExportConfig.MaskType.valueOf(savedType)); }
        catch (IllegalArgumentException e) { /* keep default */ }

        backgroundLabelSpinner.getValueFactory().setValue(QuietPreferences.getMaskBackgroundLabel());
        enableBoundaryCheck.setSelected(QuietPreferences.isMaskEnableBoundary());
        boundaryLabelSpinner.getValueFactory().setValue(QuietPreferences.getMaskBoundaryLabel());

        String savedSource = QuietPreferences.getMaskObjectSource();
        try { objectSourceCombo.setValue(MaskExportConfig.ObjectSource.valueOf(savedSource)); }
        catch (IllegalArgumentException e) { /* keep default */ }

        double savedDs = QuietPreferences.getMaskDownsample();
        if (savedDs >= 1.0) downsampleCombo.setValue(savedDs);

        String savedFormat = QuietPreferences.getMaskFormat();
        try {
            OutputFormat restored = OutputFormat.valueOf(savedFormat);
            // JPEG is no longer valid for masks; fall back to PNG
            if (restored == OutputFormat.JPEG) restored = OutputFormat.PNG;
            formatCombo.setValue(restored);
        } catch (IllegalArgumentException e) { /* keep default */ }

        grayscaleLutCheck.setSelected(QuietPreferences.isMaskGrayscaleLut());
        shuffleInstanceLabelsCheck.setSelected(QuietPreferences.isMaskShuffleInstanceLabels());
        boundaryThicknessSpinner.getValueFactory().setValue(QuietPreferences.getMaskBoundaryThickness());
        skipEmptyImagesCheck.setSelected(QuietPreferences.isMaskSkipEmptyImages());
    }

    /**
     * Save current UI state to persistent preferences.
     */
    public void savePreferences() {
        var type = maskTypeCombo.getValue();
        if (type != null) QuietPreferences.setMaskType(type.name());
        QuietPreferences.setMaskBackgroundLabel(backgroundLabelSpinner.getValue());
        QuietPreferences.setMaskEnableBoundary(enableBoundaryCheck.isSelected());
        QuietPreferences.setMaskBoundaryLabel(boundaryLabelSpinner.getValue());
        var source = objectSourceCombo.getValue();
        if (source != null) QuietPreferences.setMaskObjectSource(source.name());
        Double ds = downsampleCombo.getValue();
        if (ds != null) QuietPreferences.setMaskDownsample(ds);
        var fmt = formatCombo.getValue();
        if (fmt != null) QuietPreferences.setMaskFormat(fmt.name());
        QuietPreferences.setMaskGrayscaleLut(grayscaleLutCheck.isSelected());
        QuietPreferences.setMaskShuffleInstanceLabels(shuffleInstanceLabelsCheck.isSelected());
        QuietPreferences.setMaskBoundaryThickness(boundaryThicknessSpinner.getValue());
        QuietPreferences.setMaskSkipEmptyImages(skipEmptyImagesCheck.isSelected());
    }

    /**
     * Build a MaskExportConfig from current UI state.
     */
    public MaskExportConfig buildConfig(File outputDir) {
        List<String> selectedClasses = new ArrayList<>();
        for (var item : classificationList.getItems()) {
            if (item.isSelected()) {
                selectedClasses.add(item.getClassName());
            }
        }

        return new MaskExportConfig.Builder()
                .maskType(maskTypeCombo.getValue())
                .selectedClassifications(selectedClasses)
                .backgroundLabel(backgroundLabelSpinner.getValue())
                .enableBoundary(enableBoundaryCheck.isSelected())
                .boundaryLabel(boundaryLabelSpinner.getValue())
                .boundaryThickness(boundaryThicknessSpinner.getValue())
                .objectSource(objectSourceCombo.getValue())
                .downsample(downsampleCombo.getValue() != null ? downsampleCombo.getValue() : 4.0)
                .format(formatCombo.getValue())
                .grayscaleLut(grayscaleLutCheck.isSelected())
                .shuffleInstanceLabels(shuffleInstanceLabelsCheck.isSelected())
                .skipEmptyImages(skipEmptyImagesCheck.isSelected())
                .outputDirectory(outputDir)
                .build();
    }

    /**
     * Wrapper for classification names with selection state.
     */
    public static class ClassificationItem {
        private final String className;
        private final BooleanProperty selected;

        public ClassificationItem(String className, boolean selected) {
            this.className = className;
            this.selected = new SimpleBooleanProperty(selected);
        }

        public String getClassName() { return className; }
        public boolean isSelected() { return selected.get(); }
        public void setSelected(boolean value) { selected.set(value); }
        public BooleanProperty selectedProperty() { return selected; }

        @Override
        public String toString() { return className; }
    }
}
