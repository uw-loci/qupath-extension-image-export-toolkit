package qupath.ext.quiet.export;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BandedSampleModel;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.util.List;

import org.junit.jupiter.api.Test;

import qupath.lib.color.ColorMaps;
import qupath.lib.color.ColorModelFactory;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.WrappedBufferedImageServer;

class MapLayerTest {

    private static RenderedExportConfig.MapOverlayConfig mode(RenderedExportConfig.MapRangeMode m,
                                                              double min, double max,
                                                              double lowP, double highP) {
        return new RenderedExportConfig.MapOverlayConfig(RenderedExportConfig.MapSource.IMAGE_FILE,
                "x", "", m, min, max, lowP, highP, false, "", false);
    }

    /** A 2-channel float map, value = 10 * channel + x + width * y; one NaN. */
    private static WrappedBufferedImageServer floatMap(int w, int h) {
        var channels = List.of(ImageChannel.getInstance("A", 0xFF0000),
                ImageChannel.getInstance("B", 0x00FF00));
        var raster = Raster.createWritableRaster(
                new BandedSampleModel(DataBuffer.TYPE_FLOAT, w, h, 2), null);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                raster.setSample(x, y, 0, x + w * y);
                raster.setSample(x, y, 1, 10 + x + w * y);
            }
        }
        raster.setSample(0, 0, 1, Float.NaN);
        var cm = ColorModelFactory.createColorModel(PixelType.FLOAT32, channels);
        var img = new BufferedImage(cm, raster, false, null);
        return new WrappedBufferedImageServer("map", img, channels);
    }

    @Test
    void readsOneChannelStretchedOverTheBaseImage() throws Exception {
        var server = floatMap(4, 2);
        // The map is half the size of an 8 x 4 base image
        try (var layer = new MapLayer(server, false, 1, "um", 8, 4)) {
            assertEquals("B", layer.channelName());
            assertEquals("um", layer.unit());
            var band = layer.read(0, 0, 8, 4, 1.0);
            assertEquals(4, band.width());
            assertEquals(2, band.height());
            assertTrue(Float.isNaN(band.data()[0]));
            assertEquals(11f, band.data()[1]);
            assertEquals(17f, band.data()[7]);

            // Right half of the base image is the right half of the map
            var right = layer.read(4, 0, 4, 4, 1.0);
            assertEquals(2, right.width());
            assertEquals(12f, right.data()[0]);
            assertArrayEquals(right.data(), layer.read(4, 0, 4, 4, 1.0).data(), "the same region again");
        }
    }

    @Test
    void rangeModes() {
        float[] data = new float[101];
        for (int i = 0; i <= 100; i++) data[i] = i;
        data[50] = Float.NaN;
        var band = new MapLayer.Band(data, 101, 1);

        assertArrayEquals(new double[]{0, 100},
                MapLayer.range(band, mode(RenderedExportConfig.MapRangeMode.PER_IMAGE, 5, 6, 1, 99)));
        assertArrayEquals(new double[]{5, 6},
                MapLayer.range(band, mode(RenderedExportConfig.MapRangeMode.FIXED, 5, 6, 1, 99)));
        // Unresolved SHARED: this band's own percentiles
        double[] own = MapLayer.range(band, mode(RenderedExportConfig.MapRangeMode.SHARED, 5, 6, 0, 100));
        assertArrayEquals(new double[]{0, 100}, own);
        // Resolved SHARED: the stored range
        var resolved = mode(RenderedExportConfig.MapRangeMode.SHARED, 0, 0, 1, 99).withResolvedRange(2, 3);
        assertArrayEquals(new double[]{2, 3}, MapLayer.range(band, resolved));
        // All NaN
        var empty = new MapLayer.Band(new float[]{Float.NaN, Float.NaN}, 2, 1);
        assertArrayEquals(new double[]{0, 1}, MapLayer.range(empty, null));
    }

    @Test
    void percentilesInterpolate() {
        float[] sorted = {0, 10, 20, 30, 40};
        assertArrayEquals(new double[]{0, 40}, MapLayer.percentileRange(sorted, 0, 100));
        assertArrayEquals(new double[]{5, 35}, MapLayer.percentileRange(sorted, 12.5, 87.5), 1e-9);
        assertArrayEquals(new double[]{0, 1}, MapLayer.percentileRange(new float[0], 1, 99));
    }

    @Test
    void finiteSampleThinsEvenly() {
        float[] v = new float[1000];
        for (int i = 0; i < v.length; i++) v[i] = i % 7 == 0 ? Float.NaN : i;
        float[] s = MapLayer.finiteSample(v, 100);
        assertTrue(s.length <= 100 && s.length > 80, "length " + s.length);
        for (float f : s) assertTrue(Float.isFinite(f));
        assertEquals(857, MapLayer.finiteSample(v, 10_000).length);
    }

    @Test
    void paintClampsAndKeepsNaNTransparent() throws Exception {
        var map = ColorMaps.getColorMaps().get("Viridis");
        var server = grid(List.of("A"), 5, 1, new float[][]{{Float.NaN, -5, 0, 10, 50}});
        try (var layer = new MapLayer(server, false, 0, null, 5, 1, placement(5, 1, 1, 0, 0, 5, 1, false), 0, null)) {
            var img = layer.paint(0, 0, 5, 1, 1, 5, 1, map, 0, 10, false);
            assertEquals(0, img.getRGB(0, 0) >>> 24, "NaN is transparent");
            assertEquals(img.getRGB(2, 0), img.getRGB(1, 0), "below the range takes the first color");
            assertEquals(img.getRGB(3, 0), img.getRGB(4, 0), "above the range takes the last color");
            assertEquals(0xFF, img.getRGB(3, 0) >>> 24);
            assertNotEquals(img.getRGB(2, 0), img.getRGB(3, 0));

            var hidden = layer.paint(0, 0, 5, 1, 1, 5, 1, map, 0, 10, true);
            assertEquals(0, hidden.getRGB(1, 0) >>> 24, "below the minimum is hidden");
            assertEquals(0, hidden.getRGB(2, 0) >>> 24, "at the minimum is hidden");
            assertEquals(img.getRGB(3, 0), hidden.getRGB(3, 0));
        }
    }

    // ---- Grid maps placed by a Placement line ----

    /** A float map with one channel per name; values[c] row by row. */
    static WrappedBufferedImageServer grid(List<String> names, int w, int h, float[][] values) {
        var channels = names.stream().map(n -> ImageChannel.getInstance(n, 0xFFFFFF)).toList();
        var raster = Raster.createWritableRaster(
                new BandedSampleModel(DataBuffer.TYPE_FLOAT, w, h, names.size()), null);
        for (int c = 0; c < names.size(); c++) {
            for (int i = 0; i < w * h; i++) raster.setSample(i % w, i / w, c, values[c][i]);
        }
        var cm = ColorModelFactory.createColorModel(PixelType.FLOAT32, channels);
        return new WrappedBufferedImageServer("grid", new BufferedImage(cm, raster, false, null), channels);
    }

    static MapPlacement placement(int sw, int sh, double cell, double x0, double y0, int gw, int gh,
                                  boolean bilinear) {
        return new MapPlacement(sw, sh, 0.5, cell, cell, x0, y0, gw, gh, bilinear);
    }

    /** Fiber analysis's DensityLattice.nearest / bilinear, the reference the map must match. */
    static float reference(float[] g, int gw, int gh, double cell, double x0, double y0,
                           double u, double v, boolean smooth, double period) {
        if (!(u >= x0 && u < x0 + gw * cell && v >= y0 && v < y0 + gh * cell)) return Float.NaN;
        if (!smooth) {
            int gx = clampRef((int) Math.floor((u - x0) / cell), gw);
            int gy = clampRef((int) Math.floor((v - y0) / cell), gh);
            return g[gy * gw + gx];
        }
        double fx = (u - x0) / cell - 0.5;
        double fy = (v - y0) / cell - 0.5;
        int ix = (int) Math.floor(fx);
        int iy = (int) Math.floor(fy);
        double wx = fx - ix;
        double wy = fy - iy;
        double sum = 0, sumSin = 0, wsum = 0;
        double k = period > 0 ? 2 * Math.PI / period : 0;
        for (int dy = 0; dy <= 1; dy++) {
            int gy = clampRef(iy + dy, gh);
            double w1 = dy == 0 ? 1.0 - wy : wy;
            for (int dx = 0; dx <= 1; dx++) {
                int gx = clampRef(ix + dx, gw);
                double w = w1 * (dx == 0 ? 1.0 - wx : wx);
                float val = g[gy * gw + gx];
                if (w > 0.0 && !Float.isNaN(val)) {
                    if (period > 0) {
                        sum += w * Math.cos(k * val);
                        sumSin += w * Math.sin(k * val);
                    } else {
                        sum += val * w;
                    }
                    wsum += w;
                }
            }
        }
        if (wsum <= 0.0) return Float.NaN;
        if (period > 0) {
            double a = Math.atan2(sumSin, sum) * period / (2 * Math.PI);
            return (float) (a < 0 ? a + period : (a >= period ? a - period : a));
        }
        return (float) (sum / wsum);
    }

    private static int clampRef(int i, int n) {
        return i < 0 ? 0 : (i >= n ? n - 1 : i);
    }

    /** An 11 x 8 grid of 8 px cells inset 6 px, as a 20 px window at an 8 px stride gives, on 100 x 80. */
    private static final int SW = 100, SH = 80, GW = 11, GH = 8;
    private static final double CELL = 8, X0 = 6, Y0 = 6;

    private static float[] gridValues() {
        float[] g = new float[GW * GH];
        for (int i = 0; i < g.length; i++) g[i] = (float) (Math.sin(i * 0.7) * 50 + i);
        g[13] = Float.NaN;
        g[GW * GH - 1] = Float.NaN;
        return g;
    }

    @Test
    void gridIsPlacedAndMatchesTheReference() throws Exception {
        float[] g = gridValues();
        for (boolean smooth : new boolean[]{false, true}) {
            var server = grid(List.of("A"), GW, GH, new float[][]{g});
            try (var layer = new MapLayer(server, false, 0, null, SW, SH,
                    placement(SW, SH, CELL, X0, Y0, GW, GH, smooth), 0, null)) {
                // Whole image at full resolution, a downsampled whole image, and a sub-region
                int[][] cases = {{0, 0, SW, SH, 1}, {0, 0, SW, SH, 4}, {37, 21, 41, 33, 1}};
                for (int[] c : cases) {
                    int outW = (int) Math.ceil(c[2] / (double) c[4]);
                    int outH = (int) Math.ceil(c[3] / (double) c[4]);
                    float[] got = layer.values(c[0], c[1], c[2], c[3], c[4], outW, outH);
                    for (int oy = 0; oy < outH; oy++) {
                        for (int ox = 0; ox < outW; ox++) {
                            double u = c[0] + (ox + 0.5) * c[2] / (double) outW;
                            double v = c[1] + (oy + 0.5) * c[3] / (double) outH;
                            float want = reference(g, GW, GH, CELL, X0, Y0, u, v, smooth, 0);
                            float have = got[oy * outW + ox];
                            String where = (smooth ? "bilinear" : "nearest") + " at " + u + "," + v;
                            if (Float.isNaN(want)) assertTrue(Float.isNaN(have), where + " got " + have);
                            else assertEquals(want, have, 1e-4, where);
                        }
                    }
                }
                // Off the grid: the 6 px border, and the 6 px right / 10 px bottom gap, are transparent
                float[] full = layer.values(0, 0, SW, SH, 1, SW, SH);
                assertTrue(Float.isNaN(full[5 * SW + 50]), "above the grid");
                assertTrue(Float.isNaN(full[40 * SW + 5]), "left of the grid");
                assertTrue(Float.isNaN(full[40 * SW + 94]), "right of the grid");
                assertTrue(Float.isNaN(full[70 * SW + 50]), "below the grid");
                assertFalse(Float.isNaN(full[6 * SW + 6]), "the grid's first pixel");
                assertFalse(Float.isNaN(full[69 * SW + 85]), "the grid's last row");
            }
        }
    }

    @Test
    void regionOffTheGridDrawsNothing() throws Exception {
        var server = grid(List.of("A"), GW, GH, new float[][]{gridValues()});
        try (var layer = new MapLayer(server, false, 0, null, SW, SH,
                placement(SW, SH, CELL, X0, Y0, GW, GH, true), 0, null)) {
            assertEquals(0, layer.read(95, 0, 5, 80, 1).width());
            for (float v : layer.values(95, 0, 5, 80, 1, 5, 80)) assertTrue(Float.isNaN(v));
            var img = layer.paint(95, 0, 5, 80, 1, 5, 80, ColorMaps.getColorMaps().get("Viridis"), 0, 1, false);
            assertEquals(0, img.getRGB(2, 40) >>> 24);
        }
    }

    @Test
    void placementMustFitTheMapAndTheImage() {
        var server = grid(List.of("A"), GW, GH, new float[][]{gridValues()});
        var wrongImage = assertThrows(IllegalArgumentException.class, () -> new MapLayer(server, false, 0, null,
                SW, SH + 1, placement(SW, SH, CELL, X0, Y0, GW, GH, true), 0, null));
        assertTrue(wrongImage.getMessage().contains("100 x 80"), wrongImage.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new MapLayer(server, false, 0, null,
                SW, SH, placement(SW, SH, CELL, X0, Y0, GW + 1, GH, true), 0, null));
    }

    @Test
    void anglesBlendAsAngles() throws Exception {
        // 170 and 10 degrees are 20 apart through 0, not 160 apart through 90
        var server = grid(List.of("Mean angle (deg)"), 2, 1, new float[][]{{170, 10}});
        try (var layer = new MapLayer(server, false, 0, "deg", 20, 10,
                placement(20, 10, 10, 0, 0, 2, 1, true), 180, null)) {
            float[] v = layer.values(0, 0, 20, 10, 1, 20, 10);
            float mid = v[5 * 20 + 10];
            assertTrue(mid < 5 || mid > 175, "near the middle is about 0, not 90: got " + mid);
            assertEquals(reference(new float[]{170, 10}, 2, 1, 10, 0, 0, 7.5, 5.5, true, 180),
                    v[5 * 20 + 7], 1e-4);
            // Drawn from 0 to the period unless the range is fixed
            assertArrayEquals(new double[]{0, 180}, layer.range(0, 0, 20, 10, 1,
                    mode(RenderedExportConfig.MapRangeMode.PER_IMAGE, 0, 1, 1, 99)));
            assertArrayEquals(new double[]{0, 180}, layer.range(0, 0, 20, 10, 1,
                    mode(RenderedExportConfig.MapRangeMode.SHARED, 0, 1, 1, 99).withResolvedRange(3, 9)));
            assertArrayEquals(new double[]{0, 90}, layer.range(0, 0, 20, 10, 1,
                    mode(RenderedExportConfig.MapRangeMode.FIXED, 0, 90, 1, 99)));
        }
    }

    @Test
    void classIndexInClassColors() throws Exception {
        var names = List.of("P(QuIET test tumor)", "P(QuIET test stroma)", "Predicted class index");
        float nan = Float.NaN;
        var server = grid(names, 5, 1, new float[][]{
                {0.9f, 0.1f, nan, 0.5f, 0.5f}, {0.1f, 0.9f, nan, 0.5f, 0.5f}, {0, 1, nan, 2, 0.5f}});
        var tumor = qupath.lib.objects.classes.PathClass.fromString("QuIET test tumor", 0xC81E1E);
        var stroma = qupath.lib.objects.classes.PathClass.fromString("QuIET test stroma", 0x1E64C8);
        var classes = MapOverlaySource.classNames(server, 2);
        assertEquals(List.of("QuIET test tumor", "QuIET test stroma"), classes);
        assertNull(MapOverlaySource.classNames(server, 0));
        // Never blended, even when the map says bilinear
        try (var layer = new MapLayer(server, false, 2, null, 50, 10,
                placement(50, 10, 10, 0, 0, 5, 1, true), 0, classes)) {
            assertTrue(layer.isClassChannel());
            assertFalse(layer.isBilinear());
            var img = layer.paint(0, 0, 50, 10, 1, 50, 10, ColorMaps.getColorMaps().get("Viridis"), 0, 1, true);
            assertEquals(0xFF000000 | tumor.getColor(), img.getRGB(9, 5), "class 0, even with hide on");
            assertEquals(0xFF000000 | stroma.getColor(), img.getRGB(10, 5), "class 1, no blend at the edge");
            assertEquals(0, img.getRGB(25, 5) >>> 24, "NaN: the model abstained");
            assertEquals(0, img.getRGB(35, 5) >>> 24, "index out of range");
            assertEquals(0, img.getRGB(45, 5) >>> 24, "not a whole number");
            assertArrayEquals(new double[]{0, 1}, layer.range(0, 0, 50, 10, 1, null));
        }
    }

    @Test
    void stretchedMapStillCoversTheImage() throws Exception {
        // No placement: a 4 x 2 map over an 8 x 4 image, blended between cell centers
        var server = floatMap(4, 2);
        try (var layer = new MapLayer(server, false, 0, null, 8, 4)) {
            assertNull(layer.placement());
            float[] v = layer.values(0, 0, 8, 4, 1, 8, 4);
            for (float f : v) assertFalse(Float.isNaN(f));
            assertEquals(0f, v[0], 1e-6, "clamped beyond the outermost center");
            assertEquals(7f, v[31], 1e-6);
        }
    }

    @Test
    void stripsMatchASingleRead() throws Exception {
        // A smooth map tall enough for several strips at downsample 3
        // 2100 rows divide evenly at every downsample tested, so both reads share one sample grid
        int w = 30, h = 2100;
        var channels = List.of(ImageChannel.getInstance("A", 0xFF0000));
        var raster = Raster.createWritableRaster(
                new BandedSampleModel(DataBuffer.TYPE_FLOAT, w, h, 1), null);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                raster.setSample(x, y, 0, (float) (Math.sin(y / 7.0) * 10 + x));
            }
        }
        var cm = ColorModelFactory.createColorModel(PixelType.FLOAT32, channels);
        var server = new WrappedBufferedImageServer("tall", new BufferedImage(cm, raster, false, null), channels);
        for (double ds : new double[]{1.0, 1.5, 3.0, 4.0}) {
            MapLayer.Band single;
            MapLayer.Band strips;
            try (var layer = new MapLayer(server, false, 0, null, w, h)) {
                single = layer.read(0, 0, w, h, ds);
                layer.setSingleReadLimit(0);
                strips = layer.read(0, 0, w, h, ds);
            }
            assertEquals(single.width(), strips.width(), "width at " + ds);
            assertEquals(single.height(), strips.height(), "height at " + ds);
            double maxDiff = 0;
            for (int i = 0; i < single.data().length; i++) {
                maxDiff = Math.max(maxDiff, Math.abs(single.data()[i] - strips.data()[i]));
            }
            assertEquals(0.0, maxDiff, 1e-4, "strip seams at downsample " + ds);
        }
    }
}
