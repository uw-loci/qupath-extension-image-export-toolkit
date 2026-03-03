package qupath.ext.quiet.advice;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import qupath.ext.quiet.export.ExportCategory;
import qupath.ext.quiet.export.MaskExportConfig;
import qupath.ext.quiet.export.OutputFormat;
import qupath.ext.quiet.export.RenderedExportConfig;
import qupath.ext.quiet.export.TiledExportConfig;
import qupath.lib.common.ColorTools;

/**
 * Unit tests for {@link PublicationAdviceChecker}.
 * <p>
 * Pure function tests with no JavaFX dependency.
 */
class PublicationAdviceCheckerTest {

    @TempDir
    File tempDir;

    // ---- Helper methods for building test ImageContexts ----

    private static ImageContext fluorescenceCalibrated(List<Integer> channelColors) {
        var names = channelColors.stream().map(c -> "Ch").toList();
        return new ImageContext(true, "FLUORESCENCE", names, channelColors,
                channelColors.size());
    }

    private static ImageContext brightfieldCalibrated() {
        return new ImageContext(true, "BRIGHTFIELD_H_E",
                List.of("Hematoxylin", "Eosin", "Residual"),
                List.of(ColorTools.packRGB(50, 50, 200),
                        ColorTools.packRGB(200, 50, 100),
                        ColorTools.packRGB(100, 100, 100)),
                3);
    }

    private static ImageContext uncalibratedFluorescence() {
        return new ImageContext(false, "FLUORESCENCE",
                List.of("DAPI", "FITC"),
                List.of(ColorTools.packRGB(0, 0, 255),
                        ColorTools.packRGB(0, 255, 0)),
                2);
    }

    // ---- Check 1: No scale bar when image has calibration ----

    @Test
    void testNoScaleBarOnCalibratedImage() {
        var config = renderedBuilder()
                .showScaleBar(false)
                .build();
        var images = List.of(fluorescenceCalibrated(
                List.of(ColorTools.packRGB(0, 0, 255))));

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertTrue(items.stream().anyMatch(i ->
                i.quarepRef() != null && i.quarepRef().equals("IA-1")
                        && i.severity() == AdviceSeverity.WARNING
                        && i.title().contains("scale bar")));
    }

    @Test
    void testScaleBarEnabledNoWarning() {
        var config = renderedBuilder()
                .showScaleBar(true)
                .scaleBarColorHex("#FFFFFF")
                .build();
        // Only fluorescence -- white is fine
        var images = List.of(fluorescenceCalibrated(
                List.of(ColorTools.packRGB(0, 0, 255))));

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertFalse(items.stream().anyMatch(i ->
                i.title().contains("No scale bar")));
    }

    // ---- Check 2: No pixel calibration ----

    @Test
    void testScaleBarEnabledButUncalibrated() {
        var config = renderedBuilder()
                .showScaleBar(true)
                .scaleBarColorHex("#FFFFFF")
                .build();
        var images = List.of(uncalibratedFluorescence());

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertTrue(items.stream().anyMatch(i ->
                i.title().contains("lack pixel calibration")));
    }

    // ---- Check 3: Light scale bar on brightfield ----

    @Test
    void testLightScaleBarOnBrightfield() {
        var config = renderedBuilder()
                .showScaleBar(true)
                .scaleBarColorHex("#FFFFFF")
                .build();
        var images = List.of(brightfieldCalibrated());

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertTrue(items.stream().anyMatch(i ->
                i.title().contains("Light scale bar")));
    }

    @Test
    void testDarkScaleBarOnBrightfieldNoWarning() {
        var config = renderedBuilder()
                .showScaleBar(true)
                .scaleBarColorHex("#000000")
                .build();
        var images = List.of(brightfieldCalibrated());

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertFalse(items.stream().anyMatch(i ->
                i.title().contains("Light scale bar")));
    }

    // ---- Check 4: Dark scale bar on fluorescence ----

    @Test
    void testDarkScaleBarOnFluorescence() {
        var config = renderedBuilder()
                .showScaleBar(true)
                .scaleBarColorHex("#000000")
                .build();
        var images = List.of(fluorescenceCalibrated(
                List.of(ColorTools.packRGB(0, 255, 0))));

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertTrue(items.stream().anyMatch(i ->
                i.title().contains("Dark scale bar")));
    }

    @Test
    void testWhiteScaleBarOnFluorescenceNoWarning() {
        var config = renderedBuilder()
                .showScaleBar(true)
                .scaleBarColorHex("#FFFFFF")
                .build();
        var images = List.of(fluorescenceCalibrated(
                List.of(ColorTools.packRGB(0, 255, 0))));

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertFalse(items.stream().anyMatch(i ->
                i.title().contains("Dark scale bar")));
    }

    // ---- Check 5: Display settings = RAW ----

    @Test
    void testRawDisplayWarning() {
        var config = renderedBuilder()
                .displaySettingsMode(RenderedExportConfig.DisplaySettingsMode.RAW)
                .build();
        var images = List.of(fluorescenceCalibrated(
                List.of(ColorTools.packRGB(0, 0, 255))));

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertTrue(items.stream().anyMatch(i ->
                i.quarepRef() != null && i.quarepRef().equals("IC-2")
                        && i.severity() == AdviceSeverity.WARNING));
    }

