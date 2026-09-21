package qupath.ext.quiet.export;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageNamesTest {

    @TempDir
    File tempDir;

    @Test
    void stripsSimpleAndCompoundExtensions() {
        assertEquals("slide", ImageNames.stripExtension("slide.tif"));
        assertEquals("slide", ImageNames.stripExtension("slide.TIFF"));
        assertEquals("slide", ImageNames.stripExtension("slide.ome.tif"));
        assertEquals("slide", ImageNames.stripExtension("slide.OME.TIFF"));
        assertEquals("slide", ImageNames.stripExtension("slide.ome.zarr"));
        assertEquals("slide", ImageNames.stripExtension("slide.svs"));
    }

    @Test
    void stripsExtensionBeforeSeriesSuffix() {
        assertEquals("slide - 20x_01", ImageNames.stripExtension("slide.vsi - 20x_01"));
        assertEquals("exp - Scene #1", ImageNames.stripExtension("exp.czi - Scene #1"));
    }

    @Test
    void leavesDotsThatAreNotExtensions() {
        assertEquals("Dose 1.5 mg", ImageNames.stripExtension("Dose 1.5 mg"));
        assertEquals("sample.v2", ImageNames.stripExtension("sample.v2"));
        assertEquals("my.tif.notes", ImageNames.stripExtension("my.tif.notes"));
        assertEquals("a.b", ImageNames.stripExtension("a.b.tif"));
        assertEquals("home", ImageNames.stripExtension("home"));
    }

    @Test
    void neverReturnsBlank() {
        assertEquals(".tif", ImageNames.stripExtension(".tif"));
        assertNull(ImageNames.stripExtension(null));
    }

    @Test
    void collidingNamesKeepTheirExtension() {
        var names = List.of("a.tif", "A.czi", "b.ome.tif");
        assertEquals(List.of("a.tif", "A.czi", "b"), ImageNames.baseNames(names, null, true));
        assertEquals(names, ImageNames.baseNames(names, null, false));
    }

    @Test
    void collisionsAreJudgedAgainstTheWholeProject() {
        var project = List.of("a.tif", "a.czi", "b.tif");
        assertEquals(List.of("a.tif", "b"),
                ImageNames.baseNames(List.of("a.tif", "b.tif"), project, true));
    }

    @Test
    void scriptUntouchedWhenNoNamingOptionApplies() {
        String script = "    def imageName = entry.getImageName()\n    def entryName = imageName\n";
        assertEquals(script, ImageNames.applyToScript(script, "", null, false));
        assertEquals("println 'x'", ImageNames.applyToScript("println 'x'", "p_", "_s", true));
    }

    @Test
    void scriptCarriesPrefixSuffixAndStripping() {
        String script = "    def imageName = entry.getImageName()\n    def entryName = imageName\n";
        String out = ImageNames.applyToScript(script, "p_", "_$s", true);
        assertTrue(out.contains("def entryName = outputBaseName(imageName)"));
        assertTrue(out.contains("def stripImageExtension(String name)"));
        assertTrue(out.contains("return \"p_\" + base + \"_\\$s\""));

        String noStrip = ImageNames.applyToScript(script, "p_", "", false);
        assertTrue(noStrip.contains("return \"p_\" + base + \"\""));
        assertFalse(noStrip.contains("stripImageExtension"));
    }

    /** Scripts must sanitize like the Java exporters, or "my slide" exports as "my_slide". */
    @Test
    void everyGeneratedScriptNamesFilesLikeTheWizard() {
        var scripts = List.of(
                ScriptGenerator.generate(ExportCategory.RENDERED, new RenderedExportConfig.Builder()
                        .renderMode(RenderedExportConfig.RenderMode.NONE)
                        .showInfoLabel(true).infoLabelTemplate("{imageName}")
                        .outputDirectory(tempDir).build()),
                ScriptGenerator.generate(ExportCategory.RAW,
                        new RawExportConfig.Builder().outputDirectory(tempDir).build()),
                ScriptGenerator.generate(ExportCategory.MASK, new MaskExportConfig.Builder()
                        .format(OutputFormat.PNG).outputDirectory(tempDir).build()),
                ScriptGenerator.generate(ExportCategory.TILED,
                        new TiledExportConfig.Builder().outputDirectory(tempDir).build()),
                ScriptGenerator.generate(ExportCategory.OBJECT_CROPS,
                        new ObjectCropConfig.Builder().outputDirectory(tempDir).build()));
        for (String script : scripts) {
            assertTrue(script.contains("def entryName = imageName"), "naming hook missing");
            assertTrue(script.contains("stripInvalidFilenameChars(entryName)"));
            assertFalse(script.contains("replaceAll('[^a-zA-Z0-9"), "regex sanitizer differs from Java");
            assertFalse(script.contains("'{imageName}', entryName"), "info label must show the image name");
            assertTrue(ImageNames.applyToScript(script, "p_", "", true)
                    .contains("def entryName = outputBaseName(imageName)"));
        }
    }
}
