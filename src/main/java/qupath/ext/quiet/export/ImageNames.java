package qupath.ext.quiet.export;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns project image names into output base names by dropping the source
 * file's extension, so {@code slide.tif} exports as {@code slide.png} rather
 * than {@code slide.tif.png}.
 *
 * <p>Only known image extensions are removed -- never "whatever follows the
 * last dot" -- so {@code Dose 1.5 mg} is left alone. Compound extensions
 * ({@code .ome.tif}) go as a unit, and an extension is also recognised before
 * a series separator ({@code slide.vsi - 20x_01}).
 */
public final class ImageNames {

    /** Longest first within a family so {@code .ome.tiff} wins over {@code .tiff}. */
    private static final String EXTENSIONS = String.join("|",
            "ome\\.tiff?", "ome\\.zarr", "ome\\.btf", "ome\\.tf[28]",
            "tiff?", "btf", "tf[28]", "zarr", "svs", "ndpis?", "scn", "mrxs", "vsi", "vms", "vmu",
            "czi", "lif", "nd2", "oib", "oif", "lsm", "ims", "qptiff", "bif", "isyntax", "dcm",
            "jpe?g", "jp2", "j2k", "png", "bmp", "gif");

    /** An extension at the end of the name, or directly before a " - series" suffix. */
    private static final Pattern EXTENSION =
            Pattern.compile("\\.(?:" + EXTENSIONS + ")(?=$| - )", Pattern.CASE_INSENSITIVE);

    /** Marker line the generators emit; {@link #applyToScript} rewrites it. */
    private static final String SCRIPT_MARKER = "def entryName = imageName";

    private ImageNames() {}

    /**
     * Remove a known image-file extension from an image name.
     *
     * @param name the project image name
     * @return the name without its extension, or the name unchanged if it has none
     */
    public static String stripExtension(String name) {
        if (name == null) {
            return null;
        }
        String stripped = EXTENSION.matcher(name).replaceFirst("");
        return stripped.isBlank() ? name : stripped;
    }

    /**
     * Base names for a batch. A name that would collide with another image in
     * the project once stripped ({@code a.tif} and {@code a.czi}) keeps its
     * extension so neither export overwrites the other. Collisions are judged
     * against the whole project, not the batch, so an image's output name does
     * not depend on what else was selected -- and matches the generated
     * scripts, which always see the whole project.
     *
     * @param names        the image names to export, in batch order
     * @param projectNames every image name in the project (null = just {@code names})
     * @param strip        whether extensions should be removed at all
     * @return one base name per input, in the same order
     */
    public static List<String> baseNames(List<String> names, List<String> projectNames, boolean strip) {
        if (!strip) {
            return new ArrayList<>(names);
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String name : projectNames != null ? projectNames : names) {
            counts.merge(key(stripExtension(name)), 1, Integer::sum);
        }
        List<String> result = new ArrayList<>(names.size());
        for (String name : names) {
            String stripped = stripExtension(name);
            result.add(counts.getOrDefault(key(stripped), 0) > 1 ? name : stripped);
        }
        return result;
    }

    /** Case-folded because Windows and macOS filesystems are case-insensitive. */
    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    /**
     * Rewrite a generated script so it names its outputs the way the wizard
     * run did: same prefix, suffix, extension stripping and collision rule.
     *
     * @param script a script from {@link ScriptGenerator#generate}
     * @param prefix filename prefix (may be null)
     * @param suffix filename suffix (may be null)
     * @param strip  whether source extensions are dropped
     * @return the script, unchanged when no naming option is in force
     */
    public static String applyToScript(String script, String prefix, String suffix, boolean strip) {
        String pre = prefix == null ? "" : prefix;
        String suf = suffix == null ? "" : suffix;
        if (script == null || !script.contains(SCRIPT_MARKER)
                || (!strip && pre.isEmpty() && suf.isEmpty())) {
            return script;
        }
        return script.replace(SCRIPT_MARKER, "def entryName = outputBaseName(imageName)")
                + groovyNamingBlock(pre, suf, strip);
    }

    private static String groovyNamingBlock(String prefix, String suffix, boolean strip) {
        var sb = new StringBuilder("\n");
        sb.append("// --- Output naming (mirrors the QuIET wizard) ---\n");
        if (strip) {
            sb.append("@groovy.transform.Field Map quietStrippedCounts = null\n");
            sb.append("\n");
            sb.append("// Drop a known image-file extension (slide.ome.tif -> slide).\n");
            sb.append("def stripImageExtension(String name) {\n");
            sb.append("    if (name == null) return name\n");
            sb.append("    def stripped = name.replaceFirst('(?i)\\\\.(?:")
                    .append(EXTENSIONS.replace("\\", "\\\\")).append(")(?=$| - )', '')\n");
            sb.append("    return stripped.isBlank() ? name : stripped\n");
            sb.append("}\n\n");
        }
        sb.append("def outputBaseName(String imageName) {\n");
        sb.append("    def base = imageName\n");
        if (strip) {
            sb.append("    // Images that would share a name once stripped keep their extension.\n");
            sb.append("    if (quietStrippedCounts == null) {\n");
            sb.append("        quietStrippedCounts = [:]\n");
            sb.append("        def proj = getProject()\n");
            sb.append("        if (proj != null) {\n");
            sb.append("            proj.getImageList().each {\n");
            sb.append("                def k = stripImageExtension(it.getImageName()).toLowerCase(Locale.ROOT)\n");
            sb.append("                quietStrippedCounts[k] = (quietStrippedCounts[k] ?: 0) + 1\n");
            sb.append("            }\n");
            sb.append("        }\n");
            sb.append("    }\n");
            sb.append("    def stripped = stripImageExtension(imageName)\n");
            sb.append("    if ((quietStrippedCounts[stripped.toLowerCase(Locale.ROOT)] ?: 0) <= 1) base = stripped\n");
        }
        sb.append("    return ").append(ScriptGenerator.quote(prefix)).append(" + base + ")
                .append(ScriptGenerator.quote(suffix)).append("\n");
        sb.append("}\n");
        return sb.toString();
    }
}
