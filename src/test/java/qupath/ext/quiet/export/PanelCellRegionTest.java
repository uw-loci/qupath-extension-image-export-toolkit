package qupath.ext.quiet.export;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/**
 * A panel cell honors the recipe's export region: with ALL_ANNOTATIONS it shows the bounding
 * box of the matching annotations, as a per-annotation export does.
 */
class PanelCellRegionTest {

    @TempDir
    Path tempDir;

    private ImageData<BufferedImage> image() {
        var img = new BufferedImage(200, 150, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setPaint(new java.awt.GradientPaint(0, 0, java.awt.Color.RED, 200, 150, java.awt.Color.BLUE));
        g.fillRect(0, 0, 200, 150);
        g.dispose();
        var imageData = new ImageData<>(new WrappedBufferedImageServer("slide.tif", img));
        var plane = ImagePlane.getDefaultPlane();
        imageData.getHierarchy().addObject(PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(10, 10, 50, 40, plane), PathClass.fromString("Tissue")));
        imageData.getHierarchy().addObject(PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(150, 100, 20, 20, plane), PathClass.fromString("Other")));
        return imageData;
    }

    private RenderedExportConfig.Builder rendered(List<String> classes) {
        return new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.OBJECT_OVERLAY)
                .displaySettingsMode(RenderedExportConfig.DisplaySettingsMode.RAW)
                .regionType(RenderedExportConfig.RegionType.ALL_ANNOTATIONS)
                .selectedClassifications(classes)
                .paddingPixels(4)
                .includeAnnotations(true)
                .downsample(2)
                .format(OutputFormat.PNG)
                .outputDirectory(tempDir.toFile());
    }

    @Test
    void cellIsTheAnnotationRegionAsAPerAnnotationExportDrawsIt() throws Exception {
        var config = rendered(List.of("Tissue")).build();
        BufferedImage cell = RenderedImageExporter.renderToImage(image(), null, null, config, "slide.tif");
        assertEquals(29, cell.getWidth());  // (50 + 2 * 4) / 2, rounded up
        assertEquals(24, cell.getHeight());

        RenderedImageExporter.exportPerAnnotation(image(), null, null, config, "slide.tif", 0);
        File written = new File(tempDir.toFile(), config.buildOutputFilename("slide.tif", "_Tissue_0"));
        BufferedImage perAnnotation = ImageIO.read(written);
        assertEquals(perAnnotation.getWidth(), cell.getWidth());
        assertEquals(perAnnotation.getHeight(), cell.getHeight());
        for (int y = 0; y < cell.getHeight(); y++) {
            for (int x = 0; x < cell.getWidth(); x++) {
                assertEquals(perAnnotation.getRGB(x, y) & 0xFFFFFF, cell.getRGB(x, y) & 0xFFFFFF,
                        "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void severalAnnotationsShareOneCell() throws Exception {
        BufferedImage cell = RenderedImageExporter.renderToImage(
                image(), null, null, rendered(null).build(), "slide.tif");
        // 6..174 x 6..124 at downsample 2
        assertEquals(84, cell.getWidth());
        assertEquals(59, cell.getHeight());
    }

    @Test
    void anImageWithoutAMatchingAnnotationIsSkipped() {
        var e = assertThrows(IOException.class, () -> RenderedImageExporter.renderToImage(
                image(), null, null, rendered(List.of("Tumor")).build(), "slide.tif"));
        assertTrue(e.getMessage().contains("slide.tif"), e.getMessage());
    }

    @Test
    void rawCellIsCroppedToo() throws Exception {
        var config = new RawExportConfig.Builder()
                .regionType(RawExportConfig.RegionType.ALL_ANNOTATIONS)
                .paddingPixels(0)
                .downsample(1)
                .format(OutputFormat.TIFF)
                .outputDirectory(tempDir.toFile())
                .build();
        BufferedImage cell = RawImageExporter.renderToImage(image(), config, "slide.tif");
        assertEquals(160, cell.getWidth());
        assertEquals(110, cell.getHeight());
    }

    @Test
    void boundsArePaddedAndClamped() throws Exception {
        var plane = ImagePlane.getDefaultPlane();
        var a = PathObjects.createAnnotationObject(ROIs.createRectangleROI(2, 3, 10, 10, plane));
        assertArrayEquals(new int[]{0, 0, 17, 18},
                CellRegion.bounds(List.of(a), 5, 100, 100, "x"));
        assertArrayEquals(new int[]{0, 0, 10, 10},
                CellRegion.bounds(List.of(a), 5, 10, 10, "x"));
    }
}
