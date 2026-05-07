package qupath.ext.quiet.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
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

import qupath.ext.quiet.export.MaskExportConfig;
import qupath.ext.quiet.export.OutputFormat;
import qupath.ext.quiet.export.TiledExportConfig;
import qupath.ext.quiet.preferences.QuietPreferences;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.classes.PathClass;

/**
 * Step 2d of the export wizard: Configure tiled ML training export.
 */
public class TiledConfigPane extends VBox {

    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.quiet.ui.strings");

    private final QuPathGUI qupath;

    // Tile settings
    private Spinner<Integer> tileSizeSpinner;
    private Spinner<Integer> overlapSpinner;
    private ComboBox<Double> downsampleCombo;
    private ComboBox<OutputFormat> imageFormatCombo;

    // Label settings
    private CheckBox enableLabelsCheck;
    private ComboBox<OutputFormat> labelFormatCombo;
    private ComboBox<MaskExportConfig.MaskType> labelMaskTypeCombo;
    private ComboBox<MaskExportConfig.ObjectSource> labelObjectSourceCombo;
    private ListView<ClassificationItem> labelClassificationList;

    // Labels promoted for tooltip wiring
    private Label tileSizeLabel;
    private Label overlapLabel;
    private Label downsampleLabel;
    private Label imageFormatLabel;
    private Label labelFormatLabel;

    // Filter settings
    private Label parentFilterLabel;
    private ComboBox<TiledExportConfig.ParentObjectFilter> parentFilterCombo;
    private CheckBox annotatedOnlyCheck;
    private CheckBox exportJsonCheck;

    // Label section container for visibility toggling
    private VBox labelSection;

    // Label detail controls (hidden in simple mode)
    private Label labelMaskTypeLabel;
    private Label labelObjectSourceLabel;

    public TiledConfigPane(QuPathGUI qupath) {
        this.qupath = qupath;
        setSpacing(10);
        setPadding(new Insets(10));
        buildUI();
        populateClassifications();
        restorePreferences();
    }

