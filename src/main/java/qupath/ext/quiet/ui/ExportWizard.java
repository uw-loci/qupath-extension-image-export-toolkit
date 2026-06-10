package qupath.ext.quiet.ui;

import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Modality;
import javafx.stage.Stage;

import qupath.ext.quiet.export.BatchExportTask;
import qupath.ext.quiet.export.ExportCategory;
import qupath.ext.quiet.export.ExportResult;
import qupath.ext.quiet.export.MaskExportConfig;
import qupath.ext.quiet.export.ObjectCropConfig;
import qupath.ext.quiet.export.OutputFormat;
import qupath.ext.quiet.export.RawExportConfig;
import qupath.ext.quiet.export.RenderedExportConfig;
import qupath.ext.quiet.export.TiledExportConfig;
import qupath.ext.quiet.preferences.QuietPreferences;
import qupath.ext.quiet.export.ScriptGenerator;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.analysis.heatmaps.DensityMaps;
import qupath.lib.analysis.heatmaps.DensityMaps.DensityMapBuilder;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Wizard for configuring and running image exports.
 * <p>
 * The standard flow is three steps:
 * <ol>
 *   <li>Select export category (Rendered, Mask, Raw, Tiled, Object Crops)</li>
 *   <li>Configure export-specific options</li>
 *   <li>Select images, output directory, run export</li>
 * </ol>
 * The Panel / Montage flow is launched directly from its own menu item via
 * {@link #showPanelWizard(QuPathGUI)}; it skips the category picker and runs
 * as three steps: Select Images, Recipe, Layout/Captions/Output.
 */
public class ExportWizard {

    private static final Logger logger = LoggerFactory.getLogger(ExportWizard.class);
    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.quiet.ui.strings");

    private final QuPathGUI qupath;
    private final Stage stage;
    private final BorderPane root;

    // Wizard steps
    private int currentStep = 1;
    private CategorySelectionPane categoryPane;
    private RenderedConfigPane renderedConfigPane;
    private MaskConfigPane maskConfigPane;
    private RawConfigPane rawConfigPane;
    private TiledConfigPane tiledConfigPane;
    private ObjectCropConfigPane objectCropConfigPane;
    private ImageSelectionPane imageSelectionPane;

    // Panel / Montage mode steps (created lazily on first PANEL navigation)
    private PanelRecipePane panelRecipePane;
    private PanelLayoutPane panelLayoutPane;

    // Navigation buttons
    private Button backButton;
    private Button nextButton;
    private Button cancelButton;
    private Button openFolderButton;
    private ToggleButton simpleModeToggle;

    // Current export state
    private ExportCategory selectedCategory;
    private BatchExportTask currentTask;

    /**
     * True when this wizard was launched directly into Panel / Montage mode
     * (via {@link #showPanelWizard}). The category picker is then skipped and
     * the wizard runs the 3-step panel sequence.
     */
    private final boolean panelLaunch;

    /** Tracks the output directory of the last successful export. */
    private File lastExportDirectory;

    private ExportWizard(QuPathGUI qupath, boolean panelLaunch) {
        this.qupath = qupath;
        this.panelLaunch = panelLaunch;
        this.stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(qupath.getStage());
        stage.setTitle(resources.getString("wizard.title"));
        stage.setMinWidth(1150);
        stage.setMinHeight(550);

        root = new BorderPane();
        root.setPadding(new Insets(10));

        // Restore wizard size
        stage.setWidth(QuietPreferences.getWizardWidth());
        stage.setHeight(QuietPreferences.getWizardHeight());

        // Save preferences on close
        stage.setOnCloseRequest(e -> {
            saveAllPreferences();
            saveWizardSize();
            imageSelectionPane.closeAdviceDialog();
        });

        buildNavigation();
        initializeSteps();
        if (panelLaunch) {
            // Panel mode is entered directly: the category picker is skipped
            // and the wizard opens on the (former) Select Images step.
            selectedCategory = ExportCategory.PANEL;
        }
        showStep(1);

        var scene = new Scene(root);
        stage.setScene(scene);
    }

    /**
     * Show the standard export wizard, starting on the category picker.
     *
     * @param qupath the QuPath GUI instance
     */
    public static void showWizard(QuPathGUI qupath) {
        var wizard = new ExportWizard(qupath, false);
        wizard.stage.show();
    }

    /**
     * Show the wizard directly in Panel / Montage mode, skipping the category
     * picker. The wizard runs the 3-step panel sequence: Select Images,
     * Recipe, Layout/Captions/Output.
     *
     * @param qupath the QuPath GUI instance
     */
    public static void showPanelWizard(QuPathGUI qupath) {
        var wizard = new ExportWizard(qupath, true);
        wizard.stage.show();
    }

    private void buildNavigation() {
        backButton = new Button(resources.getString("button.back"));
        backButton.setOnAction(e -> goBack());

        nextButton = new Button(resources.getString("button.next"));
        nextButton.setDefaultButton(true);
        nextButton.setOnAction(e -> goNext());

        openFolderButton = new Button(resources.getString("button.openResultFolder"));
        openFolderButton.setOnAction(e -> openResultFolder());
        openFolderButton.setVisible(false);
        openFolderButton.setManaged(false);

        cancelButton = new Button(resources.getString("button.cancel"));
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> {
            if (currentTask != null && currentTask.isRunning()) {
                currentTask.cancel();
            } else {
                saveAllPreferences();
                saveWizardSize();
                stage.close();
            }
        });

        // Simple / Advanced mode toggle
        boolean isSimple = QuietPreferences.isSimpleMode();
        simpleModeToggle = new ToggleButton(isSimple
                ? resources.getString("toggle.simpleMode.simple")
                : resources.getString("toggle.simpleMode.advanced"));
        simpleModeToggle.setSelected(isSimple);
        simpleModeToggle.setTooltip(new Tooltip(resources.getString("toggle.simpleMode.tooltip")));
        simpleModeToggle.setStyle("-fx-font-weight: bold;");
        simpleModeToggle.selectedProperty().addListener((obs, old, simple) -> {
            QuietPreferences.setSimpleMode(simple);
            simpleModeToggle.setText(simple
                    ? resources.getString("toggle.simpleMode.simple")
                    : resources.getString("toggle.simpleMode.advanced"));
            applySimpleModeToCurrentStep();
        });

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var navBar = new HBox(10, simpleModeToggle, backButton, spacer, openFolderButton, cancelButton, nextButton);
        navBar.setAlignment(Pos.CENTER_RIGHT);
        navBar.setPadding(new Insets(10, 0, 0, 0));

        var bottomBox = new javafx.scene.layout.VBox(5, new Separator(), navBar);
        root.setBottom(bottomBox);
    }

    private void initializeSteps() {
        categoryPane = new CategorySelectionPane();

        // Restore last used category. PANEL is not a card in the picker, so
        // an earlier panel export leaves the picker on its default category.
        String lastCat = QuietPreferences.getLastCategory();
        try {
            ExportCategory restored = ExportCategory.valueOf(lastCat);
            if (restored != ExportCategory.PANEL) {
                categoryPane.setSelectedCategory(restored);
            }
        } catch (IllegalArgumentException e) {
            // Keep default
        }

        categoryPane.setOnAdvance(this::goNext);

        renderedConfigPane = new RenderedConfigPane(qupath);
        maskConfigPane = new MaskConfigPane(qupath);
        rawConfigPane = new RawConfigPane();
        tiledConfigPane = new TiledConfigPane(qupath);
        objectCropConfigPane = new ObjectCropConfigPane(qupath);
        imageSelectionPane = new ImageSelectionPane(qupath, stage);

        // Wire up script handlers
        imageSelectionPane.setScriptCopyHandler(this::copyScript);
        imageSelectionPane.setScriptSaveHandler(this::saveScript);

        // Panel mode: keep the Next button in sync with the image selection.
        imageSelectionPane.setSelectionChangeListener(() -> {
            if (isPanelMode() && currentStep == 1) {
                updateNavButtons();
            }
        });
    }

    /**
     * True when this wizard is running the Panel / Montage flow. Panel mode is
     * entered only via {@link #showPanelWizard} -- it is never reachable from
     * the category picker.
     */
    private boolean isPanelMode() {
        return panelLaunch;
    }

    /** The last step index for the current flow (3 for both panel and standard). */
    private int lastStep() {
        return 3;
    }

    private void showStep(int step) {
        currentStep = step;
        if (isPanelMode()) {
            // Panel mode skips the category picker -- the category is fixed.
            showPanelStep(step);
        } else {
            // Resolve the selected category as soon as the user leaves Step 1.
            if (step >= 2) {
                selectedCategory = categoryPane.getSelectedCategory();
            }
            showStandardStep(step);
        }
        updateNavButtons();
        // The Simple/Advanced toggle is hidden on the standard category picker
        // (Step 1); in panel mode it is shown on every step.
        boolean hideToggle = !isPanelMode() && step == 1;
        simpleModeToggle.setVisible(!hideToggle);
        simpleModeToggle.setManaged(!hideToggle);
        applySimpleModeToCurrentStep();
    }

    /**
     * Show a step in the standard 3-step flow (Rendered/Mask/Raw/Tiled/Crops).
     */
    private void showStandardStep(int step) {
        // Ensure the shared image-selection pane is in standard (non-panel)
        // mode whenever a non-panel category is active.
        imageSelectionPane.setPanelMode(false);
        Node centerContent;
        switch (step) {
            case 1 -> centerContent = categoryPane;
            case 2 -> {
                Node configPane = switch (selectedCategory) {
                    case RENDERED -> renderedConfigPane;
                    case MASK -> maskConfigPane;
                    case RAW -> rawConfigPane;
                    case TILED -> tiledConfigPane;
                    case OBJECT_CROPS -> objectCropConfigPane;
                    case PANEL -> renderedConfigPane;  // not reachable
                };
                var scrollPane = new ScrollPane(configPane);
                scrollPane.setFitToWidth(true);
                scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                centerContent = scrollPane;
                root.setRight(new GuidelinesPane(qupath, selectedCategory));
                var adviceItems = imageSelectionPane.getAdviceItems();
                if (selectedCategory == ExportCategory.RENDERED) {
                    renderedConfigPane.highlightAdviceSections(adviceItems);
                }
            }
            case 3 -> {
                File currentDir = imageSelectionPane.getOutputDirectory();
                if (currentDir == null) {
                    imageSelectionPane.setDefaultOutputDir(selectedCategory);
                }
                imageSelectionPane.updateAdvice(
                        selectedCategory, buildCurrentConfigForAdvice());
                centerContent = imageSelectionPane;
            }
            default -> centerContent = categoryPane;
        }
        root.setCenter(centerContent);
        if (step != 2) {
            root.setRight(null);
        }
    }

    /**
     * Show a step in the panel / montage 3-step flow:
     * 1 Select Images, 2 Recipe, 3 Layout/Captions/Output.
     * The category picker is skipped -- panel mode is launched directly.
     */
    private void showPanelStep(int step) {
        root.setRight(null);
        Node centerContent;
        switch (step) {
            case 1 -> {
                imageSelectionPane.setPanelMode(true);
                var scroll = new ScrollPane(imageSelectionPane);
                scroll.setFitToWidth(true);
                scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                centerContent = scroll;
            }
            case 2 -> {
                if (panelRecipePane == null) {
                    panelRecipePane = new PanelRecipePane(qupath, stage);
                }
                centerContent = panelRecipePane;
            }
            case 3 -> {
                if (panelLayoutPane == null) {
                    panelLayoutPane = new PanelLayoutPane(qupath, stage);
                }
                // Default the output directory the first time the layout
                // step is shown.
                if (panelLayoutPane.getOutputDirectory() == null) {
                    setPanelDefaultOutputDir();
                }
                panelLayoutPane.setRecipeCategory(panelRecipePane.getRecipeCategory());
                Object recipeConfigForScan = null;
                try {
                    recipeConfigForScan = panelRecipePane.buildRecipeConfig();
                } catch (RuntimeException ex) {
                    logger.debug("Recipe config not yet buildable for scan: {}",
                            ex.getMessage());
                }
                panelLayoutPane.refreshForSelection(
                        imageSelectionPane.getSelectedEntries(),
                        panelRecipePane.getRecipeCategory(),
                        recipeConfigForScan);
                var scroll = new ScrollPane(panelLayoutPane);
                scroll.setFitToWidth(true);
                scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                centerContent = scroll;
            }
            default -> {
                // Unreachable: panel mode has exactly three steps.
                throw new IllegalStateException("Invalid panel step: " + step);
            }
        }
        root.setCenter(centerContent);
    }

    /**
     * Set the panel-mode output directory to the project's next-available
     * {@code exports/panels/} directory.
     */
    private void setPanelDefaultOutputDir() {
        var project = qupath.getProject();
        if (project == null) {
            return;
        }
        var projectDir = project.getPath().getParent();
        if (projectDir != null) {
            File dir = ExportCategory.PANEL.getNextAvailableOutputDir(projectDir.toFile());
            panelLayoutPane.setOutputDirectory(dir.getAbsolutePath());
        }
    }

    private void applySimpleModeToCurrentStep() {
        boolean simple = simpleModeToggle.isSelected();
        renderedConfigPane.setSimpleMode(simple);
        maskConfigPane.setSimpleMode(simple);
        rawConfigPane.setSimpleMode(simple);
        tiledConfigPane.setSimpleMode(simple);
        objectCropConfigPane.setSimpleMode(simple);
        imageSelectionPane.setSimpleMode(simple);
        if (panelRecipePane != null) {
            panelRecipePane.applySimpleMode(simple);
        }
    }

    private void updateNavButtons() {
        backButton.setDisable(currentStep == 1);

        if (currentStep == lastStep()) {
            nextButton.setText(resources.getString("button.finish"));
        } else {
            nextButton.setText(resources.getString("button.next"));
        }

        // Panel mode: the Next button on the image-selection step (Step 1) is
        // disabled until at least one image is selected.
        if (isPanelMode() && currentStep == 1) {
            nextButton.setDisable(imageSelectionPane.getSelectedCount() == 0);
        } else if (currentTask == null || !currentTask.isRunning()) {
            nextButton.setDisable(false);
        }
    }

    private void goBack() {
        if (currentStep > 1) {
            showStep(currentStep - 1);
        }
    }

    private void goNext() {
        if (currentStep < lastStep()) {
            showStep(currentStep + 1);
        } else {
            startExport();
        }
    }

    private void startExport() {
        if (isPanelMode()) {
            startPanelExport();
            return;
        }
        // Validate output directory
        File outputDir = imageSelectionPane.getOutputDirectory();
        if (outputDir == null) {
            Dialogs.showWarningNotification(
                    resources.getString("name"),
                    resources.getString("error.invalidDir"));
            return;
        }
        if (!outputDir.isDirectory()) {
            if (!outputDir.mkdirs()) {
                Dialogs.showWarningNotification(
                        resources.getString("name"),
                        resources.getString("error.invalidDir"));
                return;
            }
        }

        // Validate image selection
        var selectedEntries = imageSelectionPane.getSelectedEntries();
        if (selectedEntries.isEmpty()) {
            Dialogs.showWarningNotification(
                    resources.getString("name"),
                    resources.getString("error.noImages"));
            return;
        }

        boolean addToWorkflow = imageSelectionPane.isAddToWorkflow();
        boolean exportGeoJson = imageSelectionPane.isExportGeoJson();
        QuietPreferences.setAddToWorkflow(addToWorkflow);
        QuietPreferences.setExportGeoJson(exportGeoJson);
        QuietPreferences.setLastCategory(selectedCategory.name());

        // Channel consistency validation
        var channelScan = scanChannelConsistency(selectedEntries);
        if (!channelScan.consistent) {
            if (selectedCategory == ExportCategory.RAW) {
                // Blocking error for Raw export -- channels must be consistent
                Dialogs.showErrorMessage(
                        resources.getString("channel.warning.title"),
                        resources.getString("channel.error.raw.content"));
                return;
            }
            // Warning dialog for other categories
            String groupSummary = channelScan.buildGroupSummary();
            String message = String.format(
                    resources.getString("channel.warning.content"), groupSummary);

            var continueBtn = new ButtonType(
                    resources.getString("button.continueAnyway"),
                    ButtonBar.ButtonData.OK_DONE);
            var cancelBtn = new ButtonType(
                    resources.getString("button.cancel"),
                    ButtonBar.ButtonData.CANCEL_CLOSE);
            var alert = new Alert(Alert.AlertType.WARNING, message, continueBtn, cancelBtn);
            alert.setTitle(resources.getString("channel.warning.title"));
            alert.setHeaderText(resources.getString("channel.warning.header"));
            alert.initOwner(stage);
            var result = alert.showAndWait();
            if (result.isEmpty() || result.get() == cancelBtn) {
                return;
            }
        }

        try {
            switch (selectedCategory) {
                case RENDERED -> startRenderedExport(outputDir, addToWorkflow,
                        exportGeoJson, channelScan.consistent);
                case MASK -> startMaskExport(outputDir, addToWorkflow, exportGeoJson);
                case RAW -> startRawExport(outputDir, addToWorkflow, exportGeoJson,
                        channelScan.consistent);
                case TILED -> startTiledExport(outputDir, addToWorkflow, exportGeoJson);
                case OBJECT_CROPS -> startObjectCropsExport(outputDir, addToWorkflow,
                        exportGeoJson, channelScan.consistent);
                case PANEL -> startPanelExport();  // not reachable -- handled above
            }
        } catch (IllegalArgumentException e) {
            Dialogs.showWarningNotification(
                    resources.getString("name"), e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to start export", e);
            Dialogs.showErrorMessage(
                    resources.getString("error.title"),
                    "Failed to start export: " + e.getMessage());
        }
    }

    private void startRenderedExport(File outputDir, boolean addToWorkflow,
                                      boolean exportGeoJson, boolean channelsConsistent) {
        renderedConfigPane.savePreferences();
        RenderedExportConfig config = renderedConfigPane.buildConfig(outputDir);

        if (!checkExportSize(config.getDownsample(), config.getFormat())) {
            return;
        }

        PixelClassifier classifier = null;
        DensityMapBuilder densityBuilder = null;

        if (config.getRenderMode() == RenderedExportConfig.RenderMode.CLASSIFIER_OVERLAY) {
            String classifierName = renderedConfigPane.getClassifierName();
            if (classifierName == null || classifierName.isEmpty()) {
                Dialogs.showWarningNotification(
                        resources.getString("name"),
                        resources.getString("error.noClassifier"));
                return;
            }

            if (RenderedConfigPane.ACTIVE_OVERLAY_DISPLAY_LABEL.equals(classifierName)) {
                classifier = RenderedConfigPane.getActiveOverlayClassifier(qupath);
                if (classifier == null) {
                    Dialogs.showWarningNotification(
                            resources.getString("name"),
                            "No active pixel classification overlay found on the current viewer.");
                    return;
                }
            } else {
                try {
                    classifier = qupath.getProject().getPixelClassifiers().get(classifierName);
                } catch (Exception e) {
                    logger.error("Failed to load classifier: {}", classifierName, e);
                    Dialogs.showErrorMessage(
                            resources.getString("error.title"),
                            String.format(resources.getString("error.classifierLoad"), classifierName));
                    return;
                }
            }
        } else if (config.getRenderMode() == RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY) {
            String dmName = renderedConfigPane.getDensityMapName();
            if (dmName == null || dmName.isEmpty()) {
                Dialogs.showWarningNotification(
                        resources.getString("name"),
                        resources.getString("error.noDensityMap"));
                return;
            }
            try {
                var dmResources = qupath.getProject().getResources(
                        DensityMaps.PROJECT_LOCATION, DensityMapBuilder.class, "json");
                densityBuilder = dmResources.get(dmName);
            } catch (Exception e) {
                logger.error("Failed to load density map: {}", dmName, e);
                Dialogs.showErrorMessage(
                        resources.getString("error.title"),
                        String.format(resources.getString("error.densityMapLoad"), dmName));
                return;
            }
        }

        // Skip workflow script for active overlay -- the classifier is ephemeral
        // and cannot be reproduced from a saved script
        boolean isActiveOverlay = RenderedConfigPane.ACTIVE_OVERLAY_DISPLAY_LABEL
                .equals(renderedConfigPane.getClassifierName());
        String workflowScript = (addToWorkflow && !isActiveOverlay)
                ? ScriptGenerator.generate(ExportCategory.RENDERED, config) : null;

        String prefix = imageSelectionPane.getFilenamePrefix();
        String suffix = imageSelectionPane.getFilenameSuffix();

        currentTask = BatchExportTask.forRendered(
                imageSelectionPane.getSelectedEntries(), config, classifier,
                densityBuilder, workflowScript, exportGeoJson, prefix, suffix,
                channelsConsistent);
        lastExportDirectory = outputDir;
        runTask();
    }

    private void startMaskExport(File outputDir, boolean addToWorkflow, boolean exportGeoJson) {
        maskConfigPane.savePreferences();
        MaskExportConfig config = maskConfigPane.buildConfig(outputDir);

        if (!checkExportSize(config.getDownsample(), config.getFormat())) {
            return;
        }

        String workflowScript = addToWorkflow
                ? ScriptGenerator.generate(ExportCategory.MASK, config) : null;

        String prefix = imageSelectionPane.getFilenamePrefix();
        String suffix = imageSelectionPane.getFilenameSuffix();

        currentTask = BatchExportTask.forMask(
                imageSelectionPane.getSelectedEntries(), config, workflowScript, exportGeoJson,
                prefix, suffix);
        lastExportDirectory = outputDir;
        runTask();
    }

    private void startRawExport(File outputDir, boolean addToWorkflow,
                                boolean exportGeoJson, boolean channelsConsistent) {
        rawConfigPane.savePreferences();
        RawExportConfig config = rawConfigPane.buildConfig(outputDir);

        if (!checkExportSize(config.getDownsample(), config.getFormat())) {
            return;
        }

        String workflowScript = addToWorkflow
                ? ScriptGenerator.generate(ExportCategory.RAW, config) : null;

        String prefix = imageSelectionPane.getFilenamePrefix();
        String suffix = imageSelectionPane.getFilenameSuffix();

        currentTask = BatchExportTask.forRaw(
                imageSelectionPane.getSelectedEntries(), config, workflowScript, exportGeoJson,
                prefix, suffix, channelsConsistent);
        lastExportDirectory = outputDir;
        runTask();
    }

    private void startTiledExport(File outputDir, boolean addToWorkflow, boolean exportGeoJson) {
        tiledConfigPane.savePreferences();
        TiledExportConfig config = tiledConfigPane.buildConfig(outputDir);

        String workflowScript = addToWorkflow
                ? ScriptGenerator.generate(ExportCategory.TILED, config) : null;

        String prefix = imageSelectionPane.getFilenamePrefix();
        String suffix = imageSelectionPane.getFilenameSuffix();

        currentTask = BatchExportTask.forTiled(
                imageSelectionPane.getSelectedEntries(), config, workflowScript, exportGeoJson,
                prefix, suffix);
        lastExportDirectory = outputDir;
        runTask();
    }

    private void startObjectCropsExport(File outputDir, boolean addToWorkflow,
                                        boolean exportGeoJson, boolean channelsConsistent) {
        objectCropConfigPane.savePreferences();
        ObjectCropConfig config = objectCropConfigPane.buildConfig(outputDir);

        String workflowScript = addToWorkflow
                ? ScriptGenerator.generate(ExportCategory.OBJECT_CROPS, config) : null;

        String prefix = imageSelectionPane.getFilenamePrefix();
        String suffix = imageSelectionPane.getFilenameSuffix();

        currentTask = BatchExportTask.forObjectCrops(
                imageSelectionPane.getSelectedEntries(), config, workflowScript, exportGeoJson,
                prefix, suffix, channelsConsistent);
        lastExportDirectory = outputDir;
        runTask();
    }

    /**
     * Start a panel / montage export: validate the selection, recipe and
     * output directory, build the panel config, and run the compose task.
     */
    private void startPanelExport() {
        // Drop the layout-preview window's always-on-top flag for the duration
        // of the export so the validation, progress and result / error dialogs
        // are not hidden behind it. If a task actually started, unbindProgress()
        // restores it when the task finishes; if validation failed and no task
        // ran, restore it here so the preview returns to the foreground.
        if (panelLayoutPane != null) {
            panelLayoutPane.suppressPreviewWindow();
        }
        boolean taskStarted = launchPanelExport();
        if (!taskStarted && panelLayoutPane != null) {
            panelLayoutPane.restorePreviewWindow();
        }
    }

    /**
     * Validate the panel selection / recipe / output and, if all is well, build
     * the panel config and launch the compose task.
     *
     * @return true if a compose task was started, false if validation failed
     *         or an error was reported (and no task is running)
     */
    private boolean launchPanelExport() {
        if (imageSelectionPane.getSelectedEntries().isEmpty()) {
            Dialogs.showWarningNotification(
                    resources.getString("name"),
                    resources.getString("panel.error.noImages"));
            return false;
        }
        if (panelRecipePane == null || panelLayoutPane == null) {
            Dialogs.showWarningNotification(
                    resources.getString("name"),
                    resources.getString("panel.error.noRecipe"));
            return false;
        }
        File outputDir = panelLayoutPane.getOutputDirectory();
        if (outputDir == null) {
            Dialogs.showWarningNotification(
                    resources.getString("name"),
                    resources.getString("error.invalidDir"));
            return false;
        }
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) {
            Dialogs.showWarningNotification(
                    resources.getString("name"),
                    resources.getString("error.invalidDir"));
            return false;
        }

        try {
            panelRecipePane.savePreferences();
            panelLayoutPane.savePreferences();
            ExportCategory recipeCategory = panelRecipePane.getRecipeCategory();
            Object recipeConfig = panelRecipePane.buildRecipeConfig();
            var panelConfig = panelLayoutPane.buildConfig(
                    recipeCategory, recipeConfig, outputDir);

            // Resolve the recipe's classifier / density map so a
            // CLASSIFIER_OVERLAY or DENSITY_MAP_OVERLAY recipe renders its
            // overlay in every cell. Fails loudly if resolution is impossible.
            PixelClassifier classifier = null;
            DensityMapBuilder densityBuilder = null;
            if (recipeCategory == ExportCategory.RENDERED
                    && recipeConfig instanceof RenderedExportConfig rc) {
                if (rc.getRenderMode()
                        == RenderedExportConfig.RenderMode.CLASSIFIER_OVERLAY) {
                    classifier = resolvePanelClassifier();
                    if (classifier == null) {
                        return false;
                    }
                } else if (rc.getRenderMode()
                        == RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY) {
                    densityBuilder = resolvePanelDensityMap();
                    if (densityBuilder == null) {
                        return false;
                    }
                }
            }

            // Honour the SVG 16 MP soft-warning for a large composed figure.
            if (!checkPanelExportSize(panelConfig)) {
                return false;
            }

            String workflowScript = ScriptGenerator.generate(
                    ExportCategory.PANEL, panelConfig);

            QuietPreferences.setLastCategory(ExportCategory.PANEL.name());

            // The layout preview's arrangement (after any drag-reorder) is the
            // source of truth for cell placement -- not the raw selection order.
            var orderedEntries = panelLayoutPane.getOrderedEntries();
            if (orderedEntries.isEmpty()) {
                orderedEntries = imageSelectionPane.getSelectedEntries();
            }

            currentTask = BatchExportTask.forPanel(
                    orderedEntries, panelConfig, classifier, densityBuilder,
                    workflowScript);
            lastExportDirectory = outputDir;
            runTask();
            return true;
        } catch (IllegalArgumentException e) {
            Dialogs.showWarningNotification(
                    resources.getString("name"), e.getMessage());
            return false;
        } catch (Exception e) {
            logger.error("Failed to start panel export", e);
            Dialogs.showErrorMessage(
                    resources.getString("error.title"),
                    "Failed to start panel export: " + e.getMessage());
            return false;
        }
    }

    /**
     * Resolve the pixel classifier for a CLASSIFIER_OVERLAY panel recipe.
     * Returns null (after showing a clear message) if it cannot be resolved --
     * the caller must abort rather than compose a degraded figure.
     */
    private PixelClassifier resolvePanelClassifier() {
        String classifierName = panelRecipePane.getRenderedClassifierName();
        if (classifierName == null || classifierName.isEmpty()) {
            Dialogs.showWarningNotification(
                    resources.getString("name"),
                    resources.getString("error.noClassifier"));
            return null;
        }
        if (RenderedConfigPane.ACTIVE_OVERLAY_DISPLAY_LABEL.equals(classifierName)) {
            PixelClassifier classifier =
                    RenderedConfigPane.getActiveOverlayClassifier(qupath);
            if (classifier == null) {
                Dialogs.showWarningNotification(
                        resources.getString("name"),
                        "No active pixel classification overlay found on the current viewer.");
            }
            return classifier;
        }
        try {
            PixelClassifier classifier =
                    qupath.getProject().getPixelClassifiers().get(classifierName);
            if (classifier == null) {
                Dialogs.showErrorMessage(
                        resources.getString("error.title"),
                        String.format(resources.getString("error.classifierLoad"),
                                classifierName));
            }
            return classifier;
        } catch (Exception e) {
            logger.error("Failed to load classifier: {}", classifierName, e);
            Dialogs.showErrorMessage(
                    resources.getString("error.title"),
                    String.format(resources.getString("error.classifierLoad"),
                            classifierName));
            return null;
        }
    }

    /**
     * Resolve the density-map builder for a DENSITY_MAP_OVERLAY panel recipe.
     * Returns null (after showing a clear message) if it cannot be resolved.
     */
    private DensityMapBuilder resolvePanelDensityMap() {
        String dmName = panelRecipePane.getRenderedDensityMapName();
        if (dmName == null || dmName.isEmpty()) {
            Dialogs.showWarningNotification(
                    resources.getString("name"),
                    resources.getString("error.noDensityMap"));
            return null;
        }
        try {
            var dmResources = qupath.getProject().getResources(
                    DensityMaps.PROJECT_LOCATION, DensityMapBuilder.class, "json");
            DensityMapBuilder builder = dmResources.get(dmName);
            if (builder == null) {
                Dialogs.showErrorMessage(
                        resources.getString("error.title"),
                        String.format(resources.getString("error.densityMapLoad"),
                                dmName));
            }
            return builder;
        } catch (Exception e) {
            logger.error("Failed to load density map: {}", dmName, e);
            Dialogs.showErrorMessage(
                    resources.getString("error.title"),
                    String.format(resources.getString("error.densityMapLoad"),
                            dmName));
            return null;
        }
    }

    /**
     * Apply the SVG 16-megapixel soft-warning to a panel export, using the
     * estimated composed-figure dimensions (per design D7). Returns true if
     * the export should proceed, false if the user cancelled.
     */
    private boolean checkPanelExportSize(
            qupath.ext.quiet.export.PanelExportConfig panelConfig) {
        if (panelConfig.getFormat() != OutputFormat.SVG) {
            return true;
        }
        long[] size = panelLayoutPane.estimatedFigureSize();
        long totalPixels = size[0] * size[1];
        if (totalPixels > 16_000_000L) {
            return Dialogs.showConfirmDialog(
                    resources.getString("warning.title"),
                    String.format(resources.getString("warning.svgLargeExport"),
                            size[0], size[1]));
        }
        return true;
    }

    /**
     * Scan selected images for channel consistency before export.
     * Opens each image briefly to read channel metadata (no pixel reading).
     */
    private ChannelScanResult scanChannelConsistency(
            List<ProjectImageEntry<BufferedImage>> entries) {
        Map<String, List<String>> signatureToImages = new LinkedHashMap<>();
        for (var entry : entries) {
            try {
                var imageData = entry.readImageData();
                String sig = BatchExportTask.channelSignature(imageData.getServer());
                signatureToImages.computeIfAbsent(sig, k -> new ArrayList<>())
                        .add(entry.getImageName());
                imageData.getServer().close();
            } catch (Exception e) {
                logger.warn("Failed to read channel info for {}: {}",
                        entry.getImageName(), e.getMessage());
            }
        }
        boolean consistent = signatureToImages.size() <= 1;
        return new ChannelScanResult(consistent, signatureToImages);
    }

    /**
     * Result of scanning images for channel consistency.
     */
    private static class ChannelScanResult {
        final boolean consistent;
        final Map<String, List<String>> signatureToImages;

        ChannelScanResult(boolean consistent, Map<String, List<String>> signatureToImages) {
            this.consistent = consistent;
            this.signatureToImages = signatureToImages;
        }

        String buildGroupSummary() {
            var sb = new StringBuilder();
            int groupNum = 1;
            for (var entry : signatureToImages.entrySet()) {
                sb.append("Group ").append(groupNum++).append(": ");
                var images = entry.getValue();
                if (images.size() <= 3) {
                    sb.append(String.join(", ", images));
                } else {
                    sb.append(images.get(0)).append(", ").append(images.get(1))
                      .append(" ... and ").append(images.size() - 2).append(" more");
                }
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    /**
     * Check if the estimated export size is very large and warn the user.
     * Returns true if the export should proceed, false if the user cancelled.
     */
    private boolean checkExportSize(double downsample, OutputFormat format) {
        if (format == OutputFormat.OME_TIFF_PYRAMID) return true;

        var currentImageData = qupath.getImageData();
        if (currentImageData == null) return true;

        var server = currentImageData.getServer();
        long outW = (long) Math.ceil(server.getWidth() / downsample);
        long outH = (long) Math.ceil(server.getHeight() / downsample);
        long totalPixels = outW * outH;

        if (format == OutputFormat.SVG && totalPixels > 16_000_000L) {
            return Dialogs.showConfirmDialog(
                    resources.getString("warning.title"),
                    String.format(resources.getString("warning.svgLargeExport"),
                            outW, outH));
        }

        if (totalPixels > 100_000_000L) {
            return Dialogs.showConfirmDialog(
                    resources.getString("warning.title"),
                    String.format(resources.getString("warning.largeExport"),
                            outW, outH, format.toString()));
        }

        return true;
    }

    /** The progress bar of the currently active step (panel-aware). */
    private javafx.scene.control.ProgressBar activeProgressBar() {
        if (isPanelMode() && panelLayoutPane != null) {
            return panelLayoutPane.getProgressBar();
        }
        return imageSelectionPane.getProgressBar();
    }

    /** The status label of the currently active step (panel-aware). */
    private javafx.scene.control.Label activeStatusLabel() {
        if (isPanelMode() && panelLayoutPane != null) {
            return panelLayoutPane.getStatusLabel();
        }
        return imageSelectionPane.getStatusLabel();
    }

    private void runTask() {
        var progressBar = activeProgressBar();
        var statusLabel = activeStatusLabel();

        progressBar.setVisible(true);
        progressBar.setManaged(true);
        progressBar.progressProperty().bind(currentTask.progressProperty());
        statusLabel.textProperty().bind(currentTask.messageProperty());
        nextButton.setDisable(true);
        backButton.setDisable(true);

        // Reset button labels for the running state
        cancelButton.setText(resources.getString("button.cancel"));

        // Hide open folder button while export is running
        openFolderButton.setVisible(false);
        openFolderButton.setManaged(false);

        currentTask.setOnSucceeded(e -> Platform.runLater(() -> onExportComplete(currentTask.getValue())));
        currentTask.setOnFailed(e -> Platform.runLater(() -> onExportFailed(currentTask.getException())));
        currentTask.setOnCancelled(e -> Platform.runLater(this::onExportCancelled));

        Thread exportThread = new Thread(currentTask, "QuIET-Export");
        exportThread.setDaemon(true);
        exportThread.start();
    }

    private void onExportComplete(ExportResult result) {
        unbindProgress();
        showPostExportButtons();

        activeStatusLabel().setText(result.getSummary());

        // Show open folder button after successful export, and auto-open the
        // folder so the user lands at the output without an extra click.
        // Same behaviour across every export category (Rendered, Mask, Raw,
        // Tiled, Object Crops, Panel / Montage). Best-effort: if Desktop is
        // unsupported on this platform, the button stays as the fallback.
        if (lastExportDirectory != null && result.getSucceeded() > 0) {
            openFolderButton.setVisible(true);
            openFolderButton.setManaged(true);
            openResultFolder();
        }

        if (result.hasErrors()) {
            String errorText = String.join("\n", result.getErrors());
            Dialogs.showErrorMessage(
                    resources.getString("error.title"),
                    result.getSummary() + "\n\nErrors:\n" + errorText);
        } else if (isPanelMode()) {
            Dialogs.showInfoNotification(
                    resources.getString("name"),
                    result.getSummary() + "\n\n"
                    + String.format(resources.getString("export.sidecarNotice"),
                            "panel_figure_info.txt"));
        } else {
            // Include sidecar file notice in success message
            String sidecarName = (selectedCategory == ExportCategory.MASK)
                    ? "mask_legend.txt" : "export_info.txt";
            Dialogs.showInfoNotification(
                    resources.getString("name"),
                    result.getSummary() + "\n\n"
                    + String.format(resources.getString("export.sidecarNotice"), sidecarName));
        }
    }

    private void onExportFailed(Throwable exception) {
        unbindProgress();
        showPostExportButtons();
        logger.error("Export task failed", exception);

        String message = exception != null ? exception.getMessage() : "Unknown error";
        if (exception instanceof OutOfMemoryError) {
            message = resources.getString("panel.error.outOfMemory");
        }
        activeStatusLabel().setText("Export failed: " + message);
        Dialogs.showErrorMessage(
                resources.getString("error.title"),
                "Export failed: " + message);
    }

    private void onExportCancelled() {
        unbindProgress();
        showPostExportButtons();
        activeStatusLabel().setText("Export cancelled.");
    }

    /**
     * After an export finishes (success, failure, or cancel),
     * switch "Cancel" to "Close" since there is nothing to cancel.
     */
    private void showPostExportButtons() {
        cancelButton.setText(resources.getString("button.close"));
    }

    private void unbindProgress() {
        activeProgressBar().progressProperty().unbind();
        activeStatusLabel().textProperty().unbind();
        nextButton.setDisable(false);
        backButton.setDisable(false);
        updateNavButtons();
        // Restore the layout-preview window's always-on-top flag now the export
        // (and its result / error dialogs) is done. No-op if it is not open.
        if (isPanelMode() && panelLayoutPane != null) {
            panelLayoutPane.restorePreviewWindow();
        }
    }

    /**
     * Open the result folder in the system file manager.
     * <p>
     * The {@link Desktop#open} call runs on a short-lived daemon thread, never
     * on the JavaFX Application Thread: on some platforms (notably WSL with no
     * registered file manager) the underlying native handler can block
     * indefinitely, and calling it on the FX thread would freeze the whole UI.
     */
    private void openResultFolder() {
        if (lastExportDirectory == null || !lastExportDirectory.isDirectory()) {
            return;
        }
        if (!Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            logger.warn("Opening a folder is not supported on this platform: {}",
                    lastExportDirectory.getAbsolutePath());
            return;
        }
        File dir = lastExportDirectory;
        Thread opener = new Thread(() -> {
            try {
                Desktop.getDesktop().open(dir);
            } catch (Exception e) {
                logger.warn("Failed to open result folder {}: {}",
                        dir.getAbsolutePath(), e.getMessage());
            }
        }, "QuIET-open-folder");
        opener.setDaemon(true);
        opener.start();
    }

    /**
     * Save preferences for all config panes.
     * Called when the wizard is closed (Cancel, X button) to ensure
     * user settings persist even without running an export.
     */
    private void saveAllPreferences() {
        try {
            renderedConfigPane.savePreferences();
            maskConfigPane.savePreferences();
            rawConfigPane.savePreferences();
            tiledConfigPane.savePreferences();
            objectCropConfigPane.savePreferences();
            if (panelRecipePane != null) {
                panelRecipePane.savePreferences();
            }
            if (panelLayoutPane != null) {
                panelLayoutPane.savePreferences();
            }
            QuietPreferences.setFilenamePrefix(imageSelectionPane.getFilenamePrefix());
            QuietPreferences.setFilenameSuffix(imageSelectionPane.getFilenameSuffix());
        } catch (Exception e) {
            logger.warn("Failed to save some preferences on wizard close: {}", e.getMessage());
        }
    }

    private void copyScript() {
        String script = generateCurrentScript();
        if (script != null) {
            imageSelectionPane.copyScriptToClipboard(script);
        }
    }

    private void saveScript() {
        String script = generateCurrentScript();
        if (script != null) {
            imageSelectionPane.saveScriptToFile(script);
        }
    }

    private String generateCurrentScript() {
        try {
            File outputDir = imageSelectionPane.getOutputDirectory();
            if (outputDir == null) {
                Dialogs.showWarningNotification(
                        resources.getString("name"),
                        resources.getString("error.invalidDir"));
                return null;
            }

            ExportCategory category = selectedCategory != null
                    ? selectedCategory : categoryPane.getSelectedCategory();

            if (category == ExportCategory.PANEL) {
                // Panel mode does not use the shared Step-3 script buttons.
                return null;
            }
            Object config = switch (category) {
                case RENDERED -> renderedConfigPane.buildConfig(outputDir);
                case MASK -> maskConfigPane.buildConfig(outputDir);
                case RAW -> rawConfigPane.buildConfig(outputDir);
                case TILED -> tiledConfigPane.buildConfig(outputDir);
                case OBJECT_CROPS -> objectCropConfigPane.buildConfig(outputDir);
                case PANEL -> null;  // not reachable
            };

            return ScriptGenerator.generate(category, config);
        } catch (Exception e) {
            logger.warn("Failed to generate script: {}", e.getMessage());
            Dialogs.showErrorMessage(
                    resources.getString("error.title"),
                    "Failed to generate script: " + e.getMessage());
            return null;
        }
    }

    /**
     * Build the current config object for publication advice checking.
     * Returns null if the config is incomplete (user hasn't filled required fields).
     */
    private Object buildCurrentConfigForAdvice() {
        try {
            // Use a temp directory so the config builder doesn't reject null outputDir
            File tempDir = imageSelectionPane.getOutputDirectory();
            if (tempDir == null) {
                tempDir = new File(System.getProperty("java.io.tmpdir"));
            }
            return switch (selectedCategory) {
                case RENDERED -> renderedConfigPane.buildConfig(tempDir);
                case MASK -> maskConfigPane.buildConfig(tempDir);
                case RAW -> rawConfigPane.buildConfig(tempDir);
                case TILED -> tiledConfigPane.buildConfig(tempDir);
                case OBJECT_CROPS -> objectCropConfigPane.buildConfig(tempDir);
                case PANEL -> null;  // panel mode has no shared advice config
            };
        } catch (IllegalArgumentException e) {
            // Config is incomplete -- return null so advice runs with no config
            logger.debug("Config incomplete for advice check: {}", e.getMessage());
            return null;
        }
    }

    private void saveWizardSize() {
        QuietPreferences.setWizardWidth(stage.getWidth());
        QuietPreferences.setWizardHeight(stage.getHeight());
    }
}
