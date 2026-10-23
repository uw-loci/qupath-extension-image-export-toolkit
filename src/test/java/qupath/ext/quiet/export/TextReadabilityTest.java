package qupath.ext.quiet.export;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/**
 * Text on an export is readable on light and dark images (WCAG 4.5:1 between the text and
 * what is behind it), and a panel draws one legend only when it is true for every cell.
 */
class TextReadabilityTest {

    @TempDir
    File tempDir;

    private ImageData<BufferedImage> image(String kind) {
        var img = TextOverlayParityTest.backdrop(kind, 900, 600);
        var server = new WrappedBufferedImageServer(kind + ".tif", img);
        var imageData = new ImageData<>(server, ImageData.ImageType.BRIGHTFIELD_H_E);
        var plane = ImagePlane.getDefaultPlane();
        for (int i = 0; i < 40; i++) {
            imageData.getHierarchy().addObject(PathObjects.createDetectionObject(
                    ROIs.createEllipseROI(30 + (i % 10) * 80, 150 + (i / 10) * 80, 14, 14, plane),
                    PathClass.fromString("Nucleus")));
        }
        return imageData;
    }

    private RenderedExportConfig.Builder everyText(TextBackground background, String textColor) {
        return new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.OBJECT_OVERLAY)
                .displaySettingsMode(RenderedExportConfig.DisplaySettingsMode.RAW)
                .overlayOpacity(1.0)
                .includeDetections(true)
                .downsample(1)
                .outputDirectory(tempDir)
                .textBackground(background)
                .textColorHex(textColor)
                .showScaleBar(true)
                .showInfoLabel(true).infoLabelTemplate("{imageName}").infoLabelBold(true)
                .infoLabelFontSize(22)
                .showPanelLabel(true).panelLabelText("A")
                .panelLabelPosition(ScaleBarRenderer.Position.UPPER_RIGHT)
                .showChannelLegend(true)
                .showObjectLegend(true);
    }

    private static RenderedExportConfig withoutText(RenderedExportConfig config) {
        return RenderedExportConfig.Builder.from(config)
                .showScaleBar(false).showInfoLabel(false).showPanelLabel(false)
                .showChannelLegend(false).showObjectLegend(false).build();
    }

    private List<TextContrastProbe.Block> measure(String kind, TextBackground background,
                                                  String textColor, List<Color> known) throws Exception {
        var config = everyText(background, textColor).build();
        // Pixel size so the scale bar draws
        var imageData = image(kind);
        imageData.getServer().getMetadata();
        var with = RenderedImageExporter.renderToImage(withCalibration(imageData), null, null, config, kind);
        var without = RenderedImageExporter.renderToImage(withCalibration(imageData), null, null,
                withoutText(config), kind);
        var swatches = new ArrayList<Color>();
        for (var e : RenderedImageExporter.legendContent(imageData, null, config, 0, 0, 900, 600, 1, true).rows()) {
            swatches.add(new Color(e.rgb()));
        }
        return TextContrastProbe.measure(with, without, known, swatches);
    }

    private static ImageData<BufferedImage> withCalibration(ImageData<BufferedImage> imageData) {
        var server = imageData.getServer();
        var meta = new qupath.lib.images.servers.ImageServerMetadata.Builder(server.getMetadata())
                .pixelSizeMicrons(0.5, 0.5).build();
        server.setMetadata(meta);
        return imageData;
    }

    @Test
    void autoTextIsReadableOnLightAndDarkImages() throws Exception {
        for (String kind : List.of("light", "dark", "busy")) {
            var blocks = measure(kind, TextBackground.AUTO, "", List.of());
            assertTrue(blocks.size() >= 4, kind + ": blocks found " + blocks);
            for (var block : blocks) {
                System.out.println(kind + " AUTO " + block);
                // A box guarantees every pixel; an outline is chosen only where every sampled pixel reaches 4.5:1
                assertTrue((block.boxed() ? block.minContrast() : block.p1Contrast())
                        >= TextRenderUtils.MIN_CONTRAST, kind + ": " + block);
            }
        }
    }

    @Test
    void plainOutlineOnALightImageIsWhatTheProbeCatches() throws Exception {
        // White text with a black outline on pale H&E: the 1.4 look the user could not read
        var blocks = measure("light", TextBackground.OUTLINE, "#FFFFFF", List.of(Color.WHITE));
        assertTrue(blocks.stream().anyMatch(b -> b.p1Contrast() < TextRenderUtils.MIN_CONTRAST),
                "some 1.4-style block reads below 4.5:1: " + blocks);
    }

    // ---- Panel legend: once when true for every cell, on every cell when not ----

    private static PanelImageExporter.RenderedCell cell(LegendContent legend) {
        var img = new BufferedImage(200, 150, BufferedImage.TYPE_INT_RGB);
        return new PanelImageExporter.RenderedCell("c", img, Map.of(), 1.0, legend);
    }

    private static final LegendRenderer.Entry H =
            new LegendRenderer.Entry("Hematoxylin", 0x6A3D9A, LegendRenderer.SwatchKind.FILL);
    private static final LegendRenderer.Entry E =
            new LegendRenderer.Entry("Eosin", 0xE41A1C, LegendRenderer.SwatchKind.FILL);
    private static final LegendRenderer.Entry PPM =
            new LegendRenderer.Entry("PPM Subtracted", 0xFFFFFF, LegendRenderer.SwatchKind.FILL);
    private static final LegendRenderer.Entry FIBER =
            new LegendRenderer.Entry("Fiber", 0xFF00FF, LegendRenderer.SwatchKind.LINE);

    private RenderedExportConfig legendConfig() {
        return new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.OBJECT_OVERLAY)
                .outputDirectory(tempDir)
                .showChannelLegend(true).showObjectLegend(true).build();
    }

    @Test
    void sameLegendInEveryCellIsDrawnOnce() {
        var range = new LegendContent.MapScale("Viridis", 0.05, 82.73, "Fiber coverage (%)", List.of());
        var cells = List.of(
                cell(new LegendContent(List.of(PPM), List.of(FIBER), range)),
                cell(new LegendContent(List.of(PPM), List.of(), range)),
                cell(new LegendContent(List.of(PPM), List.of(FIBER), range)));
        var lines = new ArrayList<String>();
        var shared = PanelImageExporter.shareLegend(cells, legendConfig(),
                PanelExportConfig.LegendMode.RIGHT, lines);
        assertEquals(List.of(PPM), shared.channels());
        assertEquals(List.of(FIBER), shared.objects(), "object classes are pooled over the cells");
        assertEquals(range, shared.map());
        for (var c : cells) {
            assertBlank(c.image(), "a cell keeps no legend of its own");
        }
        assertTrue(lines.stream().noneMatch(l -> l.contains(": on every cell")), lines.toString());
    }

    @Test
    void cellsThatDifferKeepTheirOwnLegend() {
        var cells = List.of(
                cell(new LegendContent(List.of(H, E), List.of(),
                        new LegendContent.MapScale("Viridis", 0, 10, "", List.of()))),
                cell(new LegendContent(List.of(PPM), List.of(),
                        new LegendContent.MapScale("Viridis", 0, 20, "", List.of()))));
        var lines = new ArrayList<String>();
        var shared = PanelImageExporter.shareLegend(cells, legendConfig(),
                PanelExportConfig.LegendMode.BELOW, lines);
        assertTrue(shared.channels().isEmpty(), "no one channel legend for H&E and biref cells");
        assertNull(shared.map(), "no one color bar for two ranges");
        for (var c : cells) {
            assertFalse(isBlank(c.image()), "each cell draws its own legend");
        }
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Channel legend: on every cell")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Color scale bar: on every cell")), lines.toString());
    }

    @Test
    void aClassInTwoColorsIsNotPooled() {
        var red = new LegendRenderer.Entry("Fiber", 0xFF0000, LegendRenderer.SwatchKind.LINE);
        assertNull(LegendContent.poolObjects(List.of(List.of(FIBER), List.of(red))));
        assertEquals(List.of(FIBER), LegendContent.poolObjects(List.of(List.of(FIBER), List.of())));
    }

    @Test
    void theLegendGetsItsOwnBandBesideTheGrid() {
        var base = new PanelExportConfig.Builder().rows(2).cols(4).gutterX(10).gutterY(10)
                .captionFontSize(28);
        var legend = new LegendContent(List.of(H, E), List.of(FIBER),
                new LegendContent.MapScale("Viridis", 0, 80, "Coverage", List.of()));
        long[] grid = PanelComposer.computeFigureSize(
                base.legendMode(PanelExportConfig.LegendMode.RIGHT).buildForLayout(), 300, 300, 0);
        long[] right = PanelComposer.computeFigureSize(
                base.legendMode(PanelExportConfig.LegendMode.RIGHT).buildForLayout(), 300, 300, 0, legend);
        long[] below = PanelComposer.computeFigureSize(
                base.legendMode(PanelExportConfig.LegendMode.BELOW).buildForLayout(), 300, 300, 0, legend);
        assertTrue(right[0] > grid[0] && right[1] == grid[1], "right: wider, same height");
        assertTrue(below[1] > grid[1] && below[0] == grid[0], "below: taller, same width");
        long[] every = PanelComposer.computeFigureSize(
                base.legendMode(PanelExportConfig.LegendMode.EVERY_CELL).buildForLayout(), 300, 300, 0, legend);
        assertArrayEquals(grid, every);
    }

    private static boolean isBlank(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void assertBlank(BufferedImage img, String what) {
        assertTrue(isBlank(img), what);
    }
}