    @Test
    void testPerImageSavedNoRawWarning() {
        var config = renderedBuilder()
                .displaySettingsMode(
                        RenderedExportConfig.DisplaySettingsMode.PER_IMAGE_SAVED)
                .build();
        var images = List.of(fluorescenceCalibrated(
                List.of(ColorTools.packRGB(0, 0, 255))));

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertFalse(items.stream().anyMatch(i ->
                i.title().contains("No brightness")));
    }

    // ---- Check 6: PER_IMAGE_SAVED with multiple images ----

    @Test
    void testPerImageSavedMultipleImages() {
        var config = renderedBuilder()
                .displaySettingsMode(
                        RenderedExportConfig.DisplaySettingsMode.PER_IMAGE_SAVED)
                .build();
        var images = List.of(
                fluorescenceCalibrated(List.of(ColorTools.packRGB(0, 0, 255))),
                fluorescenceCalibrated(List.of(ColorTools.packRGB(0, 0, 255))));

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertTrue(items.stream().anyMatch(i ->
                i.quarepRef() != null && i.quarepRef().equals("IC-3")));
    }

    @Test
    void testPerImageSavedSingleImageNoWarning() {
        var config = renderedBuilder()
                .displaySettingsMode(
                        RenderedExportConfig.DisplaySettingsMode.PER_IMAGE_SAVED)
                .build();
        var images = List.of(fluorescenceCalibrated(
                List.of(ColorTools.packRGB(0, 0, 255))));

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertFalse(items.stream().anyMatch(i ->
                i.title().contains("Per-image display")));
    }

    // ---- Check 7: JPEG format for rendered images ----

    @Test
    void testJpegRenderedWarning() {
        var config = renderedBuilder()
                .format(OutputFormat.JPEG)
                .build();
        var images = List.of(fluorescenceCalibrated(
                List.of(ColorTools.packRGB(0, 0, 255))));

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertTrue(items.stream().anyMatch(i ->
                i.quarepRef() != null && i.quarepRef().equals("ID-1")
                        && i.severity() == AdviceSeverity.WARNING));
    }

    @Test
    void testPngRenderedNoJpegWarning() {
        var config = renderedBuilder()
                .format(OutputFormat.PNG)
                .build();
        var images = List.of(fluorescenceCalibrated(
                List.of(ColorTools.packRGB(0, 0, 255))));

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertFalse(items.stream().anyMatch(i ->
                i.title().contains("JPEG")));
    }

    // ---- Check 8: Red-green channel combination ----

    @Test
    void testRedGreenChannelWarning() {
        var config = renderedBuilder().build();
        var images = List.of(fluorescenceCalibrated(List.of(
                ColorTools.packRGB(220, 30, 30),   // Red
                ColorTools.packRGB(30, 220, 30)))); // Green

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertTrue(items.stream().anyMatch(i ->
                i.quarepRef() != null && i.quarepRef().equals("IC-6")
                        && i.title().contains("Red-green")));
    }

    @Test
    void testCyanMagentaNoRedGreenWarning() {
        var config = renderedBuilder().build();
        var images = List.of(fluorescenceCalibrated(List.of(
                ColorTools.packRGB(0, 255, 255),   // Cyan
                ColorTools.packRGB(255, 0, 255)))); // Magenta

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertFalse(items.stream().anyMatch(i ->
                i.title().contains("Red-green")));
    }

    @Test
    void testRedGreenSkippedForBrightfield() {
        var config = renderedBuilder().build();
        var images = List.of(brightfieldCalibrated());

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertFalse(items.stream().anyMatch(i ->
                i.title().contains("Red-green")));
    }

    // ---- Check 9: Multi-channel without split-channel export ----

    @Test
    void testMultiChannelNoSplit() {
        var config = renderedBuilder()
                .splitChannels(false)
                .build();
        var images = List.of(fluorescenceCalibrated(List.of(
                ColorTools.packRGB(0, 0, 255),
                ColorTools.packRGB(0, 255, 0))));

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertTrue(items.stream().anyMatch(i ->
                i.quarepRef() != null && i.quarepRef().equals("IC-5")));
    }

    @Test
    void testMultiChannelWithSplitNoWarning() {
        var config = renderedBuilder()
                .splitChannels(true)
                .splitChannelsGrayscale(true)
                .build();
        var images = List.of(fluorescenceCalibrated(List.of(
                ColorTools.packRGB(0, 0, 255),
                ColorTools.packRGB(0, 255, 0))));

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertFalse(items.stream().anyMatch(i ->
                i.title().contains("without split")));
    }

    // ---- Check 10: Split channels in pseudocolor ----

    @Test
    void testSplitPseudocolorInfo() {
        var config = renderedBuilder()
                .splitChannels(true)
                .splitChannelsGrayscale(false)
                .build();
        var images = List.of(fluorescenceCalibrated(
                List.of(ColorTools.packRGB(0, 0, 255))));

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertTrue(items.stream().anyMatch(i ->
                i.quarepRef() != null && i.quarepRef().equals("IC-4")
                        && i.severity() == AdviceSeverity.INFO));
    }

