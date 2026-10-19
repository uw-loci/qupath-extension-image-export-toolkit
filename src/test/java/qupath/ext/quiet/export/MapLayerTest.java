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
                "x", "", m, min, max, lowP, highP, false, "");
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
            assertSame(right, layer.read(4, 0, 4, 4, 1.0), "the same region is cached");
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
    void colorizeClampsAndKeepsNaNTransparent() {
        var map = ColorMaps.getColorMaps().get("Viridis");
        var band = new MapLayer.Band(new float[]{Float.NaN, -5, 0, 10, 50}, 5, 1);
        var img = MapLayer.colorize(band, map, 0, 10);
        assertEquals(0, img.getRGB(0, 0) >>> 24, "NaN is transparent");
        assertEquals(img.getRGB(2, 0), img.getRGB(1, 0), "below the range takes the first color");
        assertEquals(img.getRGB(3, 0), img.getRGB(4, 0), "above the range takes the last color");
        assertEquals(0xFF, img.getRGB(3, 0) >>> 24);
        assertNotEquals(img.getRGB(2, 0), img.getRGB(3, 0));
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
