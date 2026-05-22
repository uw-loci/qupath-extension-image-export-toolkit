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
import javafx.scene.layout.VBox;
import javafx.scene.layout.Priority;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.StringConverter;

import qupath.ext.quiet.export.OutputFormat;
import qupath.ext.quiet.export.RawExportConfig;
import qupath.ext.quiet.preferences.QuietPreferences;

/**
 * Step 2c of the export wizard: Configure raw pixel data export.
 */
public class RawConfigPane extends VBox {

    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.quiet.ui.strings");

    private ComboBox<RawExportConfig.RegionType> regionTypeCombo;
    private ComboBox<Double> downsampleCombo;
    private ComboBox<OutputFormat> formatCombo;
    /**
     * False when this pane is embedded as a panel recipe -- the panel owns the
     * composed-figure output format, so this pane's own format control hides.
     */
    private boolean formatControlVisible = true;

    // Labels promoted for tooltip wiring
    private Label regionTypeLabel;
    private Label downsampleLabel;
    private Label formatLabel;
    private Label pyramidLevelsLabel;
    private Label compressionLabel;
    private Label tileSizeLabel;

    // Padding
    private Label paddingLabel;
    private Spinner<Integer> paddingSpinner;

    // Channel selection
    private VBox channelBox;
    private ListView<ChannelItem> channelListView;

    // Pyramid options
    private Spinner<Integer> pyramidLevelsSpinner;
    private ComboBox<String> compressionCombo;
    private Spinner<Integer> tileSizeSpinner;

    // Collapsible sections
    private TitledPane pyramidSection;
    private TitledPane channelSection;

    // Track if channels have been populated
    private boolean channelsPopulated = false;

    public RawConfigPane() {
        setSpacing(10);
        setPadding(new Insets(10));
        buildUI();
        restorePreferences();
    }

