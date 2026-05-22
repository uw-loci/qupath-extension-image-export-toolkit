package qupath.ext.quiet.ui;

import java.util.EnumMap;
import java.util.Map;
import java.util.ResourceBundle;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.quiet.export.ExportCategory;

/**
 * Step 1 of the export wizard: Select an export category.
 * <p>
 * Presents five clickable cards, each representing an export category
 * (Rendered, Mask, Raw, Tiled, Object Crops). The selected card is highlighted.
 * Each card includes a schematic illustration showing what the export produces.
 * <p>
 * Panel / Montage is not a card here -- it is launched from its own
 * "Panel / Montage Export..." menu item, which opens the wizard directly in
 * panel mode (skipping this picker).
 */
public class CategorySelectionPane extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(CategorySelectionPane.class);

    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.quiet.ui.strings");

    private static final String CARD_STYLE_DEFAULT =
            "-fx-border-color: #cccccc; -fx-border-radius: 8; -fx-background-radius: 8; " +
            "-fx-background-color: #f8f8f8; -fx-padding: 15; -fx-cursor: hand;";

    private static final String CARD_STYLE_SELECTED =
            "-fx-border-color: #0078d7; -fx-border-width: 2; -fx-border-radius: 8; " +
            "-fx-background-radius: 8; -fx-background-color: #e8f0fe; -fx-padding: 14; -fx-cursor: hand;";

    /** Drop-shadow focus ring so keyboard focus is visible on a card. */
    private static final String CARD_FOCUS_EFFECT =
            "-fx-effect: dropshadow(three-pass-box, #0078d7, 6, 0.5, 0, 0);";

    private static final double CARD_MIN_WIDTH = 200;
    private static final double ILLUSTRATION_HEIGHT = 140;

    private static final String IMAGE_BASE_PATH = "/qupath/ext/quiet/ui/images/";

    private static final Map<ExportCategory, String> ILLUSTRATION_FILES =
            new EnumMap<>(ExportCategory.class);

    static {
        ILLUSTRATION_FILES.put(ExportCategory.RENDERED, "category_rendered.png");
        ILLUSTRATION_FILES.put(ExportCategory.MASK, "category_mask.png");
        ILLUSTRATION_FILES.put(ExportCategory.RAW, "category_raw.png");
        ILLUSTRATION_FILES.put(ExportCategory.TILED, "category_tiled.png");
        ILLUSTRATION_FILES.put(ExportCategory.OBJECT_CROPS, "category_objectcrops.png");
    }

    /** The five categories chosen via this picker, in display order. */
    private static final ExportCategory[] PICKER_CATEGORIES = {
        ExportCategory.RENDERED,
        ExportCategory.MASK,
        ExportCategory.RAW,
        ExportCategory.TILED,
        ExportCategory.OBJECT_CROPS
    };

    private ExportCategory selectedCategory = ExportCategory.RENDERED;
    private Runnable onAdvance;
    private final Map<ExportCategory, VBox> cards = new EnumMap<>(ExportCategory.class);
    private HBox cardsBox;

    public CategorySelectionPane() {
        setSpacing(15);
        setPadding(new Insets(10));
        setAlignment(Pos.TOP_CENTER);

        var header = new Label(resources.getString("wizard.step1.title"));
        header.setFont(Font.font(null, FontWeight.BOLD, 16));

        cards.put(ExportCategory.RENDERED, createCard(
                resources.getString("category.rendered.title"),
                resources.getString("category.rendered.description"),
                ExportCategory.RENDERED));
        cards.put(ExportCategory.MASK, createCard(
                resources.getString("category.mask.title"),
                resources.getString("category.mask.description"),
                ExportCategory.MASK));
        cards.put(ExportCategory.RAW, createCard(
                resources.getString("category.raw.title"),
                resources.getString("category.raw.description"),
                ExportCategory.RAW));
        cards.put(ExportCategory.TILED, createCard(
                resources.getString("category.tiled.title"),
                resources.getString("category.tiled.description"),
                ExportCategory.TILED));
        cards.put(ExportCategory.OBJECT_CROPS, createCard(
                resources.getString("category.objectCrops.title"),
                resources.getString("category.objectCrops.description"),
                ExportCategory.OBJECT_CROPS));

        cardsBox = new HBox(15);
        cardsBox.setAlignment(Pos.TOP_CENTER);
        for (var category : PICKER_CATEGORIES) {
            cardsBox.getChildren().add(cards.get(category));
        }

        getChildren().addAll(header, cardsBox);
        VBox.setVgrow(cardsBox, Priority.ALWAYS);

        updateCardStyles();
    }

    private VBox createCard(String title, String description, ExportCategory category) {
        var titleLabel = new Label(title);
        titleLabel.setFont(Font.font(null, FontWeight.BOLD, 14));
        titleLabel.setWrapText(true);

        var descLabel = new Label(description);
        descLabel.setWrapText(true);
        descLabel.setMaxWidth(Double.MAX_VALUE);

        var card = new VBox(8, titleLabel, descLabel);
        card.setPrefWidth(CARD_MIN_WIDTH);
        card.setMinWidth(CARD_MIN_WIDTH);
        card.setMinHeight(120);
        card.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(card, Priority.ALWAYS);

        var illustration = loadIllustration(category);
        if (illustration != null) {
            card.getChildren().add(illustration);
        }

        card.setOnMouseClicked(e -> {
            selectedCategory = category;
            updateCardStyles();
            if (e.getClickCount() >= 2 && onAdvance != null) {
                onAdvance.run();
            }
        });

        // Keyboard accessibility: cards are focusable and selectable/activatable
        // via the keyboard, not mouse-only. Tab moves between cards, Space/Enter
        // selects the focused card, Enter on an already-selected card advances.
        card.setFocusTraversable(true);
        card.setAccessibleRole(javafx.scene.AccessibleRole.RADIO_BUTTON);
        card.setAccessibleText(title + ". " + description);
        card.focusedProperty().addListener((obs, was, focused) -> updateCardStyles());
        card.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case SPACE -> {
                    selectedCategory = category;
                    updateCardStyles();
                    e.consume();
                }
                case ENTER -> {
                    boolean wasSelected = selectedCategory == category;
                    selectedCategory = category;
                    updateCardStyles();
                    if (wasSelected && onAdvance != null) {
                        onAdvance.run();
                    }
                    e.consume();
                }
                default -> {
                    // no-op
                }
            }
        });

        return card;
    }

    private Node loadIllustration(ExportCategory category) {
        var filename = ILLUSTRATION_FILES.get(category);
        if (filename == null) {
            return null;
        }
        var resourcePath = IMAGE_BASE_PATH + filename;
        var stream = getClass().getResourceAsStream(resourcePath);
        if (stream == null) {
            logger.warn("Category illustration not found ({})", resourcePath);
            return null;
        }

        var image = new Image(stream);
        var imageView = new ImageView(image);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setFitHeight(ILLUSTRATION_HEIGHT);
        imageView.fitWidthProperty().bind(
                javafx.beans.binding.Bindings.createDoubleBinding(
                        () -> Math.max(0, getWidth() / 5.0 - 40),
                        widthProperty()));
        return imageView;
    }

    private void updateCardStyles() {
        for (var entry : cards.entrySet()) {
            var card = entry.getValue();
            String base = entry.getKey() == selectedCategory
                    ? CARD_STYLE_SELECTED : CARD_STYLE_DEFAULT;
            card.setStyle(card.isFocused() ? base + CARD_FOCUS_EFFECT : base);
        }
    }

    public ExportCategory getSelectedCategory() {
        return selectedCategory;
    }

    public void setSelectedCategory(ExportCategory category) {
        this.selectedCategory = category;
        updateCardStyles();
    }

    /**
     * Set a callback to invoke when the user double-clicks a category card
     * (advancing to the next wizard step).
     */
    public void setOnAdvance(Runnable onAdvance) {
        this.onAdvance = onAdvance;
    }
}
