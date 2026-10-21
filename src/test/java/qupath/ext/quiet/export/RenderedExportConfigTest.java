package qupath.ext.quiet.export;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RenderedExportConfigTest {

    @TempDir
    File tempDir;

    @Test
    void testBuildValidClassifierConfig() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .classifierName("myClassifier")
                .overlayOpacity(0.75)
                .downsample(8.0)
                .format(OutputFormat.TIFF)
                .outputDirectory(tempDir)
                .includeAnnotations(true)
                .fillAnnotations(true)
                .showNames(false)
                .addToWorkflow(false)
                .build();

        assertEquals(RenderedExportConfig.RenderMode.CLASSIFIER_OVERLAY, config.getRenderMode());
        assertEquals("myClassifier", config.overlays().classifierName());
        assertEquals(0.75, config.getOverlayOpacity(), 0.001);
        assertEquals(8.0, config.getDownsample(), 0.001);
        assertEquals(OutputFormat.TIFF, config.getFormat());
        assertEquals(tempDir, config.getOutputDirectory());
        assertTrue(config.overlays().includeAnnotations());
        assertTrue(config.overlays().fillAnnotations());
        assertFalse(config.overlays().showNames());
        assertFalse(config.isAddToWorkflow());
    }

    @Test
    void testOpacityClipping() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .classifierName("test")
                .overlayOpacity(1.5)
                .outputDirectory(tempDir)
                .build();
        assertEquals(1.0, config.getOverlayOpacity(), 0.001);

        RenderedExportConfig config2 = new RenderedExportConfig.Builder()
                .classifierName("test")
                .overlayOpacity(-0.5)
                .outputDirectory(tempDir)
                .build();
        assertEquals(0.0, config2.getOverlayOpacity(), 0.001);
    }

    @Test
    void testNullClassifierNameThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new RenderedExportConfig.Builder()
                        .outputDirectory(tempDir)
                        .build());
    }

    @Test
    void testBlankClassifierNameThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new RenderedExportConfig.Builder()
                        .classifierName("   ")
                        .outputDirectory(tempDir)
                        .build());
    }

    @Test
    void testNullOutputDirectoryThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new RenderedExportConfig.Builder()
                        .classifierName("test")
                        .build());
    }

    @Test
    void testDownsampleBelowOneThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new RenderedExportConfig.Builder()
                        .classifierName("test")
                        .outputDirectory(tempDir)
                        .downsample(0.5)
                        .build());
    }

    @Test
    void testBuildOutputFilenamePng() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .classifierName("test")
                .format(OutputFormat.PNG)
                .outputDirectory(tempDir)
                .build();

        assertEquals("my_image.png", config.buildOutputFilename("my_image"));
    }

    @Test
    void testBuildOutputFilenameTiff() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .classifierName("test")
                .format(OutputFormat.TIFF)
                .outputDirectory(tempDir)
                .build();

        assertEquals("sample_01.tif", config.buildOutputFilename("sample_01"));
    }

    @Test
    void testBuildOutputFilenameJpeg() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .classifierName("test")
                .format(OutputFormat.JPEG)
                .outputDirectory(tempDir)
                .build();

        assertEquals("sample_01.jpg", config.buildOutputFilename("sample_01"));
    }

    @Test
    void testBuildOutputFilenameHandlesEmptyName() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .classifierName("test")
                .format(OutputFormat.PNG)
                .outputDirectory(tempDir)
                .build();

        String result = config.buildOutputFilename("");
        assertNotNull(result);
        assertTrue(result.endsWith(".png"));
        assertFalse(result.startsWith("."));
    }

    @Test
    void testDefaults() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .classifierName("test")
                .outputDirectory(tempDir)
                .build();

        assertEquals(RenderedExportConfig.RenderMode.CLASSIFIER_OVERLAY, config.getRenderMode());
        assertEquals(0.5, config.getOverlayOpacity(), 0.001);
        assertEquals(4.0, config.getDownsample(), 0.001);
        assertEquals(OutputFormat.PNG, config.getFormat());
        assertFalse(config.overlays().includeAnnotations());
        assertTrue(config.overlays().includeDetections());
        assertFalse(config.overlays().fillAnnotations());
        assertFalse(config.overlays().showNames());
        assertTrue(config.isAddToWorkflow());
        // Scale bar defaults
        assertFalse(config.scaleBar().show());
        assertEquals("#FFFFFF", config.scaleBar().colorHex());
        assertEquals(0, config.scaleBar().fontSize());
        assertTrue(config.scaleBar().bold());
        assertEquals(java.awt.Color.WHITE, config.scaleBar().colorAsAwt());
    }

    @Test
    void testScaleBarColorHexConversion() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .classifierName("test")
                .outputDirectory(tempDir)
                .scaleBarColorHex("#FF0000")
                .scaleBarFontSize(24)
                .scaleBarBoldText(false)
                .build();

        assertEquals("#FF0000", config.scaleBar().colorHex());
        assertEquals(java.awt.Color.RED, config.scaleBar().colorAsAwt());
        assertEquals(24, config.scaleBar().fontSize());
        assertFalse(config.scaleBar().bold());
    }

    @Test
    void testScaleBarColorHexFallback() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .classifierName("test")
                .outputDirectory(tempDir)
                .scaleBarColorHex("not-a-color")
                .build();

        // Invalid hex should fall back to white
        assertEquals(java.awt.Color.WHITE, config.scaleBar().colorAsAwt());
    }

    @Test
    void testObjectOverlayModeDoesNotRequireClassifier() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.OBJECT_OVERLAY)
                .includeAnnotations(true)
                .outputDirectory(tempDir)
                .build();

        assertEquals(RenderedExportConfig.RenderMode.OBJECT_OVERLAY, config.getRenderMode());
        assertNull(config.overlays().classifierName());
        assertTrue(config.overlays().includeAnnotations());
    }

    @Test
    void testObjectOverlayModeRequiresAtLeastOneObjectType() {
        assertThrows(IllegalArgumentException.class, () ->
                new RenderedExportConfig.Builder()
                        .renderMode(RenderedExportConfig.RenderMode.OBJECT_OVERLAY)
                        .includeAnnotations(false)
                        .includeDetections(false)
                        .outputDirectory(tempDir)
                        .build());
    }

    @Test
    void testObjectOverlayModeWithDetectionsOnly() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.OBJECT_OVERLAY)
                .includeAnnotations(false)
                .includeDetections(true)
                .outputDirectory(tempDir)
                .build();

        assertFalse(config.overlays().includeAnnotations());
        assertTrue(config.overlays().includeDetections());
    }

    @Test
    void testDensityMapModeRequiresDensityMapName() {
        assertThrows(IllegalArgumentException.class, () ->
                new RenderedExportConfig.Builder()
                        .renderMode(RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY)
                        .outputDirectory(tempDir)
                        .build());
    }

    @Test
    void testDensityMapModeBlankNameThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new RenderedExportConfig.Builder()
                        .renderMode(RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY)
                        .densityMapName("   ")
                        .outputDirectory(tempDir)
                        .build());
    }

    @Test
    void testDensityMapModeWithValidName() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY)
                .densityMapName("my_density_map")
                .colormapName("Magma")
                .outputDirectory(tempDir)
                .build();

        assertEquals(RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY, config.getRenderMode());
        assertEquals("my_density_map", config.overlays().densityMapName());
        assertEquals("Magma", config.overlays().colormapName());
    }

    @Test
    void testColorScaleBarDefaults() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .classifierName("test")
                .outputDirectory(tempDir)
                .build();

        assertFalse(config.colorScaleBar().show());
        assertEquals(ScaleBarRenderer.Position.LOWER_RIGHT, config.colorScaleBar().position());
        assertEquals(0, config.colorScaleBar().fontSize());
        assertTrue(config.colorScaleBar().bold());
    }

    @Test
    void testColormapNameDefault() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY)
                .densityMapName("test_dm")
                .outputDirectory(tempDir)
                .build();

        assertEquals("Viridis", config.overlays().colormapName());
    }

    @Test
    void testPanelLabelDefaults() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .classifierName("test")
                .outputDirectory(tempDir)
                .build();

        assertFalse(config.panelLabel().show());
        assertNull(config.panelLabel().text());
        assertEquals(ScaleBarRenderer.Position.UPPER_LEFT, config.panelLabel().position());
        assertEquals(0, config.panelLabel().fontSize());
        assertTrue(config.panelLabel().bold());
    }

    @Test
    void testPanelLabelSettings() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .classifierName("test")
                .showPanelLabel(true)
                .panelLabelText("A")
                .panelLabelPosition(ScaleBarRenderer.Position.LOWER_RIGHT)
                .panelLabelFontSize(28)
                .panelLabelBold(false)
                .outputDirectory(tempDir)
                .build();

        assertTrue(config.panelLabel().show());
        assertEquals("A", config.panelLabel().text());
        assertEquals(ScaleBarRenderer.Position.LOWER_RIGHT, config.panelLabel().position());
        assertEquals(28, config.panelLabel().fontSize());
        assertFalse(config.panelLabel().bold());
    }

    @Test
    void testColorScaleBarSettings() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY)
                .densityMapName("test_dm")
                .showColorScaleBar(true)
                .colorScaleBarPosition(ScaleBarRenderer.Position.UPPER_LEFT)
                .colorScaleBarFontSize(18)
                .colorScaleBarBoldText(false)
                .outputDirectory(tempDir)
                .build();

        assertTrue(config.colorScaleBar().show());
        assertEquals(ScaleBarRenderer.Position.UPPER_LEFT, config.colorScaleBar().position());
        assertEquals(18, config.colorScaleBar().fontSize());
        assertFalse(config.colorScaleBar().bold());
    }

    @Test
    void testBuildOutputFilenameSvg() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .classifierName("test")
                .format(OutputFormat.SVG)
                .outputDirectory(tempDir)
                .build();

        assertEquals("my_image.svg", config.buildOutputFilename("my_image"));
    }

    @Test
    void testSvgFormatAccepted() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .classifierName("test")
                .format(OutputFormat.SVG)
                .outputDirectory(tempDir)
                .build();

        assertEquals(OutputFormat.SVG, config.getFormat());
    }

    private static RenderedExportConfig.MapOverlayConfig fileMap(RenderedExportConfig.MapRangeMode mode,
                                                                 String pattern, double min, double max) {
        return new RenderedExportConfig.MapOverlayConfig(RenderedExportConfig.MapSource.IMAGE_FILE,
                pattern, "Signal density", mode, min, max, 1, 99, false, "Signal", false);
    }

    @Test
    void testMapFileOverlayNeedsAPatternNotADensityMapName() {
        var ok = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY)
                .mapOverlay(fileMap(RenderedExportConfig.MapRangeMode.PER_IMAGE, "{image}.tif", 0, 1))
                .outputDirectory(tempDir)
                .build();
        assertEquals(RenderedExportConfig.MapSource.IMAGE_FILE, ok.mapOverlay().source());

        assertThrows(IllegalArgumentException.class, () -> new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY)
                .mapOverlay(fileMap(RenderedExportConfig.MapRangeMode.PER_IMAGE, " ", 0, 1))
                .outputDirectory(tempDir)
                .build());
    }

    @Test
    void testFixedMapRangeMustBeIncreasing() {
        assertThrows(IllegalArgumentException.class, () -> new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY)
                .mapOverlay(fileMap(RenderedExportConfig.MapRangeMode.FIXED, "{image}.tif", 1, 1))
                .outputDirectory(tempDir)
                .build());
    }

    @Test
    void testDensityMapDefaultsKeepPerImageRange() {
        var config = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY)
                .densityMapName("cells")
                .outputDirectory(tempDir)
                .build();
        assertEquals(RenderedExportConfig.MapSource.PROJECT_DENSITY_MAP, config.mapOverlay().source());
        assertEquals(RenderedExportConfig.MapRangeMode.PER_IMAGE, config.mapOverlay().rangeMode());
        assertFalse(config.mapOverlay().hasKnownRange());
    }

    @Test
    void testBuilderFromCopiesEverySetting() {
        var config = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY)
                .mapOverlay(fileMap(RenderedExportConfig.MapRangeMode.SHARED, "{image}.tif", 0, 1)
                        .withResolvedRange(0.2, 0.8))
                .showChannelLegend(true)
                .svgMaxVectorDetections(17)
                .targetDpi(300)
                .addToWorkflow(false)
                .outputDirectory(tempDir)
                .build();
        var copy = RenderedExportConfig.Builder.from(config).build();
        assertEquals(config.mapOverlay(), copy.mapOverlay());
        assertTrue(copy.mapOverlay().hasKnownRange());
        assertTrue(copy.isShowChannelLegend());
        assertEquals(17, copy.getSvgMaxVectorDetections());
        assertEquals(300, copy.getTargetDpi());
        assertFalse(copy.isAddToWorkflow());
        assertEquals(config.overlays(), copy.overlays());
        assertEquals(config.colorScaleBar(), copy.colorScaleBar());
        assertEquals(config.inset(), copy.inset());
    }
}
