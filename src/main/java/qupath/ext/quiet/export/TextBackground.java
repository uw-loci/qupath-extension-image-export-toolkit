package qupath.ext.quiet.export;

/**
 * How text drawn on an exported image is kept readable: the scale bar label, info label,
 * panel label, legends and color scale bar labels.
 */
public enum TextBackground {
    /** A one-pixel outline in the contrasting color, the behavior before 1.5.0. */
    OUTLINE("Outline"),
    /** A semi-transparent box in the contrasting color behind the whole block. */
    BOX("Box"),
    /**
     * Read the image under the block: outline only where the text already reaches a 4.5:1
     * contrast against all of it, otherwise a box. Auto text is black on light areas, white on
     * dark ones.
     */
    AUTO("Auto");

    private final String displayName;

    TextBackground(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    /**
     * @param name an enum name, any case
     * @return the value, or {@link #AUTO} if the name is unknown
     */
    public static TextBackground parse(String name) {
        if (name != null) {
            for (var value : values()) {
                if (value.name().equalsIgnoreCase(name.trim())) {
                    return value;
                }
            }
        }
        return AUTO;
    }
}
