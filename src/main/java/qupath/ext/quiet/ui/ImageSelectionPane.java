package qupath.ext.quiet.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.quiet.advice.AdviceItem;
import qupath.ext.quiet.advice.AdviceSeverity;
import qupath.ext.quiet.advice.ImageContext;
import qupath.ext.quiet.advice.PublicationAdviceChecker;
import qupath.ext.quiet.export.ExportCategory;
import qupath.ext.quiet.preferences.QuietPreferences;
import qupath.lib.common.ColorTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Step 3 of the export wizard: Select images, output directory, and run export.
 * <p>
 * Shared across all export categories.
 */
public class ImageSelectionPane extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(ImageSelectionPane.class);
    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.quiet.ui.strings");

    /** Maximum number of images to scan for advice metadata. */
    private static final int ADVICE_IMAGE_SCAN_LIMIT = 10;

    private final QuPathGUI qupath;
    private final Stage ownerStage;

    private ExportCategory currentCategory;
    private TextField outputDirField;
    private ListView<ImageEntryItem> imageListView;
    private ObservableList<ImageEntryItem> masterItems;
    private FilteredList<ImageEntryItem> filteredItems;
    private TextField filterField;
    private Label imageCountLabel;

    // Panel / Montage mode extras (created lazily; null in standard mode)
    private boolean panelMode;
    private Label stepHeader;
    private Label panelBanner;
    private TitledPane filterFacetSection;
    private ComboBox<String> imageTypeCombo;
    private ComboBox<String> metadataKeyCombo;
    private TextField metadataValueField;
    private Label orderHintLabel;
    private Label gateMessageLabel;
    private boolean facetCacheBuilt;
    private Runnable selectionChangeListener;
    private TextField prefixField;
    private TextField suffixField;
    private Label filenamePreviewLabel;
    private CheckBox addToWorkflowCheck;
    private CheckBox exportGeoJsonCheck;

    // Containers promoted for simple mode toggling
    private HBox prefixSuffixRow;
    private HBox previewRow;
    private HBox scriptBox;
    private PublicationAdvicePane advicePane;
    private javafx.scene.control.Button adviceButton;
    private ProgressBar progressBar;
    private Label statusLabel;

    public ImageSelectionPane(QuPathGUI qupath, Stage ownerStage) {
        this.qupath = qupath;
        this.ownerStage = ownerStage;
        setSpacing(10);
        setPadding(new Insets(10));
        buildUI();
        populateImageList();
    }

    private void buildUI() {
        stepHeader = new Label(resources.getString("wizard.step3.title"));
        stepHeader.setFont(Font.font(null, FontWeight.BOLD, 14));
        var header = stepHeader;

        // Output directory
        var dirLabel = new Label(resources.getString("step3.label.outputDir"));
        outputDirField = new TextField();
        outputDirField.setPromptText("Select output folder...");
        outputDirField.setTooltip(createTooltip("tooltip.step3.outputDir"));
        HBox.setHgrow(outputDirField, Priority.ALWAYS);

        var browseButton = new Button(resources.getString("button.browse"));
        browseButton.setOnAction(e -> browseOutputDirectory());
        browseButton.setTooltip(createTooltip("tooltip.step3.browse"));

        var defaultButton = new Button(resources.getString("button.useProjectDefault"));
        defaultButton.setOnAction(e -> setProjectDefaultDir());
        defaultButton.setTooltip(createTooltip("tooltip.step3.useProjectDefault"));

        var dirBox = new HBox(5, outputDirField, browseButton, defaultButton);
        HBox.setHgrow(outputDirField, Priority.ALWAYS);

        // Filename prefix/suffix
        var prefixLabel = new Label(resources.getString("step3.label.filenamePrefix"));
        prefixLabel.setTooltip(createTooltip("tooltip.step3.filenamePrefix"));
        prefixField = new TextField(QuietPreferences.getFilenamePrefix());
        prefixField.setPromptText("e.g. batch1_");
        prefixField.setTooltip(createTooltip("tooltip.step3.filenamePrefix"));
        HBox.setHgrow(prefixField, Priority.ALWAYS);

        var suffixLabel = new Label(resources.getString("step3.label.filenameSuffix"));
        suffixLabel.setTooltip(createTooltip("tooltip.step3.filenameSuffix"));
        suffixField = new TextField(QuietPreferences.getFilenameSuffix());
        suffixField.setPromptText("e.g. _masks");
        suffixField.setTooltip(createTooltip("tooltip.step3.filenameSuffix"));
        HBox.setHgrow(suffixField, Priority.ALWAYS);

        var prefixBox = new HBox(5, prefixLabel, prefixField);
        HBox.setHgrow(prefixField, Priority.ALWAYS);
        prefixBox.setAlignment(Pos.CENTER_LEFT);

        var suffixBox = new HBox(5, suffixLabel, suffixField);
        HBox.setHgrow(suffixField, Priority.ALWAYS);
        suffixBox.setAlignment(Pos.CENTER_LEFT);

        prefixSuffixRow = new HBox(15, prefixBox, suffixBox);
        HBox.setHgrow(prefixBox, Priority.ALWAYS);
        HBox.setHgrow(suffixBox, Priority.ALWAYS);

        // Filename preview
        var previewTitleLabel = new Label(resources.getString("step3.label.filenamePreview"));
        filenamePreviewLabel = new Label();
        filenamePreviewLabel.setTextFill(Color.GRAY);
        filenamePreviewLabel.setFont(Font.font("monospace", 11));
        previewRow = new HBox(5, previewTitleLabel, filenamePreviewLabel);
        previewRow.setAlignment(Pos.CENTER_LEFT);

        // Update preview when prefix/suffix change
        prefixField.textProperty().addListener((obs, o, n) -> updateFilenamePreview());
        suffixField.textProperty().addListener((obs, o, n) -> updateFilenamePreview());

        // Image list header with selection buttons
        var imagesLabel = new Label(resources.getString("step3.label.imagesToExport"));

        var selectAllButton = new Button(resources.getString("button.selectAll"));
        selectAllButton.setOnAction(e -> setAllImagesSelected(true));
        var deselectAllButton = new Button(resources.getString("button.deselectAll"));
        deselectAllButton.setOnAction(e -> setAllImagesSelected(false));

        var selectVisibleButton = new Button(resources.getString("button.selectVisible"));
        selectVisibleButton.setOnAction(e -> setVisibleImagesSelected(true));
        selectVisibleButton.setTooltip(createTooltip("tooltip.step3.selectVisible"));
        var deselectVisibleButton = new Button(resources.getString("button.deselectVisible"));
        deselectVisibleButton.setOnAction(e -> setVisibleImagesSelected(false));
        deselectVisibleButton.setTooltip(createTooltip("tooltip.step3.deselectVisible"));

        imageCountLabel = new Label();

        var selectionButtons = new HBox(5, selectAllButton, deselectAllButton,
                selectVisibleButton, deselectVisibleButton, imageCountLabel);
        selectionButtons.setAlignment(Pos.CENTER_LEFT);

        var imagesHeader = new HBox(10, imagesLabel, selectionButtons);
        imagesHeader.setAlignment(Pos.CENTER_LEFT);

        // Filter field
        filterField = new TextField();
        filterField.setPromptText(resources.getString("step3.filter.prompt"));
        filterField.setTooltip(createTooltip("tooltip.step3.filter"));

        imageListView = new ListView<>();
        imageListView.setPrefHeight(200);
        imageListView.setCellFactory(lv ->
                new CheckBoxListCell<>(ImageEntryItem::selectedProperty));
        VBox.setVgrow(imageListView, Priority.ALWAYS);

        // Script actions
        var copyScriptButton = new Button(resources.getString("button.copyScript"));
        copyScriptButton.setOnAction(e -> {
            if (scriptCopyHandler != null) scriptCopyHandler.run();
        });
        copyScriptButton.setTooltip(createTooltip("tooltip.step3.copyScript"));
        var saveScriptButton = new Button(resources.getString("button.saveScript"));
        saveScriptButton.setOnAction(e -> {
            if (scriptSaveHandler != null) scriptSaveHandler.run();
        });
        saveScriptButton.setTooltip(createTooltip("tooltip.step3.saveScript"));
        scriptBox = new HBox(10, copyScriptButton, saveScriptButton);

        // Workflow checkbox
        addToWorkflowCheck = new CheckBox(resources.getString("step3.label.addToWorkflow"));
        addToWorkflowCheck.setSelected(QuietPreferences.isAddToWorkflow());
        addToWorkflowCheck.setTooltip(createTooltip("tooltip.step3.addToWorkflow"));

        // GeoJSON checkbox
        exportGeoJsonCheck = new CheckBox(resources.getString("step3.label.exportGeoJson"));
        exportGeoJsonCheck.setSelected(QuietPreferences.isExportGeoJson());
        exportGeoJsonCheck.setTooltip(createTooltip("tooltip.step3.exportGeoJson"));

        // Publication advice
        advicePane = new PublicationAdvicePane();

        adviceButton = new javafx.scene.control.Button(resources.getString("advice.button.show"));
        adviceButton.setMaxWidth(Double.MAX_VALUE);
        adviceButton.setOnAction(e -> advicePane.showAsDialog(ownerStage));
        adviceButton.setStyle("-fx-font-weight: bold;");
        updateAdviceButtonStyle();

        // Progress
        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);

        statusLabel = new Label();
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        // --- Panel / Montage mode controls (hidden in standard mode) ---
        panelBanner = buildPanelBanner();
        filterFacetSection = buildFilterFacetSection();
        orderHintLabel = new Label(resources.getString("panel.step2.orderHint"));
        orderHintLabel.setStyle("-fx-text-fill: #555555;");
        gateMessageLabel = new Label(resources.getString("panel.step2.gateMessage"));
        gateMessageLabel.setStyle("-fx-text-fill: #b36b00; -fx-font-weight: bold;");
        setPanelControlsVisible(false);

        getChildren().addAll(
                panelBanner,
                header,
                dirLabel, dirBox,
                prefixSuffixRow,
                previewRow,
                imagesHeader,
                filterFacetSection,
                filterField,
                imageListView,
                orderHintLabel,
                gateMessageLabel,
                scriptBox,
                addToWorkflowCheck,
                exportGeoJsonCheck,
                adviceButton,
                progressBar,
                statusLabel
        );
    }

    /**
     * Build the light-blue PANEL / MONTAGE MODE banner shown on the panel-mode
     * image selection step.
     */
    private Label buildPanelBanner() {
        var banner = new Label(resources.getString("panel.banner"));
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setStyle("-fx-background-color: #e8f0fe; -fx-padding: 6 10 6 10; "
                + "-fx-font-weight: bold;");
        return banner;
    }

    /**
     * Build the collapsible filter-facet section: image type, metadata
     * key/value, used only in panel mode on top of the existing name filter.
     */
    private TitledPane buildFilterFacetSection() {
        imageTypeCombo = new ComboBox<>();
        imageTypeCombo.setTooltip(createTooltip("tooltip.panel.imageType"));
        imageTypeCombo.getItems().add(resources.getString("panel.filter.anyType"));
        imageTypeCombo.getSelectionModel().selectFirst();
        imageTypeCombo.valueProperty().addListener((o, a, b) -> applyCompoundFilter());

        metadataKeyCombo = new ComboBox<>();
        metadataKeyCombo.setTooltip(createTooltip("tooltip.panel.metadataKey"));
        metadataKeyCombo.getItems().add(resources.getString("panel.filter.anyKey"));
        metadataKeyCombo.getSelectionModel().selectFirst();
        metadataKeyCombo.valueProperty().addListener((o, a, b) -> applyCompoundFilter());

        metadataValueField = new TextField();
        metadataValueField.setPromptText(resources.getString("panel.filter.valuePrompt"));
        metadataValueField.setTooltip(createTooltip("tooltip.panel.metadataValue"));
        metadataValueField.textProperty().addListener((o, a, b) -> applyCompoundFilter());

        var clearButton = new Button(resources.getString("panel.filter.clearAll"));
        clearButton.setTooltip(createTooltip("tooltip.panel.clearFilters"));
        clearButton.setOnAction(e -> {
            imageTypeCombo.getSelectionModel().selectFirst();
            metadataKeyCombo.getSelectionModel().selectFirst();
            metadataValueField.clear();
            filterField.clear();
        });

        var typeRow = new HBox(5,
                new Label(resources.getString("panel.filter.typeLabel")), imageTypeCombo,
                new Label(resources.getString("panel.filter.metadataLabel")),
                metadataKeyCombo, new Label(" = "), metadataValueField);
        typeRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(metadataValueField, Priority.ALWAYS);

        var content = new VBox(8, typeRow, clearButton);
        content.setPadding(new Insets(5));
        var section = SectionBuilder.createSection(
                resources.getString("panel.filter.sectionTitle"), false, content);
        return section;
    }

    private void populateImageList() {
        var project = qupath.getProject();
        if (project == null) return;

        masterItems = FXCollections.observableArrayList();
        for (var entry : project.getImageList()) {
            masterItems.add(new ImageEntryItem(entry, true));
        }

        filteredItems = new FilteredList<>(masterItems, item -> true);
        imageListView.setItems(filteredItems);
        updateImageCount();
        updateFilenamePreview();

        // Track selection changes for count label and preview
        for (var item : masterItems) {
            item.selectedProperty().addListener((obs, oldVal, newVal) -> {
                updateImageCount();
                updateFilenamePreview();
                updateGateMessage();
                if (selectionChangeListener != null) {
                    selectionChangeListener.run();
                }
            });
        }

        // Wire filter field to FilteredList predicate
        filterField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (panelMode) {
                applyCompoundFilter();
                return;
            }
            String filterText = (newVal != null) ? newVal.toLowerCase() : "";
            if (filterText.isEmpty()) {
                filteredItems.setPredicate(item -> true);
            } else {
                filteredItems.setPredicate(item ->
                        item.toString().toLowerCase().contains(filterText));
            }
            updateImageCount();
        });
    }

    private void updateImageCount() {
        long selectedCount = masterItems.stream()
                .filter(ImageEntryItem::isSelected).count();
        String filterText = filterField.getText();
        if (filterText != null && !filterText.isEmpty()) {
            int visibleCount = filteredItems.size();
            int totalCount = masterItems.size();
            imageCountLabel.setText(String.format(
                    resources.getString("step3.label.imageCountFiltered"),
                    (int) selectedCount, visibleCount, totalCount));
        } else {
            imageCountLabel.setText(String.format(
                    resources.getString("step3.label.imageCount"), (int) selectedCount));
        }
    }

    private void updateFilenamePreview() {
        String prefix = getFilenamePrefix();
        String suffix = getFilenameSuffix();

        // Find first selected image name, or use placeholder
        String imageName = "image_name";
        if (masterItems != null) {
            for (var item : masterItems) {
                if (item.isSelected()) {
                    imageName = item.toString();
                    break;
                }
            }
        }

        filenamePreviewLabel.setText(prefix + imageName + suffix + ".png");
    }

    private void setAllImagesSelected(boolean selected) {
        for (var item : masterItems) {
            item.setSelected(selected);
        }
        updateImageCount();
    }

    private void setVisibleImagesSelected(boolean selected) {
        for (var item : filteredItems) {
            item.setSelected(selected);
        }
        updateImageCount();
    }

    private void browseOutputDirectory() {
        var chooser = new DirectoryChooser();
        chooser.setTitle(resources.getString("step3.label.outputDir"));

        String current = outputDirField.getText();
        if (current != null && !current.isEmpty()) {
            File dir = new File(current);
            if (dir.isDirectory()) {
                chooser.setInitialDirectory(dir);
            }
        }

        File selected = chooser.showDialog(ownerStage);
        if (selected != null) {
            outputDirField.setText(selected.getAbsolutePath());
        }
    }

    private void setProjectDefaultDir() {
        var project = qupath.getProject();
        if (project == null) return;

        var projectDir = project.getPath().getParent();
        if (projectDir != null && currentCategory != null) {
            File outputDir = currentCategory.getNextAvailableOutputDir(projectDir.toFile());
            outputDirField.setText(outputDir.getAbsolutePath());
        }
    }

    /**
     * Set the output directory field to the next available category-specific
     * directory, avoiding overwrites of previous exports.
     */
    public void setDefaultOutputDir(ExportCategory category) {
        this.currentCategory = category;
        var project = qupath.getProject();
        if (project == null) return;

        var projectDir = project.getPath().getParent();
        if (projectDir != null) {
            File outputDir = category.getNextAvailableOutputDir(projectDir.toFile());
            outputDirField.setText(outputDir.getAbsolutePath());
        }
    }

    // --- Public accessors ---

    public File getOutputDirectory() {
        String path = outputDirField.getText();
        if (path == null || path.isEmpty()) return null;
        return new File(path);
    }

    public void setOutputDirectory(String path) {
        outputDirField.setText(path);
    }

    public List<ProjectImageEntry<BufferedImage>> getSelectedEntries() {
        List<ProjectImageEntry<BufferedImage>> selected = new ArrayList<>();
        for (var item : masterItems) {
            if (item.isSelected()) {
                selected.add(item.getEntry());
            }
        }
        return selected;
    }

    public String getFilenamePrefix() {
        String text = prefixField.getText();
        return (text != null) ? text : "";
    }

    public String getFilenameSuffix() {
        String text = suffixField.getText();
        return (text != null) ? text : "";
    }

    public boolean isAddToWorkflow() {
        return addToWorkflowCheck.isSelected();
    }

    public boolean isExportGeoJson() {
        return exportGeoJsonCheck.isSelected();
    }

    public ProgressBar getProgressBar() {
        return progressBar;
    }

    public Label getStatusLabel() {
        return statusLabel;
    }

    public void copyScriptToClipboard(String script) {
        var content = new ClipboardContent();
        content.putString(script);
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("Script copied to clipboard.");
    }

    public void saveScriptToFile(String script) {
        var chooser = new FileChooser();
        chooser.setTitle("Save Groovy Script");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Groovy Scripts", "*.groovy"));
        chooser.setInitialFileName("export_script.groovy");

        File file = chooser.showSaveDialog(ownerStage);
        if (file != null) {
            try {
                java.nio.file.Files.writeString(file.toPath(), script);
                statusLabel.setText("Script saved to: " + file.getAbsolutePath());
            } catch (Exception e) {
                statusLabel.setText("Failed to save script: " + e.getMessage());
            }
        }
    }

    private static Tooltip createTooltip(String key) {
        var tip = new Tooltip(resources.getString(key));
        tip.setWrapText(true);
        tip.setMaxWidth(400);
        tip.setShowDuration(javafx.util.Duration.seconds(30));
        return tip;
    }

    /**
     * Update the publication advice panel based on the current export category
     * and configuration.
     *
     * @param category the selected export category
     * @param config   the category-specific config object, or null
     */
    public void updateAdvice(ExportCategory category, Object config) {
        List<ImageContext> imageContexts = buildImageContexts();
        List<AdviceItem> items = PublicationAdviceChecker.check(category, config, imageContexts);
        advicePane.update(items);
        updateAdviceButtonStyle();
    }

    /** Get the current advice items for use by other wizard steps. */
    public List<AdviceItem> getAdviceItems() {
        return advicePane.getItems();
    }

    /** Close the advice dialog when the wizard closes. */
    public void closeAdviceDialog() {
        advicePane.closeDialog();
    }

    private void updateAdviceButtonStyle() {
        var items = advicePane.getItems();
        boolean hasErrors = items.stream()
                .anyMatch(i -> i.severity() == AdviceSeverity.ERROR);
        boolean hasWarnings = items.stream()
                .anyMatch(i -> i.severity() == AdviceSeverity.WARNING);
        if (hasErrors) {
            adviceButton.setStyle("-fx-font-weight: bold; -fx-base: #ffcccc;");
            adviceButton.setText(resources.getString("advice.button.show") + " [!]");
        } else if (hasWarnings) {
            adviceButton.setStyle("-fx-font-weight: bold; -fx-base: #fff3cd;");
            adviceButton.setText(resources.getString("advice.button.show") + " [*]");
        } else if (!items.isEmpty()) {
            adviceButton.setStyle("-fx-font-weight: bold; -fx-base: #d1ecf1;");
            adviceButton.setText(resources.getString("advice.button.show") + " [i]");
        } else {
            adviceButton.setStyle("-fx-font-weight: bold;");
            adviceButton.setText(resources.getString("advice.button.show"));
        }
    }

    /**
     * Build ImageContext metadata from the first N selected images.
     * Only reads server metadata (no pixel data).
     */
    private List<ImageContext> buildImageContexts() {
        var entries = getSelectedEntries();
        int limit = Math.min(entries.size(), ADVICE_IMAGE_SCAN_LIMIT);
        var contexts = new ArrayList<ImageContext>();

        for (int i = 0; i < limit; i++) {
            var entry = entries.get(i);
            try {
                var imageData = entry.readImageData();
                var server = imageData.getServer();
                var metadata = server.getMetadata();

                boolean hasCalibration = metadata.getPixelCalibration().hasPixelSizeMicrons();
                String imageType = imageData.getImageType() != null
                        ? imageData.getImageType().name() : null;

                var channelNames = new ArrayList<String>();
                var channelColors = new ArrayList<Integer>();
                for (var ch : metadata.getChannels()) {
                    channelNames.add(ch.getName());
                    channelColors.add(ch.getColor());
                }

                contexts.add(new ImageContext(
                        hasCalibration,
                        imageType,
                        channelNames,
                        channelColors,
                        metadata.getSizeC()));

                server.close();
            } catch (Exception e) {
                logger.debug("Failed to read metadata for advice from {}: {}",
                        entry.getImageName(), e.getMessage());
            }
        }
        return contexts;
    }

    // Script action handlers - set by ExportWizard
    private Runnable scriptCopyHandler;
    private Runnable scriptSaveHandler;

    public void setScriptCopyHandler(Runnable handler) {
        this.scriptCopyHandler = handler;
    }

    public void setScriptSaveHandler(Runnable handler) {
        this.scriptSaveHandler = handler;
    }

    /**
     * Enable or disable panel / montage mode for this image-selection pane.
     * <p>
     * In panel mode the prefix/suffix, filename preview, script buttons,
     * workflow/GeoJSON checkboxes and advice button are hidden (they apply
     * only to single-image exports); the panel banner, the type/metadata
     * filter facets, a row-major order hint, and a 0-images gate message are
     * shown. The first time panel mode is entered the per-image type and
     * metadata caches are built so the facets can filter without re-opening
     * images.
     *
     * @param enabled true for panel mode
     */
    public void setPanelMode(boolean enabled) {
        this.panelMode = enabled;
        setPanelControlsVisible(enabled);
        // Keep the step header consistent with the wizard flow: panel mode
        // shows this pane as Step 1 of 3, the standard flow as Step 3 of 3.
        stepHeader.setText(resources.getString(
                enabled ? "panel.step2.title" : "wizard.step3.title"));
        if (enabled) {
            // Hide single-image-only controls.
            prefixSuffixRow.setVisible(false);
            prefixSuffixRow.setManaged(false);
            previewRow.setVisible(false);
            previewRow.setManaged(false);
            scriptBox.setVisible(false);
            scriptBox.setManaged(false);
            addToWorkflowCheck.setVisible(false);
            addToWorkflowCheck.setManaged(false);
            exportGeoJsonCheck.setVisible(false);
            exportGeoJsonCheck.setManaged(false);
            adviceButton.setVisible(false);
            adviceButton.setManaged(false);
            buildFacetCache();
            updateGateMessage();
        }
    }

    private void setPanelControlsVisible(boolean visible) {
        for (var node : new javafx.scene.Node[]{
                panelBanner, filterFacetSection, orderHintLabel, gateMessageLabel}) {
            if (node != null) {
                node.setVisible(visible);
                node.setManaged(visible);
            }
        }
        if (filterFacetSection != null && visible && masterItems != null
                && masterItems.size() > 30) {
            // Large project: open the facet section so the filter is found.
            filterFacetSection.setExpanded(true);
        }
    }

    /**
     * Scan the project images once to cache image type and metadata, and
     * populate the type / metadata-key filter combos.
     */
    private void buildFacetCache() {
        if (facetCacheBuilt || masterItems == null) {
            return;
        }
        var types = new TreeSet<String>();
        var keys = new TreeSet<String>();
        for (var item : masterItems) {
            var entry = item.getEntry();
            var metadata = new LinkedHashMap<String, String>();
            try {
                var entryMeta = entry.getMetadata();
                if (entryMeta != null) {
                    for (var e : entryMeta.entrySet()) {
                        if (e.getKey() != null && e.getValue() != null) {
                            metadata.put(e.getKey(), e.getValue());
                            keys.add(e.getKey());
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to read metadata for {}: {}",
                        entry.getImageName(), e.getMessage());
            }
            String typeName = null;
            try {
                var imageData = entry.readImageData();
                if (imageData.getImageType() != null) {
                    typeName = imageData.getImageType().name();
                    types.add(typeName);
                }
                imageData.getServer().close();
            } catch (Exception e) {
                logger.debug("Failed to read image type for {}: {}",
                        entry.getImageName(), e.getMessage());
            }
            item.setImageType(typeName);
            item.setMetadata(metadata);
        }
        imageTypeCombo.getItems().setAll(resources.getString("panel.filter.anyType"));
        imageTypeCombo.getItems().addAll(types);
        imageTypeCombo.getSelectionModel().selectFirst();
        metadataKeyCombo.getItems().setAll(resources.getString("panel.filter.anyKey"));
        metadataKeyCombo.getItems().addAll(keys);
        metadataKeyCombo.getSelectionModel().selectFirst();
        facetCacheBuilt = true;
    }

    /**
     * Apply the compound (type AND metadata AND name) filter predicate.
     * Used in panel mode; the standard mode uses the name-only predicate.
     */
    private void applyCompoundFilter() {
        if (filteredItems == null) {
            return;
        }
        String anyType = resources.getString("panel.filter.anyType");
        String anyKey = resources.getString("panel.filter.anyKey");
        String typeSel = imageTypeCombo != null ? imageTypeCombo.getValue() : null;
        String keySel = metadataKeyCombo != null ? metadataKeyCombo.getValue() : null;
        String valueText = metadataValueField != null && metadataValueField.getText() != null
                ? metadataValueField.getText().toLowerCase().trim() : "";
        String nameText = filterField.getText() != null
                ? filterField.getText().toLowerCase().trim() : "";

        filteredItems.setPredicate(item -> {
            if (!nameText.isEmpty()
                    && !item.toString().toLowerCase().contains(nameText)) {
                return false;
            }
            if (typeSel != null && !typeSel.equals(anyType)
                    && !typeSel.equals(item.getImageType())) {
                return false;
            }
            if (keySel != null && !keySel.equals(anyKey)) {
                String value = item.getMetadata().get(keySel);
                if (value == null) {
                    return false;
                }
                if (!valueText.isEmpty()
                        && !value.toLowerCase().contains(valueText)) {
                    return false;
                }
            }
            return true;
        });
        updateImageCount();
    }

    private void updateGateMessage() {
        if (gateMessageLabel == null || masterItems == null) {
            return;
        }
        boolean none = masterItems.stream().noneMatch(ImageEntryItem::isSelected);
        gateMessageLabel.setVisible(panelMode && none);
        gateMessageLabel.setManaged(panelMode && none);
    }

    /**
     * Register a callback fired whenever the image selection changes. Used by
     * the wizard to enable/disable the panel-mode Next button.
     *
     * @param listener the callback (may be null to clear)
     */
    public void setSelectionChangeListener(Runnable listener) {
        this.selectionChangeListener = listener;
    }

    /** Count of currently selected images. */
    public int getSelectedCount() {
        if (masterItems == null) {
            return 0;
        }
        return (int) masterItems.stream().filter(ImageEntryItem::isSelected).count();
    }

    /**
     * Show or hide advanced controls for simple mode.
     * Hides prefix/suffix, filename preview, workflow/GeoJSON checkboxes, and script buttons.
     * <p>
     * In panel / montage mode these controls are single-image-only and stay
     * hidden regardless of the simple/advanced toggle -- {@link #setPanelMode}
     * owns their visibility there.
     */
    public void setSimpleMode(boolean simple) {
        if (panelMode) {
            // Panel mode hides these controls outright; do not let the
            // advanced-mode toggle resurrect them.
            return;
        }
        boolean show = !simple;
        prefixSuffixRow.setVisible(show);
        prefixSuffixRow.setManaged(show);
        previewRow.setVisible(show);
        previewRow.setManaged(show);
        addToWorkflowCheck.setVisible(show);
        addToWorkflowCheck.setManaged(show);
        exportGeoJsonCheck.setVisible(show);
        exportGeoJsonCheck.setManaged(show);
        scriptBox.setVisible(show);
        scriptBox.setManaged(show);
    }
}
