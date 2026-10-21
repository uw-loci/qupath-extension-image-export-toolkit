package qupath.ext.quiet.export;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import groovy.lang.GroovyShell;
import groovy.lang.Script;

/**
 * The generated scripts' map helpers must find the same files and compute the
 * same ranges as QuIET itself.
 */
class MapOverlayHelpersScriptTest {

    @TempDir
    File tempDir;

    private Script helpers;
    private File projectDir;
    private File imageDir;

    @BeforeEach
    void loadHelpers() throws IOException {
        String text;
        try (var in = RenderedScriptGenerator.class.getResourceAsStream("map-overlay-helpers.groovy")) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var imports = new ImportCustomizer()
                .addImports("qupath.lib.common.GeneralTools", "qupath.lib.images.servers.ImageServers",
                        "qupath.lib.regions.RegionRequest", "java.awt.image.BufferedImage",
                        "java.awt.Graphics2D");
        var cc = new CompilerConfiguration();
        cc.addCompilationCustomizers(imports);
        helpers = new GroovyShell(cc).parse(text);
        projectDir = new File(tempDir, "project");
        imageDir = new File(tempDir, "images");
    }

    private File touch(String relative) throws IOException {
        File f = new File(tempDir, relative);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), "x");
        return f;
    }

    private Object scriptResolve(String pattern, String imageName) {
        return helpers.invokeMethod("resolveMapFile",
                new Object[]{pattern, projectDir, imageDir, imageName});
    }

    private File javaResolve(String pattern, String imageName) {
        return MapFilePattern.resolve(pattern,
                new MapFilePattern.ImageContext(projectDir, imageDir, imageName));
    }

    @Test
    void sameFilesAsQuiet() throws IOException {
        touch("project/maps/slide_1.ome.tif_density.ome.tif");
        touch("images/slide 1_map.ome.tif");
        touch("project/m/a[1]_x.tif");
        touch("project/cls/" + "b".repeat(60) + "_model.ome.tif");
        String[][] cases = {
                {"{project}/maps/{imageSafe}_density.ome.tif", "slide 1.ome.tif"},
                {"maps/{imageSafe:60}_density.ome.tif", "slide 1.ome.tif"},
                {"{imageDir}/{imageBase}_map.ome.tif", "slide 1.ome.tif"},
                {"{project}/m/{image}_*.tif", "a[1]"},
                {"{project}/cls/{imageSafe:60}_*.ome.tif", "b".repeat(70) + ".svs"},
        };
        for (String[] c : cases) {
            assertEquals(javaResolve(c[0], c[1]).getCanonicalFile(),
                    ((File) scriptResolve(c[0], c[1])).getCanonicalFile(), c[0]);
        }
    }

    @Test
    void refusesWhatQuietRefuses() throws IOException {
        touch("project/cls/a_one.ome.tif");
        touch("project/cls/a_two.ome.tif");
        for (String pattern : new String[]{
                "{project}/cls/{image}_*.ome.tif",     // two matches
                "{project}/*/x.tif",                   // wildcard in a folder
                "{project}/none/{image}.tif"}) {       // no file
            assertThrows(IllegalArgumentException.class, () -> javaResolve(pattern, "a"), pattern);
            assertThrows(IllegalArgumentException.class, () -> scriptResolve(pattern, "a"), pattern);
        }
    }

    @Test
    void safeNameMatches() {
        for (String name : new String[]{"a b\\c/d", "x:*?\"<>|y", "name...", "name.. ", "  ", "slide 1.ome.tif"}) {
            assertEquals(MapFilePattern.safeName(name), helpers.invokeMethod("mapSafeName", new Object[]{name}), name);
        }
    }

    @Test
    void samePercentilesAndSample() {
        float[] values = new float[5000];
        for (int i = 0; i < values.length; i++) {
            values[i] = i % 13 == 0 ? Float.NaN : (float) Math.sin(i * 0.37) * 100;
        }
        float[] javaSample = MapLayer.finiteSample(values, 700);
        float[] scriptSample = (float[]) helpers.invokeMethod("mapFiniteSample", new Object[]{values, 700});
        assertArrayEquals(javaSample, scriptSample);

        java.util.Arrays.sort(javaSample);
        for (double p : new double[]{0, 1, 12.5, 50, 99, 100}) {
            double java = MapLayer.percentileRange(javaSample, p, p)[0];
            double script = ((Number) helpers.invokeMethod("mapPercentile", new Object[]{javaSample, p})).doubleValue();
            assertEquals(java, script, 1e-9, "percentile " + p);
        }
    }

    // ---- Placement, angles and classes: the script must draw what QuIET draws ----

    @Test
    void sameDescriptionParsing() throws IOException {
        String d = MapPlacementTest.FIBER;
        for (var order : new java.nio.ByteOrder[]{java.nio.ByteOrder.LITTLE_ENDIAN, java.nio.ByteOrder.BIG_ENDIAN}) {
            for (boolean big : new boolean[]{false, true}) {
                File f = TiffDescriptionTest.writeTiff(tempDir, "d.tif", big, order, d);
                assertEquals(TiffDescription.read(f), helpers.invokeMethod("readTiffDescription", new Object[]{f}));
            }
        }
        @SuppressWarnings("unchecked")
        var p = (java.util.Map<String, Object>) helpers.invokeMethod("parseMapPlacement", new Object[]{d});
        var j = MapPlacement.parse(d);
        assertEquals(j.sourceWidth(), p.get("sourceWidth"));
        assertEquals(j.sourceHeight(), p.get("sourceHeight"));
        assertEquals(j.cellPx(), ((Number) p.get("cellPx")).doubleValue());
        assertEquals(j.x0(), ((Number) p.get("x0")).doubleValue());
        assertEquals(j.y0(), ((Number) p.get("y0")).doubleValue());
        assertEquals(j.gridWidth(), p.get("gridWidth"));
        assertEquals(j.gridHeight(), p.get("gridHeight"));
        assertEquals(j.bilinear(), p.get("bilinear"));
        assertNull(helpers.invokeMethod("parseMapPlacement", new Object[]{"no placement"}));
        String bad = "Placement: source_width=100; cell_px=8; grid=10x7";
        assertThrows(IllegalArgumentException.class, () -> MapPlacement.parse(bad));
        assertThrows(IllegalArgumentException.class, () -> helpers.invokeMethod("parseMapPlacement", new Object[]{bad}));
        for (int c = 0; c < 7; c++) {
            assertEquals(TiffDescription.channelAxialPeriods(d).getOrDefault(c, 0.0),
                    ((Number) helpers.invokeMethod("mapAxialPeriod", new Object[]{d, c})).doubleValue(), "channel " + c);
        }
    }

    /** The layer the script's openMapLayer builds, for a map file with this geometry. */
    private static java.util.Map<String, Object> scriptLayer(MapLayer layer,
                                                            qupath.lib.images.servers.ImageServer<?> server,
                                                            int channel, double originX, double originY,
                                                            double cellW, double cellH) {
        var m = new java.util.HashMap<String, Object>();
        m.put("server", server);
        m.put("channel", channel);
        m.put("originX", originX);
        m.put("originY", originY);
        m.put("cellW", cellW);
        m.put("cellH", cellH);
        m.put("bilinear", layer.placement() == null || layer.placement().bilinear());
        m.put("period", layer.axialPeriod());
        m.put("classNames", layer.classNames());
        m.put("classColors", layer.isClassChannel()
                ? java.util.Arrays.stream(layer.classColors()).boxed().toList() : null);
        return m;
    }

    private void assertSamePaint(MapLayer layer, java.util.Map<String, Object> scriptLayer,
                                 RenderedExportConfig.MapOverlayConfig cfg, boolean hide,
                                 int x, int y, int w, int h, double ds) throws IOException {
        var colorMap = qupath.lib.color.ColorMaps.getColorMaps().get("Viridis");
        int outW = (int) Math.ceil(w / ds), outH = (int) Math.ceil(h / ds);
        double[] range = layer.range(x, y, w, h, ds, cfg);
        Object region = helpers.invokeMethod("readMapRegion", new Object[]{scriptLayer, x, y, w, h, ds});
        @SuppressWarnings("unchecked")
        var scriptRange = (java.util.List<Number>) helpers.invokeMethod("mapRangeFor", new Object[]{
                scriptLayer, region, cfg.rangeMode().name(), cfg.rangeMin(), cfg.rangeMax(),
                cfg.lowPercentile(), cfg.highPercentile(), cfg.rangeResolved()});
        String what = "region " + x + "," + y + " " + w + "x" + h + " at " + ds;
        assertEquals(range[0], scriptRange.get(0).doubleValue(), 1e-9, "range min, " + what);
        assertEquals(range[1], scriptRange.get(1).doubleValue(), 1e-9, "range max, " + what);
        var java = layer.paint(x, y, w, h, ds, outW, outH, colorMap, range[0], range[1], hide);
        var script = (java.awt.image.BufferedImage) helpers.invokeMethod("paintMapRegion", new Object[]{
                scriptLayer, region, x, y, w, h, outW, outH, colorMap, range[0], range[1], hide});
        assertArrayEquals(java.getRGB(0, 0, outW, outH, null, 0, outW),
                script.getRGB(0, 0, outW, outH, null, 0, outW), what);
    }

    private static RenderedExportConfig.MapOverlayConfig cfg(RenderedExportConfig.MapRangeMode mode,
                                                             double min, double max) {
        return new RenderedExportConfig.MapOverlayConfig(RenderedExportConfig.MapSource.IMAGE_FILE,
                "x", "", mode, min, max, 1, 99, false, "", false);
    }

    @Test
    void samePixelsAsQuiet() throws IOException {
        int sw = 100, sh = 80, gw = 11, gh = 8;
        float[] g = new float[gw * gh];
        float[] angle = new float[gw * gh];
        for (int i = 0; i < g.length; i++) {
            g[i] = (float) (Math.sin(i * 0.7) * 50 + i);
            angle[i] = (i * 37) % 180;
        }
        g[13] = Float.NaN;
        var server = MapLayerTest.grid(java.util.List.of("A", "Mean angle (deg)"), gw, gh, new float[][]{g, angle});
        int[][] regions = {{0, 0, sw, sh}, {37, 21, 41, 33}, {90, 60, 10, 20}};
        for (boolean smooth : new boolean[]{false, true}) {
            var placement = MapLayerTest.placement(sw, sh, 8, 6.5, 6, gw, gh, smooth);
            for (int c = 0; c < 2; c++) {
                double period = c == 1 ? 180 : 0;
                try (var layer = new MapLayer(server, false, c, null, sw, sh, placement, period, null)) {
                    var sl = scriptLayer(layer, server, c, 6.5, 6, 8, 8);
                    for (int[] r : regions) {
                        for (double ds : new double[]{1, 3}) {
                            assertSamePaint(layer, sl, cfg(RenderedExportConfig.MapRangeMode.PER_IMAGE, 0, 1),
                                    false, r[0], r[1], r[2], r[3], ds);
                            assertSamePaint(layer, sl, cfg(RenderedExportConfig.MapRangeMode.FIXED, 10, 40),
                                    true, r[0], r[1], r[2], r[3], ds);
                        }
                    }
                }
            }
        }
        // A stretched map (no placement), as before
        try (var layer = new MapLayer(server, false, 0, null, sw, sh)) {
            var sl = scriptLayer(layer, server, 0, 0, 0, sw / (double) gw, sh / (double) gh);
            assertSamePaint(layer, sl, cfg(RenderedExportConfig.MapRangeMode.SHARED, 0, 1), false, 0, 0, sw, sh, 2);
        }
    }

    @Test
    void sameClassPixelsAsQuiet() throws IOException {
        var names = java.util.List.of("P(QuIET script tumor)", "P(QuIET script stroma)", "Predicted class index");
        qupath.lib.objects.classes.PathClass.fromString("QuIET script tumor", 0xC81E1E);
        float nan = Float.NaN;
        var server = MapLayerTest.grid(names, 5, 1, new float[][]{
                {0.9f, 0.1f, nan, 0.5f, 0.5f}, {0.1f, 0.9f, nan, 0.5f, 0.5f}, {0, 1, nan, 2, 0.5f}});
        assertEquals(MapOverlaySource.classNames(server, 2), helpers.invokeMethod("mapClassNames", new Object[]{server, 2}));
        assertNull(helpers.invokeMethod("mapClassNames", new Object[]{server, 0}));
        var classes = MapOverlaySource.classNames(server, 2);
        try (var layer = new MapLayer(server, false, 2, null, 50, 10,
                MapLayerTest.placement(50, 10, 10, 0, 0, 5, 1, true), 0, classes)) {
            var sl = scriptLayer(layer, server, 2, 0, 0, 10, 10);
            assertSamePaint(layer, sl, cfg(RenderedExportConfig.MapRangeMode.PER_IMAGE, 0, 1), true, 0, 0, 50, 10, 1);
        }
    }
}
