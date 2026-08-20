package qupath.ext.quiet.export;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import qupath.ext.quiet.export.ExportCategory;
import qupath.ext.quiet.export.OutputFormat;
import qupath.ext.quiet.export.RenderedExportConfig;

class RenderedScriptGeneratorTest {

    @TempDir
    File tempDir;

    private RenderedExportConfig classifierConfig;

    @BeforeEach
    void setUp() {
        classifierConfig = new RenderedExportConfig.Builder()
                .classifierName("tissue_classifier")
                .overlayOpacity(0.6)
                .downsample(4.0)
                .format(OutputFormat.PNG)
                .outputDirectory(tempDir)
                .includeAnnotations(true)
                .includeDetections(true)
                .fillAnnotations(false)
                .showNames(false)
                .addToWorkflow(true)
                .build();
    }

    @Test
    void testClassifierScriptContainsImports() {
        String script = ScriptGenerator.generate(ExportCategory.RENDERED, classifierConfig);

        assertTrue(script.contains("import qupath.lib.classifiers.pixel.PixelClassificationImageServer"));
        assertTrue(script.contains("import qupath.lib.images.writers.ImageWriterTools"));
        // NOT HierarchyOverlay. The generator paints through
        // PathObjectPainter.paintSpecifiedObjects instead, because
        // HierarchyOverlay silently drops detections at downsample > 1.0 unless
        // it is given a regionStore tile cache the generated script does not
        // have (GitHub #1, fixed in v1.2.1/v1.2.2). This assertion used to
        // require the import that fix removed, so it failed from the moment the
        // bug was fixed -- a red test documenting a working fix.
        assertTrue(script.contains("import qupath.lib.gui.viewer.PathObjectPainter"));
        assertFalse(script.contains("HierarchyOverlay"),
                "HierarchyOverlay drops detections at downsample > 1.0 in a script "
                + "with no tile cache; paint through PathObjectPainter instead (GitHub #1)");
        assertTrue(script.contains("import java.awt.AlphaComposite"));
        assertTrue(script.contains("import java.awt.RenderingHints"));
        assertTrue(script.contains("import qupath.lib.regions.RegionRequest"));
    }

    @Test
    void testClassifierScriptContainsConfigValues() {
        String script = ScriptGenerator.generate(ExportCategory.RENDERED, classifierConfig);

        assertTrue(script.contains("\"tissue_classifier\""));
        assertTrue(script.contains("0.6"));
        assertTrue(script.contains("4.0"));
        assertTrue(script.contains("\"png\""));
        assertTrue(script.contains("includeAnnotations = true"));
        assertTrue(script.contains("includeDetections = true"));
        assertTrue(script.contains("fillAnnotations = false"));
        assertTrue(script.contains("showNames = false"));
    }

    @Test
    void testScaleBarScriptContainsRgbAndFontConfig() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .classifierName("test_classifier")
                .outputDirectory(tempDir)
                .showScaleBar(true)
                .scaleBarColorHex("#FF8000")
                .scaleBarFontSize(24)
                .scaleBarBoldText(false)
                .build();

        String script = ScriptGenerator.generate(ExportCategory.RENDERED, config);

