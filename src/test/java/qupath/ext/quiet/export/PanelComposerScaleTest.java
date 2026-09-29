package qupath.ext.quiet.export;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;

class PanelComposerScaleTest {

    private static final int BAR = Color.YELLOW.getRGB();

    private static BufferedImage solid(int size, Color c) {
        var img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, size, size);
        g.dispose();
        return img;
    }

    private static PanelExportConfig.Builder base() {
        return new PanelExportConfig.Builder()
                .rows(1).cols(2).gutterX(0).gutterY(0)
                .backgroundColor(Color.WHITE)
                .fixedCellSize(true).cellWidth(200).cellHeight(200)
                .cellFitMode(CellFitMode.FIT_LETTERBOX)
                .scaleBarColor(Color.YELLOW)
                .outputDirectory(new File("."));
    }

    // 100 um wide at 1 um/px, and 50 um wide at 0.5 um/px
    private static List<PanelComposer.Cell> cells() {
        return List.of(new PanelComposer.Cell(solid(100, Color.RED), List.of(), 1.0),
                new PanelComposer.Cell(solid(100, Color.BLUE), List.of(), 0.5));
    }

    /** Longest horizontal run of scale bar colour in cell {@code col}. */
    private static int longestBarRun(BufferedImage fig, int col) {
        int best = 0;
        for (int y = 0; y < fig.getHeight(); y++) {
            int run = 0;
            for (int x = col * 200; x < (col + 1) * 200; x++) {
                run = fig.getRGB(x, y) == BAR ? run + 1 : 0;
                best = Math.max(best, run);
            }
        }
        return best;
    }

    @Test
    void sameScaleDrawsSmallerSpecimenSmaller() {
        var config = base().matchScale(true).buildForLayout();
        var fig = PanelComposer.compose(config, cells(), 200, 200, 0);
        assertEquals(Color.RED.getRGB(), fig.getRGB(5, 100));
        // Blue image is 100 px wide, centred: background at the cell edge
        assertEquals(Color.WHITE.getRGB(), fig.getRGB(200 + 20, 100));
        assertEquals(Color.BLUE.getRGB(), fig.getRGB(200 + 100, 100));
    }

    @Test
    void withoutSameScaleBothFillTheirCells() {
        var config = base().matchScale(false).buildForLayout();
        var fig = PanelComposer.compose(config, cells(), 200, 200, 0);
        assertEquals(Color.BLUE.getRGB(), fig.getRGB(200 + 5, 100));
    }

    @Test
    void sameScaleBarsHaveEqualLength() {
        var config = base().matchScale(true)
                .scaleBarMode(PanelExportConfig.ScaleBarMode.EVERY_CELL).buildForLayout();
        var fig = PanelComposer.compose(config, cells(), 200, 200, 0);
        // 0.5 um per figure pixel; auto length for a 100 um wide cell is 10 um = 20 px, in both cells
        assertEquals(20, longestBarRun(fig, 0));
        assertEquals(20, longestBarRun(fig, 1));
    }

    @Test
    void barMatchesEachCellsOwnScale() {
        var config = base().matchScale(false).scaleBarLengthMicrons(20)
                .scaleBarMode(PanelExportConfig.ScaleBarMode.EVERY_CELL).buildForLayout();
        var fig = PanelComposer.compose(config, cells(), 200, 200, 0);
        // Red at 0.5 um/figure px -> 40 px; blue at 0.25 um/figure px -> 80 px
        assertEquals(40, longestBarRun(fig, 0));
        assertEquals(80, longestBarRun(fig, 1));
    }

    @Test
    void lastCellOnly() {
        var config = base().matchScale(true)
                .scaleBarMode(PanelExportConfig.ScaleBarMode.LAST_CELL).buildForLayout();
        var fig = PanelComposer.compose(config, cells(), 200, 200, 0);
        assertEquals(0, longestBarRun(fig, 0));
        assertEquals(20, longestBarRun(fig, 1));
    }

    @Test
    void barStaysInsideTheVisibleImage() {
        // Letterboxed blue image occupies x 50..150 of its cell; a lower-right bar must end inside it
        var config = base().matchScale(true)
                .scaleBarMode(PanelExportConfig.ScaleBarMode.EVERY_CELL).buildForLayout();
        var fig = PanelComposer.compose(config, cells(), 200, 200, 0);
        for (int y = 0; y < 200; y++) {
            for (int x = 200 + 150; x < 400; x++) {
                assertNotEquals(BAR, fig.getRGB(x, y), "bar drawn in the letterbox at " + x + "," + y);
            }
        }
    }
}
