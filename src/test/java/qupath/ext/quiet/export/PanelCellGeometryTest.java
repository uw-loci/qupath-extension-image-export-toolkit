package qupath.ext.quiet.export;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PanelCellGeometryTest {

    // A: 100 px at 1 um/px (100 um wide); B: 100 px at 0.5 um/px (50 um wide)
    private static final double[] W = {100, 100};
    private static final double[] H = {100, 100};
    private static final double[] UM = {1.0, 0.5};

    @Test
    void fitSharesTheScaleThatFitsTheLargestImage() {
        double common = PanelCellGeometry.commonMicronsPerPixel(
                CellFitMode.FIT_LETTERBOX, 200, 200, W, H, UM);
        assertEquals(0.5, common, 1e-9);
        var a = PanelCellGeometry.place(CellFitMode.FIT_LETTERBOX, 200, 200, 100, 100, 1.0, common);
        var b = PanelCellGeometry.place(CellFitMode.FIT_LETTERBOX, 200, 200, 100, 100, 0.5, common);
        assertEquals(200, a.width(), 1e-9);
        assertEquals(100, b.width(), 1e-9);
        assertEquals(50, b.x(), 1e-9);
        assertEquals(0.5, a.micronsPerPixel(), 1e-9);
        assertEquals(0.5, b.micronsPerPixel(), 1e-9);
    }

    @Test
    void fillSharesTheScaleAtWhichTheSmallestImageCovers() {
        double common = PanelCellGeometry.commonMicronsPerPixel(
                CellFitMode.FILL_CROP, 200, 100, W, H, UM);
        // B must cover 200 x 100: its 50 um width over 200 px
        assertEquals(0.25, common, 1e-9);
        var b = PanelCellGeometry.place(CellFitMode.FILL_CROP, 200, 100, 100, 100, 0.5, common);
        assertEquals(200, b.width(), 1e-9);
        int[] v = b.visible(200, 100);
        assertArrayEquals(new int[] {0, 0, 200, 100}, v);
    }

    @Test
    void unknownPixelSizeFallsBackToFittingEachImage() {
        double common = PanelCellGeometry.commonMicronsPerPixel(CellFitMode.FIT_LETTERBOX,
                200, 200, W, H, new double[] {1.0, Double.NaN});
        assertTrue(Double.isNaN(common));
        var b = PanelCellGeometry.place(CellFitMode.FIT_LETTERBOX, 200, 200, 100, 50, Double.NaN, common);
        assertEquals(200, b.width(), 1e-9);
        assertEquals(100, b.height(), 1e-9);
        assertTrue(Double.isNaN(b.micronsPerPixel()));
    }

    @Test
    void withoutMatchingEachImageIsFittedOnItsOwn() {
        var a = PanelCellGeometry.place(CellFitMode.FIT_LETTERBOX, 200, 100, 100, 100, 1.0, Double.NaN);
        assertEquals(100, a.width(), 1e-9);
        assertEquals(50, a.x(), 1e-9);
        assertEquals(1.0, a.micronsPerPixel(), 1e-9);
    }
}
