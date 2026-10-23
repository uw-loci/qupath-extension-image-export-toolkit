package qupath.ext.quiet.export;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.display.ImageDisplay;
import qupath.lib.display.settings.DisplaySettingUtils;
import qupath.lib.display.settings.ImageDisplaySettings;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.images.servers.PixelType;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;

/**
 * Scans images at low resolution to compute per-channel global display ranges
 * with percentile-based saturation clipping.
 * <p>
 * Used by the GLOBAL_MATCHED display settings mode to ensure all images
 * in a batch share identical brightness/contrast per channel.
 */
public class GlobalDisplayRangeScanner {

    private static final Logger logger = LoggerFactory.getLogger(GlobalDisplayRangeScanner.class);

    /** Default scan downsample for fast reading. */
    private static final double DEFAULT_SCAN_DOWNSAMPLE = 32.0;

    /** Number of histogram bins for float-type images. */
    static final int FLOAT_HISTOGRAM_BINS = 10000;

    /** Exact bins for unsigned images of up to 16 bits. */
    private static final int INTEGER_HISTOGRAM_BINS = 65536;

    /**
     * Per-channel computed display range.
     */
    public record ChannelRange(String name, int color, double minDisplay, double maxDisplay) {}

    private GlobalDisplayRangeScanner() {
        // Utility class
    }

    /**
     * One image read for the scan: its pixels at the scan downsample and its pixel type.
     *
     * @param raster the pixels
     * @param type   the image's pixel type
     */
    record ScanImage(Raster raster, PixelType type) {}

    /**
     * Images of one kind (same pixel type and channel names) and their shared ranges.
     *
     * @param kind    the pixel type and channel names, e.g. {@code UINT16 [Channel 1]}
     * @param indices positions of the group's images in the scanned list
     * @param ranges  per-channel ranges, ordered by channel index
     */
    public record MatchedGroup(String kind, List<Integer> indices, List<ChannelRange> ranges) {}

