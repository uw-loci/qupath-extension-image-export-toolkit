package qupath.ext.quiet.ui;

import java.util.Map;
import java.util.ResourceBundle;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
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

    private static final double CARD_MIN_WIDTH = 200;
    private static final double ILLUSTRATION_HEIGHT = 140;

    private static final String IMAGE_BASE_PATH = "/qupath/ext/quiet/ui/images/";

    private static final Map<ExportCategory, String> ILLUSTRATION_FILES = Map.of(
            ExportCategory.RENDERED, "category_rendered.png",
            ExportCategory.MASK, "category_mask.png",
            ExportCategory.RAW, "category_raw.png",
            ExportCategory.TILED, "category_tiled.png",
            ExportCategory.OBJECT_CROPS, "category_objectcrops.png"
    );

    private ExportCategory selectedCategory = ExportCategory.RENDERED;
    private Runnable onAdvance;
    private VBox renderedCard;
    private VBox maskCard;
    private VBox rawCard;
    private VBox tiledCard;
    private VBox objectCropsCard;

    public CategorySelectionPane() {
        setSpacing(15);
        setPadding(new Insets(10));
        setAlignment(Pos.TOP_CENTER);

        var header = new Label(resources.getString("wizard.step1.title"));
        header.setFont(Font.font(null, FontWeight.BOLD, 16));

        renderedCard = createCard(
                resources.getString("category.rendered.title"),
                resources.getString("category.rendered.description"),
                ExportCategory.RENDERED);

        maskCard = createCard(
                resources.getString("category.mask.title"),
                resources.getString("category.mask.description"),
                ExportCategory.MASK);

        rawCard = createCard(
                resources.getString("category.raw.title"),
                resources.getString("category.raw.description"),
                ExportCategory.RAW);

        tiledCard = createCard(
                resources.getString("category.tiled.title"),
                resources.getString("category.tiled.description"),
                ExportCategory.TILED);

        objectCropsCard = createCard(
                resources.getString("category.objectCrops.title"),
                resources.getString("category.objectCrops.description"),
                ExportCategory.OBJECT_CROPS);

        var cardsBox = new HBox(15, renderedCard, maskCard, rawCard, tiledCard, objectCropsCard);
        HBox.setHgrow(renderedCard, Priority.ALWAYS);
        HBox.setHgrow(maskCard, Priority.ALWAYS);
        HBox.setHgrow(rawCard, Priority.ALWAYS);
        HBox.setHgrow(tiledCard, Priority.ALWAYS);
        HBox.setHgrow(objectCropsCard, Priority.ALWAYS);

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

        // Add illustration image
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

        return card;
    }

    private ImageView loadIllustration(ExportCategory category) {
        var filename = ILLUSTRATION_FILES.get(category);
        if (filename == null)
            return null;

        var resourcePath = IMAGE_BASE_PATH + filename;
        var stream = getClass().getResourceAsStream(resourcePath);
        if (stream == null) {
            logger.warn("Category illustration not found: {}", resourcePath);
            return null;
        }

        var image = new Image(stream);
        var imageView = new ImageView(image);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setFitHeight(ILLUSTRATION_HEIGHT);
        // Bind width to card width so it scales with the card
        imageView.fitWidthProperty().bind(
                javafx.beans.binding.Bindings.createDoubleBinding(
                        () -> Math.max(0, getWidth() / 5.0 - 50),
                        widthProperty()
                )
        );

        return imageView;
    }

    private void updateCardStyles() {
        renderedCard.setStyle(selectedCategory == ExportCategory.RENDERED
                ? CARD_STYLE_SELECTED : CARD_STYLE_DEFAULT);
        maskCard.setStyle(selectedCategory == ExportCategory.MASK
                ? CARD_STYLE_SELECTED : CARD_STYLE_DEFAULT);
        rawCard.setStyle(selectedCategory == ExportCategory.RAW
                ? CARD_STYLE_SELECTED : CARD_STYLE_DEFAULT);
        tiledCard.setStyle(selectedCategory == ExportCategory.TILED
                ? CARD_STYLE_SELECTED : CARD_STYLE_DEFAULT);
        objectCropsCard.setStyle(selectedCategory == ExportCategory.OBJECT_CROPS
                ? CARD_STYLE_SELECTED : CARD_STYLE_DEFAULT);
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
