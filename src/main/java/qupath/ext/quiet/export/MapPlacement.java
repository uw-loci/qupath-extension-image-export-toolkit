package qupath.ext.quiet.export;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a map's grid sits on the image it was computed from, read from a
 * {@code Placement:} line in the map file's description:
 * <pre>
 * Placement: source_width=16952; source_height=18225; source_pixel_um=0.1732; cell_px=36;
 *            window_px=144; x0=54; y0=54; grid=467x503; render=bilinear
 * </pre>
 * Map pixel (i, j) covers image pixels {@code x0 + i*cell_px} to {@code x0 + (i+1)*cell_px}
 * across (and the same down), and its value belongs at the cell center. Image pixels outside
 * the grid have no value.
 *
 * @param sourceWidth   width of the image the map belongs to
 * @param sourceHeight  height of the image the map belongs to
 * @param sourcePixelUm that image's pixel size in um, NaN if not given
 * @param cellPx        image pixels per map pixel
 * @param windowPx      image pixels a map pixel's analysis window spans (information only)
 * @param x0            image x of the grid's left edge
 * @param y0            image y of the grid's top edge
 * @param gridWidth     map pixels across
 * @param gridHeight    map pixels down
 * @param bilinear      true to interpolate between cell centers, false to fill each cell
 */
public record MapPlacement(int sourceWidth, int sourceHeight, double sourcePixelUm,
                           double cellPx, double windowPx, double x0, double y0,
                           int gridWidth, int gridHeight, boolean bilinear) {

    /** The description line prefix. */
    public static final String PREFIX = "Placement:";

    private static final Pattern KEY_VALUE = Pattern.compile("(\\w+)=([^;\\s]+)");

    /**
     * @throws IllegalArgumentException if the geometry is not usable
     */
    public MapPlacement {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException("source size must be positive, got "
                    + sourceWidth + " x " + sourceHeight);
        }
        if (!(cellPx > 0)) {
            throw new IllegalArgumentException("cell_px must be positive, got " + cellPx);
        }
        if (gridWidth <= 0 || gridHeight <= 0) {
            throw new IllegalArgumentException("grid must be non-empty, got "
                    + gridWidth + "x" + gridHeight);
        }
    }

    /**
     * The placement in a description.
     *
     * @param description the map file's description, may be null
     * @return the placement, or null if there is no {@code Placement:} line
     * @throws IllegalArgumentException if the line is there but cannot be read
     */
    public static MapPlacement parse(String description) {
        if (description == null) return null;
        String line = null;
        for (String l : description.split("\\R")) {
            String t = l.trim();
            if (t.startsWith(PREFIX)) {
                line = t.substring(PREFIX.length());
                break;
            }
        }
        if (line == null) return null;
        Map<String, String> kv = new HashMap<>();
        Matcher m = KEY_VALUE.matcher(line);
        while (m.find()) kv.put(m.group(1), m.group(2));
        try {
            String[] grid = require(kv, "grid").toLowerCase(Locale.ROOT).split("x");
            double cell = Double.parseDouble(require(kv, "cell_px"));
            String render = kv.getOrDefault("render", "nearest").toLowerCase(Locale.ROOT);
            if (!render.equals("nearest") && !render.equals("bilinear")) {
                throw new IllegalArgumentException("unknown render '" + render + "'");
            }
            return new MapPlacement(
                    Integer.parseInt(require(kv, "source_width")),
                    Integer.parseInt(require(kv, "source_height")),
                    kv.containsKey("source_pixel_um")
                            ? Double.parseDouble(kv.get("source_pixel_um")) : Double.NaN,
                    cell,
                    kv.containsKey("window_px") ? Double.parseDouble(kv.get("window_px")) : cell,
                    Double.parseDouble(kv.getOrDefault("x0", "0")),
                    Double.parseDouble(kv.getOrDefault("y0", "0")),
                    Integer.parseInt(grid[0]),
                    Integer.parseInt(grid[1]),
                    render.equals("bilinear"));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the map's Placement line cannot be read ("
                    + e.getMessage() + "):" + line, e);
        }
    }

    private static String require(Map<String, String> kv, String key) {
        String v = kv.get(key);
        if (v == null) throw new IllegalArgumentException("no " + key);
        return v;
    }

    /** One line for logs and export records. */
    public String describe() {
        return String.format(Locale.ROOT, "%dx%d grid of %s px cells from (%s, %s) on a %d x %d image, %s",
                gridWidth, gridHeight, num(cellPx), num(x0), num(y0), sourceWidth, sourceHeight,
                bilinear ? "bilinear" : "nearest");
    }

    private static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
