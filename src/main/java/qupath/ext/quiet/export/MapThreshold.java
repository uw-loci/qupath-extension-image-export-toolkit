package qupath.ext.quiet.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The threshold a map's values were cut at, from a {@code Threshold:} line in the map
 * file's description:
 * <pre>
 * Threshold: Otsu 0.07800 | Otsu pooled over 1 image(s), ... | settings 7a3ec246 at 0.1732 um/px
 * </pre>
 * The three parts are the cut (method, optional unit, value), how it was obtained, and the
 * settings fingerprint with an optional pixel size. Two maps can be compared when the cut's
 * unit and value, as printed, and the fingerprint match and the pixel sizes are within 2%; the
 * method name and how the cut was obtained do not matter. (The format and this rule are the
 * fiber-analysis extension's {@code ThresholdIdentity}.)
 *
 * @param cut      the cut as written, e.g. {@code Otsu 0.07800}
 * @param key      the cut's unit and value as printed, e.g. {@code :0.07800}
 * @param settings the settings fingerprint
 * @param pixelUm  the pixel size the image was analyzed at, NaN if not given
 * @param line     the whole line after {@code Threshold:}
 */
public record MapThreshold(String cut, String key, String settings, double pixelUm, String line) {

    /** The description line prefix. */
    public static final String PREFIX = "Threshold:";

    /** Pixel sizes within this ratio count as the same. */
    static final double PIXEL_TOLERANCE = 1.02;

    private static final Pattern SETTINGS =
            Pattern.compile("settings (\\w+)(?: at ([0-9.eE+-]+) um/px)?");

    /**
     * The threshold line in a description.
     *
     * @param description the map file's description, may be null
     * @return the text after {@code Threshold:}, or null if there is none
     */
    public static String findLine(String description) {
        if (description == null) return null;
        for (String l : description.split("\\R")) {
            String t = l.trim();
            if (t.startsWith(PREFIX)) return t.substring(PREFIX.length()).trim();
        }
        return null;
    }

    /**
     * @param line the text after {@code Threshold:}
     * @return the threshold, or null if the line is not in the expected form
     */
    public static MapThreshold parse(String line) {
        if (line == null) return null;
        String[] parts = line.trim().split(" \\| ", -1);
        if (parts.length != 3) return null;
        Matcher sm = SETTINGS.matcher(parts[2].trim());
        if (!sm.matches()) return null;
        String cut = parts[0].trim().replaceAll("\\s+", " ");
        String[] words = cut.split(" ");
        if (words.length < 2) return null;
        String unit = String.join(" ", java.util.Arrays.copyOfRange(words, 1, words.length - 1));
        String key = unit + ":" + words[words.length - 1];
        double px = Double.NaN;
        if (sm.group(2) != null) {
            try {
                px = Double.parseDouble(sm.group(2));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new MapThreshold(cut, key, sm.group(1), px, line.trim());
    }

    /**
     * @param other another threshold
     * @return true when the cut's unit and value and the settings match and the pixel sizes
     *         are within 2%
     *         (or either is unknown)
     */
    public boolean comparableWith(MapThreshold other) {
        if (other == null || !key.equals(other.key) || !settings.equals(other.settings)) return false;
        if (!(pixelUm > 0) || !(other.pixelUm > 0)) return true;
        return Math.max(pixelUm, other.pixelUm) / Math.min(pixelUm, other.pixelUm) <= PIXEL_TOLERANCE;
    }

    /** The cut, settings and pixel size, without how the cut was obtained. */
    public String shortForm() {
        return cut + ", settings " + settings
                + (pixelUm > 0 ? " at " + pixelUm + " um/px" : "");
    }

    /**
     * Says when maps drawn together were cut at different thresholds or pixel sizes, so their
     * values are not comparable.
     *
     * @param lines image name to its map's threshold line (null or absent when the map has none)
     * @return a warning naming each group and its images, or null when every map that records
     *         a threshold is comparable with the others
     */
    public static String incomparableWarning(Map<String, String> lines) {
        List<MapThreshold> groups = new ArrayList<>();
        Map<MapThreshold, List<String>> members = new LinkedHashMap<>();
        List<String> unreadable = new ArrayList<>();
        for (var e : lines.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            MapThreshold t = parse(e.getValue());
            if (t == null) {
                unreadable.add(e.getKey());
                continue;
            }
            MapThreshold group = null;
            for (MapThreshold g : groups) {
                if (g.comparableWith(t)) {
                    group = g;
                    break;
                }
            }
            if (group == null) {
                group = t;
                groups.add(t);
            }
            members.computeIfAbsent(group, k -> new ArrayList<>()).add(e.getKey());
        }
        if (groups.size() <= 1) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("The maps were cut at ").append(groups.size())
                .append(" different thresholds or pixel sizes, so their values are not comparable:");
        int shown = 0;
        for (MapThreshold g : groups) {
            if (shown++ == 4) {
                sb.append("\n  ...");
                break;
            }
            List<String> names = members.get(g);
            sb.append("\n  ").append(g.shortForm()).append(": ")
                    .append(names.size() <= 3 ? String.join(", ", names)
                            : String.join(", ", names.subList(0, 3)) + " and "
                            + (names.size() - 3) + " more");
        }
        if (!unreadable.isEmpty()) {
            sb.append("\n  (unreadable threshold line: ").append(String.join(", ", unreadable)).append(")");
        }
        return sb.toString();
    }
}
