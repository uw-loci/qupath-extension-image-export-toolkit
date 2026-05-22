package qupath.ext.quiet.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.quiet.export.CellFitMode;
import qupath.ext.quiet.export.ExportCategory;
import qupath.ext.quiet.export.OutputFormat;
import qupath.ext.quiet.export.PanelComposer;
import qupath.ext.quiet.export.PanelExportConfig;
import qupath.ext.quiet.export.PanelImageExporter;
import qupath.ext.quiet.preferences.QuietPreferences;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Layout step of the panel / montage wizard: grid layout, captions, output
 * format and the live composed-size feedback.
 * <p>
 * The grid spinners seed to a near-square layout from the selected image
 * count; the output-format combo is gated live against a 100-megapixel cap
 * (only OME-TIFF above that). The pane builds a validated
 * {@link PanelExportConfig}.
 */
public class PanelLayoutPane extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(PanelLayoutPane.class);

    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.quiet.ui.strings");

    /** Composed-figure pixel cap above which only OME-TIFF is offered. */
    private static final long SIZE_CAP_PIXELS = 100_000_000L;

    /** Maximum images opened to estimate the cell size and metadata keys. */
    private static final int SCAN_LIMIT = 12;

    private final Stage ownerStage;

    private Spinner<Integer> rowsSpinner;
    private Spinner<Integer> colsSpinner;
    private Spinner<Integer> gutterXSpinner;
    private Spinner<Integer> gutterYSpinner;
    private ColorPicker backgroundPicker;
    private ComboBox<CellFitMode> cellFitCombo;
    private Label gridSummaryLabel;
    private Label reseedNoticeLabel;

    private CheckBox showFilenameCheck;
    private ToggleGroup captionPositionGroup;
    private RadioButton aboveRadio;
    private RadioButton belowRadio;
    private ListView<MetadataFieldItem> metadataFieldsList;
    private Spinner<Integer> captionFontSizeSpinner;
    private ColorPicker captionColorPicker;

    private ComboBox<OutputFormat> formatCombo;
    private TextField outputDirField;
    private TextField filenameField;
    private Label extensionLabel;

    private Label composedSizeLabel;
    private Label estimatedFileSizeLabel;
    private Label formatGatingNotice;

    /** Interactive visual layout preview (grid + thumbnails + drag-reorder). */
    private PanelLayoutPreview layoutPreview;

    /** The separate preview window, or null when it is not open (singleton). */
    private Stage previewStage;

    private javafx.scene.control.ProgressBar progressBar;
    private Label statusLabel;

    private List<ProjectImageEntry<BufferedImage>> selectedEntries = new ArrayList<>();
    private int seededCount = -1;
    private int estimatedCellWidth = 512;
    private int estimatedCellHeight = 512;
    private int estimatedChannels = 3;

    /** The recipe's single-image config, used for the real per-cell downsample. */
    private Object recipeConfig;

    /** QuPath GUI reference, used to resolve pixel size for DPI-driven recipes. */
    private final QuPathGUI qupathRef;

    public PanelLayoutPane(QuPathGUI qupath, Stage ownerStage) {
        this.qupathRef = qupath;
        this.ownerStage = ownerStage;
        setSpacing(10);
        setPadding(new Insets(10));
        buildUI();
        restorePreferences();
    }

    private void buildUI() {
        var banner = new Label(resources.getString("panel.banner"));
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setStyle("-fx-background-color: #e8f0fe; -fx-padding: 6 10 6 10; "
                + "-fx-font-weight: bold;");

        var header = new Label(resources.getString("panel.step4.title"));
        header.setFont(Font.font(null, FontWeight.BOLD, 14));

        progressBar = new javafx.scene.control.ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        statusLabel = new Label();
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        getChildren().addAll(banner, header,
                buildGridSection(),
                buildCaptionsSection(),
                buildPreviewSection(),
                buildOutputSection(),
                buildSizeSection(),
                progressBar,
                statusLabel);
    }

    /**
     * The layout preview section. The interactive {@link PanelLayoutPreview}
     * itself lives in a separate, non-modal window opened by the button here --
     * the wizard step is too narrow to host it comfortably. The preview
     * component instance is created eagerly so it keeps receiving live layout
     * updates even while its window is closed; reordering a cell in it makes
     * the preview's order the source of truth for the export.
     */
    private VBox buildPreviewSection() {
        layoutPreview = new PanelLayoutPreview();
        layoutPreview.setReorderListener(this::updateSizeFeedback);

        var note = new Label(resources.getString("panel.step4.openPreviewNote"));
        note.setWrapText(true);
        note.setStyle("-fx-text-fill: #555555;");

        var openButton = new Button(resources.getString("panel.step4.openPreviewButton"));
        openButton.setTooltip(tooltip("tooltip.panel.openPreview"));
        openButton.setOnAction(e -> showPreviewWindow());

        var content = new VBox(6, note, openButton);
        content.setPadding(new Insets(5));
        return new VBox(6, SectionBuilder.createSection(
                resources.getString("panel.step4.previewSection"), true, content));
    }

    /**
     * Open the interactive layout preview in its own window, or focus the
     * existing window if it is already open.
     * <p>
     * The window is owned by the wizard stage (so it closes with the wizard),
     * non-modal (so the user can keep changing wizard settings while it is
     * open) and always-on-top (so it is not lost behind the wizard). The
     * {@link PanelLayoutPreview} component is the same instance the wizard
     * feeds live updates to, so it stays current while the window is open.
     */
    private void showPreviewWindow() {
        if (previewStage != null) {
            // Singleton: already open -- raise and focus it.
            previewStage.toFront();
            previewStage.requestFocus();
            return;
        }
        var stage = new Stage();
        stage.initOwner(ownerStage);
        stage.initModality(Modality.NONE);
        stage.setAlwaysOnTop(true);
        stage.setTitle(resources.getString("panel.preview.windowTitle"));

        var note = new Label(resources.getString("panel.step4.previewNote"));
        note.setWrapText(true);
        note.setStyle("-fx-text-fill: #555555;");
        var content = new VBox(8, note, layoutPreview);
        content.setPadding(new Insets(10));
        VBox.setVgrow(layoutPreview, Priority.ALWAYS);

        stage.setScene(new javafx.scene.Scene(content, 720, 560));
        stage.setMinWidth(420);
        stage.setMinHeight(320);

        // Clear the tracking reference when the user closes the window, so the
        // button reopens it (and refreshes it with the latest settings).
        stage.setOnHidden(e -> {
            if (previewStage == stage) {
                previewStage = null;
            }
        });
        previewStage = stage;

        // Push the current layout into the preview before it is shown, so a
        // window opened after settings changed reflects them immediately.
        updateSizeFeedback();
        stage.show();
    }

    /**
     * Lower the preview window's always-on-top flag so wizard alerts and the
     * export progress / result dialogs are not hidden behind it. No-op when the
     * preview window is closed. See {@link #restorePreviewWindow()}.
     */
    public void suppressPreviewWindow() {
        if (previewStage != null) {
            previewStage.setAlwaysOnTop(false);
        }
    }

    /**
     * Restore the preview window's always-on-top flag after an export-related
     * dialog sequence has finished. No-op when the preview window is closed.
     */
    public void restorePreviewWindow() {
        if (previewStage != null) {
            previewStage.setAlwaysOnTop(true);
        }
    }

    /** The progress bar for the compose task. */
    public javafx.scene.control.ProgressBar getProgressBar() {
        return progressBar;
    }

    /** The status label for the compose task. */
    public Label getStatusLabel() {
        return statusLabel;
    }

    private VBox buildGridSection() {
        var sectionLabel = new Label(resources.getString("panel.step4.gridSection"));
        sectionLabel.setFont(Font.font(null, FontWeight.BOLD, 12));

        rowsSpinner = intSpinner(1, 100, 2, 1);
        rowsSpinner.setTooltip(tooltip("tooltip.panel.rows"));
        colsSpinner = intSpinner(1, 100, 2, 1);
        colsSpinner.setTooltip(tooltip("tooltip.panel.cols"));
        gutterXSpinner = intSpinner(0, 1000, 10, 5);
        gutterXSpinner.setTooltip(tooltip("tooltip.panel.gutterX"));
        gutterYSpinner = intSpinner(0, 1000, 10, 5);
        gutterYSpinner.setTooltip(tooltip("tooltip.panel.gutterY"));

        backgroundPicker = new ColorPicker(Color.WHITE);
        backgroundPicker.setTooltip(tooltip("tooltip.panel.background"));

        cellFitCombo = new ComboBox<>(FXCollections.observableArrayList(
                CellFitMode.values()));
        cellFitCombo.setValue(CellFitMode.FIT_LETTERBOX);
        cellFitCombo.setTooltip(tooltip("tooltip.panel.cellFit"));
        cellFitCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(CellFitMode m) {
                return m == null ? "" : m.getDisplayName();
            }

            @Override
            public CellFitMode fromString(String s) {
                return CellFitMode.FIT_LETTERBOX;
            }
        });

        gridSummaryLabel = new Label();
        gridSummaryLabel.setTooltip(tooltip("tooltip.panel.gridSummary"));
        reseedNoticeLabel = new Label();
        reseedNoticeLabel.setStyle("-fx-text-fill: #b36b00;");
        reseedNoticeLabel.setVisible(false);
        reseedNoticeLabel.setManaged(false);

        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.add(new Label(resources.getString("panel.step4.rowsLabel")), 0, 0);
        grid.add(rowsSpinner, 1, 0);
        grid.add(new Label(resources.getString("panel.step4.colsLabel")), 2, 0);
        grid.add(colsSpinner, 3, 0);
        grid.add(gridSummaryLabel, 4, 0);
        grid.add(new Label(resources.getString("panel.step4.gutterXLabel")), 0, 1);
        grid.add(gutterXSpinner, 1, 1);
        grid.add(new Label(resources.getString("panel.step4.gutterYLabel")), 2, 1);
        grid.add(gutterYSpinner, 3, 1);
        grid.add(new Label(resources.getString("panel.step4.backgroundLabel")), 0, 2);
        grid.add(backgroundPicker, 1, 2);
        grid.add(new Label(resources.getString("panel.step4.cellFitLabel")), 2, 2);
        grid.add(cellFitCombo, 3, 2);

        // Live updates.
        Runnable update = this::updateSizeFeedback;
        rowsSpinner.valueProperty().addListener((o, a, b) -> update.run());
        colsSpinner.valueProperty().addListener((o, a, b) -> update.run());
        gutterXSpinner.valueProperty().addListener((o, a, b) -> update.run());
        gutterYSpinner.valueProperty().addListener((o, a, b) -> update.run());
        cellFitCombo.valueProperty().addListener((o, a, b) -> update.run());
        // Background colour does not affect the size readout but does affect
        // the visual preview, so it also drives a redraw.
        backgroundPicker.valueProperty().addListener((o, a, b) -> update.run());

        return new VBox(6, sectionLabel, grid, reseedNoticeLabel);
    }

    private VBox buildCaptionsSection() {
        showFilenameCheck = new CheckBox(resources.getString("panel.step4.showFilename"));
        showFilenameCheck.setTooltip(tooltip("tooltip.panel.showFilename"));

        captionPositionGroup = new ToggleGroup();
        aboveRadio = new RadioButton(resources.getString("panel.step4.positionAbove"));
        aboveRadio.setToggleGroup(captionPositionGroup);
        aboveRadio.setTooltip(tooltip("tooltip.panel.captionPosition"));
        belowRadio = new RadioButton(resources.getString("panel.step4.positionBelow"));
        belowRadio.setToggleGroup(captionPositionGroup);
        belowRadio.setTooltip(tooltip("tooltip.panel.captionPosition"));
        belowRadio.setSelected(true);
        var positionRow = new HBox(10,
                new Label(resources.getString("panel.step4.captionPosition")),
                aboveRadio, belowRadio);
        positionRow.setAlignment(Pos.CENTER_LEFT);

        metadataFieldsList = new ListView<>();
        metadataFieldsList.setPrefHeight(110);
        metadataFieldsList.setTooltip(tooltip("tooltip.panel.metadataFields"));
        metadataFieldsList.setCellFactory(lv ->
                new CheckBoxListCell<>(MetadataFieldItem::selectedProperty));

        captionFontSizeSpinner = intSpinner(6, 96, 14, 1);
        captionFontSizeSpinner.setTooltip(tooltip("tooltip.panel.captionFontSize"));
        captionColorPicker = new ColorPicker(Color.BLACK);
        captionColorPicker.setTooltip(tooltip("tooltip.panel.captionColor"));

        var fontRow = new HBox(10,
                new Label(resources.getString("panel.step4.captionFontSize")),
                captionFontSizeSpinner,
                new Label(resources.getString("panel.step4.captionColor")),
                captionColorPicker);
        fontRow.setAlignment(Pos.CENTER_LEFT);

        var captionNote = new Label(resources.getString("panel.step4.captionNote"));
        captionNote.setWrapText(true);
        captionNote.setStyle("-fx-text-fill: #555555;");

        var content = new VBox(8, captionNote, showFilenameCheck, positionRow,
                new Label(resources.getString("panel.step4.metadataFieldsLabel")),
                metadataFieldsList, fontRow);
        content.setPadding(new Insets(5));

        // Disable caption font/colour when no caption content is on.
        Runnable updateEnabled = () -> {
            boolean anyContent = showFilenameCheck.isSelected()
                    || metadataFieldsList.getItems().stream()
                            .anyMatch(MetadataFieldItem::isSelected);
            captionFontSizeSpinner.setDisable(!anyContent);
            captionColorPicker.setDisable(!anyContent);
            aboveRadio.setDisable(!anyContent);
            belowRadio.setDisable(!anyContent);
            updateSizeFeedback();
        };
        showFilenameCheck.selectedProperty().addListener((o, a, b) -> updateEnabled.run());
        captionFontSizeSpinner.valueProperty().addListener((o, a, b) -> updateSizeFeedback());
        // Caption position affects the preview (band above vs below the cell).
        captionPositionGroup.selectedToggleProperty()
                .addListener((o, a, b) -> updateSizeFeedback());

        captionEnabledUpdater = updateEnabled;

        return new VBox(6,
                SectionBuilder.createSection(
                        resources.getString("panel.step4.captionsSection"), false, content));
    }

    private Runnable captionEnabledUpdater = () -> { };

    private VBox buildOutputSection() {
        var sectionLabel = new Label(resources.getString("panel.step4.outputSection"));
        sectionLabel.setFont(Font.font(null, FontWeight.BOLD, 12));

        formatCombo = new ComboBox<>();
        formatCombo.setTooltip(tooltip("tooltip.panel.format"));
        formatCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(OutputFormat f) {
                return f == null ? "" : f.toString();
            }

            @Override
            public OutputFormat fromString(String s) {
                return OutputFormat.PNG;
            }
        });
        formatCombo.valueProperty().addListener((o, a, b) -> {
            if (b != null) {
                extensionLabel.setText("." + b.getExtension());
            }
        });

        outputDirField = new TextField();
        outputDirField.setTooltip(tooltip("tooltip.panel.outputFolder"));
        HBox.setHgrow(outputDirField, Priority.ALWAYS);
        var browseButton = new Button(resources.getString("button.browse"));
        browseButton.setTooltip(tooltip("tooltip.panel.browse"));
        browseButton.setOnAction(e -> browseOutputDir());

        filenameField = new TextField("panel_figure");
        filenameField.setTooltip(tooltip("tooltip.panel.filename"));
        HBox.setHgrow(filenameField, Priority.ALWAYS);
        extensionLabel = new Label(".png");

        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.add(new Label(resources.getString("panel.step4.formatLabel")), 0, 0);
        grid.add(formatCombo, 1, 0);
        grid.add(new Label(resources.getString("panel.step4.outputFolderLabel")), 0, 1);
        var dirRow = new HBox(5, outputDirField, browseButton);
        HBox.setHgrow(outputDirField, Priority.ALWAYS);
        grid.add(dirRow, 1, 1);
        GridPane.setHgrow(dirRow, Priority.ALWAYS);
        grid.add(new Label(resources.getString("panel.step4.filenameLabel")), 0, 2);
        var fileRow = new HBox(5, filenameField, extensionLabel);
        fileRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(filenameField, Priority.ALWAYS);
        grid.add(fileRow, 1, 2);
        GridPane.setHgrow(fileRow, Priority.ALWAYS);

        return new VBox(6, sectionLabel, grid);
    }

    private VBox buildSizeSection() {
        composedSizeLabel = new Label();
        composedSizeLabel.setTooltip(tooltip("tooltip.panel.composedSize"));
        estimatedFileSizeLabel = new Label();
        estimatedFileSizeLabel.setTooltip(tooltip("tooltip.panel.estimatedFileSize"));
        formatGatingNotice = new Label(resources.getString("panel.step4.formatGatingNotice"));
        formatGatingNotice.setWrapText(true);
        formatGatingNotice.setStyle("-fx-text-fill: #b36b00;");
        formatGatingNotice.setVisible(false);
        formatGatingNotice.setManaged(false);

        var box = new VBox(4, composedSizeLabel, estimatedFileSizeLabel,
                formatGatingNotice);
        box.setPadding(new Insets(8));
        box.setStyle("-fx-border-color: #cccccc; -fx-border-radius: 4;");

        return new VBox(6, SectionBuilder.createSection(
                resources.getString("panel.step4.sizeSection"), true, box));
    }

    private static Spinner<Integer> intSpinner(int min, int max, int initial, int step) {
        var spinner = new Spinner<Integer>(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, initial, step));
        spinner.setEditable(true);
        spinner.setPrefWidth(90);
        // Commit a typed-but-not-entered value when focus leaves the spinner,
        // so the grid/gutter/font values used at compose time are never stale.
        spinner.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                commitSpinner(spinner);
            }
        });
        return spinner;
    }

    /** Commit the spinner editor's current text into its value factory. */
    private static void commitSpinner(Spinner<Integer> spinner) {
        if (!spinner.isEditable()) {
            return;
        }
        String text = spinner.getEditor().getText();
        var factory = spinner.getValueFactory();
        if (factory == null || text == null) {
            return;
        }
        try {
            factory.setValue(factory.getConverter().fromString(text));
        } catch (RuntimeException e) {
            // Reject unparseable input -- restore the editor to the live value.
            spinner.getEditor().setText(factory.getConverter().toString(factory.getValue()));
        }
    }

    private static Tooltip tooltip(String key) {
        var tip = new Tooltip(resources.getString(key));
        tip.setWrapText(true);
        tip.setMaxWidth(400);
        tip.setShowDuration(Duration.seconds(30));
        return tip;
    }

    private void browseOutputDir() {
        var chooser = new DirectoryChooser();
        chooser.setTitle(resources.getString("panel.step4.outputFolderLabel"));
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

    private void restorePreferences() {
        rowsSpinner.getValueFactory().setValue(QuietPreferences.getPanelRows());
        colsSpinner.getValueFactory().setValue(QuietPreferences.getPanelCols());
        gutterXSpinner.getValueFactory().setValue(QuietPreferences.getPanelGutterX());
        gutterYSpinner.getValueFactory().setValue(QuietPreferences.getPanelGutterY());
        backgroundPicker.setValue(parseColor(QuietPreferences.getPanelBackgroundColor(),
                Color.WHITE));
        try {
            cellFitCombo.setValue(CellFitMode.valueOf(
                    QuietPreferences.getPanelCellFitMode()));
        } catch (IllegalArgumentException e) {
            cellFitCombo.setValue(CellFitMode.FIT_LETTERBOX);
        }
        showFilenameCheck.setSelected(QuietPreferences.isPanelShowFilenameCaption());
        if ("ABOVE".equals(QuietPreferences.getPanelCaptionPosition())) {
            aboveRadio.setSelected(true);
        } else {
            belowRadio.setSelected(true);
        }
        captionFontSizeSpinner.getValueFactory()
                .setValue(QuietPreferences.getPanelCaptionFontSize());
        captionColorPicker.setValue(parseColor(QuietPreferences.getPanelCaptionColor(),
                Color.BLACK));
        filenameField.setText(QuietPreferences.getPanelFilename());
        seededCount = QuietPreferences.getPanelSeededCount();
    }

    /**
     * Persist all panel-layout fields.
     */
    public void savePreferences() {
        QuietPreferences.setPanelRows(rowsSpinner.getValue());
        QuietPreferences.setPanelCols(colsSpinner.getValue());
        QuietPreferences.setPanelGutterX(gutterXSpinner.getValue());
        QuietPreferences.setPanelGutterY(gutterYSpinner.getValue());
        QuietPreferences.setPanelBackgroundColor(toHex(backgroundPicker.getValue()));
        QuietPreferences.setPanelCellFitMode(cellFitCombo.getValue().name());
        QuietPreferences.setPanelShowFilenameCaption(showFilenameCheck.isSelected());
        QuietPreferences.setPanelCaptionPosition(
                aboveRadio.isSelected() ? "ABOVE" : "BELOW");
        QuietPreferences.setPanelCaptionFontSize(captionFontSizeSpinner.getValue());
        QuietPreferences.setPanelCaptionColor(toHex(captionColorPicker.getValue()));
        QuietPreferences.setPanelMetadataFields(String.join(",", getSelectedMetadataFields()));
        QuietPreferences.setPanelFilename(filenameField.getText());
        QuietPreferences.setPanelSeededCount(seededCount);
        var fmt = formatCombo.getValue();
        if (fmt != null) {
            QuietPreferences.setPanelFormat(fmt.name());
        }
    }

    /**
     * Refresh this pane for a set of selected images and a recipe.
     * Seeds the grid to near-square if the image count changed, scans for cell
     * dimensions and metadata keys in a single pass, and recomputes the size
     * feedback using the recipe's real downsample.
     *
     * @param entries        the selected image entries (panel placement order)
     * @param recipeCategory the recipe category (for format gating)
     * @param recipeConfig   the recipe's single-image config object, used for
     *                       the real per-cell downsample (may be null)
     */
    public void refreshForSelection(List<ProjectImageEntry<BufferedImage>> entries,
                                    ExportCategory recipeCategory,
                                    Object recipeConfig) {
        this.selectedEntries = entries != null ? entries : new ArrayList<>();
        this.recipeConfig = recipeConfig;
        int n = selectedEntries.size();

        // Near-square re-seed if the count changed since the grid was seeded.
        if (n > 0 && n != seededCount) {
            int cols = (int) Math.ceil(Math.sqrt(n));
            int rows = (int) Math.ceil((double) n / cols);
            int prevCount = seededCount;
            colsSpinner.getValueFactory().setValue(cols);
            rowsSpinner.getValueFactory().setValue(rows);
            if (prevCount > 0 && prevCount != n) {
                reseedNoticeLabel.setText(String.format(
                        resources.getString("panel.step4.reseedNotice"),
                        prevCount, n, rows, cols));
                reseedNoticeLabel.setVisible(true);
                reseedNoticeLabel.setManaged(true);
            } else {
                reseedNoticeLabel.setVisible(false);
                reseedNoticeLabel.setManaged(false);
            }
            seededCount = n;
        }

        var scan = scanSelection();
        rebuildMetadataFieldList(scan.metadataKeys);
        rebuildFormatCombo(recipeCategory);
        // Feed the visual preview. Only reset the preview's entry list when the
        // selection actually changed, so a user's drag-reorder survives a
        // return to this step with the same images.
        if (layoutPreview != null && !sameEntries(layoutPreview.getOrderedEntries(),
                selectedEntries)) {
            layoutPreview.setEntries(selectedEntries);
        }
        updateSizeFeedback();
    }

    /**
     * Whether two entry lists contain the same entries in any order (used to
     * decide if a drag-reorder should be preserved across a step revisit).
     */
    private static boolean sameEntries(List<ProjectImageEntry<BufferedImage>> a,
                                       List<ProjectImageEntry<BufferedImage>> b) {
        if (a.size() != b.size()) {
            return false;
        }
        return new java.util.HashSet<>(a).equals(new java.util.HashSet<>(b));
    }

    /**
     * The selected image entries in the user's current preview arrangement.
     * This is the order the panel figure is composed in -- the wizard uses it
     * (not the raw selection-list order) as the export's source of truth.
     *
     * @return the ordered entry list
     */
    public List<ProjectImageEntry<BufferedImage>> getOrderedEntries() {
        if (layoutPreview != null) {
            return layoutPreview.getOrderedEntries();
        }
        return new ArrayList<>(selectedEntries);
    }

    /** Result of a single image-scan pass: dimensions plus metadata keys. */
    private static final class ScanResult {
        final List<String> metadataKeys;

        ScanResult(List<String> metadataKeys) {
            this.metadataKeys = metadataKeys;
        }
    }

    /**
     * Open a few images once to estimate a representative cell size and channel
     * count and collect the union of metadata keys. Best-effort -- defaults are
     * kept on failure. A single pass (previously the size scan and the metadata
     * scan opened the same images separately, doubling the I/O).
     */
    private ScanResult scanSelection() {
        int maxW = 0;
        int maxH = 0;
        int channels = estimatedChannels;
        int scanned = 0;
        var keys = new java.util.LinkedHashSet<String>();
        for (var entry : selectedEntries) {
            if (scanned >= SCAN_LIMIT) {
                break;
            }
            try {
                var imageData = entry.readImageData();
                try {
                    var server = imageData.getServer();
                    maxW = Math.max(maxW, server.getWidth());
                    maxH = Math.max(maxH, server.getHeight());
                    channels = server.nChannels();
                    keys.addAll(PanelImageExporter.readMetadataKeys(entry, imageData));
                    scanned++;
                } finally {
                    imageData.getServer().close();
                }
            } catch (Exception e) {
                logger.debug("Failed to scan {}: {}",
                        entry.getImageName(), e.getMessage());
            }
        }
        if (maxW > 0 && maxH > 0) {
            // Use the recipe's real downsample so the composed-size readout and
            // the 100 MP format gating match the actual export.
            double ds = recipeDownsample();
            estimatedCellWidth = Math.max(1, (int) Math.round(maxW / ds));
            estimatedCellHeight = Math.max(1, (int) Math.round(maxH / ds));
            estimatedChannels = Math.max(1, channels);
        }
        return new ScanResult(new ArrayList<>(keys));
    }

    /**
     * The effective per-cell downsample governing the real render, taken from
     * the recipe config. Honours the RENDERED DPI-driven resolution mode.
     * Falls back to 1.0 (full resolution) when no recipe config is available.
     */
    private double recipeDownsample() {
        Object recipe = recipeConfig;
        if (recipe instanceof qupath.ext.quiet.export.RenderedExportConfig rc) {
            if (rc.getTargetDpi() > 0) {
                // Estimate from the current image's pixel size, if available.
                var imageData = qupathRef != null ? qupathRef.getImageData() : null;
                if (imageData != null) {
                    var cal = imageData.getServer().getPixelCalibration();
                    if (cal != null && cal.hasPixelSizeMicrons()) {
                        return Math.max(1.0, rc.computeEffectiveDownsample(
                                cal.getAveragedPixelSizeMicrons()));
                    }
                }
            }
            return Math.max(1.0, rc.getDownsample());
        }
        if (recipe instanceof qupath.ext.quiet.export.RawExportConfig raw) {
            return Math.max(1.0, raw.getDownsample());
        }
        if (recipe instanceof qupath.ext.quiet.export.MaskExportConfig mc) {
            return Math.max(1.0, mc.getDownsample());
        }
        if (recipe instanceof qupath.ext.quiet.export.ObjectCropConfig occ) {
            return Math.max(1.0, occ.getDownsample());
        }
        return 1.0;
    }

    private void rebuildMetadataFieldList(List<String> keys) {
        var previouslySelected = new java.util.HashSet<>(getSelectedMetadataFields());
        if (previouslySelected.isEmpty()) {
            String saved = QuietPreferences.getPanelMetadataFields();
            if (saved != null && !saved.isBlank()) {
                for (String s : saved.split(",")) {
                    if (!s.isBlank()) {
                        previouslySelected.add(s.trim());
                    }
                }
            }
        }
        var items = FXCollections.<MetadataFieldItem>observableArrayList();
        for (String key : keys) {
            var item = new MetadataFieldItem(key, previouslySelected.contains(key));
            item.selectedProperty().addListener((o, a, b) -> captionEnabledUpdater.run());
            items.add(item);
        }
        metadataFieldsList.setItems(items);
        captionEnabledUpdater.run();
    }

    /**
     * Rebuild the output-format combo. Below the 100 MP cap the standard
     * formats are offered (filtered to the recipe category's allowed set);
     * at or above the cap only OME-TIFF (pyramid) is allowed.
     */
    private void rebuildFormatCombo(ExportCategory recipeCategory) {
        long pixels = computeFigurePixels();
        var allowed = new ArrayList<OutputFormat>();
        if (pixels >= SIZE_CAP_PIXELS) {
            allowed.add(OutputFormat.OME_TIFF_PYRAMID);
            formatGatingNotice.setVisible(true);
            formatGatingNotice.setManaged(true);
        } else {
            allowed.add(OutputFormat.PNG);
            allowed.add(OutputFormat.TIFF);
            if (allowsJpeg(recipeCategory)) {
                allowed.add(OutputFormat.JPEG);
            }
            if (allowsSvg(recipeCategory)) {
                allowed.add(OutputFormat.SVG);
            }
            allowed.add(OutputFormat.OME_TIFF);
            allowed.add(OutputFormat.OME_TIFF_PYRAMID);
            formatGatingNotice.setVisible(false);
            formatGatingNotice.setManaged(false);
        }
        OutputFormat previous = formatCombo.getValue();
        formatCombo.setItems(FXCollections.observableArrayList(allowed));
        if (previous != null && allowed.contains(previous)) {
            formatCombo.setValue(previous);
        } else {
            // Try restoring the saved format, else first allowed.
            OutputFormat saved = null;
            try {
                saved = OutputFormat.valueOf(QuietPreferences.getPanelFormat());
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
            formatCombo.setValue(saved != null && allowed.contains(saved)
                    ? saved : allowed.get(0));
        }
    }

    /**
     * Whether the recipe category permits JPEG output (Mask and Raw forbid it).
     */
    private static boolean allowsJpeg(ExportCategory recipeCategory) {
        return recipeCategory != ExportCategory.MASK
                && recipeCategory != ExportCategory.RAW;
    }

    /**
     * Whether the recipe category permits SVG output (Mask forbids it).
     */
    private static boolean allowsSvg(ExportCategory recipeCategory) {
        return recipeCategory != ExportCategory.MASK;
    }

    /**
     * Recompute and display the composed-figure size and a rough file-size
     * estimate. Also re-gates the format combo across the 100 MP line.
     */
    private void updateSizeFeedback() {
        // Debounce lightly by deferring to the FX queue.
        Platform.runLater(this::doUpdateSizeFeedback);
    }

    private void doUpdateSizeFeedback() {
        PanelExportConfig probe;
        try {
            probe = buildProbeConfig();
        } catch (RuntimeException e) {
            return;
        }
        int maxCaptionLines = countCaptionLines();
        long[] size = PanelComposer.computeFigureSize(probe,
                estimatedCellWidth, estimatedCellHeight, maxCaptionLines);
        double megapixels = (size[0] * size[1]) / 1_000_000.0;
        composedSizeLabel.setText(String.format(
                resources.getString("panel.step4.composedSize"),
                size[0], size[1], megapixels));

        long bytes = estimateBytes(formatCombo.getValue(), size[0], size[1],
                estimatedChannels);
        estimatedFileSizeLabel.setText(String.format(
                resources.getString("panel.step4.estimatedFileSize"),
                humanBytes(bytes))
                + " " + resources.getString("panel.step4.estimateDisclaimer"));

        updateGridSummary();
        // Re-gate the format combo as size crosses the cap.
        long pixels = size[0] * size[1];
        boolean overCap = pixels >= SIZE_CAP_PIXELS;
        boolean noticeShown = formatGatingNotice.isVisible();
        if (overCap != noticeShown) {
            rebuildFormatCombo(lastRecipeCategory);
        }

        // Refresh the interactive visual preview with the current settings.
        if (layoutPreview != null) {
            layoutPreview.updateLayout(probe, estimatedCellWidth,
                    estimatedCellHeight, maxCaptionLines);
        }
    }

    private ExportCategory lastRecipeCategory = ExportCategory.RENDERED;

    private void updateGridSummary() {
        int n = selectedEntries.size();
        int rows = rowsSpinner.getValue();
        int cols = colsSpinner.getValue();
        int capacity = rows * cols;
        if (capacity == n) {
            gridSummaryLabel.setText(String.format(
                    resources.getString("panel.step4.gridSummaryFits"),
                    n, rows, cols, capacity));
            gridSummaryLabel.setStyle("");
        } else if (capacity > n) {
            gridSummaryLabel.setText(String.format(
                    resources.getString("panel.step4.gridSummaryEmpty"),
                    n, rows, cols, capacity, capacity - n));
            gridSummaryLabel.setStyle("");
        } else {
            // Overflow: not every image will be placed. Amber-emphasise so the
            // problem is distinguishable by colour as well as by the text.
            gridSummaryLabel.setText(String.format(
                    resources.getString("panel.step4.gridSummaryOverflow"),
                    n, rows, cols, capacity, n - capacity));
            gridSummaryLabel.setStyle("-fx-text-fill: #b36b00; -fx-font-weight: bold;");
        }
    }

    private long computeFigurePixels() {
        long[] size = estimatedFigureSize();
        return size[0] * size[1];
    }

    /**
     * The estimated composed-figure dimensions {@code [width, height]} from
     * the current grid / gutter / caption settings and the scanned cell size.
     * Returns {@code [0, 0]} if the probe config cannot be built.
     */
    public long[] estimatedFigureSize() {
        PanelExportConfig probe;
        try {
            probe = buildProbeConfig();
        } catch (RuntimeException e) {
            return new long[] {0, 0};
        }
        return PanelComposer.computeFigureSize(probe,
                estimatedCellWidth, estimatedCellHeight, countCaptionLines());
    }

    private int countCaptionLines() {
        int lines = showFilenameCheck.isSelected() ? 1 : 0;
        lines += getSelectedMetadataFields().size();
        return lines;
    }

    /**
     * A probe config with a temp output dir, used purely for the size
     * arithmetic so it does not require the real output directory.
     */
    private PanelExportConfig buildProbeConfig() {
        // A layout probe -- the preview and size readout need only the grid,
        // gutters, captions and background, not the recipe config. buildForLayout
        // skips the export-only requirements (recipe config / output directory).
        return panelBuilder().buildForLayout();
    }

    private PanelExportConfig.Builder panelBuilder() {
        var builder = new PanelExportConfig.Builder()
                .recipeCategory(lastRecipeCategory)
                .rows(rowsSpinner.getValue())
                .cols(colsSpinner.getValue())
                .gutterX(gutterXSpinner.getValue())
                .gutterY(gutterYSpinner.getValue())
                .backgroundColor(toAwt(backgroundPicker.getValue()))
                .cellFitMode(cellFitCombo.getValue())
                .showFilenameCaption(showFilenameCheck.isSelected())
                .captionPosition(aboveRadio.isSelected()
                        ? PanelExportConfig.CaptionPosition.ABOVE
                        : PanelExportConfig.CaptionPosition.BELOW)
                .metadataFields(getSelectedMetadataFields())
                .captionFontSize(captionFontSizeSpinner.getValue())
                .captionColor(toAwt(captionColorPicker.getValue()))
                .format(formatCombo.getValue() != null
                        ? formatCombo.getValue() : OutputFormat.PNG)
                .filename(filenameField.getText());
        return builder;
    }

    /**
     * Build the final {@link PanelExportConfig} for export.
     *
     * @param recipeCategory the recipe category
     * @param recipeConfig   the recipe's single-image config object
     * @param outputDir      the composed-figure output directory
     * @return a validated panel export config
     */
    public PanelExportConfig buildConfig(ExportCategory recipeCategory,
                                         Object recipeConfig, File outputDir) {
        this.lastRecipeCategory = recipeCategory;
        return panelBuilder()
                .recipeCategory(recipeCategory)
                .recipeConfig(recipeConfig)
                .outputDirectory(outputDir)
                .build();
    }

    /**
     * Set the recipe category so format gating uses the right allowed-format
     * set. Called by the wizard when entering the layout step.
     */
    public void setRecipeCategory(ExportCategory category) {
        this.lastRecipeCategory = category;
        rebuildFormatCombo(category);
    }

    /** The output directory text, or null if blank. */
    public File getOutputDirectory() {
        String text = outputDirField.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        return new File(text);
    }

    public void setOutputDirectory(String path) {
        outputDirField.setText(path);
    }

    /** The currently selected output format. */
    public OutputFormat getFormat() {
        return formatCombo.getValue();
    }

    private List<String> getSelectedMetadataFields() {
        var list = new ArrayList<String>();
        if (metadataFieldsList != null) {
            for (var item : metadataFieldsList.getItems()) {
                if (item.isSelected()) {
                    list.add(item.getKey());
                }
            }
        }
        return list;
    }

    // ------------------------------------------------------------------
    // File-size estimation
    // ------------------------------------------------------------------

    /**
     * Estimate the output file size in bytes from per-format bytes-per-pixel
     * heuristics. A rough estimate only -- the real size depends on content.
     */
    private static long estimateBytes(OutputFormat format, long width, long height,
                                       int channels) {
        long pixels = width * height;
        double bytesPerPixel = switch (format == null ? OutputFormat.PNG : format) {
            case PNG -> 1.4;
            case TIFF -> 3.0;
            case JPEG -> 0.35;
            case SVG -> 1.5;
            case OME_TIFF -> 3.0;
            case OME_TIFF_PYRAMID -> 4.0;
        };
        return Math.round(pixels * bytesPerPixel);
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format("%.0f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ------------------------------------------------------------------
    // Colour helpers
    // ------------------------------------------------------------------

    private static Color parseColor(String hex, Color fallback) {
        try {
            return Color.web(hex);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String toHex(Color c) {
        if (c == null) {
            return "#FFFFFF";
        }
        return String.format("#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    private static java.awt.Color toAwt(Color c) {
        if (c == null) {
            return java.awt.Color.WHITE;
        }
        return new java.awt.Color(
                (float) c.getRed(), (float) c.getGreen(),
                (float) c.getBlue(), (float) c.getOpacity());
    }

    /**
     * A metadata field key with a checkbox selection state, for the captions
     * field list.
     */
    public static final class MetadataFieldItem {
        private final String key;
        private final BooleanProperty selected;

        MetadataFieldItem(String key, boolean selected) {
            this.key = key;
            this.selected = new SimpleBooleanProperty(selected);
        }

        public String getKey() {
            return key;
        }

        public boolean isSelected() {
            return selected.get();
        }

        public BooleanProperty selectedProperty() {
            return selected;
        }

        @Override
        public String toString() {
            return key;
        }
    }
}
