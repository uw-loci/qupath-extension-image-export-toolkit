package qupath.ext.quiet.ui;

import java.util.List;
import java.util.ResourceBundle;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import qupath.ext.quiet.advice.AdviceItem;
import qupath.ext.quiet.advice.AdviceSeverity;

/**
 * JavaFX pane that displays publication advice items.
 * <p>
 * Each item shows a severity icon, title, QUAREP reference, and a tooltip
 * with the full description and suggested action.
 */
class PublicationAdvicePane extends VBox {

    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.quiet.ui.strings");

    private final Label summaryLabel;
    private final VBox itemsBox;

    PublicationAdvicePane() {
        setSpacing(5);
        setPadding(new Insets(5));

        summaryLabel = new Label(resources.getString("advice.noIssues"));
        summaryLabel.setFont(Font.font(null, FontWeight.NORMAL, 12));

        itemsBox = new VBox(3);

        getChildren().addAll(summaryLabel, itemsBox);
    }

    /**
     * Update the displayed advice items.
     *
     * @param items the advice items to display (may be empty)
     */
    void update(List<AdviceItem> items) {
        itemsBox.getChildren().clear();

        if (items == null || items.isEmpty()) {
            summaryLabel.setText(resources.getString("advice.noIssues"));
            summaryLabel.setTextFill(Color.GRAY);
            return;
        }

        // Count by severity
        long errors = items.stream()
                .filter(i -> i.severity() == AdviceSeverity.ERROR).count();
        long warnings = items.stream()
                .filter(i -> i.severity() == AdviceSeverity.WARNING).count();
        long info = items.stream()
                .filter(i -> i.severity() == AdviceSeverity.INFO).count();

        summaryLabel.setText(String.format(
                resources.getString("advice.summary"), errors, warnings, info));
        summaryLabel.setTextFill(errors > 0 ? Color.RED
                : warnings > 0 ? Color.DARKORANGE : Color.GRAY);

        for (var item : items) {
            itemsBox.getChildren().add(createItemRow(item));
        }
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

        var row = new HBox(5, icon, title, ref);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // Tooltip with full description and action
        var sb = new StringBuilder();
        sb.append(item.description());
        if (item.suggestedAction() != null && !item.suggestedAction().isEmpty()) {
            sb.append("\n\nSuggested action: ").append(item.suggestedAction());
        }
        var tip = new Tooltip(sb.toString());
        tip.setWrapText(true);
        tip.setMaxWidth(450);
        tip.setShowDuration(Duration.seconds(30));
        Tooltip.install(row, tip);

        return row;
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
