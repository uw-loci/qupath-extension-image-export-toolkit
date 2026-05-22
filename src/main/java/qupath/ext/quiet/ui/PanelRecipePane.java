package qupath.ext.quiet.ui;

import java.io.File;
import java.util.ResourceBundle;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.quiet.export.ExportCategory;
import qupath.ext.quiet.export.ExportRecipe;
import qupath.ext.quiet.preferences.QuietPreferences;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

/**
 * Recipe step of the panel / montage wizard: choose and configure the export
 * <em>recipe</em>.
 * <p>
 * The recipe defines how each panel cell is rendered. The user picks one of
 * the three single-image categories (Rendered, Raw, Mask) via a
 * compact toggle-button group; the matching single-image config pane is
 * embedded below and swapped in/out. Load / Save recipe buttons read and write
 * a portable {@link ExportRecipe} JSON file.
 * <p>
 * The embedded config panes are the same {@code *ConfigPane} types the
 * single-image flow uses; their preference round-trip, simple-mode toggle and
 * {@code buildConfig} are reused unchanged. A config pane restores its state
 * from {@link QuietPreferences} in its constructor, so loading a recipe (which
 * writes the snapshot into the preference store) is followed by recreating the
 * relevant pane.
 */
public class PanelRecipePane extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(PanelRecipePane.class);

    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.quiet.ui.strings");

    private final QuPathGUI qupath;
    private final Stage ownerStage;

    private RenderedConfigPane renderedConfigPane;
    private RawConfigPane rawConfigPane;
    private MaskConfigPane maskConfigPane;

    private final ToggleGroup categoryGroup = new ToggleGroup();
    private final ToggleButton renderedToggle;
    private final ToggleButton rawToggle;
    private final ToggleButton maskToggle;

    private final StackPane embeddedHolder = new StackPane();
    private final Label loadedRecipeLabel;

    private ExportCategory recipeCategory = ExportCategory.RENDERED;
    private boolean simpleMode = true;

    public PanelRecipePane(QuPathGUI qupath, Stage ownerStage) {
        this.qupath = qupath;
        this.ownerStage = ownerStage;
        setSpacing(10);
        setPadding(new Insets(10));

        renderedConfigPane = new RenderedConfigPane(qupath);
        rawConfigPane = new RawConfigPane();
        maskConfigPane = new MaskConfigPane(qupath);
        // The panel's Layout step owns the composed-figure output format.
        // Hide each recipe pane's own format control so the format is set in
        // exactly one place, not two.
        renderedConfigPane.setFormatControlVisible(false);
        rawConfigPane.setFormatControlVisible(false);
        maskConfigPane.setFormatControlVisible(false);

        var banner = new Label(resources.getString("panel.banner"));
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setStyle("-fx-background-color: #e8f0fe; -fx-padding: 6 10 6 10; "
                + "-fx-font-weight: bold;");

        var header = new Label(resources.getString("panel.step3.title"));
        header.setFont(Font.font(null, FontWeight.BOLD, 14));

        var intro = new Label(resources.getString("panel.step3.intro"));
        intro.setWrapText(true);

        renderedToggle = makeCategoryToggle(
                resources.getString("category.rendered.title"), ExportCategory.RENDERED);
        rawToggle = makeCategoryToggle(
                resources.getString("category.raw.title"), ExportCategory.RAW);
        maskToggle = makeCategoryToggle(
                resources.getString("category.mask.title"), ExportCategory.MASK);

        var toggleRow = new HBox(5, renderedToggle, rawToggle, maskToggle);

        var loadButton = new Button(resources.getString("panel.recipe.loadButton"));
        loadButton.setTooltip(tooltip("tooltip.panel.loadRecipe"));
        loadButton.setOnAction(e -> loadRecipe());
        var saveButton = new Button(resources.getString("panel.recipe.saveButton"));
        saveButton.setTooltip(tooltip("tooltip.panel.saveRecipe"));
        saveButton.setOnAction(e -> saveRecipe());

        loadedRecipeLabel = new Label(resources.getString("panel.recipe.usingCurrent"));
        loadedRecipeLabel.setTooltip(tooltip("tooltip.panel.loadedRecipe"));
        loadedRecipeLabel.setStyle("-fx-text-fill: #555555;");

        var recipeButtons = new HBox(8, loadButton, saveButton, loadedRecipeLabel);
        recipeButtons.setAlignment(Pos.CENTER_LEFT);

        var sourceLabel = new Label(resources.getString("panel.recipe.sourceLabel"));
        sourceLabel.setFont(Font.font(null, FontWeight.BOLD, 12));

        var sourceBox = new VBox(6, sourceLabel, toggleRow, recipeButtons);
        var sourceSection = SectionBuilder.createSection(
                resources.getString("panel.section.recipeSource"), true, sourceBox);

        VBox.setVgrow(embeddedHolder, Priority.ALWAYS);
        getChildren().addAll(banner, header, intro, sourceSection, embeddedHolder);

        // Restore last recipe category.
        try {
            recipeCategory = ExportCategory.valueOf(
                    QuietPreferences.getPanelRecipeCategory());
            if (!isPanelRecipeCategory(recipeCategory)) {
                recipeCategory = ExportCategory.RENDERED;
            }
        } catch (IllegalArgumentException e) {
            recipeCategory = ExportCategory.RENDERED;
        }
        selectToggleFor(recipeCategory);
        showEmbeddedPane();
    }

    private ToggleButton makeCategoryToggle(String text, ExportCategory category) {
        var toggle = new ToggleButton(text);
        toggle.setToggleGroup(categoryGroup);
        toggle.setUserData(category);
        toggle.setTooltip(tooltip("tooltip.panel.recipeCategory"));
        toggle.setOnAction(e -> {
            if (toggle.isSelected()) {
                recipeCategory = category;
                QuietPreferences.setPanelRecipeCategory(category.name());
                showEmbeddedPane();
            } else {
                // Prevent deselecting all -- keep this one selected.
                toggle.setSelected(true);
            }
        });
        return toggle;
    }

    /**
     * The categories valid as a panel recipe: those that render to exactly one
     * image per source image. Object Crops (many crops per image), Tiled (many
     * tiles per image) and Panel itself are excluded.
     */
    static boolean isPanelRecipeCategory(ExportCategory category) {
        return category == ExportCategory.RENDERED
                || category == ExportCategory.RAW
                || category == ExportCategory.MASK;
    }

    private void selectToggleFor(ExportCategory category) {
        switch (category) {
            case RAW -> rawToggle.setSelected(true);
            case MASK -> maskToggle.setSelected(true);
            default -> renderedToggle.setSelected(true);
        }
    }

    private void showEmbeddedPane() {
        Node pane = switch (recipeCategory) {
            case RAW -> rawConfigPane;
            case MASK -> maskConfigPane;
            default -> renderedConfigPane;
        };
        var scroll = new ScrollPane(pane);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        embeddedHolder.getChildren().setAll(scroll);
        applySimpleMode(simpleMode);
    }

    private static Tooltip tooltip(String key) {
        var tip = new Tooltip(resources.getString(key));
        tip.setWrapText(true);
        tip.setMaxWidth(400);
        tip.setShowDuration(Duration.seconds(30));
        return tip;
    }

    /**
     * Apply the simple/advanced mode toggle to the embedded recipe pane.
     */
    public void applySimpleMode(boolean simple) {
        this.simpleMode = simple;
        renderedConfigPane.setSimpleMode(simple);
        rawConfigPane.setSimpleMode(simple);
        maskConfigPane.setSimpleMode(simple);
    }

    /**
     * The currently selected recipe category.
     */
    public ExportCategory getRecipeCategory() {
        return recipeCategory;
    }

    /**
     * Persist the embedded config panes' preferences.
     */
    public void savePreferences() {
        renderedConfigPane.savePreferences();
        rawConfigPane.savePreferences();
        maskConfigPane.savePreferences();
    }

    /**
     * Build the recipe's single-image config object for the selected category.
     * <p>
     * The output format / directory on the returned config are placeholders --
     * the panel owns the composed-figure output. A temp directory is supplied
     * so the builder's required-field validation passes.
     *
     * @return the recipe config object (a {@code *ExportConfig})
     */
    public Object buildRecipeConfig() {
        savePreferences();
        File tempDir = new File(System.getProperty("java.io.tmpdir"));
        return switch (recipeCategory) {
            case RAW -> rawConfigPane.buildConfig(tempDir);
            case MASK -> maskConfigPane.buildConfig(tempDir);
            default -> renderedConfigPane.buildConfig(tempDir);
        };
    }

    /**
     * The pixel-classifier name selected on the embedded Rendered recipe pane,
     * or {@code null} when the recipe category is not RENDERED. Used by the
     * wizard to resolve the classifier a CLASSIFIER_OVERLAY recipe needs.
     */
    public String getRenderedClassifierName() {
        return recipeCategory == ExportCategory.RENDERED
                ? renderedConfigPane.getClassifierName() : null;
    }

    /**
     * The density-map name selected on the embedded Rendered recipe pane, or
     * {@code null} when the recipe category is not RENDERED.
     */
    public String getRenderedDensityMapName() {
        return recipeCategory == ExportCategory.RENDERED
                ? renderedConfigPane.getDensityMapName() : null;
    }

    /**
     * Save the current recipe settings to a JSON file chosen by the user.
     */
    private void saveRecipe() {
        savePreferences();
        var chooser = new FileChooser();
        chooser.setTitle(resources.getString("panel.recipe.saveButton"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                resources.getString("panel.recipe.fileDescription"), "*.json"));
        chooser.setInitialFileName(
                "quiet_" + recipeCategory.name().toLowerCase() + "_recipe.json");
        File file = chooser.showSaveDialog(ownerStage);
        if (file == null) {
            return;
        }
        if (!file.getName().toLowerCase().endsWith(".json")) {
            file = new File(file.getParentFile(), file.getName() + ".json");
        }
        try {
            var snapshot = QuietPreferences.snapshotCategoryPreferences(recipeCategory);
            var recipe = new ExportRecipe(recipeCategory, snapshot);
            recipe.saveToFile(file);
            loadedRecipeLabel.setText(resources.getString("panel.recipe.loadedPrefix")
                    + " " + file.getName());
            logger.info("Saved panel recipe: {}", file.getAbsolutePath());
        } catch (Exception e) {
            Dialogs.showErrorMessage(resources.getString("error.title"),
                    String.format(resources.getString("panel.recipe.saveError"),
                            e.getMessage()));
        }
    }

    /**
     * Load a recipe from a JSON file chosen by the user, validating the file
     * and refreshing the relevant embedded config pane.
     */
    private void loadRecipe() {
        var chooser = new FileChooser();
        chooser.setTitle(resources.getString("panel.recipe.loadButton"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                resources.getString("panel.recipe.fileDescription"), "*.json"));
        File file = chooser.showOpenDialog(ownerStage);
        if (file == null) {
            return;
        }
        ExportRecipe recipe;
        try {
            recipe = ExportRecipe.loadFromFile(file);
        } catch (Exception e) {
            Dialogs.showErrorMessage(resources.getString("error.title"),
                    String.format(resources.getString("panel.recipe.loadError"),
                            e.getMessage()));
            return;
        }
        ExportCategory category = recipe.getCategory();

        // A panel recipe must render one image per source image. Object Crops,
        // Tiled and Panel recipes are rejected cleanly.
        if (!isPanelRecipeCategory(category)) {
            Dialogs.showErrorMessage(resources.getString("error.title"),
                    String.format(
                            resources.getString("panel.recipe.unsupportedCategory"),
                            category.getDisplayName()));
            return;
        }

        // Completeness guard: a recipe must carry every key for its category.
        // A partial recipe is rejected cleanly rather than applied piecemeal.
        var expectedKeys = QuietPreferences.recipeKeyNames(category);
        var missing = new java.util.ArrayList<String>();
        for (String key : expectedKeys) {
            if (!recipe.getSettings().containsKey(key)) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            logger.warn("Recipe {} is missing {} of {} keys for category {}",
                    file.getName(), missing.size(), expectedKeys.size(), category);
            Dialogs.showErrorMessage(resources.getString("error.title"),
                    String.format(resources.getString("panel.recipe.incomplete"),
                            file.getName(), missing.size()));
            return;
        }

        // Apply the snapshot into the embedded config pane's in-memory state
        // ONLY -- without leaving the user's shared single-image export
        // preferences mutated. The config panes read from the preference store
        // in their constructors, so the store is temporarily set to the recipe
        // values, the pane is recreated, then the store is restored.
        var priorPrefs = QuietPreferences.snapshotCategoryPreferences(category);
        try {
            QuietPreferences.restoreCategoryPreferences(category, recipe.getSettings());
            switch (category) {
                case RAW -> {
                    rawConfigPane = new RawConfigPane();
                    rawConfigPane.setFormatControlVisible(false);
                }
                case MASK -> {
                    maskConfigPane = new MaskConfigPane(qupath);
                    maskConfigPane.setFormatControlVisible(false);
                }
                default -> {
                    renderedConfigPane = new RenderedConfigPane(qupath);
                    renderedConfigPane.setFormatControlVisible(false);
                }
            }
        } finally {
            // Restore the user's own single-image export preferences -- loading
            // a panel recipe must not overwrite them.
            QuietPreferences.restoreCategoryPreferences(category, priorPrefs);
        }
        recipeCategory = category;
        QuietPreferences.setPanelRecipeCategory(category.name());
        selectToggleFor(category);
        showEmbeddedPane();
        loadedRecipeLabel.setText(resources.getString("panel.recipe.loadedPrefix")
                + " " + file.getName());
        logger.info("Loaded panel recipe: {}", file.getAbsolutePath());
    }
}
