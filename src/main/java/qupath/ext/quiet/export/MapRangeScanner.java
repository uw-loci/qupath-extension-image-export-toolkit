package qupath.ext.quiet.export;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.images.ImageData;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Computes one map color range over many images, from percentiles of their pooled values.
 */
public final class MapRangeScanner {

    private static final Logger logger = LoggerFactory.getLogger(MapRangeScanner.class);

    /** Longest side, in pixels, of the map read from each image for the scan. */
    private static final int SCAN_MAX_SIDE = 4096;

    private MapRangeScanner() {
    }

    /** Progress callback: image {@code index} of {@code total} is being scanned. */
    @FunctionalInterface
    public interface Progress {
        void update(int index, int total);
    }

    /**
     * The result of a scan.
     *
     * @param min        the lower percentile of the pooled values
     * @param max        the upper percentile of the pooled values
     * @param imagesUsed images whose map contributed values
     * @param missing    "name: reason" for each image that had no usable map
     */
    public record Result(double min, double max, int imagesUsed, List<String> missing) {}

    /**
     * Scan the images and compute the shared range.
     *
     * @param entries    the images to scan
     * @param source     where each image's map comes from
     * @param config     the map overlay configuration (percentiles)
     * @param downsample the export downsample; the scan reads no finer
     * @param progress   progress callback, may be null
     * @param cancelled  cancellation probe, may be null
     * @return the range, or null if no image had map values (or the scan was cancelled)
     */
    public static Result scan(List<ProjectImageEntry<BufferedImage>> entries,
                              MapOverlaySource source,
                              RenderedExportConfig.MapOverlayConfig config,
                              double downsample,
                              Progress progress,
                              BooleanSupplier cancelled) {
        List<float[]> samples = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int perImage = Math.max(10_000, MapLayer.MAX_PERCENTILE_SAMPLES / Math.max(1, entries.size()));
        for (int i = 0; i < entries.size(); i++) {
            if (cancelled != null && cancelled.getAsBoolean()) return null;
            var entry = entries.get(i);
            if (progress != null) progress.update(i, entries.size());
            ImageData<BufferedImage> imageData = null;
            try {
                imageData = entry.readImageData();
                var base = imageData.getServer();
                try (MapLayer layer = source.open(imageData)) {
                    double ds = Math.max(Math.max(1.0, downsample),
                            Math.max(base.getWidth(), base.getHeight()) / (double) SCAN_MAX_SIDE);
                    var band = layer.read(0, 0, base.getWidth(), base.getHeight(), ds);
                    float[] sample = MapLayer.finiteSample(band.data(), perImage);
                    if (sample.length == 0) {
                        missing.add(entry.getImageName() + ": the map has no values");
                    } else {
                        samples.add(sample);
                    }
                }
            } catch (Exception e) {
                missing.add(entry.getImageName() + ": " + e.getMessage());
            } finally {
                if (imageData != null) {
                    try {
                        imageData.getServer().close();
                    } catch (Exception e) {
                        logger.debug("Error closing {}: {}", entry.getImageName(), e.getMessage());
                    }
                }
            }
        }
        if (samples.isEmpty()) return null;
        int n = samples.stream().mapToInt(a -> a.length).sum();
        float[] pooled = new float[n];
        int off = 0;
        for (float[] a : samples) {
            System.arraycopy(a, 0, pooled, off, a.length);
            off += a.length;
        }
        Arrays.sort(pooled);
        double[] r = MapLayer.percentileRange(pooled, config.lowPercentile(), config.highPercentile());
        logger.info("Shared map range {} to {} ({} to {} percentile of {} values from {} images)",
                r[0], r[1], config.lowPercentile(), config.highPercentile(), n, samples.size());
        return new Result(r[0], r[1], samples.size(), missing);
    }

    /**
     * Resolve a SHARED range in a rendered configuration by scanning the images.
     *
     * @param entries   the images to export
     * @param source    the map source
     * @param config    the configuration
     * @param progress  progress callback, may be null
     * @param cancelled cancellation probe, may be null
     * @return the configuration with the range resolved; unchanged if it needs no scan
     *         or no image had map values
     */
    public static RenderedExportConfig resolve(List<ProjectImageEntry<BufferedImage>> entries,
                                               MapOverlaySource source,
                                               RenderedExportConfig config,
                                               Progress progress,
                                               BooleanSupplier cancelled) {
        var map = config.mapOverlay();
        if (source == null
                || config.getRenderMode() != RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY
                || map.rangeMode() != RenderedExportConfig.MapRangeMode.SHARED
                || map.rangeResolved()) {
            return config;
        }
        var result = scan(entries, source, map, config.getDownsample(), progress, cancelled);
        if (result == null) {
            logger.warn("No image had map values; each image uses its own percentiles");
            return config;
        }
        double max = result.max() > result.min() ? result.max() : result.min() + 1e-6;
        return RenderedExportConfig.Builder.from(config)
                .mapOverlay(map.withResolvedRange(result.min(), max))
                .build();
    }
}
