package qupath.ext.quiet.export;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * A portable, JSON-serialisable QuIET export recipe.
 * <p>
 * A recipe captures the single-image export settings ("how one panel is
 * rendered") so they can be re-used across projects or sessions. It stores the
 * recipe {@link ExportCategory} plus a flat snapshot of that category's
 * {@code quiet.<category>.*} preference key/value pairs. The single-image
 * config panes already restore themselves from these preferences, so loading a
 * recipe is simply writing the snapshot into the preference store and asking
 * the relevant pane to reload.
 * <p>
 * The recipe carries no executable code (unlike a Groovy script). The JSON is
 * still treated as untrusted input: {@link #loadFromFile(File)} tolerates
 * missing or malformed fields and never throws a raw parse exception to the
 * caller for a structurally valid file -- callers handle a thrown
 * {@link IOException} with a clean user-facing error.
 * <p>
 * Serialization pattern copied from
 * {@code qupath.ext.ocr4labels.model.OCRTemplate} (REFERENCES Part A,
 * "JSON template files").
 */
public class ExportRecipe {

    private static final Logger logger = LoggerFactory.getLogger(ExportRecipe.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Schema version, for forward/backward tolerance. */
    private int schemaVersion = 1;

    /** Marker identifying this as a QuIET recipe file. */
    private String recipeType = "quiet-export-recipe";

    /** The export category this recipe configures (enum name). */
    private String category;

    /** Optional free-text description. */
    private String description;

    /** Creation timestamp (epoch millis). */
    private long createdTimestamp;

    /** Flat snapshot of {@code quiet.<category>.*} preference values. */
    private Map<String, String> settings;

    /** No-argument constructor required for Gson. */
    public ExportRecipe() {
        this.settings = new LinkedHashMap<>();
        this.createdTimestamp = System.currentTimeMillis();
    }

    /**
     * Create a recipe for a category with a settings snapshot.
     *
     * @param category the export category
     * @param settings the {@code quiet.<category>.*} key/value snapshot
     */
    public ExportRecipe(ExportCategory category, Map<String, String> settings) {
        this();
        this.category = category != null ? category.name() : null;
        this.settings = settings != null ? new LinkedHashMap<>(settings) : new LinkedHashMap<>();
    }

    /**
     * Resolve the export category, or {@code null} if the stored value is
     * missing or not a recognised category.
     */
    public ExportCategory getCategory() {
        if (category == null) {
            return null;
        }
        try {
            return ExportCategory.valueOf(category);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void setCategory(ExportCategory category) {
        this.category = category != null ? category.name() : null;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getCreatedTimestamp() {
        return createdTimestamp;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    /**
     * Get the settings snapshot. Never {@code null}.
     */
    public Map<String, String> getSettings() {
        if (settings == null) {
            settings = new LinkedHashMap<>();
        }
        return settings;
    }

    /**
     * Save this recipe to a JSON file (UTF-8).
     *
     * @param file the destination file
     * @throws IOException if writing fails
     */
    public void saveToFile(File file) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
            logger.info("Saved export recipe to: {}", file.getAbsolutePath());
        }
    }

    /**
     * Load and validate a recipe from a JSON file.
     * <p>
     * The file is treated as untrusted input. Malformed JSON or a structurally
     * unrecognisable document produces an {@link IOException} with a concise
     * message; the caller surfaces this to the user as a clean error. The
     * returned recipe always has a non-null category and a non-null (possibly
     * empty) settings map.
     *
     * @param file the recipe file
     * @return the loaded, validated recipe
     * @throws IOException if the file cannot be read or is not a valid recipe
     */
    public static ExportRecipe loadFromFile(File file) throws IOException {
        ExportRecipe recipe;
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            recipe = GSON.fromJson(reader, ExportRecipe.class);
        } catch (JsonParseException e) {
            throw new IOException("the file is not valid JSON", e);
        }
        if (recipe == null) {
            throw new IOException("the file is empty");
        }
        if (recipe.getCategory() == null) {
            throw new IOException(
                    "it does not specify a recognised export category");
        }
        if (recipe.getCategory() == ExportCategory.TILED
                || recipe.getCategory() == ExportCategory.PANEL) {
            throw new IOException(
                    "panel recipes support Rendered, Raw, Mask and Object Crop"
                    + " only (found " + recipe.getCategory().getDisplayName() + ")");
        }
        // Defensive: drop any settings keys not in the quiet.* namespace so a
        // hand-edited file cannot inject unrelated preference writes.
        var clean = new LinkedHashMap<String, String>();
        for (var entry : recipe.getSettings().entrySet()) {
            String key = entry.getKey();
            if (key != null && key.startsWith("quiet.") && entry.getValue() != null) {
                clean.put(key, entry.getValue());
            }
        }
        recipe.settings = clean;
        logger.info("Loaded export recipe from: {}", file.getAbsolutePath());
        return recipe;
    }

    @Override
    public String toString() {
        return "ExportRecipe[category=" + category
                + ", settings=" + (settings != null ? settings.size() : 0) + "]";
    }
}
