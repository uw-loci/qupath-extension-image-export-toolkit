package qupath.ext.quiet.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.StringConverter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.quiet.export.ObjectCropConfig;
import qupath.ext.quiet.export.OutputFormat;
import qupath.ext.quiet.preferences.QuietPreferences;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Step 2 config pane for Object Crops export category.
 */
public class ObjectCropConfigPane extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(ObjectCropConfigPane.class);

    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.quiet.ui.strings");

    private final QuPathGUI qupath;

    private ComboBox<ObjectCropConfig.ObjectType> objectTypeCombo;
    private Spinner<Integer> cropSizeSpinner;
    private Spinner<Integer> paddingSpinner;
    private ComboBox<Double> downsampleCombo;
    private ComboBox<ObjectCropConfig.LabelFormat> labelFormatCombo;
    private ComboBox<OutputFormat> formatCombo;
    private ListView<TiledConfigPane.ClassificationItem> classificationList;
    private Button searchProjectBtn;

    // Labels promoted for tooltip wiring and simple mode toggling
    private Label objectTypeLabel;
    private Label cropSizeLabel;
    private Label paddingLabel;
    private Label downsampleLabel;
    private Label labelFormatLabel;
    private Label formatLabel;

    public ObjectCropConfigPane(QuPathGUI qupath) {
        this.qupath = qupath;
        setSpacing(10);
        setPadding(new Insets(10));
        buildUI();
        populateClassifications();
        restorePreferences();
    }

    private void buildUI() {
        var header = new Label(resources.getString("wizard.step2.title") + " - " +
                resources.getString("step2.objectCrops.title"));
        header.setFont(Font.font(null, FontWeight.BOLD, 14));

        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);

        int row = 0;

        // Object type
        objectTypeLabel = new Label(resources.getString("objectCrops.label.objectType"));
        grid.add(objectTypeLabel, 0, row);
        objectTypeCombo = new ComboBox<>(FXCollections.observableArrayList(
                ObjectCropConfig.ObjectType.values()));
        objectTypeCombo.setValue(ObjectCropConfig.ObjectType.DETECTIONS);
        objectTypeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ObjectCropConfig.ObjectType type) {
                if (type == null) return "";
                return switch (type) {
                    case DETECTIONS -> resources.getString("objectCrops.objectType.detections");
                    case CELLS -> resources.getString("objectCrops.objectType.cells");
                    case ANNOTATIONS -> resources.getString("objectCrops.objectType.annotations");
                    case ALL -> resources.getString("objectCrops.objectType.all");
                };
            }
            @Override
            public ObjectCropConfig.ObjectType fromString(String s) {
                return ObjectCropConfig.ObjectType.DETECTIONS;
            }
        });
        grid.add(objectTypeCombo, 1, row);
        row++;

        // Crop size
        cropSizeLabel = new Label(resources.getString("objectCrops.label.cropSize"));
        grid.add(cropSizeLabel, 0, row);
        cropSizeSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                16, 512, 64, 16));
        cropSizeSpinner.setEditable(true);
        cropSizeSpinner.setPrefWidth(100);
        grid.add(cropSizeSpinner, 1, row);
        row++;

        // Padding
        paddingLabel = new Label(resources.getString("objectCrops.label.cropPadding"));
        grid.add(paddingLabel, 0, row);
        paddingSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                0, 128, 0, 4));
        paddingSpinner.setEditable(true);
        paddingSpinner.setPrefWidth(100);
        grid.add(paddingSpinner, 1, row);
        row++;

        // Downsample
        downsampleLabel = new Label(resources.getString("objectCrops.label.cropDownsample"));
        grid.add(downsampleLabel, 0, row);
        downsampleCombo = new ComboBox<>(FXCollections.observableArrayList(
                1.0, 2.0, 4.0, 8.0));
        downsampleCombo.setEditable(true);
        downsampleCombo.setValue(1.0);
        downsampleCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Double value) {
                if (value == null) return "";
                return value == Math.floor(value)
                        ? String.valueOf(value.intValue()) : String.valueOf(value);
            }
            @Override
            public Double fromString(String string) {
                try { return Double.parseDouble(string); }
                catch (NumberFormatException e) { return 1.0; }
            }
        });
        grid.add(downsampleCombo, 1, row);
        row++;

        // Label format
        labelFormatLabel = new Label(resources.getString("objectCrops.label.labelFormat"));
        grid.add(labelFormatLabel, 0, row);
        labelFormatCombo = new ComboBox<>(FXCollections.observableArrayList(
                ObjectCropConfig.LabelFormat.values()));
        labelFormatCombo.setValue(ObjectCropConfig.LabelFormat.SUBDIRECTORY);
        labelFormatCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ObjectCropConfig.LabelFormat fmt) {
                if (fmt == null) return "";
                return switch (fmt) {
                    case SUBDIRECTORY -> resources.getString("objectCrops.labelFormat.subdirectory");
                    case FILENAME_PREFIX -> resources.getString("objectCrops.labelFormat.filenamePrefix");
                };
            }
            @Override
            public ObjectCropConfig.LabelFormat fromString(String s) {
                return ObjectCropConfig.LabelFormat.SUBDIRECTORY;
            }
        });
        grid.add(labelFormatCombo, 1, row);
        row++;

        // Output format
        formatLabel = new Label(resources.getString("objectCrops.label.format"));
        grid.add(formatLabel, 0, row);
        formatCombo = new ComboBox<>(FXCollections.observableArrayList(
                OutputFormat.PNG, OutputFormat.TIFF, OutputFormat.JPEG));
        formatCombo.setValue(OutputFormat.PNG);
        grid.add(formatCombo, 1, row);
        row++;

        // Section 1: Crop Settings
        var cropSettingsSection = SectionBuilder.createSection(
                resources.getString("objectCrops.section.cropSettings"), true, grid);

        // Section 2: Classifications
        var classLabel = new Label(resources.getString("objectCrops.label.classifications"));
        classificationList = new ListView<>();
        classificationList.setPrefHeight(120);
        classificationList.setCellFactory(lv ->
                new CheckBoxListCell<>(TiledConfigPane.ClassificationItem::selectedProperty));

        var selectAllBtn = new Button(resources.getString("button.selectAll"));
        selectAllBtn.setOnAction(e -> classificationList.getItems().forEach(it -> it.setSelected(true)));
        var selectNoneBtn = new Button(resources.getString("button.deselectAll"));
        selectNoneBtn.setOnAction(e -> classificationList.getItems().forEach(it -> it.setSelected(false)));
        searchProjectBtn = new Button(resources.getString("objectCrops.button.searchProject"));
        searchProjectBtn.setOnAction(e -> searchProjectForClasses());
        var selectionBtns = new HBox(5, selectAllBtn, selectNoneBtn, searchProjectBtn);

        var classBox = new VBox(5, classLabel, classificationList, selectionBtns);
        VBox.setVgrow(classificationList, Priority.ALWAYS);

        var classificationsSection = SectionBuilder.createSection(
                resources.getString("objectCrops.section.classifications"), true, classBox);

        getChildren().addAll(header, cropSettingsSection, classificationsSection);
        VBox.setVgrow(classificationsSection, Priority.ALWAYS);
        wireTooltips();

        objectTypeCombo.valueProperty().addListener((obs, oldVal, newVal) -> populateClassifications());
    }

    private void wireTooltips() {
        objectTypeCombo.setTooltip(createTooltip("tooltip.objectCrops.objectType"));
        objectTypeLabel.setTooltip(createTooltip("tooltip.objectCrops.objectType"));
        cropSizeSpinner.setTooltip(createTooltip("tooltip.objectCrops.cropSize"));
        cropSizeLabel.setTooltip(createTooltip("tooltip.objectCrops.cropSize"));
        paddingSpinner.setTooltip(createTooltip("tooltip.objectCrops.cropPadding"));
        paddingLabel.setTooltip(createTooltip("tooltip.objectCrops.cropPadding"));
        downsampleCombo.setTooltip(createTooltip("tooltip.objectCrops.cropDownsample"));
        downsampleLabel.setTooltip(createTooltip("tooltip.objectCrops.cropDownsample"));
        labelFormatCombo.setTooltip(createTooltip("tooltip.objectCrops.labelFormat"));
        labelFormatLabel.setTooltip(createTooltip("tooltip.objectCrops.labelFormat"));
        formatCombo.setTooltip(createTooltip("tooltip.objectCrops.format"));
        formatLabel.setTooltip(createTooltip("tooltip.objectCrops.format"));
        classificationList.setTooltip(createTooltip("tooltip.objectCrops.classifications"));
        searchProjectBtn.setTooltip(createTooltip("tooltip.objectCrops.searchProject"));
    }

    private static Tooltip createTooltip(String key) {
        var tip = new Tooltip(resources.getString(key));
        tip.setWrapText(true);
        tip.setMaxWidth(400);
        tip.setShowDuration(javafx.util.Duration.seconds(30));
        return tip;
    }

    private void populateClassifications() {
        populateClassifications(collectClassNamesForObjectType(objectTypeCombo.getValue()));
    }

    private void populateClassifications(Set<String> classNames) {
        Set<String> previouslySelected = new LinkedHashSet<>();
        for (var item : classificationList.getItems()) {
            if (item.isSelected()) previouslySelected.add(item.getClassName());
        }
        boolean hadPriorItems = !classificationList.getItems().isEmpty();
        classificationList.getItems().clear();

        for (String name : classNames) {
            boolean selected = hadPriorItems ? previouslySelected.contains(name) : true;
            classificationList.getItems().add(
                    new TiledConfigPane.ClassificationItem(name, selected));
        }
    }

    /**
     * Scan every image in the project (off the FX thread), collecting the set
     * of classifications present on objects of the currently selected type.
     * Slow for large projects since each image must be opened.
     */
    private void searchProjectForClasses() {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) return;

        ObjectCropConfig.ObjectType type = objectTypeCombo.getValue();
        if (type == null) return;

        String originalLabel = searchProjectBtn.getText();
        searchProjectBtn.setDisable(true);
        searchProjectBtn.setText(originalLabel + "...");

        CompletableFuture.supplyAsync(() -> scanProjectForClassNames(project, type))
                .whenComplete((names, err) -> Platform.runLater(() -> {
                    searchProjectBtn.setDisable(false);
                    searchProjectBtn.setText(originalLabel);
                    if (err != null) {
                        logger.error("Error scanning project for classes", err);
                        return;
                    }
                    // Union with current-image classes so nothing from the open image is dropped
                    Set<String> union = new LinkedHashSet<>(collectClassNamesForObjectType(type));
                    union.addAll(names);
                    populateClassifications(union);
                }));
    }

    private static Set<String> scanProjectForClassNames(
            Project<BufferedImage> project, ObjectCropConfig.ObjectType type) {
        Set<String> names = new LinkedHashSet<>();
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            try {
                ImageData<BufferedImage> imageData = entry.readImageData();
                collectClassNamesFromHierarchy(imageData.getHierarchy(), type, names);
            } catch (Exception e) {
                logger.warn("Skipping entry '{}' during class scan: {}",
                        entry.getImageName(), e.getMessage());
            }
        }
        return names;
    }

    /**
     * Collect the set of classification names present on objects of the given type
     * in the current image's hierarchy. Returns an empty set if no image is open.
     */
    private Set<String> collectClassNamesForObjectType(ObjectCropConfig.ObjectType type) {
        Set<String> names = new LinkedHashSet<>();
        var viewer = qupath.getViewer();
        if (viewer == null || viewer.getImageData() == null || type == null) return names;
        collectClassNamesFromHierarchy(viewer.getImageData().getHierarchy(), type, names);
        return names;
    }

    private static void collectClassNamesFromHierarchy(
            qupath.lib.objects.hierarchy.PathObjectHierarchy hierarchy,
            ObjectCropConfig.ObjectType type,
            Set<String> out) {
        if (hierarchy == null || type == null) return;
        Collection<? extends PathObject> objects = switch (type) {
            case DETECTIONS -> hierarchy.getDetectionObjects();
            case CELLS -> hierarchy.getCellObjects();
            case ANNOTATIONS -> hierarchy.getAnnotationObjects();
            case ALL -> {
                var combined = new ArrayList<PathObject>();
                combined.addAll(hierarchy.getDetectionObjects());
                hierarchy.getCellObjects().stream()
                        .filter(o -> !(o instanceof PathDetectionObject))
                        .forEach(combined::add);
                yield combined;
            }
        };
        for (PathObject obj : objects) {
            PathClass pc = obj.getPathClass();
            if (pc == null || pc == PathClass.NULL_CLASS) continue;
            out.add(pc.toString());
        }
    }

    private void restorePreferences() {
        String savedType = QuietPreferences.getObjectCropType();
        try { objectTypeCombo.setValue(ObjectCropConfig.ObjectType.valueOf(savedType)); }
        catch (IllegalArgumentException e) { /* keep default */ }

        cropSizeSpinner.getValueFactory().setValue(QuietPreferences.getObjectCropSize());
        paddingSpinner.getValueFactory().setValue(QuietPreferences.getObjectCropPadding());

        double savedDs = QuietPreferences.getObjectCropDownsample();
        if (savedDs >= 1.0) downsampleCombo.setValue(savedDs);

        String savedLabelFmt = QuietPreferences.getObjectCropLabelFormat();
        try { labelFormatCombo.setValue(ObjectCropConfig.LabelFormat.valueOf(savedLabelFmt)); }
        catch (IllegalArgumentException e) { /* keep default */ }

        String savedFmt = QuietPreferences.getObjectCropFormat();
        try { formatCombo.setValue(OutputFormat.valueOf(savedFmt)); }
        catch (IllegalArgumentException e) { /* keep default */ }
    }

    /**
     * Save current UI state to persistent preferences.
     */
    public void savePreferences() {
        var objType = objectTypeCombo.getValue();
        if (objType != null) QuietPreferences.setObjectCropType(objType.name());
        QuietPreferences.setObjectCropSize(cropSizeSpinner.getValue());
        QuietPreferences.setObjectCropPadding(paddingSpinner.getValue());
        Double ds = downsampleCombo.getValue();
        if (ds != null) QuietPreferences.setObjectCropDownsample(ds);
        var labelFmt = labelFormatCombo.getValue();
        if (labelFmt != null) QuietPreferences.setObjectCropLabelFormat(labelFmt.name());
        var fmt = formatCombo.getValue();
        if (fmt != null) QuietPreferences.setObjectCropFormat(fmt.name());
    }

    /**
     * Build an ObjectCropConfig from the current UI state.
     */
    public ObjectCropConfig buildConfig(File outputDir) {
        List<String> selectedClasses = new ArrayList<>();
        for (var item : classificationList.getItems()) {
            if (item.isSelected()) {
                selectedClasses.add(item.getClassName());
            }
        }

        return new ObjectCropConfig.Builder()
                .objectType(objectTypeCombo.getValue())
                .cropSize(cropSizeSpinner.getValue())
                .padding(paddingSpinner.getValue())
                .downsample(downsampleCombo.getValue() != null ? downsampleCombo.getValue() : 1.0)
                .labelFormat(labelFormatCombo.getValue())
                .format(formatCombo.getValue())
                .outputDirectory(outputDir)
                .selectedClasses(selectedClasses)
                .build();
    }

    /**
     * Show or hide advanced controls for simple mode.
     * Hides padding, downsample, and label format rows.
     */
    public void setSimpleMode(boolean simple) {
        boolean show = !simple;
        paddingLabel.setVisible(show);
        paddingLabel.setManaged(show);
        paddingSpinner.setVisible(show);
        paddingSpinner.setManaged(show);
        downsampleLabel.setVisible(show);
        downsampleLabel.setManaged(show);
        downsampleCombo.setVisible(show);
        downsampleCombo.setManaged(show);
        labelFormatLabel.setVisible(show);
        labelFormatLabel.setManaged(show);
        labelFormatCombo.setVisible(show);
        labelFormatCombo.setManaged(show);
    }
}
