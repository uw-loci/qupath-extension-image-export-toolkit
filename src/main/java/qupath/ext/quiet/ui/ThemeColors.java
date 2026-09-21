package qupath.ext.quiet.ui;

/**
 * Theme-aware colours as CSS fragments, readable on both the light and dark QuPath themes.
 *
 * <p>Each constant is a complete CSS colour expression -- a {@code ladder()} keyed on the
 * theme's {@code -fx-background} -- so it drops into a style string and follows a theme
 * switch live:
 *
 * <pre>
 * label.setStyle("-fx-text-fill: " + ThemeColors.MUTED + ";");
 * </pre>
 *
 * <p>Text colours are for text on the theme's own background. A callout panel must use one
 * of the {@code *_BG} constants and leave its text at the theme default; never pair a fixed
 * background with adaptive text or the reverse.
 */
public final class ThemeColors {

    private ThemeColors() {}

    /**
     * Set a node's text fill to one of the colour expressions here, keeping the rest of its
     * inline style.
     *
     * @param node   the labelled control to colour
     * @param colour a CSS colour expression, normally a constant from this class
     */
    public static void textFill(javafx.scene.Node node, String colour) {
        String style = node.getStyle() == null ? "" : node.getStyle();
        style = style.replaceAll("-fx-text-fill:[^;]*;?\\s*", "").trim();
        node.setStyle(style + (style.isEmpty() || style.endsWith(";") ? "" : ";") + " -fx-text-fill: " + colour + ";");
    }

    private static String ladder(String onDark, String onLight) {
        return "ladder(-fx-background, " + onDark + " 49%, " + onLight + " 50%)";
    }

    /** Secondary text: hints, notes, captions. */
    public static final String MUTED = ladder("#ADADAD", "#666666");

    /** Body text one step quieter than the default. */
    public static final String SUBTLE = ladder("#CFCFCF", "#3C3C3C");

    /** Failures and hard blocks. */
    public static final String ERROR = ladder("#FF8A80", "#C62828");

    /** Confirmed good, recommended actions. */
    public static final String SUCCESS = ladder("#81C784", "#2E7D32");

    /** Proceeding is possible but worth a second look. */
    public static final String WARNING = ladder("#FFB74D", "#9E4E00");

    /** Links, informational emphasis. */
    public static final String INFO = ladder("#90CAF9", "#1565C0");

    /** Hairline borders around cards and boxes. */
    public static final String BORDER = ladder("#5A5A5A", "#CCCCCC");

    /** Selection / focus accent for borders and glows. */
    public static final String ACCENT = ladder("#4DA3FF", "#0078D7");

    /** Neutral raised panel background. */
    public static final String PANEL_BG = ladder("#3A3D40", "#F8F8F8");

    /** Informational callout background. */
    public static final String INFO_BG = ladder("#2C3E57", "#E8F0FE");

    /** Warning callout background. */
    public static final String WARNING_BG = ladder("#574A1F", "#FFF3CD");

    /** Error callout background. */
    public static final String ERROR_BG = ladder("#5C2B2B", "#FFCCCC");
}
