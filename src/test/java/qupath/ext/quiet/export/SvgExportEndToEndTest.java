package qupath.ext.quiet.export;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.geom.Point2;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/**
 * Runs the real SVG export on an in-memory image.
 */
class SvgExportEndToEndTest {

    @TempDir
    Path tempDir;

    private ImageData<BufferedImage> imageWithPointsAndPolygon() {
        var img = new BufferedImage(200, 150, BufferedImage.TYPE_INT_RGB);
        var server = new WrappedBufferedImageServer("sample_42.tif", img);
        var imageData = new ImageData<>(server);
        var plane = ImagePlane.getDefaultPlane();
        imageData.getHierarchy().addObject(PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(10, 10, 50, 40, plane), PathClass.fromString("Tumor")));
        imageData.getHierarchy().addObject(PathObjects.createAnnotationObject(
                ROIs.createPointsROI(List.of(new Point2(100, 100), new Point2(120, 80)), plane),
                PathClass.fromString("macrophage")));
        return imageData;
    }

    private RenderedExportConfig config() {
        return new RenderedExportConfig.Builder()
                .renderMode(RenderedExportConfig.RenderMode.OBJECT_OVERLAY)
                .displaySettingsMode(RenderedExportConfig.DisplaySettingsMode.RAW)
                .format(OutputFormat.SVG)
                .outputDirectory(tempDir.toFile())
                .includeAnnotations(true)
                .showInfoLabel(true)
                .infoLabelTemplate("{imageName}")
                .build();
    }

    private String export() throws Exception {
        var config = config();
        RenderedImageExporter.exportWithObjectOverlay(imageWithPointsAndPolygon(), config, "sample_42.tif", 0);
        File out = new File(tempDir.toFile(), config.buildOutputFilename("sample_42.tif"));
        return Files.readString(out.toPath(), StandardCharsets.UTF_8);
    }

    @Test
    void svgWithPointAnnotationsIsWellFormed() throws Exception {
        String svg = export();
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        assertDoesNotThrow(() -> factory.newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8))));
        assertTrue(svg.contains("id='macrophage'") || svg.contains("id=\"macrophage\""));
        assertTrue(svg.contains("id='Tumor'") || svg.contains("id=\"Tumor\""));
    }

    @Test
    void svgDrawsInfoLabel() throws Exception {
        assertTrue(export().contains(">sample_42.tif</text>"), "info label text missing from SVG");
    }
}
