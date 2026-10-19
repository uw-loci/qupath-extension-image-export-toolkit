package qupath.ext.quiet.export;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapFilePatternTest {

    @TempDir
    File tempDir;

    private MapFilePattern.ImageContext ctx(String name) {
        return new MapFilePattern.ImageContext(
                new File(tempDir, "project"), new File(tempDir, "images"), name);
    }

    private File touch(String relative) throws IOException {
        File f = new File(tempDir, relative);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), "x");
        return f;
    }

    @Test
    void substitutesEveryToken() {
        var c = ctx("slide 1.ome.tif");
        String projectDir = c.projectDir().getAbsolutePath().replace('\\', '/');
        String imageDir = c.imageDir().getAbsolutePath().replace('\\', '/');
        assertEquals(projectDir + "/" + imageDir + "/slide 1.ome.tif/slide 1/slide_1.ome.tif",
                MapFilePattern.substitute("{project}/{imageDir}/{image}/{imageBase}/{imageSafe}", c));
    }

    @Test
    void lengthLimitCutsTheValue() {
        assertEquals("abcde_map", MapFilePattern.substitute("{imageSafe:5}_map", ctx("abcdefgh")));
        assertEquals("ab_map", MapFilePattern.substitute("{image:5}_map", ctx("ab")));
    }

    @Test
    void safeNameMatchesTheDocumentedRule() {
        assertEquals("a_b_c_d", MapFilePattern.safeName("a b\\c/d"));
        assertEquals("x_y", MapFilePattern.safeName("x:*?\"<>|y"));
        assertEquals("name", MapFilePattern.safeName("name..."));
        // White space is replaced before trailing dots are trimmed, as Fiber Analysis does
        assertEquals("name.._", MapFilePattern.safeName("name.. "));
        assertEquals("image", MapFilePattern.safeName("  "));
        assertEquals("image", MapFilePattern.safeName(null));
        // Fiber Analysis density map name for a real slide
        assertEquals("liver_L002_1_20x_42841_11994_7.0_biref_003.ome.tif",
                MapFilePattern.safeName("liver_L002_1_20x_42841_11994_7.0_biref_003.ome.tif"));
    }

    @Test
    void unknownTokenIsRejected() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> MapFilePattern.substitute("{nope}.tif", ctx("a")));
        assertTrue(e.getMessage().contains("{nope}"));
    }

    @Test
    void relativePatternStartsInTheProjectFolder() throws IOException {
        File f = touch("project/maps/a.tif");
        assertEquals(f.getCanonicalFile(),
                MapFilePattern.resolve("maps/{imageBase}.tif", ctx("a.svs")).getCanonicalFile());
    }

    @Test
    void absolutePatternWithImageDir() throws IOException {
        File f = touch("images/a_map.ome.tif");
        assertEquals(f.getCanonicalFile(), MapFilePattern.resolve(
                "{imageDir}/{imageBase}_map.ome.tif", ctx("a.ome.tif")).getCanonicalFile());
    }

    @Test
    void missingFileIsReported() {
        var e = assertThrows(MapFilePattern.MapFileNotFoundException.class,
                () -> MapFilePattern.resolve("{project}/maps/{image}.tif", ctx("b")));
        assertTrue(e.getMessage().contains("no map file"), e.getMessage());
    }

    @Test
    void wildcardNeedsExactlyOneMatch() throws IOException {
        File one = touch("project/cls/a_modelA.ome.tif");
        assertEquals(one.getCanonicalFile(), MapFilePattern.resolve(
                "{project}/cls/{imageSafe}_*.ome.tif", ctx("a")).getCanonicalFile());

        touch("project/cls/a_modelB.ome.tif");
        var e = assertThrows(MapFilePattern.MapFileNotFoundException.class,
                () -> MapFilePattern.resolve("{project}/cls/{imageSafe}_*.ome.tif", ctx("a")));
        assertTrue(e.getMessage().startsWith("2 map files match"), e.getMessage());

        assertThrows(MapFilePattern.MapFileNotFoundException.class,
                () -> MapFilePattern.resolve("{project}/cls/{imageSafe}_*.ome.tif", ctx("zzz")));
    }

    @Test
    void wildcardInAFolderIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MapFilePattern.resolve("{project}/*/x.tif", ctx("a")));
    }

    @Test
    void globMetacharactersInTheNameAreLiteral() throws IOException {
        File f = touch("project/m/a[1]_x.tif");
        assertEquals(f.getCanonicalFile(), MapFilePattern.resolve(
                "{project}/m/{image}_*.tif", ctx("a[1]")).getCanonicalFile());
    }

    @Test
    void fiberAnalysisDensityPattern() throws IOException {
        String longName = "a".repeat(70) + ".ome.tif";
        File f = touch("project/fiber-analysis/density-maps/" + "a".repeat(60) + "_density.ome.tif");
        assertEquals(f.getCanonicalFile(), MapFilePattern.resolve(
                "{project}/fiber-analysis/density-maps/{imageSafe:60}_density.ome.tif",
                ctx(longName)).getCanonicalFile());
    }
}
