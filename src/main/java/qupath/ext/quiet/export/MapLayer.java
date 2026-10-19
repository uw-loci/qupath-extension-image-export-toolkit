package qupath.ext.quiet.export;

import java.awt.image.BufferedImage;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.color.ColorMaps;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.RegionRequest;

/**
 * One channel of a map image, laid over a base image.
 * <p>
 * The map covers the whole base image and is stretched to it, so a map may be
 * written at full resolution or coarser. A region too large to read with all
 * its channels at once is read in strips, so a many-channel map never has to
 * fit in memory whole.
 */
public final class MapLayer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(MapLayer.class);

    /** Output rows read per strip. */
    private static final int STRIP_ROWS = 256;

    /** Largest all-channel read done in one piece; larger regions are read in strips. */
    static final long MAX_SINGLE_READ_BYTES = 128L * 1024 * 1024;

    /** Most values kept when sorting for a percentile. */
    static final int MAX_PERCENTILE_SAMPLES = 2_000_000;

    private final ImageServer<BufferedImage> server;
    private final boolean ownsServer;
    private final int channel;
    private final String channelName;
    private final String unit;
    private final double scaleX;
    private final double scaleY;

    private String cacheKey;
    private Band cacheBand;
    private long singleReadLimit = MAX_SINGLE_READ_BYTES;

    /**
     * One channel of a region, as float values. NaN means no data.
     *
     * @param data   values, row by row
     * @param width  width in pixels
     * @param height height in pixels
     */
    public record Band(float[] data, int width, int height) {}

    /**
     * @param server      the map server
     * @param ownsServer  true to close the server with this layer
     * @param channel     zero-based channel to draw
     * @param unit        the channel's unit, or null
     * @param baseWidth   width of the base image the map covers
     * @param baseHeight  height of the base image the map covers
     */
    public MapLayer(ImageServer<BufferedImage> server, boolean ownsServer, int channel,
                    String unit, int baseWidth, int baseHeight) {
        this.server = server;
        this.ownsServer = ownsServer;
        this.channel = channel;
        this.channelName = server.getChannel(channel).getName();
        this.unit = unit;
        this.scaleX = server.getWidth() / (double) baseWidth;
        this.scaleY = server.getHeight() / (double) baseHeight;
    }

    /** Set the largest read done in one piece (tests use it to force strips). */
    void setSingleReadLimit(long bytes) {
        this.singleReadLimit = bytes;
        this.cacheKey = null;
    }

    /** The drawn channel's name in the map. */
    public String channelName() {
        return channelName;
    }

    /** The drawn channel's unit, or null if the map does not state one. */
    public String unit() {
        return unit;
    }

    /** Map pixels per base pixel, horizontally and vertically. */
    public double[] scale() {
        return new double[]{scaleX, scaleY};
    }

    /**
     * Read the channel over a base-image region.
     *
     * @param x          region x in base pixels
     * @param y          region y in base pixels
     * @param w          region width in base pixels
     * @param h          region height in base pixels
     * @param downsample the export downsample of the base image
     * @return the values; drawn stretched over the region's output size
     * @throws java.io.IOException if the map cannot be read
     */
    public Band read(int x, int y, int w, int h, double downsample) throws java.io.IOException {
        String key = x + "," + y + "," + w + "," + h + "," + downsample;
        if (key.equals(cacheKey)) return cacheBand;

        int mx = (int) Math.floor(x * scaleX);
        int my = (int) Math.floor(y * scaleY);
        int mx2 = Math.min(server.getWidth(), (int) Math.ceil((x + w) * scaleX));
        int my2 = Math.min(server.getHeight(), (int) Math.ceil((y + h) * scaleY));
        int mw = Math.max(1, mx2 - mx);
        int mh = Math.max(1, my2 - my);
        // Never read a map finer than the output: the drawing stretches it anyway
        double mds = Math.max(1.0, downsample * Math.sqrt(scaleX * scaleY));

        long bytes = (long) Math.ceil(mw / mds) * (long) Math.ceil(mh / mds)
                * server.nChannels() * Math.max(1, server.getPixelType().getBytesPerPixel());
        Band band;
        if (bytes <= singleReadLimit) {
            BufferedImage img = server.readRegion(
                    RegionRequest.createInstance(server.getPath(), mds, mx, my, mw, mh));
            if (img == null) throw new java.io.IOException("the map returned no pixels for this region");
            var raster = img.getRaster();
            band = new Band(raster.getSamples(0, 0, raster.getWidth(), raster.getHeight(), channel,
                    (float[]) null), raster.getWidth(), raster.getHeight());
        } else {
            band = readInStrips(mx, my, mw, mh, mds);
        }
        cacheKey = key;
        cacheBand = band;
        return band;
    }

    /**
     * Read a large region in strips of whole output rows. Each strip is read with a few
     * rows of overlap, which are cropped off, so the resize gives no seam between strips.
     */
    private Band readInStrips(int mx, int my, int mw, int mh, double mds) throws java.io.IOException {
        int stripMapRows = (int) Math.max(1, Math.round(STRIP_ROWS * mds));
        int marginRows = 2;
        for (int k = 2; k <= 16; k++) {
            if (Math.abs(k * mds - Math.rint(k * mds)) < 1e-6) {
                marginRows = k;
                break;
            }
        }
        int marginMap = (int) Math.ceil(marginRows * mds);

        java.util.List<float[]> strips = new java.util.ArrayList<>();
        java.util.List<Integer> heights = new java.util.ArrayList<>();
        int width = -1;
        int height = 0;
        for (int sy = my; sy < my + mh; sy += stripMapRows) {
            int sh = Math.min(stripMapRows, my + mh - sy);
            int top = Math.min(marginMap, sy - my);
            int bottom = Math.min(marginMap, my + mh - (sy + sh));
            var request = RegionRequest.createInstance(server.getPath(), mds,
                    mx, sy - top, mw, sh + top + bottom);
            BufferedImage img = server.readRegion(request);
            if (img == null) continue;
            var raster = img.getRaster();
            int cropTop = (int) Math.round(top / mds);
            int cropBottom = (int) Math.round(bottom / mds);
            int rows = raster.getHeight() - cropTop - cropBottom;
            if (rows <= 0) continue;
            if (width < 0) width = raster.getWidth();
            int cw = Math.min(width, raster.getWidth());
            float[] vals = raster.getSamples(0, cropTop, cw, rows, channel, (float[]) null);
            if (cw < width) {
                float[] padded = new float[width * rows];
                Arrays.fill(padded, Float.NaN);
                for (int r = 0; r < rows; r++) {
                    System.arraycopy(vals, r * cw, padded, r * width, cw);
                }
                vals = padded;
            }
            strips.add(vals);
            heights.add(rows);
            height += rows;
        }
        if (width <= 0 || height <= 0) {
            throw new java.io.IOException("the map returned no pixels for this region");
        }
        float[] data = new float[width * height];
        int offset = 0;
        for (int s = 0; s < strips.size(); s++) {
            System.arraycopy(strips.get(s), 0, data, offset, width * heights.get(s));
            offset += width * heights.get(s);
        }
        return new Band(data, width, height);
    }

    /**
     * The color range for a band under a map overlay configuration.
     *
     * @param band the values being drawn
     * @param cfg  the map overlay configuration
     * @return {min, max}
     */
    public static double[] range(Band band, RenderedExportConfig.MapOverlayConfig cfg) {
        if (cfg != null && cfg.hasKnownRange()) {
            return new double[]{cfg.rangeMin(), cfg.rangeMax()};
        }
        if (cfg != null && cfg.rangeMode() == RenderedExportConfig.MapRangeMode.SHARED) {
            // Not yet resolved over a batch (a preview, or a single image): use this image
            float[] sample = finiteSample(band.data(), MAX_PERCENTILE_SAMPLES);
            Arrays.sort(sample);
            return percentileRange(sample, cfg.lowPercentile(), cfg.highPercentile());
        }
        return minMax(band.data());
    }

    /** The minimum and maximum finite value; {0, 1} if there is none. */
    public static double[] minMax(float[] values) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (float v : values) {
            if (!Float.isFinite(v)) continue;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        return min > max ? new double[]{0, 1} : new double[]{min, max};
    }

    /**
     * Two percentiles of sorted values, with linear interpolation.
     *
     * @param sorted values in ascending order, finite only
     * @param low    lower percentile (0-100)
     * @param high   upper percentile (0-100)
     * @return {low value, high value}; {0, 1} if there are no values
     */
    public static double[] percentileRange(float[] sorted, double low, double high) {
        if (sorted.length == 0) return new double[]{0, 1};
        return new double[]{percentile(sorted, low), percentile(sorted, high)};
    }

    private static double percentile(float[] sorted, double p) {
        double pos = Math.max(0, Math.min(100, p)) / 100.0 * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(sorted.length - 1, lo + 1);
        double t = pos - lo;
        return sorted[lo] * (1 - t) + sorted[hi] * t;
    }

    /**
     * The finite values, thinned to at most {@code max} by an even stride.
     *
     * @param values the values
     * @param max    most values to keep
     * @return a new array of finite values
     */
    public static float[] finiteSample(float[] values, int max) {
        int finite = 0;
        for (float v : values) if (Float.isFinite(v)) finite++;
        int stride = Math.max(1, (int) Math.ceil(finite / (double) max));
        float[] out = new float[(finite + stride - 1) / stride];
        int seen = 0;
        int n = 0;
        for (float v : values) {
            if (!Float.isFinite(v)) continue;
            if (seen++ % stride == 0 && n < out.length) out[n++] = v;
        }
        return n == out.length ? out : Arrays.copyOf(out, n);
    }

    /**
     * Color a band. NaN is fully transparent; values outside the range take the end colors.
     *
     * @param band     the values
     * @param colorMap the color map
     * @param min      value drawn with the first color
     * @param max      value drawn with the last color
     * @return an ARGB image the size of the band
     */
    public static BufferedImage colorize(Band band, ColorMaps.ColorMap colorMap, double min, double max) {
        int w = band.width();
        int h = band.height();
        int[] argb = new int[w * h];
        float[] data = band.data();
        double lo = Math.min(min, max);
        double hi = Math.max(min, max);
        for (int i = 0; i < argb.length; i++) {
            float v = data[i];
            if (Float.isNaN(v)) continue; // transparent
            double c = Math.max(lo, Math.min(hi, v));
            argb[i] = 0xFF000000 | (colorMap.getColor(c, min, max) & 0x00FFFFFF);
        }
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, argb, 0, w);
        return img;
    }

    @Override
    public void close() {
        if (!ownsServer) return;
        try {
            server.close();
        } catch (Exception e) {
            logger.debug("Error closing map server: {}", e.getMessage());
        }
    }
}
