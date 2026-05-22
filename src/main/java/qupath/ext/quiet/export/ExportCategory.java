package qupath.ext.quiet.export;

import java.io.File;

/**
 * The export categories supported by QuIET.
 */
public enum ExportCategory {

    RENDERED("Rendered Image", "rendered"),
    MASK("Label / Mask", "masks"),
    RAW("Raw Image Data", "raw"),
    TILED("Tiled Export (ML)", "tiles"),
    OBJECT_CROPS("Object Crops (Classification)", "crops"),
    PANEL("Panel / Montage", "panels");

    private final String displayName;
    private final String defaultSubdirectory;

    ExportCategory(String displayName, String defaultSubdirectory) {
        this.displayName = displayName;
        this.defaultSubdirectory = defaultSubdirectory;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultSubdirectory() {
        return defaultSubdirectory;
    }

    /**
     * Returns the default output directory for this category within a project.
     *
     * @param projectDir the project root directory
     * @return a File pointing to {@code <projectDir>/exports/<subdirectory>/}
     */
    public File getDefaultOutputDir(File projectDir) {
        return new File(new File(projectDir, "exports"), defaultSubdirectory);
    }

    /**
     * Returns the next available output directory for this category.
     * <p>
     * If the default directory already contains files from a previous export,
     * appends a numeric suffix ({@code _2}, {@code _3}, ...) to prevent
     * silent overwrites.
     *
     * @param projectDir the project root directory
     * @return a directory that does not yet contain exported files
     */
    public File getNextAvailableOutputDir(File projectDir) {
        File baseDir = getDefaultOutputDir(projectDir);
        if (!hasContent(baseDir)) {
            return baseDir;
        }
        File exportsDir = new File(projectDir, "exports");
        for (int i = 2; i < 1000; i++) {
            File candidate = new File(exportsDir, defaultSubdirectory + "_" + i);
            if (!hasContent(candidate)) {
                return candidate;
            }
        }
        // Fallback: timestamp
        return new File(exportsDir,
                defaultSubdirectory + "_" + System.currentTimeMillis());
    }

    private static boolean hasContent(File dir) {
        if (!dir.isDirectory()) return false;
        String[] files = dir.list();
        return files != null && files.length > 0;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