    private void buildUI() {
        var header = new Label(resources.getString("wizard.step2.title") + " - " +
                resources.getString("category.tiled.title"));
        header.setFont(Font.font(null, FontWeight.BOLD, 14));

        // -- Tile grid --
        var tileGrid = new GridPane();
        tileGrid.setHgap(10);
        tileGrid.setVgap(8);

        int row = 0;

        // Tile size
        tileSizeLabel = new Label(resources.getString("tiled.label.tileSize"));
        tileGrid.add(tileSizeLabel, 0, row);
        tileSizeSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                32, 4096, 512, 64));
        tileSizeSpinner.setEditable(true);
        tileSizeSpinner.setPrefWidth(100);
        tileGrid.add(tileSizeSpinner, 1, row);
        row++;

        // Overlap
        overlapLabel = new Label(resources.getString("tiled.label.overlap"));
        tileGrid.add(overlapLabel, 0, row);
        overlapSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                0, 512, 0, 16));
        overlapSpinner.setEditable(true);
        overlapSpinner.setPrefWidth(100);
        tileGrid.add(overlapSpinner, 1, row);
        row++;

        // Downsample
        downsampleLabel = new Label(resources.getString("tiled.label.downsample"));
        tileGrid.add(downsampleLabel, 0, row);
        downsampleCombo = new ComboBox<>(FXCollections.observableArrayList(
                1.0, 2.0, 4.0, 8.0));
        downsampleCombo.setEditable(true);
        downsampleCombo.setValue(1.0);
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
                catch (NumberFormatException e) { return 1.0; }
            }
        });
        tileGrid.add(downsampleCombo, 1, row);
        row++;

        // Image format
        imageFormatLabel = new Label(resources.getString("tiled.label.imageFormat"));
        tileGrid.add(imageFormatLabel, 0, row);
        imageFormatCombo = new ComboBox<>(FXCollections.observableArrayList(
                OutputFormat.PNG, OutputFormat.TIFF, OutputFormat.JPEG));
        imageFormatCombo.setValue(OutputFormat.TIFF);
        tileGrid.add(imageFormatCombo, 1, row);
        row++;

        // Parent filter
        parentFilterLabel = new Label(resources.getString("tiled.label.parentFilter"));
        tileGrid.add(parentFilterLabel, 0, row);
        parentFilterCombo = new ComboBox<>(FXCollections.observableArrayList(
                TiledExportConfig.ParentObjectFilter.values()));
        parentFilterCombo.setValue(TiledExportConfig.ParentObjectFilter.ANNOTATIONS);
        parentFilterCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(TiledExportConfig.ParentObjectFilter filter) {
                if (filter == null) return "";
                return switch (filter) {
                    case ANNOTATIONS -> resources.getString("tiled.filter.annotations");
                    case TMA_CORES -> resources.getString("tiled.filter.tmaCores");
                    case ALL -> resources.getString("tiled.filter.all");
                };
            }
            @Override
            public TiledExportConfig.ParentObjectFilter fromString(String s) {
                return TiledExportConfig.ParentObjectFilter.ANNOTATIONS;
            }
        });
        tileGrid.add(parentFilterCombo, 1, row);
        row++;

        // Annotated only
        annotatedOnlyCheck = new CheckBox(resources.getString("tiled.label.annotatedOnly"));
        annotatedOnlyCheck.setSelected(true);
        tileGrid.add(annotatedOnlyCheck, 1, row);
        row++;

        // Export GeoJSON per tile
        exportJsonCheck = new CheckBox(resources.getString("tiled.label.exportJson"));
        exportJsonCheck.setSelected(false);
        tileGrid.add(exportJsonCheck, 1, row);
        row++;

        // -- Label section --
        enableLabelsCheck = new CheckBox(resources.getString("tiled.label.enableLabels"));
        enableLabelsCheck.setSelected(true);
        enableLabelsCheck.setFont(Font.font(null, FontWeight.BOLD, 12));

        var labelGrid = new GridPane();
        labelGrid.setHgap(10);
        labelGrid.setVgap(8);

        int lRow = 0;

        // Label format
        labelFormatLabel = new Label(resources.getString("tiled.label.labelFormat"));
        labelGrid.add(labelFormatLabel, 0, lRow);
        labelFormatCombo = new ComboBox<>(FXCollections.observableArrayList(
                OutputFormat.PNG, OutputFormat.TIFF));
        labelFormatCombo.setValue(OutputFormat.PNG);
        labelGrid.add(labelFormatCombo, 1, lRow);
        lRow++;

        // Label mask type
        labelMaskTypeLabel = new Label(resources.getString("tiled.label.labelMaskType"));
        labelGrid.add(labelMaskTypeLabel, 0, lRow);
        labelMaskTypeCombo = new ComboBox<>(FXCollections.observableArrayList(
                MaskExportConfig.MaskType.values()));
        labelMaskTypeCombo.setValue(MaskExportConfig.MaskType.BINARY);
        labelMaskTypeCombo.setConverter(new StringConverter<>() {
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
        labelGrid.add(labelMaskTypeCombo, 1, lRow);
        lRow++;

        // Label object source
        labelObjectSourceLabel = new Label(resources.getString("tiled.label.labelObjectSource"));
        labelGrid.add(labelObjectSourceLabel, 0, lRow);
        labelObjectSourceCombo = new ComboBox<>(FXCollections.observableArrayList(
                MaskExportConfig.ObjectSource.values()));
        labelObjectSourceCombo.setValue(MaskExportConfig.ObjectSource.ANNOTATIONS);
        labelObjectSourceCombo.setConverter(new StringConverter<>() {
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
        labelGrid.add(labelObjectSourceCombo, 1, lRow);
        lRow++;

        // Label classifications
        var classLabel = new Label(resources.getString("tiled.label.labelClassifications"));
        labelClassificationList = new ListView<>();
        labelClassificationList.setPrefHeight(100);
        labelClassificationList.setCellFactory(lv ->
                new CheckBoxListCell<>(ClassificationItem::selectedProperty));

        var selectAllBtn = new javafx.scene.control.Button(resources.getString("button.selectAll"));
        selectAllBtn.setOnAction(e -> labelClassificationList.getItems().forEach(it -> it.setSelected(true)));
        var selectNoneBtn = new javafx.scene.control.Button(resources.getString("button.deselectAll"));
        selectNoneBtn.setOnAction(e -> labelClassificationList.getItems().forEach(it -> it.setSelected(false)));
        var selectionBtns = new HBox(5, selectAllBtn, selectNoneBtn);

        var classBox = new VBox(5, classLabel, labelClassificationList, selectionBtns);
        VBox.setVgrow(labelClassificationList, Priority.ALWAYS);

        labelSection = new VBox(8, enableLabelsCheck, labelGrid, classBox);
        labelSection.setPadding(new Insets(5, 0, 0, 0));

        // Toggle label section visibility
        enableLabelsCheck.selectedProperty().addListener((obs, old, selected) -> {
            labelGrid.setDisable(!selected);
            classBox.setDisable(!selected);
        });

        // Hide classifications for INSTANCE type
        labelMaskTypeCombo.valueProperty().addListener((obs, old, newType) -> {
            boolean needsClasses = (newType != MaskExportConfig.MaskType.INSTANCE);
            classBox.setVisible(needsClasses);
            classBox.setManaged(needsClasses);
        });

        // -- Wrap sections in collapsible TitledPanes --
        var tileGridSection = SectionBuilder.createSection(
                resources.getString("tiled.section.tileGrid"), true, tileGrid);
        var labelMasksSection = SectionBuilder.createSection(
                resources.getString("tiled.section.labelMasks"), false, labelSection);

        getChildren().addAll(header, tileGridSection, labelMasksSection);
        VBox.setVgrow(labelMasksSection, Priority.ALWAYS);
        wireTooltips();
    }

    private void wireTooltips() {
        tileSizeSpinner.setTooltip(createTooltip("tooltip.tiled.tileSize"));
        tileSizeLabel.setTooltip(createTooltip("tooltip.tiled.tileSize"));
        overlapSpinner.setTooltip(createTooltip("tooltip.tiled.overlap"));
        overlapLabel.setTooltip(createTooltip("tooltip.tiled.overlap"));
        downsampleCombo.setTooltip(createTooltip("tooltip.tiled.downsample"));
        downsampleLabel.setTooltip(createTooltip("tooltip.tiled.downsample"));
        imageFormatCombo.setTooltip(createTooltip("tooltip.tiled.imageFormat"));
        imageFormatLabel.setTooltip(createTooltip("tooltip.tiled.imageFormat"));
        parentFilterCombo.setTooltip(createTooltip("tooltip.tiled.parentFilter"));
        parentFilterLabel.setTooltip(createTooltip("tooltip.tiled.parentFilter"));
        annotatedOnlyCheck.setTooltip(createTooltip("tooltip.tiled.annotatedOnly"));
        exportJsonCheck.setTooltip(createTooltip("tooltip.tiled.exportJson"));
        enableLabelsCheck.setTooltip(createTooltip("tooltip.tiled.enableLabels"));
        labelFormatCombo.setTooltip(createTooltip("tooltip.tiled.labelFormat"));
        labelFormatLabel.setTooltip(createTooltip("tooltip.tiled.labelFormat"));
        labelMaskTypeCombo.setTooltip(createTooltip("tooltip.tiled.labelMaskType"));
        labelMaskTypeLabel.setTooltip(createTooltip("tooltip.tiled.labelMaskType"));
        labelObjectSourceCombo.setTooltip(createTooltip("tooltip.tiled.labelObjectSource"));
        labelObjectSourceLabel.setTooltip(createTooltip("tooltip.tiled.labelObjectSource"));
        labelClassificationList.setTooltip(createTooltip("tooltip.tiled.labelClassifications"));
    }

    private static Tooltip createTooltip(String key) {
        var tip = new Tooltip(resources.getString(key));
        tip.setWrapText(true);
        tip.setMaxWidth(400);
        tip.setShowDuration(javafx.util.Duration.seconds(30));
        return tip;
    }

    private void populateClassifications() {
        labelClassificationList.getItems().clear();
        var project = qupath.getProject();
        if (project == null) return;

        var classes = project.getPathClasses();
        for (PathClass pc : classes) {
            if (pc == null || pc == PathClass.NULL_CLASS) continue;
            labelClassificationList.getItems().add(new ClassificationItem(pc.toString(), true));
        }
    }

    private void restorePreferences() {
        tileSizeSpinner.getValueFactory().setValue(QuietPreferences.getTiledTileSize());
        overlapSpinner.getValueFactory().setValue(QuietPreferences.getTiledOverlap());

        double savedDs = QuietPreferences.getTiledDownsample();
        if (savedDs >= 1.0) downsampleCombo.setValue(savedDs);

        String savedImgFmt = QuietPreferences.getTiledImageFormat();
        try { imageFormatCombo.setValue(OutputFormat.valueOf(savedImgFmt)); }
        catch (IllegalArgumentException e) { /* keep default */ }

        annotatedOnlyCheck.setSelected(QuietPreferences.isTiledAnnotatedOnly());
        exportJsonCheck.setSelected(QuietPreferences.isTiledExportJson());
        enableLabelsCheck.setSelected(QuietPreferences.isTiledEnableLabels());

        String savedLabelFmt = QuietPreferences.getTiledLabelFormat();
        try { labelFormatCombo.setValue(OutputFormat.valueOf(savedLabelFmt)); }
        catch (IllegalArgumentException e) { /* keep default */ }
    }

    /**
     * Save current UI state to persistent preferences.
     */
    public void savePreferences() {
        QuietPreferences.setTiledTileSize(tileSizeSpinner.getValue());
        QuietPreferences.setTiledOverlap(overlapSpinner.getValue());
        Double ds = downsampleCombo.getValue();
        if (ds != null) QuietPreferences.setTiledDownsample(ds);
        var imgFmt = imageFormatCombo.getValue();
        if (imgFmt != null) QuietPreferences.setTiledImageFormat(imgFmt.name());
        QuietPreferences.setTiledAnnotatedOnly(annotatedOnlyCheck.isSelected());
        QuietPreferences.setTiledExportJson(exportJsonCheck.isSelected());
        QuietPreferences.setTiledEnableLabels(enableLabelsCheck.isSelected());
        var labelFmt = labelFormatCombo.getValue();
        if (labelFmt != null) QuietPreferences.setTiledLabelFormat(labelFmt.name());
    }

    /**
     * Build a TiledExportConfig from current UI state.
     */
    public TiledExportConfig buildConfig(File outputDir) {
        var builder = new TiledExportConfig.Builder()
                .tileSize(tileSizeSpinner.getValue())
                .overlap(overlapSpinner.getValue())
                .downsample(downsampleCombo.getValue() != null ? downsampleCombo.getValue() : 1.0)
                .imageFormat(imageFormatCombo.getValue())
                .parentObjectFilter(parentFilterCombo.getValue())
                .annotatedTilesOnly(annotatedOnlyCheck.isSelected())
                .exportGeoJson(exportJsonCheck.isSelected())
                .outputDirectory(outputDir);

        // Label configuration
        if (enableLabelsCheck.isSelected()) {
            builder.labelFormat(labelFormatCombo.getValue());

            // Build a MaskExportConfig for label generation
            List<String> selectedClasses = new ArrayList<>();
            for (var item : labelClassificationList.getItems()) {
                if (item.isSelected()) {
                    selectedClasses.add(item.getClassName());
                }
            }

            var labelConfig = new MaskExportConfig.Builder()
                    .maskType(labelMaskTypeCombo.getValue())
                    .objectSource(labelObjectSourceCombo.getValue())
                    .selectedClassifications(selectedClasses)
                    .outputDirectory(outputDir) // required by builder validation
                    .build();
            builder.labeledServerConfig(labelConfig);
        }

        return builder.build();
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

    /**
     * Show or hide advanced controls for simple mode.
     * Hides parent filter, export GeoJSON, and detailed label config controls.
     */
    public void setSimpleMode(boolean simple) {
        boolean show = !simple;
        parentFilterLabel.setVisible(show);
        parentFilterLabel.setManaged(show);
        parentFilterCombo.setVisible(show);
        parentFilterCombo.setManaged(show);
        exportJsonCheck.setVisible(show);
        exportJsonCheck.setManaged(show);
        labelMaskTypeLabel.setVisible(show);
        labelMaskTypeLabel.setManaged(show);
        labelMaskTypeCombo.setVisible(show);
        labelMaskTypeCombo.setManaged(show);
        labelObjectSourceLabel.setVisible(show);
        labelObjectSourceLabel.setManaged(show);
        labelObjectSourceCombo.setVisible(show);
        labelObjectSourceCombo.setManaged(show);
    }
}