    /**
     * Group the entries by kind (pixel type and channel names) and compute per-channel
     * percentile-based ranges within each group, so an 8-bit RGB image and a 16-bit
     * single-channel image never share a histogram. Entries that cannot be opened are left out.
     *
     * @param entries          project images to scan
     * @param percentile       saturation percentile (e.g., 0.1 for 0.1% clip at each end)
     * @param scanDownsample   downsample for fast scanning (e.g., 32.0)
     * @param progressCallback optional (index, total) callback for UI progress
     * @return one group per kind, in order of first appearance
     */
    public static List<MatchedGroup> computeGroupedRanges(
            List<ProjectImageEntry<BufferedImage>> entries,
            double percentile, double scanDownsample,
            BiConsumer<Integer, Integer> progressCallback) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        Map<String, List<Integer>> byKind = new LinkedHashMap<>();
        Map<String, ImageServerMetadata> metadataByKind = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            try (var server = entries.get(i).getServerBuilder().build()) {
                var metadata = server.getMetadata();
                String kind = kindOf(metadata);
                byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(i);
                metadataByKind.putIfAbsent(kind, metadata);
            } catch (Exception e) {
                logger.warn("Failed to open {} for display ranges: {}",
                        entries.get(i).getImageName(), e.getMessage());
            }
        }

        double ds = Math.max(scanDownsample, 1.0);
        int total = entries.size();
        int[] done = {0};
        List<MatchedGroup> groups = new ArrayList<>();
        for (var e : byKind.entrySet()) {
            List<Integer> indices = e.getValue();
            var channels = metadataByKind.get(e.getKey()).getChannels();
            int base = done[0];
            double[][] minMax = rangesFromImages(indices.size(),
                    j -> readScanImage(entries.get(indices.get(j)), ds), channels.size(), percentile,
                    progressCallback == null ? null : (j, n) -> progressCallback.accept(
                            Math.min(total - 1, base + j * indices.size() / Math.max(n, 1)), total));
            done[0] += indices.size();
            List<ChannelRange> ranges = new ArrayList<>();
            for (int c = 0; c < channels.size(); c++) {
                ranges.add(new ChannelRange(channels.get(c).getName(), channels.get(c).getColor(),
                        minMax[c][0], minMax[c][1]));
            }
            groups.add(new MatchedGroup(e.getKey(), List.copyOf(indices), ranges));
            logger.info("Matched display ranges for {} image(s) of kind {}", indices.size(), e.getKey());
        }
        return groups;
    }

    /**
     * The kind an image is matched within: pixel type and channel names in order.
     *
     * @param metadata the image's metadata
     * @return e.g. {@code UINT8 [Red, Green, Blue]}
     */
    static String kindOf(ImageServerMetadata metadata) {
        return metadata.getPixelType() + " "
                + metadata.getChannels().stream().map(ImageChannel::getName).toList();
    }

    private static ScanImage readScanImage(ProjectImageEntry<BufferedImage> entry, double ds) {
        try (var server = entry.getServerBuilder().build()) {
            RegionRequest request = RegionRequest.createInstance(
                    server.getPath(), ds, 0, 0, server.getWidth(), server.getHeight());
            Raster raster = server.readRegion(request).getRaster();
            return new ScanImage(raster, server.getPixelType());
        } catch (Exception e) {
            logger.warn("Failed to scan image {} for display ranges: {}",
                    entry.getImageName(), e.getMessage());
            return null;
        }
    }

    /**
     * Per-channel display ranges over a set of images. Unsigned images of up to 16 bits are
     * counted in an exact 65536-bin histogram whatever the first image's depth, so a mix of
     * 8-bit and 16-bit images keeps the 16-bit values. If any image is signed, floating point
     * or deeper than 16 bits, every image is read again in two passes (minimum and maximum,
     * then a {@value #FLOAT_HISTOGRAM_BINS}-bin histogram). The generated script's
     * {@code quietRangesFromImages} is the same procedure.
     *
     * @param n          number of images
     * @param reader     reads image i; null when it cannot be read (it is left out)
     * @param nChannels  channels to compute
     * @param percentile saturation percentile clipped at each end
     * @param progress   optional (index, total) callback
     * @return {min, max} per channel
     */
    static double[][] rangesFromImages(int n, IntFunction<ScanImage> reader, int nChannels,
            double percentile, BiConsumer<Integer, Integer> progress) {
        long[][] histograms = new long[nChannels][INTEGER_HISTOGRAM_BINS];
        int maxBits = 0;
        boolean integer = true;
        for (int i = 0; i < n && integer; i++) {
            if (progress != null) progress.accept(i, n);
            ScanImage img = reader.apply(i);
            if (img == null) continue;
            if (!fitsIntegerHistogram(img.type())) {
                integer = false;
                break;
            }
            maxBits = Math.max(maxBits, img.type().getBitsPerPixel());
            Raster raster = img.raster();
            int bands = Math.min(raster.getNumBands(), nChannels);
            for (int y = 0; y < raster.getHeight(); y++) {
                for (int x = 0; x < raster.getWidth(); x++) {
                    for (int c = 0; c < bands; c++) {
                        int val = raster.getSample(x, y, c);
                        if (val >= 0 && val < INTEGER_HISTOGRAM_BINS) {
                            histograms[c][val]++;
                        }
                    }
                }
            }
        }
        if (integer) {
            double fullScale = maxBits > 8 ? 65535 : 255;
            double[][] result = new double[nChannels][];
            for (int c = 0; c < nChannels; c++) {
                long totalPixels = 0;
                for (long count : histograms[c]) totalPixels += count;
                if (totalPixels == 0) {
                    result[c] = new double[]{0, fullScale};
                    continue;
                }
                double clipCount = totalPixels * percentile / 100.0;
                double minVal = findPercentileFromHistogram(histograms[c], clipCount, true);
                double maxVal = findPercentileFromHistogram(histograms[c], clipCount, false);
                if (minVal >= maxVal) maxVal = minVal + 1;
                result[c] = new double[]{minVal, maxVal};
            }
            return result;
        }
        return binnedRanges(n, reader, nChannels, percentile, progress);
    }

    /** Whether an image's values all fit the exact integer histogram. */
    static boolean fitsIntegerHistogram(PixelType type) {
        return type.isUnsignedInteger() && type.getBitsPerPixel() <= 16;
    }

    /**
     * Two passes: global minimum and maximum, then a binned histogram.
     */
    private static double[][] binnedRanges(int n, IntFunction<ScanImage> reader, int nChannels,
            double percentile, BiConsumer<Integer, Integer> progress) {
        double[] globalMin = new double[nChannels];
        double[] globalMax = new double[nChannels];
        for (int c = 0; c < nChannels; c++) {
            globalMin[c] = Double.MAX_VALUE;
            globalMax[c] = -Double.MAX_VALUE;
        }
        for (int i = 0; i < n; i++) {
            if (progress != null) progress.accept(i, n * 2);
            ScanImage img = reader.apply(i);
            if (img == null) continue;
            Raster raster = img.raster();
            int bands = Math.min(raster.getNumBands(), nChannels);
            for (int y = 0; y < raster.getHeight(); y++) {
                for (int x = 0; x < raster.getWidth(); x++) {
                    for (int c = 0; c < bands; c++) {
                        double val = raster.getSampleDouble(x, y, c);
                        if (Double.isFinite(val)) {
                            if (val < globalMin[c]) globalMin[c] = val;
                            if (val > globalMax[c]) globalMax[c] = val;
                        }
                    }
                }
            }
        }

        long[][] histograms = new long[nChannels][FLOAT_HISTOGRAM_BINS];
        double[] binWidth = new double[nChannels];
        for (int c = 0; c < nChannels; c++) {
            double range = globalMax[c] - globalMin[c];
            binWidth[c] = range > 0 ? range / FLOAT_HISTOGRAM_BINS : 1.0;
        }
        for (int i = 0; i < n; i++) {
            if (progress != null) progress.accept(n + i, n * 2);
            ScanImage img = reader.apply(i);
            if (img == null) continue;
            Raster raster = img.raster();
            int bands = Math.min(raster.getNumBands(), nChannels);
            for (int y = 0; y < raster.getHeight(); y++) {
                for (int x = 0; x < raster.getWidth(); x++) {
                    for (int c = 0; c < bands; c++) {
                        double val = raster.getSampleDouble(x, y, c);
                        if (Double.isFinite(val)) {
                            int bin = (int) ((val - globalMin[c]) / binWidth[c]);
                            bin = Math.max(0, Math.min(bin, FLOAT_HISTOGRAM_BINS - 1));
                            histograms[c][bin]++;
                        }
                    }
                }
            }
        }

        double[][] result = new double[nChannels][];
        for (int c = 0; c < nChannels; c++) {
            long totalPixels = 0;
            for (long count : histograms[c]) totalPixels += count;
            if (totalPixels == 0) {
                result[c] = new double[]{globalMin[c], globalMax[c]};
                continue;
            }
            double clipCount = totalPixels * percentile / 100.0;
            double minBin = findPercentileFromHistogram(histograms[c], clipCount, true);
            double maxBin = findPercentileFromHistogram(histograms[c], clipCount, false);
            double minVal = globalMin[c] + minBin * binWidth[c];
            double maxVal = globalMin[c] + maxBin * binWidth[c];
            if (minVal >= maxVal) maxVal = minVal + binWidth[c];
            result[c] = new double[]{minVal, maxVal};
        }
        return result;
    }

    /**
     * Find the histogram bin at which the cumulative count exceeds clipCount.
     *
     * @param histogram  the histogram array
     * @param clipCount  number of pixels to clip
     * @param fromLow    true for low percentile (scan from left), false for high (scan from right)
     * @return the bin index at the percentile boundary
     */
    private static double findPercentileFromHistogram(long[] histogram, double clipCount, boolean fromLow) {
        long cumulative = 0;
        if (fromLow) {
            for (int i = 0; i < histogram.length; i++) {
                cumulative += histogram[i];
                if (cumulative > clipCount) {
                    return i;
                }
            }
            return histogram.length - 1;
        } else {
            for (int i = histogram.length - 1; i >= 0; i--) {
                cumulative += histogram[i];
                if (cumulative > clipCount) {
                    return i;
                }
            }
            return 0;
        }
    }

    /**
     * Build ImageDisplaySettings from computed channel ranges.
     * Uses the first entry to create a template display, then adjusts
     * each channel's min/max to match the computed global ranges.
     *
     * @param ranges  computed per-channel ranges
     * @param firstEntry  a project image entry to use as template
     * @return display settings with global ranges applied, or null on failure
     */
    public static ImageDisplaySettings buildDisplaySettings(
            List<ChannelRange> ranges,
            ProjectImageEntry<BufferedImage> firstEntry) {
        if (ranges == null || ranges.isEmpty() || firstEntry == null) {
            return null;
        }
        try {
            var imageData = firstEntry.readImageData();
            var display = ImageDisplay.create(imageData);

            var channels = display.selectedChannels();
            for (int i = 0; i < Math.min(channels.size(), ranges.size()); i++) {
                var channel = channels.get(i);
                var range = ranges.get(i);
                display.setMinMaxDisplay(channel, (float) range.minDisplay(), (float) range.maxDisplay());
            }

            var settings = DisplaySettingUtils.displayToSettings(display, "global_matched");
            imageData.getServer().close();
            return settings;
        } catch (Exception e) {
            logger.error("Failed to build display settings from computed ranges: {}", e.getMessage());
            return null;
        }
    }
}
