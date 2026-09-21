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

    /** The same rule as Groovy source, appended to generated scripts. */
    static final String GROOVY_FUNCTION = String.join("\n",
            "",
            "// Drop the source file extension from an image name (slide.ome.tif -> slide).",
            "def stripImageExtension(String name) {",
            "    if (name == null) return name",
            "    def stripped = name.replaceFirst('(?i)\\\\.(?:" + EXTENSIONS.replace("\\", "\\\\")
                    + ")(?=$| - )', '')",
            "    return stripped.isBlank() ? name : stripped",
            "}",
            "");

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
     * Base names for a batch. Names that would collide once stripped
     * ({@code a.tif} and {@code a.czi}) keep their extension so neither
     * export overwrites the other.
     *
     * @param names the project image names, in batch order
     * @param strip whether extensions should be removed at all
     * @return one base name per input, in the same order
     */
    public static List<String> baseNames(List<String> names, boolean strip) {
        if (!strip) {
            return new ArrayList<>(names);
        }
        Map<String, Integer> counts = new HashMap<>();
        List<String> stripped = new ArrayList<>(names.size());
        for (String name : names) {
            String s = stripExtension(name);
            stripped.add(s);
            counts.merge(key(s), 1, Integer::sum);
        }
        List<String> result = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            boolean collides = counts.get(key(stripped.get(i))) > 1;
            result.add(collides ? names.get(i) : stripped.get(i));
        }
        return result;
    }

    /** Case-folded because Windows and macOS filesystems are case-insensitive. */
    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    /**
     * Rewrite a generated script so it names its outputs the same way.
     *
     * @param script a script from {@link ScriptGenerator#generate}
     * @return the script with image names stripped of their extension
     */
    public static String applyToScript(String script) {
        if (script == null) {
            return null;
        }
        String out = script
                .replace("def entryName = entry.getImageName()",
                        "def entryName = stripImageExtension(entry.getImageName())")
                .replace("def entryName = getCurrentImageData().getServer().getMetadata().getName()",
                        "def entryName = stripImageExtension("
                                + "getCurrentImageData().getServer().getMetadata().getName())");
        return out.equals(script) ? script : out + GROOVY_FUNCTION;
    }
}
