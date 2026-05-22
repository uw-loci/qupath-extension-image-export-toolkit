package qupath.ext.quiet.export;

import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import qupath.lib.common.GeneralTools;

/**
 * Immutable configuration for a panel / montage export.
 * <p>
 * A panel export applies one single-image export <em>recipe</em> to every
 * selected image and composes the rendered results into a single
 * rows-by-columns figure with gutters, an optional per-cell caption, and a
 * chosen background colour.
 * <p>
 * The recipe is carried as the recipe {@link ExportCategory} plus that
 * category's existing {@code *ExportConfig} object ({@code RenderedExportConfig},
 * {@code RawExportConfig}, {@code MaskExportConfig} or {@code ObjectCropConfig}).
 * The recipe config's own output format / directory are ignored -- the panel
 * owns the single composed-figure output.
 */
public class PanelExportConfig {

    /** Where the per-image caption text band sits relative to the cell. */
    public enum CaptionPosition {
        ABOVE,
        BELOW
    }

    private final ExportCategory recipeCategory;
    private final Object recipeConfig;
    private final int rows;
    private final int cols;
    private final int gutterX;
    private final int gutterY;
    private final Color backgroundColor;
    private final CellFitMode cellFitMode;
    private final boolean showFilenameCaption;
    private final CaptionPosition captionPosition;
    private final List<String> metadataFields;
    private final int captionFontSize;
    private final Color captionColor;
    private final OutputFormat format;
    private final File outputDirectory;
    private final String filename;

    private PanelExportConfig(Builder b) {
        this.recipeCategory = b.recipeCategory;
        this.recipeConfig = b.recipeConfig;
        this.rows = b.rows;
        this.cols = b.cols;
        this.gutterX = b.gutterX;
        this.gutterY = b.gutterY;
        this.backgroundColor = b.backgroundColor;
        this.cellFitMode = b.cellFitMode;
        this.showFilenameCaption = b.showFilenameCaption;
        this.captionPosition = b.captionPosition;
        this.metadataFields = b.metadataFields == null
                ? List.of() : List.copyOf(b.metadataFields);
        this.captionFontSize = b.captionFontSize;
        this.captionColor = b.captionColor;
        this.format = b.format;
        this.outputDirectory = b.outputDirectory;
        this.filename = b.filename;
    }

    public ExportCategory getRecipeCategory() {
        return recipeCategory;
    }

    /**
     * The recipe's single-image config object. Cast to the type matching
     * {@link #getRecipeCategory()}.
     */
    public Object getRecipeConfig() {
        return recipeConfig;
    }

    public int getRows() {
        return rows;
    }

    public int getCols() {
        return cols;
    }

    public int getGutterX() {
        return gutterX;
    }

    public int getGutterY() {
        return gutterY;
    }

    public Color getBackgroundColor() {
        return backgroundColor;
    }

    public CellFitMode getCellFitMode() {
        return cellFitMode;
    }

    public boolean isShowFilenameCaption() {
        return showFilenameCaption;
    }

    public CaptionPosition getCaptionPosition() {
        return captionPosition;
    }

    /** Metadata field keys drawn as caption lines (never {@code null}). */
    public List<String> getMetadataFields() {
        return metadataFields;
    }

    public int getCaptionFontSize() {
        return captionFontSize;
    }

    public Color getCaptionColor() {
        return captionColor;
    }

    public OutputFormat getFormat() {
        return format;
    }

    public File getOutputDirectory() {
        return outputDirectory;
    }

    /** The composed-figure base filename (without extension). */
    public String getFilename() {
        return filename;
    }

    /**
     * Whether any caption content is configured (filename line or >=1
     * metadata field).
     */
    public boolean hasCaption() {
        return showFilenameCaption || !metadataFields.isEmpty();
    }

    /**
     * Build the sanitized output filename for the composed figure, including
     * the format extension. Uses {@link GeneralTools#stripInvalidFilenameChars}
     * plus a Windows reserved-name guard.
     */
    public String buildOutputFilename() {
        String base = GeneralTools.stripInvalidFilenameChars(filename);
        if (base == null || base.isBlank()) {
            base = "panel_figure";
        }
        if (isWindowsReservedName(base)) {
            base = base + "_figure";
        }
        return base + "." + format.getExtension();
    }

