package qupath.ext.quiet.export;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.color.ColorMaps;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.RegionRequest;

/**
 * One channel of a map image, laid over a base image.
 * <p>
 * A map with a {@link MapPlacement} is a grid of cells placed on the image as the placement
 * says; image pixels outside the grid have no value. A map without one covers the whole image
 * and is stretched to it. Either way it is drawn at the output's resolution: each output pixel
 * takes the value of the cell under it ({@code nearest}) or a blend of the four nearest cell
 * centers ({@code bilinear}, NaN cells left out and the weights renormalized, clamped beyond the
 * outermost centers). A channel marked as an angle of period P is blended as an angle. A
 * class-index channel is drawn in its classes' colors and never blended.
 * <p>
 * A region too large to read with all its channels at once is read in strips, so a
 * many-channel map never has to fit in memory whole.
 */
public final class MapLayer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(MapLayer.class);

    /** Output rows read per strip. */
    private static final int STRIP_ROWS = 256;

    /** Largest all-channel read done in one piece; larger regions are read in strips. */
    static final long MAX_SINGLE_READ_BYTES = 128L * 1024 * 1024;

    /** Most values kept when sorting for a percentile. */
    static final int MAX_PERCENTILE_SAMPLES = 2_000_000;

    /** Color for a class with no color of its own. */
    private static final int DEFAULT_CLASS_RGB = 0x808080;

    private final ImageServer<BufferedImage> server;
    private final boolean ownsServer;
    private final int channel;
    private final String channelName;
    private final String unit;
    private final MapPlacement placement;
    private final double axialPeriod;
    private final List<String> classNames;
    private final int[] classColors;

    // Image x/y of map pixel (0, 0)'s top-left corner, and image pixels per map pixel
    private final double originX;
    private final double originY;
    private final double cellW;
    private final double cellH;
    private final boolean bilinear;

    private String cacheKey;
    private Region cacheRegion;
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
     * Map values read for a region: the cells under it plus a margin for blending.
     *
     * @param band  the values read, possibly from a downsampled level
     * @param mapX  map x of the band's left edge
     * @param mapY  map y of the band's top edge
     * @param fx    map pixels per band pixel, across
     * @param fy    map pixels per band pixel, down
     * @param core  band pixels under the region itself: {x0, y0, x1, y1}, end exclusive
     */
    private record Region(Band band, int mapX, int mapY, double fx, double fy, int[] core) {
        static final Region EMPTY = new Region(new Band(new float[0], 0, 0), 0, 0, 1, 1,
                new int[]{0, 0, 0, 0});

        boolean isEmpty() {
            return band.width() == 0 || band.height() == 0;
        }
    }

    /**
     * A map stretched over the whole base image.
     *
     * @param server      the map server
     * @param ownsServer  true to close the server with this layer
     * @param channel     zero-based channel to draw
     * @param unit        the channel's unit, or null
     * @param baseWidth   width of the base image the map covers
     * @param baseHeight  height of the base image the map covers
     */
    public MapLayer(ImageServer<BufferedImage> server, boolean ownsServer, int channel,
                    String unit, int baseWidth, int baseHeight) {
        this(server, ownsServer, channel, unit, baseWidth, baseHeight, null, 0, null);
    }

    /**
     * @param server      the map server
     * @param ownsServer  true to close the server with this layer
     * @param channel     zero-based channel to draw
     * @param unit        the channel's unit, or null
     * @param baseWidth   width of the base image
     * @param baseHeight  height of the base image
     * @param placement   where the map's grid sits on the base image; null to stretch the map
     *                    over the whole image
     * @param axialPeriod the period of an angle channel (180 for an orientation in degrees),
     *                    or 0 for an ordinary value
     * @param classNames  class names by index for a class-index channel, or null
     * @throws IllegalArgumentException if the placement does not fit the map or the base image
     */
    public MapLayer(ImageServer<BufferedImage> server, boolean ownsServer, int channel,
                    String unit, int baseWidth, int baseHeight,
                    MapPlacement placement, double axialPeriod, List<String> classNames) {
        this.server = server;
        this.ownsServer = ownsServer;
        this.channel = channel;
        this.channelName = server.getChannel(channel).getName();
        this.unit = unit;
        this.placement = placement;
        this.axialPeriod = axialPeriod > 0 ? axialPeriod : 0;
        this.classNames = classNames == null || classNames.isEmpty() ? null : List.copyOf(classNames);
        if (this.classNames != null) {
            classColors = new int[this.classNames.size()];
            for (int i = 0; i < classColors.length; i++) {
                Integer c = PathClass.fromString(this.classNames.get(i)).getColor();
                classColors[i] = c == null ? DEFAULT_CLASS_RGB : c & 0xFFFFFF;
            }
        } else {
            classColors = null;
        }
        if (placement != null) {
            if (placement.gridWidth() != server.getWidth() || placement.gridHeight() != server.getHeight()) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "the map is %d x %d but its Placement line says the grid is %dx%d",
                        server.getWidth(), server.getHeight(),
                        placement.gridWidth(), placement.gridHeight()));
            }
            if (placement.sourceWidth() != baseWidth || placement.sourceHeight() != baseHeight) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "the map was made for a %d x %d image but this image is %d x %d",
                        placement.sourceWidth(), placement.sourceHeight(), baseWidth, baseHeight));
            }
            originX = placement.x0();
            originY = placement.y0();
            cellW = placement.cellPx();
            cellH = placement.cellPx();
            bilinear = placement.bilinear();
        } else {
            originX = 0;
            originY = 0;
            cellW = baseWidth / (double) server.getWidth();
            cellH = baseHeight / (double) server.getHeight();
            bilinear = true;
        }
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

    /** Where the map's grid sits on the image, or null if it is stretched over the image. */
    public MapPlacement placement() {
        return placement;
    }

    /** The period of an angle channel, or 0 for an ordinary value. */
    public double axialPeriod() {
        return axialPeriod;
    }

    /** Class names by index for a class-index channel, or null. */
    public List<String> classNames() {
        return classNames;
    }

    /** Packed RGB by class index for a class-index channel, or null. */
    public int[] classColors() {
        return classColors == null ? null : classColors.clone();
    }

    /** True when each value is a class index drawn in that class's color. */
    public boolean isClassChannel() {
        return classNames != null;
    }

    /** True when values are blended between cell centers; false when each cell is filled. */
    public boolean isBilinear() {
        return bilinear && !isClassChannel();
    }

    /**
     * Read the map cells under a base-image region, at their own resolution (or a coarser
     * pyramid level when the output is coarser still).
     *
     * @param x          region x in base pixels
     * @param y          region y in base pixels
     * @param w          region width in base pixels
     * @param h          region height in base pixels
     * @param downsample the export downsample of the base image
     * @return the values; empty (0 x 0) if the region misses the map's grid
     * @throws java.io.IOException if the map cannot be read
     */
    public Band read(int x, int y, int w, int h, double downsample) throws java.io.IOException {
        Region r = region(x, y, w, h, downsample);
        if (r.isEmpty()) return r.band();
        int[] c = r.core();
        int cw = c[2] - c[0];
        int ch = c[3] - c[1];
        if (cw == r.band().width() && ch == r.band().height()) return r.band();
        float[] out = new float[cw * ch];
        for (int row = 0; row < ch; row++) {
            System.arraycopy(r.band().data(), (c[1] + row) * r.band().width() + c[0], out, row * cw, cw);
        }
        return new Band(out, cw, ch);
    }

    /**
     * The color range for a region under a map overlay configuration. An angle channel is
     * drawn from 0 to its period unless a fixed range is set, so the color wheel means the
     * same in every image; a class-index channel spans its classes.
     *
     * @param x          region x in base pixels
     * @param y          region y in base pixels
     * @param w          region width in base pixels
     * @param h          region height in base pixels
     * @param downsample the export downsample
     * @param cfg        the map overlay configuration, may be null
     * @return {min, max}
     * @throws java.io.IOException if the map cannot be read
     */
    public double[] range(int x, int y, int w, int h, double downsample,
                          RenderedExportConfig.MapOverlayConfig cfg) throws java.io.IOException {
        if (isClassChannel()) return new double[]{0, classNames.size() - 1};
        if (axialPeriod > 0 && (cfg == null || cfg.rangeMode() != RenderedExportConfig.MapRangeMode.FIXED)) {
            return new double[]{0, axialPeriod};
        }
        return range(read(x, y, w, h, downsample), cfg);
    }

    /**
     * Draw the channel over a base-image region at the output's size. Image pixels outside
     * the map's grid, NaN values and (with {@code hideAtOrBelowMin}) values at or below the
     * range's lower end are transparent; values outside the range take the end colors.
     *
     * @param x                region x in base pixels
     * @param y                region y in base pixels
     * @param w                region width in base pixels
     * @param h                region height in base pixels
     * @param downsample       the export downsample
     * @param outW             output width in pixels
     * @param outH             output height in pixels
     * @param colorMap         the color map (unused for a class-index channel)
     * @param min              value drawn with the first color
     * @param max              value drawn with the last color
     * @param hideAtOrBelowMin leave values at or below the range's lower end undrawn
     * @return an ARGB image of {@code outW x outH}
     * @throws java.io.IOException if the map cannot be read
     */
    public BufferedImage paint(int x, int y, int w, int h, double downsample, int outW, int outH,
                               ColorMaps.ColorMap colorMap, double min, double max,
                               boolean hideAtOrBelowMin) throws java.io.IOException {
        int[] argb = new int[outW * outH];
        double lo = Math.min(min, max);
        double hi = Math.max(min, max);
        sample(x, y, w, h, downsample, outW, outH, (i, v) -> {
            if (classColors != null) {
                int k = Math.round(v);
                if (k == v && k >= 0 && k < classColors.length) argb[i] = 0xFF000000 | classColors[k];
            } else if (!(hideAtOrBelowMin && v <= lo)) {
                double c = Math.max(lo, Math.min(hi, v));
                argb[i] = 0xFF000000 | (colorMap.getColor(c, min, max) & 0x00FFFFFF);
            }
        });
        var img = new BufferedImage(Math.max(1, outW), Math.max(1, outH), BufferedImage.TYPE_INT_ARGB);
        if (outW > 0 && outH > 0) img.setRGB(0, 0, outW, outH, argb, 0, outW);
        return img;
    }

    /**
     * The values drawn over a base-image region at the output's size, before coloring.
     *
     * @return values row by row, NaN where nothing is drawn (off the grid, or no data)
     * @throws java.io.IOException if the map cannot be read
     */
    float[] values(int x, int y, int w, int h, double downsample, int outW, int outH)
            throws java.io.IOException {
        float[] out = new float[outW * outH];
        Arrays.fill(out, Float.NaN);
        sample(x, y, w, h, downsample, outW, outH, (i, v) -> out[i] = v);
        return out;
    }

    /** Receives the value at one output pixel ({@code index = y * outW + x}). */
    @FunctionalInterface
    private interface ValueSink {
        void accept(int index, float value);
    }

    /** Sample the map at each output pixel's center; off-grid and NaN pixels are not passed on. */
    private void sample(int x, int y, int w, int h, double downsample, int outW, int outH,
                        ValueSink sink) throws java.io.IOException {
        Region r = region(x, y, w, h, downsample);
        if (r.isEmpty() || outW <= 0 || outH <= 0) return;
        Axis cols = axis(x, w, outW, originX, cellW, server.getWidth(), r.mapX(), r.fx(), r.band().width());
        Axis rows = axis(y, h, outH, originY, cellH, server.getHeight(), r.mapY(), r.fy(), r.band().height());
        float[] data = r.band().data();
        int bw = r.band().width();
        boolean blend = isBilinear();
        for (int oy = 0; oy < outH; oy++) {
            if (!rows.inside[oy]) continue;
            for (int ox = 0; ox < outW; ox++) {
                if (!cols.inside[ox]) continue;
                float v = blend
                        ? blend(data, bw, cols.i0[ox], cols.i1[ox], cols.t[ox],
                                rows.i0[oy], rows.i1[oy], rows.t[oy], axialPeriod)
                        : data[rows.n[oy] * bw + cols.n[ox]];
                if (!Float.isNaN(v)) sink.accept(oy * outW + ox, v);
            }
        }
    }

    /**
     * Where each output column (or row) samples the band.
     *
     * @param inside true where the output pixel's center is on the map's grid
     * @param n      nearest band index
     * @param i0     lower band index for blending
     * @param i1     upper band index for blending
     * @param t      weight of {@code i1}
     */
    private record Axis(boolean[] inside, int[] n, int[] i0, int[] i1, double[] t) {}

    private static Axis axis(int start, int length, int out, double origin, double cell, int mapSize,
                             int bandStart, double perBandPixel, int bandSize) {
        boolean[] inside = new boolean[out];
        int[] n = new int[out];
        int[] i0 = new int[out];
        int[] i1 = new int[out];
        double[] t = new double[out];
        double scale = length / (double) out;
        double end = origin + mapSize * cell;
        for (int o = 0; o < out; o++) {
            double b = start + (o + 0.5) * scale;
            if (b < origin || b >= end) continue;
            inside[o] = true;
            double u = ((b - origin) / cell - bandStart) / perBandPixel;
            n[o] = clamp((int) Math.floor(u), bandSize);
            double f = u - 0.5;
            int lo = (int) Math.floor(f);
            t[o] = f - lo;
            i0[o] = clamp(lo, bandSize);
            i1[o] = clamp(lo + 1, bandSize);
        }
        return new Axis(inside, n, i0, i1, t);
    }

    /** Bilinear blend of four cells, NaN cells left out; an angle of period P blended as an angle. */
    private static float blend(float[] data, int bw, int x0, int x1, double tx,
                               int y0, int y1, double ty, double period) {
        double sum = 0;
        double sumSin = 0;
        double wsum = 0;
        double k = period > 0 ? 2 * Math.PI / period : 0;
        for (int dy = 0; dy <= 1; dy++) {
            int row = (dy == 0 ? y0 : y1) * bw;
            double wy = dy == 0 ? 1 - ty : ty;
            for (int dx = 0; dx <= 1; dx++) {
                double wt = wy * (dx == 0 ? 1 - tx : tx);
                float v = data[row + (dx == 0 ? x0 : x1)];
                if (wt > 0 && !Float.isNaN(v)) {
                    if (period > 0) {
                        sum += wt * Math.cos(k * v);
                        sumSin += wt * Math.sin(k * v);
                    } else {
                        sum += wt * v;
                    }
                    wsum += wt;
                }
            }
        }
        if (wsum <= 0) return Float.NaN;
        if (period > 0) {
            double a = Math.atan2(sumSin, sum) * period / (2 * Math.PI);
            return (float) (a < 0 ? a + period : (a >= period ? a - period : a));
        }
        return (float) (sum / wsum);
    }

    private static int clamp(int i, int n) {
        return i < 0 ? 0 : (i >= n ? n - 1 : i);
    }

    /** The cells under a region plus a blending margin, cached for the last region asked for. */
    private Region region(int x, int y, int w, int h, double downsample) throws java.io.IOException {
        String key = x + "," + y + "," + w + "," + h + "," + downsample;
        if (key.equals(cacheKey)) return cacheRegion;

        int mapW = server.getWidth();
        int mapH = server.getHeight();
        int cx0 = Math.max(0, (int) Math.floor((x - originX) / cellW));
        int cy0 = Math.max(0, (int) Math.floor((y - originY) / cellH));
        int cx1 = Math.min(mapW, (int) Math.ceil((x + w - originX) / cellW));
        int cy1 = Math.min(mapH, (int) Math.ceil((y + h - originY) / cellH));
        Region region;
        if (cx1 <= cx0 || cy1 <= cy0) {
            region = Region.EMPTY;
        } else {
            // Never read a map finer than the output; angles and class indices are read at
            // full resolution, since a pyramid level averages them as plain numbers
            double mds = axialPeriod > 0 || isClassChannel()
                    ? 1.0 : Math.max(1.0, downsample / Math.sqrt(cellW * cellH));
            int margin = (int) Math.ceil(mds) + 1;
            int mx = Math.max(0, cx0 - margin);
            int my = Math.max(0, cy0 - margin);
            int mw = Math.min(mapW, cx1 + margin) - mx;
            int mh = Math.min(mapH, cy1 + margin) - my;
            Band band = readMap(mx, my, mw, mh, mds);
            double fx = mw / (double) band.width();
            double fy = mh / (double) band.height();
            int bx0 = Math.max(0, Math.min(band.width() - 1, (int) Math.floor((cx0 - mx) / fx)));
            int by0 = Math.max(0, Math.min(band.height() - 1, (int) Math.floor((cy0 - my) / fy)));
            int bx1 = Math.max(bx0 + 1, Math.min(band.width(), (int) Math.ceil((cx1 - mx) / fx)));
            int by1 = Math.max(by0 + 1, Math.min(band.height(), (int) Math.ceil((cy1 - my) / fy)));
            region = new Region(band, mx, my, fx, fy, new int[]{bx0, by0, bx1, by1});
        }
        cacheKey = key;
        cacheRegion = region;
        return region;
    }

    /** Read one channel of a map rectangle at a downsample, in one piece or in strips. */
    private Band readMap(int mx, int my, int mw, int mh, double mds) throws java.io.IOException {
        long bytes = (long) Math.ceil(mw / mds) * (long) Math.ceil(mh / mds)
                * server.nChannels() * Math.max(1, server.getPixelType().getBytesPerPixel());
        if (bytes > singleReadLimit) return readInStrips(mx, my, mw, mh, mds);
        BufferedImage img = server.readRegion(
                RegionRequest.createInstance(server.getPath(), mds, mx, my, mw, mh));
        if (img == null) throw new java.io.IOException("the map returned no pixels for this region");
        var raster = img.getRaster();
        return new Band(raster.getSamples(0, 0, raster.getWidth(), raster.getHeight(), channel,
                (float[]) null), raster.getWidth(), raster.getHeight());
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
