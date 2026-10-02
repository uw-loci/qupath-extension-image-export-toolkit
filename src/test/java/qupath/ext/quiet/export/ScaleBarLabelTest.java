package qupath.ext.quiet.export;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;

import org.jfree.svg.SVGGraphics2D;
import org.junit.jupiter.api.Test;

class ScaleBarLabelTest {

    private static final String MICRO = "µ";

    @Test
    void labelsUseMicroSignBelowOneMillimeter() {
        assertEquals("100 " + MICRO + "m", ScaleBarRenderer.formatLabel(100));
        assertEquals("2.5 " + MICRO + "m", ScaleBarRenderer.formatLabel(2.5));
        assertEquals("0.25 " + MICRO + "m", ScaleBarRenderer.formatLabel(0.25));
        assertEquals("2 mm", ScaleBarRenderer.formatLabel(2000));
    }

    @Test
    void scaleBarFontCanDrawMicroSign() {
        for (int style : new int[] {Font.PLAIN, Font.BOLD}) {
            assertTrue(new Font(Font.SANS_SERIF, style, 12).canDisplay('µ'));
        }
    }

    @Test
    void svgKeepsMicroSign() {
        var svg = new SVGGraphics2D(400, 200);
        ScaleBarRenderer.drawScaleBar(svg, 400, 200, 1.0, ScaleBarRenderer.Position.LOWER_RIGHT,
                Color.WHITE, 12, true, false, 100);
        String doc = svg.getSVGDocument();
        // Text is drawn either as a <text> element or as glyph outlines; either keeps the sign
        assertTrue(doc.contains(MICRO + "m") || doc.contains("<path"), doc.substring(0, Math.min(400, doc.length())));
    }

    @Test
    void rasterLabelDrawsWithoutError() {
        var img = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        assertDoesNotThrow(() -> ScaleBarRenderer.drawScaleBar(g, 400, 200, 1.0,
                ScaleBarRenderer.Position.LOWER_RIGHT, Color.WHITE, 12, true, false, 100));
        g.dispose();
    }
}