    /**
     * Returns true if {@code name} (case-insensitive, extension ignored)
     * matches a Windows reserved device name.
     */
    static boolean isWindowsReservedName(String name) {
        if (name == null) {
            return false;
        }
        String stem = name;
        int dot = stem.indexOf('.');
        if (dot >= 0) {
            stem = stem.substring(0, dot);
        }
        stem = stem.trim().toUpperCase();
        if (stem.equals("CON") || stem.equals("PRN") || stem.equals("AUX")
                || stem.equals("NUL")) {
            return true;
        }
        for (int i = 1; i <= 9; i++) {
            if (stem.equals("COM" + i) || stem.equals("LPT" + i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builder for {@link PanelExportConfig}.
     */
    public static class Builder {

        private ExportCategory recipeCategory = ExportCategory.RENDERED;
        private Object recipeConfig;
        private int rows = 2;
        private int cols = 2;
        private int gutterX = 10;
        private int gutterY = 10;
        private Color backgroundColor = Color.WHITE;
        private CellFitMode cellFitMode = CellFitMode.FIT_LETTERBOX;
        private boolean showFilenameCaption = false;
        private CaptionPosition captionPosition = CaptionPosition.BELOW;
        private List<String> metadataFields = new ArrayList<>();
        private int captionFontSize = 14;
        private Color captionColor = Color.BLACK;
        private OutputFormat format = OutputFormat.PNG;
        private File outputDirectory;
        private String filename = "panel_figure";

        public Builder recipeCategory(ExportCategory c) {
            this.recipeCategory = c;
            return this;
        }

        public Builder recipeConfig(Object config) {
            this.recipeConfig = config;
            return this;
        }

        public Builder rows(int r) {
            this.rows = r;
            return this;
        }

        public Builder cols(int c) {
            this.cols = c;
            return this;
        }

        public Builder gutterX(int g) {
            this.gutterX = g;
            return this;
        }

        public Builder gutterY(int g) {
            this.gutterY = g;
            return this;
        }

        public Builder backgroundColor(Color c) {
            this.backgroundColor = c;
            return this;
        }

        public Builder cellFitMode(CellFitMode m) {
            this.cellFitMode = m;
            return this;
        }

        public Builder showFilenameCaption(boolean b) {
            this.showFilenameCaption = b;
            return this;
        }

        public Builder captionPosition(CaptionPosition p) {
            this.captionPosition = p;
            return this;
        }

        public Builder metadataFields(List<String> fields) {
            this.metadataFields = fields;
            return this;
        }

        public Builder captionFontSize(int size) {
            this.captionFontSize = size;
            return this;
        }

        public Builder captionColor(Color c) {
            this.captionColor = c;
            return this;
        }

        public Builder format(OutputFormat f) {
            this.format = f;
            return this;
        }

        public Builder outputDirectory(File dir) {
            this.outputDirectory = dir;
            return this;
        }

        public Builder filename(String name) {
            this.filename = name;
            return this;
        }

        /**
         * Build the panel export configuration, validating and clamping fields.
         *
         * @return a new PanelExportConfig
         * @throws IllegalArgumentException if a required field is missing
         */
        public PanelExportConfig build() {
            if (outputDirectory == null) {
                throw new IllegalArgumentException("Output directory is required");
            }
            if (recipeCategory == null) {
                throw new IllegalArgumentException("Recipe category is required");
            }
            if (recipeCategory == ExportCategory.TILED
                    || recipeCategory == ExportCategory.PANEL) {
                throw new IllegalArgumentException(
                        "Panel recipe category must be a single-image category");
            }
            if (recipeConfig == null) {
                throw new IllegalArgumentException("Recipe configuration is required");
            }
            if (format == null) {
                throw new IllegalArgumentException("Output format is required");
            }
            if (cellFitMode == null) {
                throw new IllegalArgumentException("Cell fit mode is required");
            }
            return buildForLayout();
        }

        /**
         * Build a configuration for layout / preview computation only.
         * <p>
         * Unlike {@link #build()} this does NOT require a recipe configuration,
         * output directory or format: the composed-figure size and the visual
         * layout preview depend only on the grid, gutters, captions, cell-fit
         * mode and background colour. Grid and gutter values are clamped the
         * same way. Never use the result for an actual export -- it lacks the
         * recipe configuration needed to render cells.
         *
         * @return a PanelExportConfig valid for layout / size computation
         */
        public PanelExportConfig buildForLayout() {
            // Clamp grid and gutters to sane ranges (also guards untrusted input).
            rows = Math.max(1, Math.min(rows, 100));
            cols = Math.max(1, Math.min(cols, 100));
            gutterX = Math.max(0, Math.min(gutterX, 1000));
            gutterY = Math.max(0, Math.min(gutterY, 1000));
            captionFontSize = Math.max(6, Math.min(captionFontSize, 96));
            if (backgroundColor == null) {
                backgroundColor = Color.WHITE;
            }
            if (captionColor == null) {
                captionColor = Color.BLACK;
            }
            if (captionPosition == null) {
                captionPosition = CaptionPosition.BELOW;
            }
            if (cellFitMode == null) {
                cellFitMode = CellFitMode.FIT_LETTERBOX;
            }
            return new PanelExportConfig(this);
        }
    }
}
