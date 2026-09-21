package qupath.ext.quiet.export;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class ImageNamesTest {

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
        assertEquals(List.of("a.tif", "A.czi", "b"), ImageNames.baseNames(names, true));
        assertEquals(names, ImageNames.baseNames(names, false));
    }

    @Test
    void scriptRewriteAddsFunctionOnlyWhenUsed() {
        String script = "    def entryName = entry.getImageName()\n";
        String out = ImageNames.applyToScript(script);
        assertTrue(out.contains("stripImageExtension(entry.getImageName())"));
        assertTrue(out.contains("def stripImageExtension(String name)"));
        assertEquals("println 'x'", ImageNames.applyToScript("println 'x'"));
    }
}
