import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

import qupath.ext.quiet.export.ColorScaleBarRenderer;
import qupath.ext.quiet.export.LegendContent;
import qupath.ext.quiet.export.OutputFormat;
import qupath.ext.quiet.export.RenderedExportConfig;
import qupath.ext.quiet.export.RenderedImageExporter;
import qupath.ext.quiet.export.ScaleBarRenderer;
import qupath.ext.quiet.export.TextBackground;
import qupath.ext.quiet.export.TextContrastProbe;
import qupath.ext.quiet.export.TextRenderUtils;
import qupath.lib.color.ColorMaps;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/**
 * Real-QuPath scenario: every text element QuIET draws stays readable on a light (H&E-like)
 * and a dark (birefringence-like) image with the default Auto text style -- a WCAG contrast
 * of at least 4.5:1 between the text and what is behind it.
 *
 * <p>Renders through the production exporter (RenderedImageExporter.renderToImage) with the
 * scale bar, info label, panel label, stain/channel legend and object class legend on, and
 * draws a color scale bar with its title; measures each text block from the pixels with
 * TextContrastProbe against the same render without text. Writes the renders to the output
 * folder for a look.
 *
 * <p>args: outputDir
 */
public final class QuietTextContrastScenario {

    private static int failures = 0;

    private static void check(boolean condition, String what) {
        System.out.println((condition ? "[PASS] " : "[FAIL] ") + what);
        if (!condition) failures++;
    }

    private static void note(String what) {
        System.out.println("[....] " + what);
    }

    /** A pale pink H&E-like field with darker nuclei, or a dark field with bright fibre streaks. */
    private static BufferedImage field(boolean light, int w, int h) {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var rnd = new java.util.Random(light ? 1 : 2);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = rnd.nextInt(12);
                img.setRGB(x, y, light
                        ? new Color(232 - v, 196 + v, 220 - v).getRGB()
                        : new Color(30 + v, 30 + v, 32 + v).getRGB());
            }
        }
        var g = img.createGraphics();
        for (int i = 0; i < 400; i++) {
            int x = rnd.nextInt(w);
            int y = rnd.nextInt(h);
            if (light) {
                g.setColor(new Color(110, 60, 140));
                g.fillOval(x, y, 9, 9);
            } else {
                g.setColor(new Color(230, 230, 230));
                g.drawLine(x, y, x + 30, y + rnd.nextInt(20) - 10);
            }
        }
        g.dispose();
        return img;
    }

    private static ImageData<BufferedImage> imageData(boolean light) {
        var server = new WrappedBufferedImageServer(light ? "light_HE.tif" : "dark_biref.tif",
                field(light, 1200, 800));
        server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata())
                .pixelSizeMicrons(0.5, 0.5).build());
        var data = new ImageData<>(server, light ? ImageData.ImageType.BRIGHTFIELD_H_E
                : ImageData.ImageType.FLUORESCENCE);
        var plane = ImagePlane.getDefaultPlane();
        var rnd = new java.util.Random(3);
        for (int i = 0; i < 60; i++) {
            int x = 40 + rnd.nextInt(1100);
            int y = 40 + rnd.nextInt(700);
            data.getHierarchy().addObject(light
                    ? PathObjects.createDetectionObject(ROIs.createEllipseROI(x, y, 14, 12, plane))
                    : PathObjects.createDetectionObject(ROIs.createLineROI(x, y, x + 40, y + 15, plane),
                            PathClass.fromString("Fiber")));
        }
        return data;
    }

    private static RenderedExportConfig config(File out, boolean withText) {
        return new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.OBJECT_OVERLAY)
                .displaySettingsMode(RenderedExportConfig.DisplaySettingsMode.RAW)
                .overlayOpacity(1.0).includeDetections(true).downsample(1)
                .format(OutputFormat.PNG).outputDirectory(out)
                .textBackground(TextBackground.AUTO)
                .showScaleBar(withText)
                .showInfoLabel(withText).infoLabelTemplate("{imageName}").infoLabelFontSize(26)
                .infoLabelBold(true)
                .showPanelLabel(withText).panelLabelText("A")
                .panelLabelPosition(ScaleBarRenderer.Position.UPPER_RIGHT)
                .showChannelLegend(withText).showObjectLegend(withText)
                .build();
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "quiet-text-contrast");
        out.mkdirs();
        for (boolean light : new boolean[]{true, false}) {
            String kind = light ? "light" : "dark";
            var data = imageData(light);
            var with = RenderedImageExporter.renderToImage(data, null, null, config(out, true), kind);
            var without = RenderedImageExporter.renderToImage(data, null, null, config(out, false), kind);
            // A color scale bar with its title, on both renders' copies
            var colorMap = ColorMaps.getColorMaps().get("Viridis");
            var withBar = copy(with);
            var g = withBar.createGraphics();
            ColorScaleBarRenderer.drawColorScaleBar(g, withBar.getWidth(), withBar.getHeight(), colorMap,
                    0.05, 82.73, ScaleBarRenderer.Position.UPPER_RIGHT, 0, true, "Fiber coverage (%)",
                    TextBackground.AUTO, null, TextRenderUtils.Backdrop.of(withBar));
            g.dispose();
            ImageIO.write(with, "png", new File(out, kind + "_text.png"));
            ImageIO.write(withBar, "png", new File(out, kind + "_colorbar.png"));

            // Swatch colors are not background: the stains and the object classes
            var swatches = new ArrayList<Color>();
            var stains = data.getColorDeconvolutionStains();
            for (int i = 1; stains != null && i <= 3; i++) {
                swatches.add(new Color(stains.getStain(i).getColor() & 0xFFFFFF));
            }
            for (var e : LegendContent.objectEntries(data, 0, 0, 1200, 800, false, true, false)) {
                swatches.add(new Color(e.rgb()));
            }
            List<TextContrastProbe.Block> blocks = new ArrayList<>(
                    TextContrastProbe.measure(with, without, List.of(), swatches));
            // The color bar: its gradient is not background, so measure it against the text render
            var gradient = new ArrayList<Color>();
            for (int i = 0; i <= 4096; i++) {
                gradient.add(new Color(colorMap.getColor(0.05 + i * (82.73 - 0.05) / 4096, 0.05, 82.73)));
            }
            blocks.addAll(TextContrastProbe.measure(withBar, with, List.of(), gradient));
            check(blocks.size() >= 5, kind + ": every text element found (" + blocks.size() + " blocks)");
            for (var block : blocks) {
                double c = block.boxed() ? block.minContrast() : block.p1Contrast();
                note(kind + " " + block);
                check(c >= TextRenderUtils.MIN_CONTRAST,
                        String.format("%s: text at %d,%d reads at %.2f:1 or better", kind, block.x(), block.y(), c));
            }
        }
        System.out.println(failures == 0 ? "SCENARIO PASSED" : "SCENARIO FAILED (" + failures + ")");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static BufferedImage copy(BufferedImage src) {
        var img = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return img;
    }
}
