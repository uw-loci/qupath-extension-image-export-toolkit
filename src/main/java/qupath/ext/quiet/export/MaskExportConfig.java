package qupath.ext.quiet.export;

import java.io.File;
import java.util.Collections;
import java.util.List;

import qupath.lib.common.GeneralTools;

/**
 * Immutable configuration for a mask/label export operation.
 */
public class MaskExportConfig {

    /**
     * The type of mask to generate.
     */
    public enum MaskType {
        /** Single-class binary mask (foreground = 1, background = 0). */
        BINARY,
        /** Integer labels per class in grayscale. */
        GRAYSCALE_LABELS,
        /** Class-colored RGB mask using PathClass colors. */
        COLORED,
        /** Unique integer ID per object instance (16-bit). */
        INSTANCE,
        /** One binary channel per classification. */
        MULTICHANNEL
    }

    /**
     * Which objects to use as the source for mask generation.
     */
    public enum ObjectSource {
        ANNOTATIONS,
        DETECTIONS,
        CELLS
    }

    private final MaskType maskType;
    private final List<String> selectedClassifications;
    private final int backgroundLabel;
    private final int boundaryLabel;
    private final boolean enableBoundary;
    private final ObjectSource objectSource;
    private final double downsample;
    private final OutputFormat format;
    private final boolean grayscaleLut;
    private final File outputDirectory;
    private final boolean addToWorkflow;
    private final boolean shuffleInstanceLabels;
    private final int boundaryThickness;
    private final boolean skipEmptyImages;

    private MaskExportConfig(Builder builder) {
        this.maskType = builder.maskType;
        this.selectedClassifications = builder.selectedClassifications == null
                ? Collections.emptyList()
                : List.copyOf(builder.selectedClassifications);
        this.backgroundLabel = builder.backgroundLabel;
        this.boundaryLabel = builder.boundaryLabel;
        this.enableBoundary = builder.enableBoundary;
        this.objectSource = builder.objectSource;
        this.downsample = builder.downsample;
        this.format = builder.format;
        this.grayscaleLut = builder.grayscaleLut;
        this.outputDirectory = builder.outputDirectory;
        this.addToWorkflow = builder.addToWorkflow;
        this.shuffleInstanceLabels = builder.shuffleInstanceLabels;
        this.boundaryThickness = builder.boundaryThickness;
        this.skipEmptyImages = builder.skipEmptyImages;
    }

    public MaskType getMaskType() {
        return maskType;
    }

    public List<String> getSelectedClassifications() {
        return selectedClassifications;
    }

    public int getBackgroundLabel() {
        return backgroundLabel;
    }

    public int getBoundaryLabel() {
        return boundaryLabel;
    }

    public boolean isEnableBoundary() {
        return enableBoundary;
    }

    public ObjectSource getObjectSource() {
        return objectSource;
    }

    public double getDownsample() {
        return downsample;
    }

    public OutputFormat getFormat() {
        return format;
    }

    public boolean isGrayscaleLut() {
        return grayscaleLut;
    }

    public File getOutputDirectory() {
        return outputDirectory;
    }

    public boolean isAddToWorkflow() {
        return addToWorkflow;
    }

    /**
     * Whether to shuffle instance label IDs for visual distinction.
     * Only relevant when mask type is INSTANCE.
     */
    public boolean isShuffleInstanceLabels() {
        return shuffleInstanceLabels;
    }

    /**
     * Boundary line thickness in pixels for boundary erosion.
     * Used with setBoundaryLabel(String, int, double) overload.
     * Only relevant when boundary is enabled.
     */
    public int getBoundaryThickness() {
        return boundaryThickness;
    }

    /**
     * Whether to skip images that contain no objects matching any of the
     * selected classifications. When true, images without relevant objects
     * are skipped entirely rather than producing blank mask files.
     */
    public boolean isSkipEmptyImages() {
        return skipEmptyImages;
    }

    /**
     * Generates a sanitized output filename for a given image entry name.
     *
     * @param entryName the project image entry name
     * @return sanitized filename with appropriate extension
     */
    public String buildOutputFilename(String entryName) {
        String sanitized = GeneralTools.stripInvalidFilenameChars(entryName);
        if (sanitized == null || sanitized.isBlank()) {
            sanitized = "unnamed";
        }
        return sanitized + "." + format.getExtension();
    }

    /**
     * Builder for creating {@link MaskExportConfig} instances.
     */
    public static class Builder {

        private MaskType maskType = MaskType.BINARY;
        private List<String> selectedClassifications;
        private int backgroundLabel = 0;
        private int boundaryLabel = -1;
        private boolean enableBoundary = false;
        private ObjectSource objectSource = ObjectSource.ANNOTATIONS;
        private double downsample = 4.0;
        private OutputFormat format = OutputFormat.PNG;
        private boolean grayscaleLut = false;
        private File outputDirectory;
        private boolean addToWorkflow = true;
        private boolean shuffleInstanceLabels = false;
        private int boundaryThickness = 1;
        private boolean skipEmptyImages = false;

        public Builder maskType(MaskType type) {
            this.maskType = type;
            return this;
        }

        public Builder selectedClassifications(List<String> classes) {
            this.selectedClassifications = classes;
            return this;
        }

        public Builder backgroundLabel(int label) {
            this.backgroundLabel = label;
            return this;
        }

        public Builder boundaryLabel(int label) {
            this.boundaryLabel = label;
            return this;
        }

        public Builder enableBoundary(boolean enable) {
            this.enableBoundary = enable;
            return this;
        }

        public Builder objectSource(ObjectSource source) {
            this.objectSource = source;
            return this;
        }

        public Builder downsample(double ds) {
            this.downsample = ds;
            return this;
        }

        public Builder format(OutputFormat fmt) {
            this.format = fmt;
            return this;
        }

        public Builder grayscaleLut(boolean lut) {
            this.grayscaleLut = lut;
            return this;
        }

        public Builder outputDirectory(File dir) {
            this.outputDirectory = dir;
            return this;
        }

        public Builder addToWorkflow(boolean add) {
            this.addToWorkflow = add;
            return this;
        }

        public Builder shuffleInstanceLabels(boolean shuffle) {
            this.shuffleInstanceLabels = shuffle;
            return this;
        }

        public Builder boundaryThickness(int thickness) {
            this.boundaryThickness = thickness;
            return this;
        }

        public Builder skipEmptyImages(boolean skip) {
            this.skipEmptyImages = skip;
            return this;
        }

        /**
         * Build the mask export configuration, validating required fields.
         *
         * @return a new MaskExportConfig
         * @throws IllegalArgumentException if required fields are missing or invalid
         */
        public MaskExportConfig build() {
            if (outputDirectory == null) {
                throw new IllegalArgumentException("Output directory is required");
            }
            if (downsample < 1.0) {
                throw new IllegalArgumentException("Downsample must be >= 1.0");
            }
            if (format == null) {
                throw new IllegalArgumentException("Output format is required");
            }
            if (format == OutputFormat.JPEG) {
                throw new IllegalArgumentException(
                        "JPEG is not supported for mask export -- lossy compression destroys label values");
            }
            if (maskType == null) {
                throw new IllegalArgumentException("Mask type is required");
            }
            return new MaskExportConfig(this);
        }
    }
}
