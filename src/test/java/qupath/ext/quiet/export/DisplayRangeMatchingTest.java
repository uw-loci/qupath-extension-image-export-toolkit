package qupath.ext.quiet.export;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import groovy.lang.Closure;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelType;

/**
 * Global Matched display ranges: the generated script's helpers compute what QuIET computes,
 * and a copied script works on the wizard's images.
 */
class DisplayRangeMatchingTest {

    @TempDir
    File tempDir;

    private Script helpers;

    @BeforeEach
    void loadHelpers() throws IOException {
        try (var in = RenderedScriptGenerator.class.getResourceAsStream("display-range-helpers.groovy")) {
            helpers = new GroovyShell().parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static Raster raster(int dataType, int bands, int w, int h, long seed, double scale) {
        WritableRaster r = Raster.createWritableRaster(
                new java.awt.image.BandedSampleModel(dataType, w, h, bands), null);
        var rnd = new Random(seed);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int b = 0; b < bands; b++) {
                    r.setSample(x, y, b, rnd.nextGaussian() * scale / 6 + scale / 2);
                }
            }
        }
        return r;
    }

    private double[][] java(List<GlobalDisplayRangeScanner.ScanImage> images, int nChannels) {
        return GlobalDisplayRangeScanner.rangesFromImages(images.size(), images::get, nChannels, 0.1, null);
    }

    private double[][] groovy(List<GlobalDisplayRangeScanner.ScanImage> images, int nChannels) {
        Closure<Object> reader = new Closure<>(null) {
            @Override
            public Object call(Object... args) {
                var img = images.get((Integer) args[0]);
                return List.of(img.raster(), img.type());
            }
        };
        return (double[][]) helpers.invokeMethod("quietRangesFromImages",
                new Object[]{images.size(), reader, nChannels, 0.1});
    }

    @Test
    void scriptRangesMatchQuiet() {
        var rgb = List.of(
                new GlobalDisplayRangeScanner.ScanImage(raster(DataBuffer.TYPE_BYTE, 3, 40, 30, 1, 255), PixelType.UINT8),
                new GlobalDisplayRangeScanner.ScanImage(raster(DataBuffer.TYPE_BYTE, 3, 25, 20, 2, 255), PixelType.UINT8));
        var biref = List.of(
                new GlobalDisplayRangeScanner.ScanImage(raster(DataBuffer.TYPE_USHORT, 1, 40, 30, 3, 20000), PixelType.UINT16),
                new GlobalDisplayRangeScanner.ScanImage(raster(DataBuffer.TYPE_USHORT, 1, 30, 30, 4, 60000), PixelType.UINT16));
        var floats = List.of(
                new GlobalDisplayRangeScanner.ScanImage(raster(DataBuffer.TYPE_FLOAT, 2, 30, 20, 5, 1.5), PixelType.FLOAT32),
                new GlobalDisplayRangeScanner.ScanImage(raster(DataBuffer.TYPE_FLOAT, 2, 20, 20, 6, 3.0), PixelType.FLOAT32));
        for (var images : List.of(rgb, biref, floats)) {
            int n = images.get(0).raster().getNumBands();
            double[][] j = java(images, n);
            double[][] g = groovy(images, n);
            for (int c = 0; c < n; c++) {
                assertArrayEquals(j[c], g[c], 1e-12, "channel " + c + " of " + images.get(0).type());
            }
        }
    }

    @Test
    void sixteenBitValuesAreKeptAfterAnEightBitImage() {
        // Before 1.4.1 the histogram was sized from the first image: 256 bins after an 8-bit one
        var images = List.of(
                new GlobalDisplayRangeScanner.ScanImage(raster(DataBuffer.TYPE_BYTE, 1, 20, 20, 7, 255), PixelType.UINT8),
                new GlobalDisplayRangeScanner.ScanImage(raster(DataBuffer.TYPE_USHORT, 1, 40, 40, 8, 60000), PixelType.UINT16));
        double[][] r = java(images, 1);
        assertTrue(r[0][1] > 30000, "max " + r[0][1]);
        assertArrayEquals(r[0], groovy(images, 1)[0], 1e-12);
    }

    @Test
    void imagesOfDifferentKindsAreMatchedSeparately() {
        var rgb = new ImageServerMetadata.Builder()
                .width(10).height(10).pixelType(PixelType.UINT8).rgb(true)
                .channels(ImageChannel.getDefaultRGBChannels()).build();
        var biref = new ImageServerMetadata.Builder()
                .width(10).height(10).pixelType(PixelType.UINT16)
                .channels(List.of(ImageChannel.getInstance("Channel 1", ImageChannel.getDefaultChannelColor(0))))
                .build();
        assertEquals("UINT8 [Red, Green, Blue]", GlobalDisplayRangeScanner.kindOf(rgb));
        assertEquals("UINT16 [Channel 1]", GlobalDisplayRangeScanner.kindOf(biref));
        assertEquals(GlobalDisplayRangeScanner.kindOf(rgb), helpers.invokeMethod("quietImageKind", rgb));
        assertEquals(GlobalDisplayRangeScanner.kindOf(biref), helpers.invokeMethod("quietImageKind", biref));
    }

    @Test
    void copiedScriptWorksOnTheSelectedImages() {
        String script = "def entries = project.getImageList()\nprintln entries.size()\n";
        List<String> all = List.of("a.svs", "b \"biref\".tif", "c$x.tif");
        assertEquals(script, ScriptImageSelection.applyToScript(script, all, all),
                "the whole project needs no list");
        String chosen = ScriptImageSelection.applyToScript(script, List.of("c$x.tif", "b \"biref\".tif"), all);
        assertTrue(chosen.contains("def entries = exportImageList(project)"));
        assertTrue(chosen.contains("\"c\\$x.tif\",\n    \"b \\\"biref\\\".tif\""));
        new GroovyShell().parse(chosen);
    }

    @Test
    void generatedMatchedScriptsCompile() {
        int compiled = 0;
        for (var mode : RenderedExportConfig.RenderMode.values()) {
            var builder = new RenderedExportConfig.Builder()
                    .renderMode(mode)
                    .displaySettingsMode(RenderedExportConfig.DisplaySettingsMode.GLOBAL_MATCHED)
                    .matchedDisplayPercentile(0.1)
                    .classifierName("c")
                    .densityMapName("cell_density")
                    .colormapName("Viridis")
                    .downsample(20)
                    .format(OutputFormat.PNG)
                    .outputDirectory(tempDir);
            String script = ScriptGenerator.generate(ExportCategory.RENDERED, builder.build());
            String selected = ScriptImageSelection.applyToScript(script, List.of("x"), List.of("x", "y"));
            assertTrue(selected.contains("quietMatchedSettingsByKind(scanEntries"), mode.name());
            assertTrue(selected.contains("def scanEntries = exportImageList(project)"), mode.name());
            assertDoesNotThrow(() -> new GroovyShell().parse(selected), mode.name());
            compiled++;
        }
        assertEquals(RenderedExportConfig.RenderMode.values().length, compiled);
    }
}