        assertTrue(script.contains("scaleBarColorR"), "Script should contain scaleBarColorR");
        assertTrue(script.contains("scaleBarColorG"), "Script should contain scaleBarColorG");
        assertTrue(script.contains("scaleBarColorB"), "Script should contain scaleBarColorB");
        assertTrue(script.contains("scaleBarFontSize"), "Script should contain scaleBarFontSize");
        assertTrue(script.contains("scaleBarBoldText"), "Script should contain scaleBarBoldText");
        // Verify the RGB values for #FF8000
        assertTrue(script.contains("scaleBarColorR = 255"), "Red should be 255");
        assertTrue(script.contains("scaleBarColorG = 128"), "Green should be 128");
        assertTrue(script.contains("scaleBarColorB = 0"), "Blue should be 0");
        assertTrue(script.contains("scaleBarFontSize = 24"), "Font size should be 24");
        assertTrue(script.contains("scaleBarBoldText = false"), "Bold should be false");
        // Verify luminance-based outline in the function
        assertTrue(script.contains("new Color(colR, colG, colB)"),
                "Function should construct Color from RGB");
        assertTrue(script.contains("0.299"),
                "Function should use luminance formula");
    }

    @Test
    void testClassifierScriptContainsDirectCompositing() {
        String script = ScriptGenerator.generate(ExportCategory.RENDERED, classifierConfig);

        assertTrue(script.contains("readRegion"));
        assertTrue(script.contains("AlphaComposite.SRC_OVER"));
        assertTrue(script.contains("createGraphics"));
        assertTrue(script.contains("TYPE_INT_RGB"));
    }

    @Test
    void testClassifierScriptContainsMainLoop() {
        String script = ScriptGenerator.generate(ExportCategory.RENDERED, classifierConfig);

        assertTrue(script.contains("for (int i = 0; i < entries.size(); i++)"));
        assertTrue(script.contains("entry.readImageData()"));
        assertTrue(script.contains("ImageWriterTools.writeImage"));
    }

    @Test
    void testClassifierScriptContainsErrorHandling() {
        String script = ScriptGenerator.generate(ExportCategory.RENDERED, classifierConfig);

        assertTrue(script.contains("try {"));
        assertTrue(script.contains("catch (Exception e)"));
        assertTrue(script.contains("FAIL:"));
        assertTrue(script.contains("SKIP:"));
    }

    @Test
    void testClassifierScriptContainsCleanup() {
        String script = ScriptGenerator.generate(ExportCategory.RENDERED, classifierConfig);

        assertTrue(script.contains("classServer.close()"));
        assertTrue(script.contains("baseServer.close()"));
    }

    @Test
    void testClassifierScriptIsAsciiOnly() {
        String script = ScriptGenerator.generate(ExportCategory.RENDERED, classifierConfig);
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            assertTrue(c < 128,
                    "Non-ASCII character found at index " + i + ": U+" +
                    String.format("%04X", (int) c) + " '" + c + "'");
        }
    }

    @Test
    void testClassifierScriptWithWindowsPath() {
        File windowsDir = new File("C:\\Users\\test\\output");
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .classifierName("test")
                .outputDirectory(windowsDir)
                .build();

        String script = ScriptGenerator.generate(ExportCategory.RENDERED, config);

        assertFalse(script.contains("C:\\U"), "Unescaped backslash found");
        assertTrue(script.contains("C:\\\\"), "Backslashes should be escaped");
    }

    @Test
    void testClassifierScriptContainsSummary() {
        String script = ScriptGenerator.generate(ExportCategory.RENDERED, classifierConfig);
        assertTrue(script.contains("Export complete:"));
        assertTrue(script.contains("succeeded"));
        assertTrue(script.contains("failed"));
    }

    // --- Object overlay mode tests ---

    @Test
    void testObjectOverlayScriptExcludesClassifier() {
        RenderedExportConfig objConfig = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.OBJECT_OVERLAY)
                .includeAnnotations(true)
                .includeDetections(true)
                .overlayOpacity(0.7)
                .outputDirectory(tempDir)
                .build();

        String script = ScriptGenerator.generate(ExportCategory.RENDERED, objConfig);

        assertFalse(script.contains("PixelClassificationImageServer"),
                "Object overlay script should not reference PixelClassificationImageServer");
        assertFalse(script.contains("classifierName"),
                "Object overlay script should not reference classifierName");
        assertFalse(script.contains("classServer"),
                "Object overlay script should not reference classServer");
    }

    @Test
    void testObjectOverlayScriptContainsOverlayOptions() {
        RenderedExportConfig objConfig = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.OBJECT_OVERLAY)
                .includeAnnotations(true)
                .includeDetections(true)
                .outputDirectory(tempDir)
                .build();

        String script = ScriptGenerator.generate(ExportCategory.RENDERED, objConfig);

        assertTrue(script.contains("OverlayOptions"));
        assertTrue(script.contains("setShowAnnotations"));
        assertTrue(script.contains("setShowDetections"));
        // Objects are painted directly, not via HierarchyOverlay -- see
        // testClassifierScriptContainsImports for why.
        assertTrue(script.contains("PathObjectPainter.paintSpecifiedObjects"));
        assertFalse(script.contains("HierarchyOverlay"),
                "HierarchyOverlay drops detections at downsample > 1.0 (GitHub #1)");
    }

    @Test
    void testObjectOverlayScriptIsAsciiOnly() {
        RenderedExportConfig objConfig = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.OBJECT_OVERLAY)
                .includeAnnotations(true)
                .outputDirectory(tempDir)
                .build();

        String script = ScriptGenerator.generate(ExportCategory.RENDERED, objConfig);
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            assertTrue(c < 128,
                    "Non-ASCII character found at index " + i + ": U+" +
                    String.format("%04X", (int) c) + " '" + c + "'");
        }
    }

    @Test
    void testQuoteMethod() {
        assertEquals("null", ScriptGenerator.quote(null));
        assertEquals("\"hello\"", ScriptGenerator.quote("hello"));
        assertEquals("\"say \\\"hi\\\"\"", ScriptGenerator.quote("say \"hi\""));
        assertEquals("\"C:\\\\path\\\\to\"", ScriptGenerator.quote("C:\\path\\to"));
    }

    // --- Density map mode tests ---

    @Test
    void testDensityMapScriptContainsImports() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY)
                .densityMapName("cell_density")
                .colormapName("Viridis")
                .outputDirectory(tempDir)
                .build();

        String script = ScriptGenerator.generate(ExportCategory.RENDERED, config);

        assertTrue(script.contains("import qupath.lib.analysis.heatmaps.DensityMaps"),
                "Script should import DensityMaps");
        assertTrue(script.contains("import qupath.lib.color.ColorMaps"),
                "Script should import ColorMaps");
    }

    @Test
    void testDensityMapScriptContainsColorizationLogic() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY)
                .densityMapName("cell_density")
                .outputDirectory(tempDir)
                .build();

        String script = ScriptGenerator.generate(ExportCategory.RENDERED, config);

        assertTrue(script.contains("colorizeDensityMap"),
                "Script should contain colorizeDensityMap function");
        assertTrue(script.contains("densityBuilder.buildServer"),
                "Script should build density server per image");
        assertTrue(script.contains("densityMin"),
                "Script should compute min/max from density raster");
    }

    @Test
    void testDensityMapScriptContainsConfigValues() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY)
                .densityMapName("my_density")
                .colormapName("Magma")
                .overlayOpacity(0.8)
                .outputDirectory(tempDir)
                .build();

        String script = ScriptGenerator.generate(ExportCategory.RENDERED, config);

        assertTrue(script.contains("\"my_density\""));
        assertTrue(script.contains("\"Magma\""));
        assertTrue(script.contains("0.8"));
    }

    @Test
    void testColorScaleBarScriptFunction() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY)
                .densityMapName("test_dm")
                .showColorScaleBar(true)
                .colorScaleBarPosition(ScaleBarRenderer.Position.UPPER_LEFT)
                .colorScaleBarFontSize(16)
                .colorScaleBarBoldText(false)
                .outputDirectory(tempDir)
                .build();

        String script = ScriptGenerator.generate(ExportCategory.RENDERED, config);

        assertTrue(script.contains("drawColorScaleBar"),
                "Script should contain drawColorScaleBar function");
        assertTrue(script.contains("showColorScaleBar = true"),
                "Script should set showColorScaleBar");
        assertTrue(script.contains("colorScaleBarFontSize = 16"),
                "Script should set font size");
        assertTrue(script.contains("colorScaleBarBoldText = false"),
                "Script should set bold text");
    }

    @Test
    void testDensityMapScriptExcludesClassifier() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY)
                .densityMapName("test_dm")
                .outputDirectory(tempDir)
                .build();

        String script = ScriptGenerator.generate(ExportCategory.RENDERED, config);

        assertFalse(script.contains("PixelClassificationImageServer"),
                "Density map script should not reference PixelClassificationImageServer");
        assertFalse(script.contains("classifierName"),
                "Density map script should not reference classifierName");
    }

    @Test
    void testClassifierScriptContainsPanelLabel() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .classifierName("test_classifier")
                .showPanelLabel(true)
                .panelLabelText("A")
                .panelLabelPosition(ScaleBarRenderer.Position.UPPER_LEFT)
                .panelLabelFontSize(20)
                .panelLabelBold(true)
                .outputDirectory(tempDir)
                .build();

        String script = ScriptGenerator.generate(ExportCategory.RENDERED, config);

        assertTrue(script.contains("drawPanelLabel"),
                "Script should contain drawPanelLabel function");
        assertTrue(script.contains("showPanelLabel = true"),
                "Script should set showPanelLabel");
        assertTrue(script.contains("panelLabelFontSize = 20"),
                "Script should set panel label font size");
        assertTrue(script.contains("panelLabelBold = true"),
                "Script should set panel label bold");
        assertTrue(script.contains("\"UPPER_LEFT\""),
                "Script should set panel label position");
    }

    @Test
    void testClassifierScriptOmitsPanelLabelWhenDisabled() {
        // Default config has showPanelLabel=false
        String script = ScriptGenerator.generate(ExportCategory.RENDERED, classifierConfig);

        assertFalse(script.contains("drawPanelLabel"),
                "Script should not contain drawPanelLabel function when disabled");
        assertTrue(script.contains("showPanelLabel = false"),
                "Script should still have the config variable");
    }

    @Test
    void testDensityMapScriptContainsPanelLabel() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY)
                .densityMapName("test_dm")
                .showPanelLabel(true)
                .panelLabelBold(false)
                .outputDirectory(tempDir)
                .build();

        String script = ScriptGenerator.generate(ExportCategory.RENDERED, config);

        assertTrue(script.contains("drawPanelLabel"),
                "Density map script should contain drawPanelLabel function");
        assertTrue(script.contains("showPanelLabel = true"),
                "Script should set showPanelLabel");
    }

    @Test
    void testObjectOverlayScriptContainsPanelLabel() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.OBJECT_OVERLAY)
                .includeAnnotations(true)
                .showPanelLabel(true)
                .outputDirectory(tempDir)
                .build();

        String script = ScriptGenerator.generate(ExportCategory.RENDERED, config);

        assertTrue(script.contains("drawPanelLabel"),
                "Object overlay script should contain drawPanelLabel function");
    }

    @Test
    void testDensityMapScriptIsAsciiOnly() {
        RenderedExportConfig config = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY)
                .densityMapName("test_dm")
                .showColorScaleBar(true)
                .outputDirectory(tempDir)
                .build();

        String script = ScriptGenerator.generate(ExportCategory.RENDERED, config);
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            assertTrue(c < 128,
                    "Non-ASCII character found at index " + i + ": U+" +
                    String.format("%04X", (int) c) + " '" + c + "'");
        }
    }
}
