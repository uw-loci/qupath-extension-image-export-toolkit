package qupath.ext.quiet.export;

/**
 * Controls how a rendered panel image is placed inside its grid cell.
 * <p>
 * All three modes preserve the image aspect ratio -- there is no mode that
 * distorts an image.
 */
public enum CellFitMode {

    /**
     * Scale the image (up or down) to fit fully inside the cell, preserving
     * aspect ratio. Leftover space is filled with the panel background colour.
     * The whole image is always visible (no cropping).
     */
    FIT_LETTERBOX("Fit (letterbox)"),

    /**
     * Scale the image to completely cover the cell, preserving aspect ratio.
     * Overflow is cropped. No background padding is visible.
     */
    FILL_CROP("Fill (crop)"),

    /**
     * Place the image at its native pixel size, centred in the cell. The image
     * is padded with background if smaller than the cell, cropped if larger.
     */
    ACTUAL_SIZE("Actual size");

    private final String displayName;

    CellFitMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
