package qupath.ext.quiet.export;

import qupath.ext.quiet.export.ExportCategory;
import qupath.ext.quiet.export.MaskExportConfig;
import qupath.ext.quiet.export.RawExportConfig;
import qupath.ext.quiet.export.RenderedExportConfig;
import qupath.ext.quiet.export.TiledExportConfig;

/**
 * Dispatcher and shared utilities for Groovy script generation.
 * <p>
 * All generated scripts are self-contained, user-editable, and runnable
 * independently in QuPath's Script Editor without requiring this extension.
 */
public class ScriptGenerator {

    private ScriptGenerator() {
        // Utility class
    }

    /**
     * Generate a Groovy script for the given category and configuration.
     *
     * @param category the export category
     * @param config   the configuration object (must match category type)
     * @return a self-contained Groovy script string (ASCII-only)
     * @throws IllegalArgumentException if config type doesn't match category
     */
    public static String generate(ExportCategory category, Object config) {
        return switch (category) {
            case RENDERED -> {
                if (!(config instanceof RenderedExportConfig rc)) {
                    throw new IllegalArgumentException("Expected RenderedExportConfig");
                }
                yield RenderedScriptGenerator.generate(rc);
            }
            case MASK -> {
                if (!(config instanceof MaskExportConfig mc)) {
                    throw new IllegalArgumentException("Expected MaskExportConfig");
                }
                yield MaskScriptGenerator.generate(mc);
            }
            case RAW -> {
                if (!(config instanceof RawExportConfig rawc)) {
                    throw new IllegalArgumentException("Expected RawExportConfig");
                }
                yield RawScriptGenerator.generate(rawc);
            }
            case TILED -> {
                if (!(config instanceof TiledExportConfig tc)) {
                    throw new IllegalArgumentException("Expected TiledExportConfig");
                }
                yield TiledScriptGenerator.generate(tc);
            }
            case OBJECT_CROPS -> {
                if (!(config instanceof ObjectCropConfig occ)) {
                    throw new IllegalArgumentException("Expected ObjectCropConfig");
                }
                yield ObjectCropScriptGenerator.generate(occ);
            }
            case PANEL -> {
                if (!(config instanceof PanelExportConfig pc)) {
                    throw new IllegalArgumentException("Expected PanelExportConfig");
                }
                yield PanelScriptGenerator.generate(pc);
            }
        };
    }

    /**
     * Properly quotes and escapes a string value for use in Groovy source code.
     * Handles backslashes (Windows paths), quotes, and newlines.
     */
    static String quote(String value) {
        if (value == null) return "null";
        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "\"" + escaped + "\"";
    }

    /**
     * Append a line with a newline character to a StringBuilder.
     */
    static void appendLine(StringBuilder sb, String line) {
        sb.append(line).append("\n");
    }
}
