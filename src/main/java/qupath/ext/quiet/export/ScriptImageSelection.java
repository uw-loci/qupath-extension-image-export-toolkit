package qupath.ext.quiet.export;

import java.util.HashSet;
import java.util.List;

/**
 * Makes a generated script work on the images the wizard exported rather than on every image
 * in the project, so pre-scans (matched display ranges, a shared map range) and the export
 * loop see the same images as the wizard run.
 */
public final class ScriptImageSelection {

    /** What the generators emit for "the images to work on". */
    static final String SCRIPT_MARKER = "project.getImageList()";

    private ScriptImageSelection() {}

    /**
     * Rewrite a generated script to work on the selected images, in the selected order.
     *
     * @param script       a script from {@link ScriptGenerator#generate}
     * @param selected     names of the images the wizard exports, in batch order
     * @param projectNames every image name in the project
     * @return the script, unchanged when the selection is the whole project or empty
     */
    public static String applyToScript(String script, List<String> selected, List<String> projectNames) {
        if (script == null || selected == null || selected.isEmpty() || !script.contains(SCRIPT_MARKER)) {
            return script;
        }
        if (projectNames != null && new HashSet<>(selected).containsAll(projectNames)
                && selected.size() == projectNames.size()) {
            return script;
        }
        return script.replace(SCRIPT_MARKER, "exportImageList(project)") + groovyBlock(selected);
    }

    private static String groovyBlock(List<String> selected) {
        var sb = new StringBuilder("\n");
        sb.append("// --- Images to export: the ").append(selected.size())
                .append(" selected in the QuIET wizard ---\n");
        sb.append("// Images are matched by name, in this order. Empty the list to use every image\n");
        sb.append("// in the project; any scan across images (matched display range, shared map\n");
        sb.append("// range) then covers them all and can give different ranges.\n");
        sb.append("@groovy.transform.Field List<String> exportImageNames = [\n");
        for (int i = 0; i < selected.size(); i++) {
            sb.append("    ").append(ScriptGenerator.quote(selected.get(i)))
                    .append(i < selected.size() - 1 ? ",\n" : "\n");
        }
        sb.append("]\n");
        sb.append("@groovy.transform.Field List quietExportEntries = null\n");
        sb.append("\n");
        sb.append("def exportImageList(project) {\n");
        sb.append("    if (quietExportEntries != null) return quietExportEntries\n");
        sb.append("    def all = project.getImageList()\n");
        sb.append("    if (exportImageNames.isEmpty()) {\n");
        sb.append("        quietExportEntries = all\n");
        sb.append("        return all\n");
        sb.append("    }\n");
        sb.append("    def found = []\n");
        sb.append("    def missing = []\n");
        sb.append("    for (name in exportImageNames) {\n");
        sb.append("        def matches = all.findAll { it.getImageName() == name }\n");
        sb.append("        if (matches.isEmpty()) missing << name else found.addAll(matches - found)\n");
        sb.append("    }\n");
        sb.append("    if (!missing.isEmpty()) {\n");
        sb.append("        println \"WARNING: ${missing.size()} selected image(s) not in this project: \""
                + " + missing.join(', ')\n");
        sb.append("    }\n");
        sb.append("    quietExportEntries = found\n");
        sb.append("    return found\n");
        sb.append("}\n");
        return sb.toString();
    }
}
