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
                        "qupath.lib.regions.RegionRequest", "java.awt.image.BufferedImage");
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
}
