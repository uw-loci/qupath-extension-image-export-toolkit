package qupath.ext.quiet.export;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import qupath.lib.gui.tools.ColorToolsFX;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.regions.ImageRegion;

/**
 * What one exported image's legends say: its channels or stains, the object classes drawn
 * on it, and its map's color scale. A panel compares these across cells to decide whether
 * one legend beside the figure is true for every cell.
 *
 * @param channels channel or stain rows; empty when the channel legend is off
 * @param objects  object class rows; empty when the object legend is off or nothing is drawn
 * @param map      the map's color scale, or null when there is none
 */
public record LegendContent(List<LegendRenderer.Entry> channels,
                            List<LegendRenderer.Entry> objects,
                            MapScale map) {

    /** No legend. */
    public static final LegendContent EMPTY = new LegendContent(List.of(), List.of(), null);

    /**
     * A map's color scale.
     *
     * @param colormapName the color map, for a continuous map
     * @param min          value at the bottom of the bar
     * @param max          value at the top of the bar
     * @param title        title of the bar; blank for none
     * @param classes      the classes of a class-index map, in place of a bar; empty otherwise
     */
    public record MapScale(String colormapName, double min, double max, String title,
                           List<LegendRenderer.Entry> classes) {}

    public LegendContent {
        channels = List.copyOf(channels);
        objects = List.copyOf(objects);
    }

    /** True when there is nothing to draw. */
    public boolean isEmpty() {
        return channels.isEmpty() && objects.isEmpty() && map == null;
    }

    /** The channel and object rows, in the order one legend block lists them. */
    public List<LegendRenderer.Entry> rows() {
        var rows = new ArrayList<LegendRenderer.Entry>(channels);
        rows.addAll(objects);
        return rows;
    }

    /**
     * The object classes drawn in a region, one row per class and object type.
     * <p>
     * Annotations come first, then detections; named classes alphabetically, then the
     * unclassified ones as "Annotations" or "Detections". The swatch takes the color most
     * objects of the row are drawn in, and its kind follows how they are drawn.
     *
     * @param annotations true if annotations are drawn
     * @param detections  true if detections are drawn
     * @param fillAnnotations true if annotations are drawn filled
     */
    public static List<LegendRenderer.Entry> objectEntries(ImageData<BufferedImage> imageData,
                                                           int x, int y, int w, int h,
                                                           boolean annotations, boolean detections,
                                                           boolean fillAnnotations) {
        if (!annotations && !detections) {
            return List.of();
        }
        var region = ImageRegion.createInstance(x, y, w, h, 0, 0);
        var objects = imageData.getHierarchy().getObjectsForRegion(null, region, null);
        boolean fillDetections = new OverlayOptions().getFillDetections();
        // key: "A" or "D", then the class name ("" when unclassified)
        Map<String, Group> groups = new LinkedHashMap<>();
        for (PathObject o : objects) {
            if (!o.hasROI()) {
                continue;
            }
            boolean annotation = o.isAnnotation();
            if (annotation ? !annotations : !(detections && o.isDetection())) {
                continue;
            }
            String name = o.getPathClass() == null ? "" : o.getPathClass().toString();
            var group = groups.computeIfAbsent((annotation ? "A" : "D") + name,
                    k -> new Group(annotation, name));
            Integer rgb = ColorToolsFX.getDisplayedColorARGB(o);
            group.colors.merge(rgb == null ? 0xFFFFFF : rgb & 0xFFFFFF, 1, Integer::sum);
            var roi = o.getROI();
            if (roi.isPoint()) {
                group.points++;
            } else if (roi.isLine()) {
                group.lines++;
            }
            group.count++;
        }
        var sorted = new ArrayList<>(groups.values());
        sorted.sort((a, b) -> {
            if (a.annotation != b.annotation) {
                return a.annotation ? -1 : 1;
            }
            if (a.name.isEmpty() != b.name.isEmpty()) {
                return a.name.isEmpty() ? 1 : -1;
            }
            return a.name.compareTo(b.name);
        });
        var entries = new ArrayList<LegendRenderer.Entry>();
        for (var g : sorted) {
            String label = g.name.isEmpty() ? (g.annotation ? "Annotations" : "Detections") : g.name;
            boolean twice = !g.name.isEmpty() && groups.containsKey("A" + g.name)
                    && groups.containsKey("D" + g.name);
            if (twice) {
                label += g.annotation ? " (annotations)" : " (detections)";
            }
            LegendRenderer.SwatchKind kind;
            if (g.points == g.count) {
                kind = LegendRenderer.SwatchKind.POINT;
            } else if (g.lines == g.count) {
                kind = LegendRenderer.SwatchKind.LINE;
            } else if (g.annotation ? fillAnnotations : fillDetections) {
                kind = LegendRenderer.SwatchKind.FILL;
            } else {
                kind = LegendRenderer.SwatchKind.OUTLINE;
            }
            entries.add(new LegendRenderer.Entry(label, g.mainColor(), kind));
        }
        return entries;
    }

    /** Objects of one class and type, counted by color and ROI kind. */
    private static final class Group {
        final boolean annotation;
        final String name;
        final Map<Integer, Integer> colors = new LinkedHashMap<>();
        int count;
        int points;
        int lines;

        Group(boolean annotation, String name) {
            this.annotation = annotation;
            this.name = name;
        }

        /** The most frequent color; the smaller value on a tie. */
        int mainColor() {
            int best = 0;
            int bestCount = -1;
            for (var e : colors.entrySet()) {
                int c = e.getKey();
                int n = e.getValue();
                if (n > bestCount || (n == bestCount && c < best)) {
                    best = c;
                    bestCount = n;
                }
            }
            return best;
        }
    }

    /**
     * Pool object rows from several cells: each row once, in the usual order. Null if two
     * cells draw the same row in different colors or swatches.
     */
    static List<LegendRenderer.Entry> poolObjects(List<List<LegendRenderer.Entry>> perCell) {
        Map<String, LegendRenderer.Entry> byName = new LinkedHashMap<>();
        for (var cell : perCell) {
            for (var e : cell) {
                var seen = byName.putIfAbsent(e.name(), e);
                if (seen != null && !seen.equals(e)) {
                    return null;
                }
            }
        }
        // Keep the order of the first cell that lists each row, cells in placement order
        return new ArrayList<>(byName.values());
    }

    /** The value every cell shares, or null if any differs. */
    static <T> T shared(List<T> perCell) {
        if (perCell.isEmpty()) {
            return null;
        }
        T first = perCell.get(0);
        for (T t : perCell) {
            if (!Objects.equals(first, t)) {
                return null;
            }
        }
        return first;
    }
}
