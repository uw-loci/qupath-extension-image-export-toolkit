package qupath.ext.quiet.export;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import groovy.lang.GroovyShell;
import groovy.lang.Script;
import qupath.lib.color.ColorMaps;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/**
 * The generated scripts' text helpers draw the same pixels as QuIET: every element, on
 * light, dark and busy images, in every text style.
 */
class TextOverlayParityTest {

    private Script helpers;

    @BeforeEach
    void loadHelpers() throws IOException {
        try (var in = RenderedScriptGenerator.class.getResourceAsStream("text-overlay-helpers.groovy")) {
            helpers = new GroovyShell().parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** A light (pale pink), dark or busy (stripes of both) background. */
    static BufferedImage backdrop(String kind, int w, int h) {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = switch (kind) {
                    case "light" -> new Color(236, 200 + (x % 30), 222).getRGB();
                    case "dark" -> new Color(18 + (x % 12), 18, 24 + (y % 9)).getRGB();
                    default -> ((x / 7 + y / 11) % 2 == 0) ? 0xF4E8F0 : 0x401030;
                };
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    private static BufferedImage copy(BufferedImage src) {
        var out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static List<LegendRenderer.Entry> entries() {
        return List.of(
                new LegendRenderer.Entry("Hematoxylin", 0x6A3D9A, LegendRenderer.SwatchKind.FILL),
                new LegendRenderer.Entry("Nucleus", 0xE41A1C, LegendRenderer.SwatchKind.OUTLINE),
                new LegendRenderer.Entry("Fiber", 0xFF00FF, LegendRenderer.SwatchKind.LINE),
                new LegendRenderer.Entry("Seeds", 0x33A02C, LegendRenderer.SwatchKind.POINT));
    }

    private static List<Map<String, Object>> groovyEntries(List<LegendRenderer.Entry> entries) {
        var out = new ArrayList<Map<String, Object>>();
        for (var e : entries) {
            var m = new LinkedHashMap<String, Object>();
            m.put("name", e.name());
            m.put("rgb", e.rgb());
            m.put("kind", e.kind().name());
            out.add(m);
        }
        return out;
    }

    private static void assertSamePixels(BufferedImage a, BufferedImage b, String what) {
        int differ = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    differ++;
                }
            }
        }
        assertEquals(0, differ, what + ": pixels that differ");
    }

    @Test
    void everyElementDrawsTheSamePixels() {
        var colorMap = ColorMaps.getColorMaps().values().iterator().next();
        int checked = 0;
        for (String kind : List.of("light", "dark", "busy")) {
            var base = backdrop(kind, 640, 420);
            for (var background : TextBackground.values()) {
                for (Color fixed : new Color[]{null, new Color(0, 0, 128)}) {
                    String what = kind + " " + background + " " + (fixed == null ? "auto" : "navy");
                    var java = copy(base);
                    Graphics2D g = java.createGraphics();
                    var bd = TextRenderUtils.Backdrop.of(java);
                    ScaleBarRenderer.drawScaleBar(g, 640, 420, 0.5, ScaleBarRenderer.Position.LOWER_RIGHT,
                            fixed != null ? fixed : Color.WHITE, 0, true, background, bd, 0);
                    ColorScaleBarRenderer.drawColorScaleBar(g, 640, 420, colorMap, 0.05, 82.7,
                            ScaleBarRenderer.Position.UPPER_RIGHT, 0, true, "Fiber coverage (%)",
                            background, fixed, bd);
                    PanelLabelRenderer.drawPanelLabel(g, 640, 420, "B", ScaleBarRenderer.Position.LOWER_RIGHT,
                            0, true, fixed, background, bd);
                    LegendRenderer.drawCornerLegend(g, entries(), ScaleBarRenderer.Position.UPPER_LEFT,
                            640, 420, background, fixed, bd);
                    InfoLabelRenderer.drawInfoLabel(g, 640, 420, "PDAC_5_20x_32474_27471_90.0_025.ome.tif",
                            ScaleBarRenderer.Position.LOWER_LEFT, 18, true, fixed, background, bd);
                    g.dispose();

                    var groovy = copy(base);
                    g = groovy.createGraphics();
                    String bg = background.name();
                    helpers.invokeMethod("quietDrawScaleBar", new Object[]{g, 640, 420, 0.5d, "LOWER_RIGHT",
                            fixed != null ? fixed : Color.WHITE, 0, true, bg, groovy});
                    helpers.invokeMethod("quietDrawColorScaleBar", new Object[]{g, 640, 420, colorMap, 0.05d, 82.7d,
                            "UPPER_RIGHT", 0, true, "Fiber coverage (%)", bg, fixed, groovy});
                    helpers.invokeMethod("quietDrawPanelLabel", new Object[]{g, 640, 420, "B", "LOWER_RIGHT",
                            0, true, fixed, bg, groovy});
                    helpers.invokeMethod("quietDrawCornerLegend", new Object[]{g, groovyEntries(entries()),
                            "UPPER_LEFT", 640, 420, bg, fixed, groovy});
                    helpers.invokeMethod("quietDrawInfoLabel", new Object[]{g, 640, 420,
                            "PDAC_5_20x_32474_27471_90.0_025.ome.tif", "LOWER_LEFT", 18, true, fixed, bg, groovy});
                    g.dispose();

                    assertSamePixels(java, groovy, what);
                    checked++;
                }
            }
        }
        assertEquals(18, checked);
    }

    @Test
    void autoChoosesBoxOnLightImagesAndOutlineOnDarkOnes() {
        var light = backdrop("light", 300, 200);
        var dark = backdrop("dark", 300, 200);
        var onLight = TextRenderUtils.resolveInk(TextBackground.AUTO, null,
                TextRenderUtils.Backdrop.of(light), 10, 10, 200, 40);
        var onDark = TextRenderUtils.resolveInk(TextBackground.AUTO, null,
                TextRenderUtils.Backdrop.of(dark), 10, 10, 200, 40);
        assertEquals(Color.BLACK, onLight.text());
        assertEquals(Color.WHITE, onDark.text());
        assertNull(onDark.box(), "white text on a dark image needs no box");
        // White text on the light image would be unreadable without a box
        var whiteOnLight = TextRenderUtils.resolveInk(TextBackground.AUTO, Color.WHITE,
                TextRenderUtils.Backdrop.of(light), 10, 10, 200, 40);
        assertEquals(Color.BLACK, whiteOnLight.box());
        var busy = TextRenderUtils.resolveInk(TextBackground.AUTO, null,
                TextRenderUtils.Backdrop.of(backdrop("busy", 300, 200)), 10, 10, 200, 40);
        assertNotNull(busy.box(), "text over light and dark stripes gets a box");
    }

    @Test
    void legendRowsMatchTheScript() {
        var img = backdrop("light", 400, 300);
        var imageData = new ImageData<>(new WrappedBufferedImageServer("slide.tif", img),
                ImageData.ImageType.BRIGHTFIELD_H_E);
        var plane = ImagePlane.getDefaultPlane();
        var h = imageData.getHierarchy();
        h.addObject(PathObjects.createAnnotationObject(ROIs.createRectangleROI(5, 5, 300, 200, plane),
                PathClass.fromString("Tissue")));
        h.addObject(PathObjects.createAnnotationObject(ROIs.createLineROI(10, 10, 80, 90, plane),
                PathClass.fromString("Fiber")));
        h.addObject(PathObjects.createAnnotationObject(ROIs.createPointsROI(50, 60, plane)));
        for (int i = 0; i < 5; i++) {
            h.addObject(PathObjects.createDetectionObject(
                    ROIs.createEllipseROI(20 + 30 * i, 120, 12, 12, plane), PathClass.fromString("Nucleus")));
            h.addObject(PathObjects.createDetectionObject(
                    ROIs.createEllipseROI(20 + 30 * i, 160, 12, 12, plane)));
        }
        h.addObject(PathObjects.createDetectionObject(ROIs.createEllipseROI(200, 200, 10, 10, plane),
                PathClass.fromString("Tissue")));

        var java = LegendContent.objectEntries(imageData, 0, 0, 400, 300, true, true, false);
        @SuppressWarnings("unchecked")
        var groovy = (List<Map<String, Object>>) helpers.invokeMethod("quietObjectEntries",
                new Object[]{imageData, 0, 0, 400, 300, true, true, false});
        assertEquals(groovyEntries(java), groovy);
        assertEquals(List.of("Fiber", "Tissue (annotations)", "Annotations", "Nucleus",
                        "Tissue (detections)", "Detections"),
                java.stream().map(LegendRenderer.Entry::name).toList());
        assertEquals(LegendRenderer.SwatchKind.LINE, java.get(0).kind());
        assertEquals(LegendRenderer.SwatchKind.OUTLINE, java.get(1).kind());
        assertEquals(LegendRenderer.SwatchKind.POINT, java.get(2).kind());

        var config = new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.OBJECT_OVERLAY)
                .outputDirectory(new java.io.File("."))
                .displaySettingsMode(RenderedExportConfig.DisplaySettingsMode.RAW).build();
        var stains = RenderedImageExporter.channelEntries(imageData, config);
        assertEquals(List.of("Hematoxylin", "Eosin"),
                stains.stream().map(LegendRenderer.Entry::name).toList());
        assertEquals(groovyEntries(stains),
                helpers.invokeMethod("quietChannelEntries", new Object[]{imageData, null}));
    }

