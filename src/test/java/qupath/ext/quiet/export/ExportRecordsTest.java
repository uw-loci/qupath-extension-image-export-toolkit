package qupath.ext.quiet.export;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportRecordsTest {

    @TempDir
    File tempDir;

    @Test
    void exactNumbersAreScriptReady() {
        assertEquals("0.0000012", ExportMetadataWriter.formatExact(1.2e-6));
        assertEquals("0.02010050229728222", ExportMetadataWriter.formatExact(0.02010050229728222));
        assertEquals("12.5", ExportMetadataWriter.formatExact(12.5));
        assertEquals("3", ExportMetadataWriter.formatExact(3.0));
    }

    @Test
    void mapRangeUsesAPointInEveryLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            var map = new RenderedExportConfig.MapOverlayConfig(RenderedExportConfig.MapSource.IMAGE_FILE,
                    "{image}.tif", "Signal", RenderedExportConfig.MapRangeMode.SHARED,
                    0, 0, 1, 99, false, "").withResolvedRange(0.0000012, 12.5);
            var config = new RenderedExportConfig.Builder()
                    .renderMode(RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY)
                    .mapOverlay(map).overlayOpacity(0.5).outputDirectory(tempDir).build();
            List<String> lines = ExportMetadataWriter.mapOverlayLines(config);
            String range = lines.stream().filter(l -> l.startsWith("Color range:")).findFirst().orElseThrow();
            assertTrue(range.startsWith("Color range: 0.0000012 to 12.5, the 1 to 99 percentile"), range);
            String opacity = lines.stream().filter(l -> l.startsWith("Overlay opacity:")).findFirst().orElseThrow();
            assertTrue(opacity.startsWith("Overlay opacity: 0.5 (blended"), opacity);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void noMapLinesWithoutAMapOverlay() {
        var config = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.NONE).outputDirectory(tempDir).build();
        assertTrue(ExportMetadataWriter.mapOverlayLines(config).isEmpty());
    }

    @Test
    void skippedImagesAreCountedPlainly() {
        var result = new ExportResult(7, 0, 1, List.of("tme_03: no map file"));
        assertEquals("Exported 7 images. 1 skipped.", result.getSummary());
        assertFalse(result.hasErrors(), "a skip alone is not a failure");
    }

    @Test
    void versionLineNamesBothVersions() {
        String line = ScriptGenerator.versionLine();
        assertTrue(line.startsWith("QuIET "), line);
        assertTrue(line.contains(" for QuPath "), line);
    }
}