    private void buildUI() {
        var header = new Label(resources.getString("wizard.step2.title") + " - Raw Image Data");
        header.setFont(Font.font(null, FontWeight.BOLD, 14));

        // --- Section 1: Image Settings ---
        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        int row = 0;

        // Region type
        regionTypeLabel = new Label(resources.getString("raw.label.regionType"));
        grid.add(regionTypeLabel, 0, row);
        regionTypeCombo = new ComboBox<>(FXCollections.observableArrayList(
                RawExportConfig.RegionType.values()));
        regionTypeCombo.setValue(RawExportConfig.RegionType.WHOLE_IMAGE);
        regionTypeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(RawExportConfig.RegionType type) {
                if (type == null) return "";
                return switch (type) {
                    case WHOLE_IMAGE -> resources.getString("raw.region.wholeImage");
                    case SELECTED_ANNOTATIONS -> resources.getString("raw.region.selectedAnnotations");
                    case ALL_ANNOTATIONS -> resources.getString("raw.region.allAnnotations");
                };
            }
            @Override
            public RawExportConfig.RegionType fromString(String s) {
                return RawExportConfig.RegionType.WHOLE_IMAGE;
            }
        });
        grid.add(regionTypeCombo, 1, row);
        row++;

        // Downsample
        downsampleLabel = new Label(resources.getString("raw.label.downsample"));
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

        // Format
        formatLabel = new Label(resources.getString("raw.label.format"));
        grid.add(formatLabel, 0, row);
        formatCombo = new ComboBox<>(FXCollections.observableArrayList(
                java.util.Arrays.stream(OutputFormat.values())
                        .filter(f -> f != OutputFormat.SVG)
                        .toList()));
        formatCombo.setValue(OutputFormat.TIFF);
        grid.add(formatCombo, 1, row);
        row++;

        // Padding (visible only for annotation-based region types)
        paddingLabel = new Label(resources.getString("raw.label.padding"));
        grid.add(paddingLabel, 0, row);
        paddingSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 10000, 0, 10));
        paddingSpinner.setEditable(true);
        paddingSpinner.setPrefWidth(100);
        grid.add(paddingSpinner, 1, row);

        var imageSettingsSection = SectionBuilder.createSection(
                resources.getString("raw.section.imageSettings"), true, grid);

        // --- Section 2: Pyramid Options ---
        var pyramidGrid = new GridPane();
        pyramidGrid.setHgap(10);
        pyramidGrid.setVgap(10);

        int pRow = 0;

        pyramidLevelsLabel = new Label(resources.getString("raw.label.pyramidLevels"));
        pyramidGrid.add(pyramidLevelsLabel, 0, pRow);
        pyramidLevelsSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 10, 4));
        pyramidLevelsSpinner.setEditable(true);
        pyramidLevelsSpinner.setPrefWidth(100);
        pyramidGrid.add(pyramidLevelsSpinner, 1, pRow);
        pRow++;

        compressionLabel = new Label(resources.getString("raw.label.compression"));
        pyramidGrid.add(compressionLabel, 0, pRow);
        compressionCombo = new ComboBox<>(FXCollections.observableArrayList(
                "DEFAULT", "LZW", "JPEG", "J2K", "ZLIB", "UNCOMPRESSED"));
        compressionCombo.setValue("DEFAULT");
        pyramidGrid.add(compressionCombo, 1, pRow);
        pRow++;

        tileSizeLabel = new Label(resources.getString("raw.label.tileSize"));
        pyramidGrid.add(tileSizeLabel, 0, pRow);
        tileSizeSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(64, 2048, 512, 64));
        tileSizeSpinner.setEditable(true);
        tileSizeSpinner.setPrefWidth(100);
        pyramidGrid.add(tileSizeSpinner, 1, pRow);

        pyramidSection = SectionBuilder.createSection(
                resources.getString("raw.section.pyramidOptions"), false, pyramidGrid);

        // --- Section 3: Channel Selection ---
        var channelLabel = new Label(resources.getString("raw.label.channels"));
        channelListView = new ListView<>();
        channelListView.setPrefHeight(120);
        channelListView.setCellFactory(lv ->
                new CheckBoxListCell<>(ChannelItem::selectedProperty));
        channelBox = new VBox(5, channelLabel, channelListView);
        VBox.setVgrow(channelListView, Priority.ALWAYS);

        channelSection = SectionBuilder.createSection(
                resources.getString("raw.section.channelSelection"), true, channelBox);

        getChildren().addAll(header, imageSettingsSection, pyramidSection, channelSection);
        VBox.setVgrow(channelSection, Priority.ALWAYS);

        // Dynamic visibility
        regionTypeCombo.valueProperty().addListener((obs, old, newType) -> updateVisibility());
        formatCombo.valueProperty().addListener((obs, old, newFmt) -> updateVisibility());
        updateVisibility();
        wireTooltips();
    }

    private void updateVisibility() {
        var regionType = regionTypeCombo.getValue();
        boolean isAnnotation = regionType == RawExportConfig.RegionType.SELECTED_ANNOTATIONS
                || regionType == RawExportConfig.RegionType.ALL_ANNOTATIONS;
        paddingLabel.setVisible(isAnnotation);
        paddingLabel.setManaged(isAnnotation);
        paddingSpinner.setVisible(isAnnotation);
        paddingSpinner.setManaged(isAnnotation);

        boolean isPyramid = formatControlVisible
                && formatCombo.getValue() == OutputFormat.OME_TIFF_PYRAMID;
        pyramidSection.setVisible(isPyramid);
        pyramidSection.setManaged(isPyramid);
    }

    private void wireTooltips() {
        regionTypeCombo.setTooltip(createTooltip("tooltip.raw.regionType"));
        regionTypeLabel.setTooltip(createTooltip("tooltip.raw.regionType"));
        downsampleCombo.setTooltip(createTooltip("tooltip.raw.downsample"));
        downsampleLabel.setTooltip(createTooltip("tooltip.raw.downsample"));
        formatCombo.setTooltip(createTooltip("tooltip.raw.format"));
        formatLabel.setTooltip(createTooltip("tooltip.raw.format"));
        paddingSpinner.setTooltip(createTooltip("tooltip.raw.padding"));
        paddingLabel.setTooltip(createTooltip("tooltip.raw.padding"));
        pyramidLevelsSpinner.setTooltip(createTooltip("tooltip.raw.pyramidLevels"));
        pyramidLevelsLabel.setTooltip(createTooltip("tooltip.raw.pyramidLevels"));
        compressionCombo.setTooltip(createTooltip("tooltip.raw.compression"));
        compressionLabel.setTooltip(createTooltip("tooltip.raw.compression"));
        tileSizeSpinner.setTooltip(createTooltip("tooltip.raw.tileSize"));
        tileSizeLabel.setTooltip(createTooltip("tooltip.raw.tileSize"));
        channelListView.setTooltip(createTooltip("tooltip.raw.channels"));
    }

    private static Tooltip createTooltip(String key) {
        var tip = new Tooltip(resources.getString(key));
        tip.setWrapText(true);
        tip.setMaxWidth(400);
        tip.setShowDuration(javafx.util.Duration.seconds(30));
        return tip;
    }

    /**
     * Populate the channel list from the given server metadata.
     * Called externally when image data is available.
     */
    public void populateChannels(List<String> channelNames) {
        channelListView.getItems().clear();
        if (channelNames == null || channelNames.isEmpty()) {
            channelSection.setVisible(false);
            channelSection.setManaged(false);
            channelsPopulated = false;
            return;
        }
        for (int i = 0; i < channelNames.size(); i++) {
            channelListView.getItems().add(new ChannelItem(i, channelNames.get(i), true));
        }
        channelSection.setVisible(true);
        channelSection.setManaged(true);
        channelsPopulated = true;
    }

    private void restorePreferences() {
        String savedType = QuietPreferences.getRawRegionType();
        try { regionTypeCombo.setValue(RawExportConfig.RegionType.valueOf(savedType)); }
        catch (IllegalArgumentException e) { /* keep default */ }

        double savedDs = QuietPreferences.getRawDownsample();
        if (savedDs >= 1.0) downsampleCombo.setValue(savedDs);

        String savedFormat = QuietPreferences.getRawFormat();
        try { formatCombo.setValue(OutputFormat.valueOf(savedFormat)); }
        catch (IllegalArgumentException e) { /* keep default */ }

        paddingSpinner.getValueFactory().setValue(QuietPreferences.getRawPadding());
        pyramidLevelsSpinner.getValueFactory().setValue(QuietPreferences.getRawPyramidLevels());
        tileSizeSpinner.getValueFactory().setValue(QuietPreferences.getRawTileSize());

        String savedCompression = QuietPreferences.getRawCompression();
        if (savedCompression != null && !savedCompression.isEmpty()) {
            compressionCombo.setValue(savedCompression);
        }
    }

    /**
     * Save current UI state to persistent preferences.
     */
    public void savePreferences() {
        var type = regionTypeCombo.getValue();
        if (type != null) QuietPreferences.setRawRegionType(type.name());
        Double ds = downsampleCombo.getValue();
        if (ds != null) QuietPreferences.setRawDownsample(ds);
        var fmt = formatCombo.getValue();
        if (fmt != null) QuietPreferences.setRawFormat(fmt.name());
        QuietPreferences.setRawPadding(paddingSpinner.getValue());
        QuietPreferences.setRawPyramidLevels(pyramidLevelsSpinner.getValue());
        QuietPreferences.setRawTileSize(tileSizeSpinner.getValue());
        String comp = compressionCombo.getValue();
        if (comp != null) QuietPreferences.setRawCompression(comp);
    }

    /**
     * Build a RawExportConfig from current UI state.
     */
    public RawExportConfig buildConfig(File outputDir) {
        var builder = new RawExportConfig.Builder()
                .regionType(regionTypeCombo.getValue())
                .downsample(downsampleCombo.getValue() != null ? downsampleCombo.getValue() : 4.0)
                .format(formatCombo.getValue())
                .outputDirectory(outputDir)
                .paddingPixels(paddingSpinner.getValue());

        // Channel selection -- only if channels were populated and not all selected
        if (channelsPopulated && !channelListView.getItems().isEmpty()) {
            List<Integer> selected = new ArrayList<>();
            boolean allSelected = true;
            for (var item : channelListView.getItems()) {
                if (item.isSelected()) {
                    selected.add(item.getIndex());
                } else {
                    allSelected = false;
                }
            }
            if (!allSelected && !selected.isEmpty()) {
                builder.selectedChannels(selected);
            }
        }

        // Pyramid options
        if (formatCombo.getValue() == OutputFormat.OME_TIFF_PYRAMID) {
            builder.pyramidLevels(pyramidLevelsSpinner.getValue());
            builder.tileSize(tileSizeSpinner.getValue());
            String comp = compressionCombo.getValue();
            if (comp != null && !"DEFAULT".equals(comp)) {
                builder.compressionType(comp);
            }
        }

        return builder.build();
    }

    /**
     * Wrapper for channel names with selection state and index.
     */
    public static class ChannelItem {
        private final int index;
        private final String name;
        private final BooleanProperty selected;

        public ChannelItem(int index, String name, boolean selected) {
            this.index = index;
            this.name = name;
            this.selected = new SimpleBooleanProperty(selected);
        }

        public int getIndex() { return index; }
        public String getName() { return name; }
        public boolean isSelected() { return selected.get(); }
        public void setSelected(boolean value) { selected.set(value); }
        public BooleanProperty selectedProperty() { return selected; }

        @Override
        public String toString() { return index + ": " + name; }
    }

    /**
     * Show or hide advanced controls for simple mode.
     * Hides padding, pyramid options, and channel selection.
     */
    public void setSimpleMode(boolean simple) {
        paddingLabel.setVisible(!simple);
        paddingLabel.setManaged(!simple);
        paddingSpinner.setVisible(!simple);
        paddingSpinner.setManaged(!simple);
        pyramidSection.setVisible(!simple && formatControlVisible);
        pyramidSection.setManaged(!simple && formatControlVisible);
        channelSection.setVisible(!simple);
        channelSection.setManaged(!simple);
    }

    /**
     * Show or hide this pane's output-format control. Hidden when the pane is
     * embedded as a panel recipe -- the panel itself owns the composed-figure
     * output format, so a second format control here would be redundant.
     */
    public void setFormatControlVisible(boolean visible) {
        this.formatControlVisible = visible;
        formatLabel.setVisible(visible);
        formatLabel.setManaged(visible);
        formatCombo.setVisible(visible);
        formatCombo.setManaged(visible);
        updateVisibility();
    }
}
