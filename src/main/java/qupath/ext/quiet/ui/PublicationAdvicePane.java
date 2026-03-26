package qupath.ext.quiet.ui;

import java.util.List;
import java.util.ResourceBundle;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import qupath.ext.quiet.advice.AdviceItem;
import qupath.ext.quiet.advice.AdviceSeverity;

/**
 * JavaFX pane that displays publication advice items.
 * <p>
 * Each item shows a severity icon, title, QUAREP reference, and the full
 * description with suggested action shown inline (not just in a tooltip).
 * <p>
 * Can be displayed either as a collapsible section or as a floating modeless
 * dialog via {@link #showAsDialog(Stage)}.
 */
class PublicationAdvicePane extends VBox {

    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.quiet.ui.strings");

    private final Label summaryLabel;
    private final VBox itemsBox;
    private Stage floatingDialog;
    private List<AdviceItem> currentItems = List.of();

    PublicationAdvicePane() {
        setSpacing(5);
        setPadding(new Insets(5));

        summaryLabel = new Label(resources.getString("advice.noIssues"));
        summaryLabel.setFont(Font.font(null, FontWeight.NORMAL, 12));

        itemsBox = new VBox(6);

        getChildren().addAll(summaryLabel, itemsBox);
    }

    /**
     * Update the displayed advice items.
     *
     * @param items the advice items to display (may be empty)
     */
    void update(List<AdviceItem> items) {
        currentItems = items != null ? List.copyOf(items) : List.of();
        itemsBox.getChildren().clear();

        if (currentItems.isEmpty()) {
            summaryLabel.setText(resources.getString("advice.noIssues"));
            summaryLabel.setTextFill(Color.GRAY);
            return;
        }

        // Count by severity
        long errors = currentItems.stream()
                .filter(i -> i.severity() == AdviceSeverity.ERROR).count();
        long warnings = currentItems.stream()
                .filter(i -> i.severity() == AdviceSeverity.WARNING).count();
        long info = currentItems.stream()
                .filter(i -> i.severity() == AdviceSeverity.INFO).count();

        summaryLabel.setText(String.format(
                resources.getString("advice.summary"), errors, warnings, info));
        summaryLabel.setTextFill(errors > 0 ? Color.RED
                : warnings > 0 ? Color.DARKORANGE : Color.GRAY);

        for (var item : currentItems) {
            itemsBox.getChildren().add(createItemRow(item));
        }
    }

    /** Get the current advice items. */
    List<AdviceItem> getItems() {
        return currentItems;
    }

    /**
     * Show the advice as a floating modeless dialog window.
     *
     * @param owner the owner stage (the wizard window)
     */
    void showAsDialog(Stage owner) {
        if (floatingDialog != null && floatingDialog.isShowing()) {
            floatingDialog.toFront();
            return;
        }

        floatingDialog = new Stage();
        floatingDialog.initOwner(owner);
        floatingDialog.initStyle(StageStyle.UTILITY);
        floatingDialog.initModality(Modality.NONE);
        floatingDialog.setTitle(resources.getString("advice.dialog.title"));

        var scroll = new ScrollPane(this);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        var scene = new Scene(scroll, 450, 400);
        floatingDialog.setScene(scene);
        floatingDialog.setMinWidth(350);
        floatingDialog.setMinHeight(200);

        // Position beside the owner
        floatingDialog.setX(owner.getX() + owner.getWidth() + 10);
        floatingDialog.setY(owner.getY());

        floatingDialog.show();
    }

    /** Close the floating dialog if open. */
    void closeDialog() {
        if (floatingDialog != null && floatingDialog.isShowing()) {
            floatingDialog.close();
        }
        floatingDialog = null;
    }

    /** Whether the floating dialog is currently visible. */
    boolean isDialogShowing() {
        return floatingDialog != null && floatingDialog.isShowing();
    }

    /**
     * Wrap this pane in a collapsible TitledPane section.
     */
    TitledPane asCollapsibleSection() {
        return SectionBuilder.createSection(
                resources.getString("advice.section.title"), false, this);
    }

    private Node createItemRow(AdviceItem item) {
        // Severity icon
        Label icon = new Label(severityIcon(item.severity()));
        icon.setTextFill(severityColor(item.severity()));
        icon.setFont(Font.font("monospace", FontWeight.BOLD, 12));
        icon.setMinWidth(30);

        // Title
        Label title = new Label(item.title());
        title.setFont(Font.font(null, FontWeight.BOLD, 12));
        title.setTextFill(severityColor(item.severity()));

        // QUAREP ref
        Label ref = new Label();
        if (item.quarepRef() != null) {
            ref.setText("[" + item.quarepRef() + "]");
            ref.setTextFill(Color.GRAY);
            ref.setFont(Font.font(null, FontWeight.NORMAL, 11));
        }

        var titleRow = new HBox(5, icon, title, ref);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        // Show description and action inline (not hidden in tooltip)
        var descLabel = new Label(item.description());
        descLabel.setWrapText(true);
        descLabel.setFont(Font.font(null, FontWeight.NORMAL, 11));
        descLabel.setTextFill(Color.rgb(60, 60, 60));
        descLabel.setPadding(new Insets(0, 0, 0, 30));

        var itemBox = new VBox(2, titleRow, descLabel);

        if (item.suggestedAction() != null && !item.suggestedAction().isEmpty()) {
            var actionLabel = new Label("Action: " + item.suggestedAction());
            actionLabel.setWrapText(true);
            actionLabel.setFont(Font.font(null, FontWeight.NORMAL, 11));
            actionLabel.setTextFill(Color.rgb(40, 100, 40));
            actionLabel.setPadding(new Insets(0, 0, 0, 30));
            itemBox.getChildren().add(actionLabel);
        }

        if (item.configSection() != null) {
            var sectionHint = new Label("-> " + formatSectionName(item.configSection()));
            sectionHint.setFont(Font.font(null, FontWeight.BOLD, 10));
            sectionHint.setTextFill(Color.rgb(100, 100, 160));
            sectionHint.setPadding(new Insets(0, 0, 0, 30));
            itemBox.getChildren().add(sectionHint);
        }

        itemBox.setPadding(new Insets(2, 0, 2, 0));
        return itemBox;
    }

    private static String formatSectionName(String section) {
        return switch (section) {
            case "scaleBar" -> "Scale Bar section";
            case "format" -> "Format / Output Settings";
            case "displaySettings" -> "Display Settings section";
            case "splitChannel" -> "Split Channel Export section";
            case "tiledLabels" -> "Label Mask section";
            default -> section;
        };
    }

    private static String severityIcon(AdviceSeverity severity) {
        return switch (severity) {
            case ERROR -> "[!]";
            case WARNING -> "[*]";
            case INFO -> "[i]";
        };
    }

    private static Color severityColor(AdviceSeverity severity) {
        return switch (severity) {
            case ERROR -> Color.RED;
            case WARNING -> Color.DARKORANGE;
            case INFO -> Color.STEELBLUE;
        };
    }
}
