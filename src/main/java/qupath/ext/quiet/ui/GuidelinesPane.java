package qupath.ext.quiet.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import qupath.ext.quiet.advice.ImageContext;
import qupath.ext.quiet.export.ExportCategory;
import qupath.lib.common.ColorTools;
import qupath.lib.gui.QuPathGUI;

/**
 * Context-sensitive QUAREP-LiMi guidelines panel shown alongside export
 * configuration on Step 2 of the export wizard.
 * <p>
 * Scans project images to detect image types (brightfield, fluorescence,
 * multiplex) and channel information, then displays relevant publication
 * quality recommendations for the selected export category.
 *
 * @see <a href="https://doi.org/10.1038/s41592-023-01987-9">
 *      Schmied et al., 2023, Nature Methods</a>
 */
class GuidelinesPane extends ScrollPane {

    private static final Logger logger = LoggerFactory.getLogger(GuidelinesPane.class);

    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.quiet.ui.strings");

    private static final int PANEL_WIDTH = 300;
    private static final int MAX_IMAGES_TO_SCAN = 15;

    private final VBox content;
    private final List<ImageContext> imageContexts;

    /**
     * Create the guidelines panel, scanning project images for context.
     *
     * @param qupath   the QuPath GUI instance
     * @param category the selected export category
     */
    GuidelinesPane(QuPathGUI qupath, ExportCategory category) {
        this.imageContexts = scanProjectImages(qupath);

        content = new VBox(8);
        content.setPadding(new Insets(10));
        content.setPrefWidth(PANEL_WIDTH);
        content.setMaxWidth(PANEL_WIDTH);

        buildGuidelines(category);

        setContent(content);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setPrefWidth(PANEL_WIDTH + 20);
        setMaxWidth(PANEL_WIDTH + 20);
        setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #ddd; "
                + "-fx-border-width: 0 0 0 1;");
    }

    private void buildGuidelines(ExportCategory category) {
        // Header
        var header = new Label(resources.getString("guidelines.header"));
        header.setFont(Font.font(null, FontWeight.BOLD, 13));
        header.setWrapText(true);
        content.getChildren().add(header);

        var subheader = new Label(resources.getString("guidelines.subheader"));
        subheader.setFont(Font.font(null, FontWeight.NORMAL, 10));
        subheader.setTextFill(Color.GRAY);
        subheader.setWrapText(true);
        content.getChildren().add(subheader);

        // QUAREP website link
        var linkLabel = new Label(resources.getString("guidelines.quarepLink"));
        linkLabel.setFont(Font.font(null, FontWeight.NORMAL, 10));
        linkLabel.setTextFill(Color.rgb(30, 100, 180));
        linkLabel.setStyle("-fx-underline: true; -fx-cursor: hand;");
        linkLabel.setWrapText(true);
        content.getChildren().add(linkLabel);

        addSeparator();

        // Image type summary
        addImageTypeSummary();

        // Category-specific guidelines
        switch (category) {
            case RENDERED -> addRenderedGuidelines();
            case MASK -> addMaskGuidelines();
            case RAW -> addRawGuidelines();
            case TILED -> addTiledGuidelines();
            case OBJECT_CROPS -> addObjectCropGuidelines();
            case PANEL -> {
                // Panel / Montage mode does not show the guidelines panel.
            }
        }
    }

    // ---------------------------------------------------------------
    //  Image type summary
    // ---------------------------------------------------------------

    private void addImageTypeSummary() {
        if (imageContexts.isEmpty()) {
            addItem(GuidelineLevel.NOTE,
                    resources.getString("guidelines.noImages.title"),
                    resources.getString("guidelines.noImages.description"));
            addSeparator();
            return;
        }

        boolean hasBF = imageContexts.stream().anyMatch(ImageContext::isBrightfield);
        boolean hasFL = imageContexts.stream().anyMatch(ImageContext::isFluorescence);
        int maxChannels = imageContexts.stream()
                .mapToInt(ImageContext::nChannels).max().orElse(0);

        var summary = new StringBuilder();
        summary.append(imageContexts.size()).append(" image(s) scanned: ");
        if (hasBF && hasFL) {
            summary.append("mixed brightfield + fluorescence");
        } else if (hasBF) {
            summary.append("brightfield");
        } else if (hasFL) {
            summary.append("fluorescence");
            if (maxChannels > 4) {
                summary.append(" (multiplex, up to ").append(maxChannels).append(" channels)");
            }
        } else {
            summary.append("unset/unknown type");
        }

        var summaryLabel = new Label(summary.toString());
        summaryLabel.setFont(Font.font(null, FontWeight.NORMAL, 11));
        summaryLabel.setWrapText(true);
        summaryLabel.setStyle("-fx-background-color: #e8eef4; -fx-padding: 6; "
                + "-fx-background-radius: 4;");
        content.getChildren().add(summaryLabel);

        if (hasBF && hasFL) {
            addItem(GuidelineLevel.NOTE,
                    resources.getString("guidelines.mixedTypes.title"),
                    resources.getString("guidelines.mixedTypes.description"));
        }

        addSeparator();
    }

    // ---------------------------------------------------------------
    //  Category-specific guidelines
    // ---------------------------------------------------------------

    private void addRenderedGuidelines() {
        addSectionHeader(resources.getString("guidelines.rendered.header"));

        // Always show
        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.rendered.lossless.title"),
                resources.getString("guidelines.rendered.lossless.description"),
                "ID-1");

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.rendered.scaleBar.title"),
                resources.getString("guidelines.rendered.scaleBar.description"),
                "IA-1");

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.rendered.consistentDisplay.title"),
                resources.getString("guidelines.rendered.consistentDisplay.description"),
                "IC-3");

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.rendered.dpi.title"),
                resources.getString("guidelines.rendered.dpi.description"),
                "ID-1");

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.rendered.borders.title"),
                resources.getString("guidelines.rendered.borders.description"),
                "ID-2");

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.rendered.legibility.title"),
                resources.getString("guidelines.rendered.legibility.description"),
                "IA-3");

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.rendered.imagingDetails.title"),
                resources.getString("guidelines.rendered.imagingDetails.description"),
                "IA-5");

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.rendered.annotationExplain.title"),
                resources.getString("guidelines.rendered.annotationExplain.description"),
                "IA-2");

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.rendered.dontObscure.title"),
                resources.getString("guidelines.rendered.dontObscure.description"),
                "IA-4");

        addItem(GuidelineLevel.NOTE,
                resources.getString("guidelines.rendered.gamma.title"),
                resources.getString("guidelines.rendered.gamma.description"),
                "IC-9");

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.rendered.quantExample.title"),
                resources.getString("guidelines.rendered.quantExample.description"),
                "ID-3");

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.rendered.phenotypeRange.title"),
                resources.getString("guidelines.rendered.phenotypeRange.description"),
                "ID-5");

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.rendered.availability.title"),
                resources.getString("guidelines.rendered.availability.description"),
                "Avail-1");

        // Fluorescence-specific
        boolean hasFL = imageContexts.stream().anyMatch(ImageContext::isFluorescence);
        if (hasFL) {
            addSeparator();
            addSectionHeader(resources.getString("guidelines.fluorescence.header"));

            // IC-1: Channel annotation for multichannel
            addItem(GuidelineLevel.TIP,
                    resources.getString("guidelines.fluorescence.annotateChannels.title"),
                    resources.getString("guidelines.fluorescence.annotateChannels.description"),
                    "IC-1");

            // Red+green check
            if (hasRedGreenChannels()) {
                addItem(GuidelineLevel.WARNING,
                        resources.getString("guidelines.fluorescence.redGreen.title"),
                        resources.getString("guidelines.fluorescence.redGreen.description"),
                        "IC-6");
            }

            // Channel listing with contrast advice
            addChannelContrastAdvice();

            // Split channel recommendation
            addItem(GuidelineLevel.TIP,
                    resources.getString("guidelines.fluorescence.splitChannels.title"),
                    resources.getString("guidelines.fluorescence.splitChannels.description"),
                    "IC-5");

            // Intensity calibration bar
            addItem(GuidelineLevel.TIP,
                    resources.getString("guidelines.fluorescence.calibrationBar.title"),
                    resources.getString("guidelines.fluorescence.calibrationBar.description"),
                    "IC-7");

            // Multiplex-specific
            int maxCh = imageContexts.stream()
                    .filter(ImageContext::isFluorescence)
                    .mapToInt(ImageContext::nChannels).max().orElse(0);
            if (maxCh > 4) {
                addItem(GuidelineLevel.TIP,
                        resources.getString("guidelines.fluorescence.multiplex.title"),
                        resources.getString("guidelines.fluorescence.multiplex.description"));
            }
        }

        // Brightfield-specific
        boolean hasBF = imageContexts.stream().anyMatch(ImageContext::isBrightfield);
        if (hasBF) {
            addSeparator();
            addSectionHeader(resources.getString("guidelines.brightfield.header"));

            addItem(GuidelineLevel.TIP,
                    resources.getString("guidelines.brightfield.stainAnnotation.title"),
                    resources.getString("guidelines.brightfield.stainAnnotation.description"),
                    "IC-1");

            addItem(GuidelineLevel.TIP,
                    resources.getString("guidelines.brightfield.scaleBarColor.title"),
                    resources.getString("guidelines.brightfield.scaleBarColor.description"),
                    "IA-1");

            addItem(GuidelineLevel.TIP,
                    resources.getString("guidelines.brightfield.display.title"),
                    resources.getString("guidelines.brightfield.display.description"),
                    "IC-2");
        }
    }

    private void addMaskGuidelines() {
        addSectionHeader(resources.getString("guidelines.mask.header"));

        addItem(GuidelineLevel.WARNING,
                resources.getString("guidelines.mask.lossless.title"),
                resources.getString("guidelines.mask.lossless.description"));

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.mask.legend.title"),
                resources.getString("guidelines.mask.legend.description"));

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.mask.boundary.title"),
                resources.getString("guidelines.mask.boundary.description"));
    }

    private void addRawGuidelines() {
        addSectionHeader(resources.getString("guidelines.raw.header"));

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.raw.omeTiff.title"),
                resources.getString("guidelines.raw.omeTiff.description"));

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.raw.pyramid.title"),
                resources.getString("guidelines.raw.pyramid.description"));
    }

    private void addTiledGuidelines() {
        addSectionHeader(resources.getString("guidelines.tiled.header"));

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.tiled.tileSize.title"),
                resources.getString("guidelines.tiled.tileSize.description"));

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.tiled.labels.title"),
                resources.getString("guidelines.tiled.labels.description"));

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.tiled.annotatedOnly.title"),
                resources.getString("guidelines.tiled.annotatedOnly.description"));
    }

    private void addObjectCropGuidelines() {
        addSectionHeader(resources.getString("guidelines.crops.header"));

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.crops.cropSize.title"),
                resources.getString("guidelines.crops.cropSize.description"));

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.crops.classBalance.title"),
                resources.getString("guidelines.crops.classBalance.description"));
    }

    // ---------------------------------------------------------------
    //  Channel analysis helpers
    // ---------------------------------------------------------------

    private boolean hasRedGreenChannels() {
        for (var img : imageContexts) {
            if (!img.isFluorescence() || img.nChannels() < 2) continue;
            if (img.channelColors() == null) continue;

            boolean hasRed = false;
            boolean hasGreen = false;
            for (int color : img.channelColors()) {
                int r = ColorTools.red(color);
                int g = ColorTools.green(color);
                int b = ColorTools.blue(color);
                if (r > 180 && g < 80 && b < 80) hasRed = true;
                if (r < 80 && g > 180 && b < 80) hasGreen = true;
            }
            if (hasRed && hasGreen) return true;
        }
        return false;
    }

    /**
     * Build advice listing the channel colors found in fluorescence images
     * and recommending that overlays/annotations contrast with them.
     */
    private void addChannelContrastAdvice() {
        // Collect unique channel descriptions across all fluorescence images
        Set<String> channelDescriptions = new LinkedHashSet<>();
        for (var img : imageContexts) {
            if (!img.isFluorescence()) continue;
            if (img.channelNames() == null || img.channelColors() == null) continue;
            int n = Math.min(img.channelNames().size(), img.channelColors().size());
            for (int i = 0; i < n; i++) {
                String name = img.channelNames().get(i);
                String colorName = approximateColorName(img.channelColors().get(i));
                channelDescriptions.add(name + " (" + colorName + ")");
            }
        }

        if (channelDescriptions.isEmpty()) return;

        var sb = new StringBuilder();
        sb.append(resources.getString("guidelines.fluorescence.contrast.description"));
        sb.append("\n");
        int count = 0;
        for (String desc : channelDescriptions) {
            if (count >= 8) {
                sb.append("  ... and more\n");
                break;
            }
            sb.append("  - ").append(desc).append("\n");
            count++;
        }

        addItem(GuidelineLevel.TIP,
                resources.getString("guidelines.fluorescence.contrast.title"),
                sb.toString().trim());
    }

    /**
     * Map a packed ARGB color to an approximate human-readable name.
     */
    private static String approximateColorName(int packedColor) {
        int r = ColorTools.red(packedColor);
        int g = ColorTools.green(packedColor);
        int b = ColorTools.blue(packedColor);

        // Check for near-white/gray/black
        if (r > 200 && g > 200 && b > 200) return "white";
        if (r < 50 && g < 50 && b < 50) return "black";
        int max = Math.max(r, Math.max(g, b));
        if (max - Math.min(r, Math.min(g, b)) < 30) return "gray";

        // Dominant color analysis
        if (r > g && r > b) {
            if (g > 100 && b < 80) return "yellow";
            if (b > 100 && g < 80) return "magenta";
            return "red";
        }
        if (g > r && g > b) {
            if (r > 100 && b < 80) return "yellow";
            if (b > 100 && r < 80) return "cyan";
            return "green";
        }
        if (b > r && b > g) {
            if (r > 100 && g < 80) return "magenta";
            if (g > 100 && r < 80) return "cyan";
            return "blue";
        }
        return "mixed";
    }

    // ---------------------------------------------------------------
    //  Image scanning
    // ---------------------------------------------------------------

    /**
     * Scan project images to build ImageContext metadata.
     * Reads image types and channel info without loading pixel data.
     */
    private static List<ImageContext> scanProjectImages(QuPathGUI qupath) {
        var contexts = new ArrayList<ImageContext>();
        var project = qupath.getProject();
        if (project == null) return contexts;

        var entries = project.getImageList();
        int total = entries.size();
        int limit = Math.min(total, MAX_IMAGES_TO_SCAN);

        // Sample evenly across the project so a heterogeneous (e.g. brightfield
        // + fluorescence) project surfaces both types even when the same type
        // is contiguous in the project's natural order.
        for (int i = 0; i < limit; i++) {
            int idx = (limit > 0) ? (int) ((long) i * total / limit) : i;
            try {
                var imageData = entries.get(idx).readImageData();
                var server = imageData.getServer();
                var metadata = server.getMetadata();

                boolean hasCal = metadata.getPixelCalibration().hasPixelSizeMicrons();
                String imageType = imageData.getImageType() != null
                        ? imageData.getImageType().name() : null;

                var channelNames = new ArrayList<String>();
                var channelColors = new ArrayList<Integer>();
                for (var ch : metadata.getChannels()) {
                    channelNames.add(ch.getName());
                    channelColors.add(ch.getColor());
                }

                contexts.add(new ImageContext(
                        hasCal, imageType, channelNames, channelColors,
                        metadata.getSizeC()));
                server.close();
            } catch (Exception e) {
                logger.debug("Failed to read image metadata for guidelines: {}",
                        e.getMessage());
            }
        }
        return contexts;
    }

    // ---------------------------------------------------------------
    //  UI building helpers
    // ---------------------------------------------------------------

    private enum GuidelineLevel {
        WARNING, NOTE, TIP
    }

    private void addSectionHeader(String text) {
        var label = new Label(text);
        label.setFont(Font.font(null, FontWeight.BOLD, 12));
        label.setWrapText(true);
        label.setPadding(new Insets(4, 0, 2, 0));
        content.getChildren().add(label);
    }

    private void addSeparator() {
        var sep = new javafx.scene.control.Separator();
        sep.setPadding(new Insets(2, 0, 2, 0));
        content.getChildren().add(sep);
    }

    private void addItem(GuidelineLevel level, String title, String description) {
        addItem(level, title, description, null);
    }

    private void addItem(GuidelineLevel level, String title, String description,
                         String quarepRef) {
        // Icon
        String iconText = switch (level) {
            case WARNING -> "[!]";
            case NOTE -> "[*]";
            case TIP -> "[>]";
        };
        Color iconColor = switch (level) {
            case WARNING -> Color.DARKORANGE;
            case NOTE -> Color.STEELBLUE;
            case TIP -> Color.rgb(80, 140, 80);
        };

        var icon = new Label(iconText);
        icon.setFont(Font.font("monospace", FontWeight.BOLD, 11));
        icon.setTextFill(iconColor);
        icon.setMinWidth(24);

        var titleLabel = new Label(title);
        titleLabel.setFont(Font.font(null, FontWeight.BOLD, 11));
        titleLabel.setWrapText(true);

        var titleRow = new HBox(4, icon, titleLabel);

        // QUAREP checklist reference tag
        if (quarepRef != null && !quarepRef.isBlank()) {
            var refLabel = new Label("[" + quarepRef + "]");
            refLabel.setFont(Font.font(null, FontWeight.NORMAL, 9));
            refLabel.setTextFill(Color.GRAY);
            titleRow.getChildren().add(refLabel);
        }
        titleRow.setAlignment(Pos.TOP_LEFT);

        var descLabel = new Label(description);
        descLabel.setFont(Font.font(null, FontWeight.NORMAL, 11));
        descLabel.setWrapText(true);
        descLabel.setTextFill(Color.rgb(60, 60, 60));
        descLabel.setPadding(new Insets(0, 0, 0, 28));

        // Tooltip for full text on long descriptions
        if (description.length() > 120) {
            var tip = new Tooltip(description);
            tip.setWrapText(true);
            tip.setMaxWidth(400);
            tip.setShowDuration(Duration.seconds(30));
            Tooltip.install(descLabel, tip);
        }

        var box = new VBox(2, titleRow, descLabel);
        box.setPadding(new Insets(2, 0, 2, 0));
        content.getChildren().add(box);
    }
}