    @Test
    void testSplitGrayscaleNoPseudocolorInfo() {
        var config = renderedBuilder()
                .splitChannels(true)
                .splitChannelsGrayscale(true)
                .build();
        var images = List.of(fluorescenceCalibrated(
                List.of(ColorTools.packRGB(0, 0, 255))));

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertFalse(items.stream().anyMatch(i ->
                i.title().contains("pseudocolor")));
    }

    // ---- Check 11: JPEG format for masks ----

    @Test
    void testJpegMaskError() {
        var config = new MaskExportConfig.Builder()
                .format(OutputFormat.JPEG)
                .outputDirectory(tempDir)
                .build();

        var items = PublicationAdviceChecker.check(
                ExportCategory.MASK, config, List.of());

        assertTrue(items.stream().anyMatch(i ->
                i.severity() == AdviceSeverity.ERROR
                        && i.title().contains("corrupt")));
    }

    @Test
    void testPngMaskNoError() {
        var config = new MaskExportConfig.Builder()
                .format(OutputFormat.PNG)
                .outputDirectory(tempDir)
                .build();

        var items = PublicationAdviceChecker.check(
                ExportCategory.MASK, config, List.of());

        assertFalse(items.stream().anyMatch(i ->
                i.severity() == AdviceSeverity.ERROR));
    }

    // ---- Check 12: No label masks for tiled export ----

    @Test
    void testNoTiledLabels() {
        var config = new TiledExportConfig.Builder()
                .tileSize(512)
                .outputDirectory(tempDir)
                .build();

        var items = PublicationAdviceChecker.check(
                ExportCategory.TILED, config, List.of());

        assertTrue(items.stream().anyMatch(i ->
                i.title().contains("label masks")));
    }

    @Test
    void testTiledWithLabelsNoWarning() {
        var labelConfig = new MaskExportConfig.Builder()
                .format(OutputFormat.PNG)
                .outputDirectory(tempDir)
                .build();
        var config = new TiledExportConfig.Builder()
                .tileSize(512)
                .labeledServerConfig(labelConfig)
                .labelFormat(OutputFormat.PNG)
                .outputDirectory(tempDir)
                .build();

        var items = PublicationAdviceChecker.check(
                ExportCategory.TILED, config, List.of());

        assertFalse(items.stream().anyMatch(i ->
                i.title().contains("label masks")));
    }

    // ---- Check 13: No pixel calibration across any category ----

    @Test
    void testAllUncalibrated() {
        var config = renderedBuilder().build();
        var images = List.of(uncalibratedFluorescence());

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertTrue(items.stream().anyMatch(i ->
                i.title().contains("No selected images have pixel calibration")));
    }

    @Test
    void testSomeCalibratedNoAllUncalibratedWarning() {
        var config = renderedBuilder().build();
        var images = List.of(
                uncalibratedFluorescence(),
                fluorescenceCalibrated(
                        List.of(ColorTools.packRGB(0, 0, 255))));

        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, images);

        assertFalse(items.stream().anyMatch(i ->
                i.title().contains("No selected images have pixel calibration")));
    }

    // ---- Empty inputs ----

    @Test
    void testNoImagesNoErrors() {
        var config = renderedBuilder().build();
        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, config, List.of());
        // Should return empty -- no images to check
        assertTrue(items.isEmpty());
    }

    @Test
    void testNullConfigNoErrors() {
        var items = PublicationAdviceChecker.check(
                ExportCategory.RENDERED, null, List.of());
        assertTrue(items.isEmpty());
    }

    // ---- Luminance helper ----

    @Test
    void testHexLuminanceWhite() {
        double lum = PublicationAdviceChecker.hexLuminance("#FFFFFF");
        assertTrue(lum > 250);
    }

    @Test
    void testHexLuminanceBlack() {
        double lum = PublicationAdviceChecker.hexLuminance("#000000");
        assertEquals(0.0, lum, 0.01);
    }

    @Test
    void testHexLuminanceInvalidFallback() {
        double lum = PublicationAdviceChecker.hexLuminance("not-a-color");
        assertEquals(128.0, lum, 0.01);
    }

    @Test
    void testHexLuminanceNull() {
        double lum = PublicationAdviceChecker.hexLuminance(null);
        assertEquals(128.0, lum, 0.01);
    }

    // ---- ImageContext helpers ----

    @Test
    void testImageContextBrightfield() {
        var ctx = brightfieldCalibrated();
        assertTrue(ctx.isBrightfield());
        assertFalse(ctx.isFluorescence());
    }

    @Test
    void testImageContextFluorescence() {
        var ctx = fluorescenceCalibrated(
                List.of(ColorTools.packRGB(0, 0, 255)));
        assertFalse(ctx.isBrightfield());
        assertTrue(ctx.isFluorescence());
    }

    // ---- Helper: Rendered config builder with defaults ----

    private RenderedExportConfig.Builder renderedBuilder() {
        return new RenderedExportConfig.Builder()
                .classifierName("test")
                .outputDirectory(tempDir);
    }
}
