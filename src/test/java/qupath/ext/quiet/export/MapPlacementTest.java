package qupath.ext.quiet.export;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;

import org.junit.jupiter.api.Test;

class MapPlacementTest {

    /** The description fiber analysis writes, abridged. */
    static final String FIBER = "FiberAnalysis density map. Pixel type: float32; NaN = no data; values are physical.\n"
            + "Each pixel is one cell of a grid laid on the source image.\n"
            + "Placement: source_width=16952; source_height=18225; source_pixel_um=0.1732; cell_px=36;"
            + " window_px=144; x0=54; y0=54; grid=467x503; render=bilinear\n"
            + "Source image: MetroHealth_FNA_PDAC_2_20x_6022_7751_90.0_021.ome.tif\n"
            + "Threshold: Otsu 0.07800 | Otsu pooled over 1 image(s), 16 sample tile(s) | settings 7a3ec246 at 0.1732 um/px\n"
            + "Channels:\n"
            + "  [0] name=Fiber coverage (%); unit=%\n"
            + "  [5] name=Mean angle (deg); unit=deg; axial=180\n";

    @Test
    void parsesFiberPlacement() {
        var p = MapPlacement.parse(FIBER);
        assertEquals(16952, p.sourceWidth());
        assertEquals(18225, p.sourceHeight());
        assertEquals(0.1732, p.sourcePixelUm());
        assertEquals(36, p.cellPx());
        assertEquals(144, p.windowPx());
        assertEquals(54, p.x0());
        assertEquals(54, p.y0());
        assertEquals(467, p.gridWidth());
        assertEquals(503, p.gridHeight());
        assertTrue(p.bilinear());
        // The grid stops 86 px short of the right edge and 63 px short of the bottom
        assertEquals(86, p.sourceWidth() - (p.x0() + p.gridWidth() * p.cellPx()));
        assertEquals(63, p.sourceHeight() - (p.y0() + p.gridHeight() * p.cellPx()));
    }

    @Test
    void halfPixelOriginAndNearest() {
        var p = MapPlacement.parse("Placement: source_width=100; source_height=80; cell_px=8; window_px=21;"
                + " x0=6.5; y0=6.5; grid=10x7; render=nearest");
        assertEquals(6.5, p.x0());
        assertFalse(p.bilinear());
        assertTrue(Double.isNaN(p.sourcePixelUm()));
    }

    @Test
    void noLineMeansStretch() {
        assertNull(MapPlacement.parse("A map with no placement.\nChannels:\n  [0] name=A\n"));
        assertNull(MapPlacement.parse(null));
    }

    @Test
    void malformedLineIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> MapPlacement.parse("Placement: source_width=100; cell_px=8; grid=10x7"));
        assertThrows(IllegalArgumentException.class,
                () -> MapPlacement.parse("Placement: source_width=100; source_height=80; cell_px=0; grid=10x7"));
        assertThrows(IllegalArgumentException.class,
                () -> MapPlacement.parse("Placement: source_width=100; source_height=80; cell_px=8; grid=10x7;"
                        + " render=bicubic"));
    }

    @Test
    void thresholdLine() {
        String line = MapThreshold.findLine(FIBER);
        var t = MapThreshold.parse(line);
        assertEquals("Otsu 0.07800", t.cut());
        assertEquals("7a3ec246", t.settings());
        assertEquals(0.1732, t.pixelUm());
        assertNull(MapThreshold.findLine("no threshold here"));
        assertNull(MapThreshold.parse("Otsu 0.078 and nothing else"));
        // Older lines have no pixel size
        assertTrue(Double.isNaN(MapThreshold.parse("Manual gray level 12 | set by hand | settings 0a0b0c0d").pixelUm()));
    }

    @Test
    void comparability() {
        var a = MapThreshold.parse("Otsu 0.07800 | pooled over 2 image(s) | settings 7a3ec246 at 0.1732 um/px");
        // How the cut was obtained, and the method name, do not matter
        assertTrue(a.comparableWith(MapThreshold.parse("Triangle 0.07800 | other | settings 7a3ec246 at 0.1740 um/px")));
        // Unknown pixel size compares
        assertTrue(a.comparableWith(MapThreshold.parse("Otsu 0.07800 | x | settings 7a3ec246")));
        assertFalse(a.comparableWith(MapThreshold.parse("Otsu 0.08100 | x | settings 7a3ec246 at 0.1732 um/px")));
        assertFalse(a.comparableWith(MapThreshold.parse("Otsu 0.07800 | x | settings 11111111 at 0.1732 um/px")));
        assertFalse(a.comparableWith(MapThreshold.parse("Otsu 0.07800 | x | settings 7a3ec246 at 0.1800 um/px")));
        assertFalse(a.comparableWith(MapThreshold.parse("Otsu response 0.07800 | x | settings 7a3ec246")));
    }

    @Test
    void warningNamesTheGroups() {
        var lines = new LinkedHashMap<String, String>();
        lines.put("slide A", "Otsu 0.07800 | run 1 | settings 7a3ec246 at 0.1732 um/px");
        lines.put("slide B", "Otsu 0.07800 | run 2 | settings 7a3ec246 at 0.1732 um/px");
        lines.put("slide C", null);
        assertNull(MapThreshold.incomparableWarning(lines), "one group and a map with no line");
        lines.put("slide D", "Otsu 0.09100 | run 3 | settings 7a3ec246 at 0.1732 um/px");
        String w = MapThreshold.incomparableWarning(lines);
        assertNotNull(w);
        assertTrue(w.contains("2 different thresholds"), w);
        assertTrue(w.contains("slide A, slide B"), w);
        assertTrue(w.contains("Otsu 0.09100") && w.contains("slide D"), w);
        assertFalse(w.contains("slide C"), w);
    }
}