    @Test
    void scriptsWithEveryTextElementCompile(@org.junit.jupiter.api.io.TempDir java.io.File dir) {
        for (var mode : RenderedExportConfig.RenderMode.values()) {
            for (var region : RenderedExportConfig.RegionType.values()) {
                for (boolean split : new boolean[]{false, true}) {
                    var config = new RenderedExportConfig.Builder()
                            .renderMode(mode).regionType(region)
                            .displaySettingsMode(RenderedExportConfig.DisplaySettingsMode.PER_IMAGE_SAVED)
                            .classifierName("c").densityMapName("d").colormapName("Viridis")
                            .outputDirectory(dir)
                            .showScaleBar(true).showInfoLabel(true).infoLabelTemplate("{imageName}")
                            .showPanelLabel(true).showChannelLegend(true).showObjectLegend(true)
                            .showColorScaleBar(true).textBackground(TextBackground.BOX)
                            .textColorHex("#202020").splitChannels(split)
                            .build();
                    String script = ScriptGenerator.generate(ExportCategory.RENDERED, config);
                    String what = mode + " " + region + (split ? " split" : "");
                    assertTrue(script.contains("quietDrawTextOverlays(g2d, result"), what);
                    assertTrue(script.contains("def textBackground = \"BOX\""), what);
                    assertTrue(script.contains("def textColor = \"#202020\""), what);
                    assertTrue(script.contains("def showObjectLegend = true"), what);
                    assertTrue(script.chars().allMatch(c -> c < 128), what + " is ASCII");
                    assertDoesNotThrow(() -> new GroovyShell().parse(script), what);
                }
            }
        }
    }
}
