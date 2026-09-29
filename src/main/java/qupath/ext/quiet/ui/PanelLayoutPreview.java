package qupath.ext.quiet.ui;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.quiet.export.CellFitMode;
import qupath.ext.quiet.export.ObjectCropConfig;
import qupath.ext.quiet.export.PanelCellGeometry;
import qupath.ext.quiet.export.PanelComposer;
import qupath.ext.quiet.export.PanelExportConfig;
import qupath.ext.quiet.export.PanelImageExporter;
import qupath.ext.quiet.export.PanelLabelRenderer;
import qupath.ext.quiet.export.ScaleBarRenderer;
import qupath.ext.quiet.export.TextRenderUtils;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Interactive visual preview of the panel / montage layout.
 * <p>
 * Draws the rows-by-columns grid on a JavaFX {@link Canvas} with the real
 * background colour and X/Y gutters, each filled cell carrying a downsampled
 * thumbnail of its source image placed according to the selected
 * {@link CellFitMode}. Caption areas are drawn as plain grey horizontal
 * placeholder bars (one per caption line) above or below the cell, not real
 * text. Trailing empty cells are background-only.
 * <p>
 * The user can drag a cell onto another to swap the two images; the resulting
 * order is the order the panel is composed in (see {@link #getOrderedEntries()}).
 * <p>
 * The logical drawing is bounded so the longer side is roughly
 * {@value #LOGICAL_BOUND} pixels; the on-screen {@link Canvas} is then scaled to
 * fit the available width while keeping the figure aspect ratio. Drag
 * hit-testing maps screen coordinates back to grid cells under that scaling.
 */
public class PanelLayoutPreview extends Pane {

    private static final Logger logger = LoggerFactory.getLogger(PanelLayoutPreview.class);

    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.quiet.ui.strings");

    /** Logical drawing bound -- the longer figure side maps to this many px. */
    private static final int LOGICAL_BOUND = 2048;

    /** Maximum on-screen canvas height, so a tall figure does not dominate. */
    private static final double MAX_DISPLAY_HEIGHT = 420;

    /** Thumbnail load is best-effort; this many entries are loaded eagerly. */
    private static final int MAX_THUMBNAILS = 200;

    private final Canvas canvas = new Canvas();

    /** The selected entries, in current (possibly reordered) placement order. */
    private final List<ProjectImageEntry<BufferedImage>> entries = new ArrayList<>();

    /** FX thumbnails, index-aligned with {@link #entries}; null until loaded. */
    private final List<Image> thumbnails = new ArrayList<>();

    /** Image size and pixel size, index-aligned with {@link #entries}; null until loaded. */
    private final List<ImageInfo> infos = new ArrayList<>();

    /** Full-resolution size and microns per pixel (NaN if uncalibrated) of a source image. */
    private record ImageInfo(int width, int height, double pixelSizeMicrons) {
    }

    /** The recipe each cell is rendered with, for its output size and pixel size. */
    private Object recipe;

    private final ReadOnlyStringWrapper hoverInfo = new ReadOnlyStringWrapper("");

    /** Called with the largest rendered cell size once image sizes are known. */
    private java.util.function.BiConsumer<Integer, Integer> sizeListener = (w, h) -> { };

    private boolean fixedCellSize;
    private int fixedCellWidth;
    private int fixedCellHeight;
    private boolean matchScale;
    private PanelExportConfig.ScaleBarMode scaleBarMode = PanelExportConfig.ScaleBarMode.NONE;
    private ScaleBarRenderer.Position scaleBarPosition = ScaleBarRenderer.Position.LOWER_RIGHT;
    private double scaleBarLength;
    private Color scaleBarColor = Color.WHITE;
    private int scaleBarFontSize;
    private boolean scaleBarBold = true;

    /** Layout parameters supplied by the owning pane on every redraw. */
    private int rows = 2;
    private int cols = 2;
    private int gutterX = 10;
    private int gutterY = 10;
    private Color background = Color.WHITE;
    private CellFitMode cellFitMode = CellFitMode.FIT_LETTERBOX;
    private int captionLines;
    private boolean captionAbove;

    /** Estimated uniform cell size in figure pixels (drives the aspect ratio). */
    private int cellWidth = 512;
    private int cellHeight = 512;
    private int captionFontSize = 14;

    private PanelLabelRenderer.PanelLabelStyle labelStyle =
            PanelLabelRenderer.PanelLabelStyle.NONE;
    private ScaleBarRenderer.Position labelPosition = ScaleBarRenderer.Position.UPPER_LEFT;
    private int labelFontSize;
    private boolean labelBold = true;
    private Color labelColor = Color.WHITE;

    /** Logical canvas size (the figure mapped into the LOGICAL_BOUND box). */
    private double logicalWidth = LOGICAL_BOUND;
    private double logicalHeight = LOGICAL_BOUND;

    /** Index of the cell currently being dragged, or -1 when none. */
    private int dragSourceIndex = -1;

    /** Index of the cell the drag is hovering over, or -1 when none. */
    private int dragHoverIndex = -1;

    /** Notified after a drag-reorder so the owner can refresh dependent UI. */
    private Runnable reorderListener = () -> { };

    public PanelLayoutPreview() {
        getChildren().add(canvas);
        setMinHeight(120);
        setPrefHeight(260);
        Tooltip tip = new Tooltip(resources.getString("tooltip.panel.preview"));
        tip.setWrapText(true);
        tip.setMaxWidth(360);
        Tooltip.install(canvas, tip);
        // Recompute the on-screen scale whenever the available width changes.
        widthProperty().addListener((obs, was, now) -> updateDisplaySize());
        installDragHandlers();
        canvas.setOnMouseMoved(e -> hoverInfo.set(describeCell(cellAt(e.getX(), e.getY()))));
        canvas.setOnMouseExited(e -> hoverInfo.set(""));
    }

    /** Text describing the cell under the mouse: image, scale and scale bar. */
    public ReadOnlyStringProperty hoverInfoProperty() {
        return hoverInfo.getReadOnlyProperty();
    }

    /**
     * Register a callback given the largest rendered cell size {width, height} whenever
     * image sizes finish loading or the recipe changes.
     */
    public void setSizeListener(java.util.function.BiConsumer<Integer, Integer> listener) {
        this.sizeListener = listener != null ? listener : (w, h) -> { };
    }

    /**
     * Set the recipe cells are rendered with; it decides each cell's pixel size.
     *
     * @param recipe the recipe config object (may be null)
     */
    public void setRecipe(Object recipe) {
        this.recipe = recipe;
        notifySize();
        redraw();
    }

    /**
     * Register a callback invoked on the FX thread after the user reorders
     * cells, so the owning pane can re-run dependent updates.
     *
     * @param listener the callback (never null)
     */
    public void setReorderListener(Runnable listener) {
        this.reorderListener = listener != null ? listener : () -> { };
    }

    /**
     * Replace the previewed image set. Thumbnails are loaded off the FX thread
     * and the preview is redrawn as each batch arrives.
     *
     * @param newEntries the selected entries in placement order (may be null)
     */
    public void setEntries(List<ProjectImageEntry<BufferedImage>> newEntries) {
        entries.clear();
        thumbnails.clear();
        infos.clear();
        if (newEntries != null) {
            entries.addAll(newEntries);
        }
        for (int i = 0; i < entries.size(); i++) {
            thumbnails.add(null);
            infos.add(null);
        }
        dragSourceIndex = -1;
        dragHoverIndex = -1;
        loadThumbnailsAsync();
        redraw();
    }

    /**
     * The selected entries in the user's current arrangement. This is the
     * order the panel is composed in.
     *
     * @return a copy of the ordered entry list
     */
    public List<ProjectImageEntry<BufferedImage>> getOrderedEntries() {
        return new ArrayList<>(entries);
    }

    /**
     * Update the layout parameters and redraw. Called by the owning pane
     * whenever a grid / gutter / background / fit / caption control changes.
     *
     * @param config          a probe config carrying the current settings
     * @param estCellWidth    estimated uniform cell width in figure pixels
     * @param estCellHeight   estimated uniform cell height in figure pixels
     * @param maxCaptionLines the caption line count to reserve space for
     */
    public void updateLayout(PanelExportConfig config, int estCellWidth,
                             int estCellHeight, int maxCaptionLines) {
        this.rows = Math.max(1, config.getRows());
        this.cols = Math.max(1, config.getCols());
        this.gutterX = Math.max(0, config.getGutterX());
        this.gutterY = Math.max(0, config.getGutterY());
        this.cellFitMode = config.getCellFitMode() != null
                ? config.getCellFitMode() : CellFitMode.FIT_LETTERBOX;
        this.captionFontSize = config.getCaptionFontSize();
        this.captionAbove =
                config.getCaptionPosition() == PanelExportConfig.CaptionPosition.ABOVE;
        this.captionLines = config.hasCaption() ? Math.max(0, maxCaptionLines) : 0;
        this.cellWidth = Math.max(1, estCellWidth);
        this.cellHeight = Math.max(1, estCellHeight);
        var awt = config.getBackgroundColor();
        if (awt != null) {
            this.background = new Color(
                    awt.getRed() / 255.0, awt.getGreen() / 255.0,
                    awt.getBlue() / 255.0, awt.getAlpha() / 255.0);
        }
        this.labelStyle = config.getPanelLabelStyle() != null
                ? config.getPanelLabelStyle() : PanelLabelRenderer.PanelLabelStyle.NONE;
        this.labelPosition = config.getPanelLabelPosition() != null
                ? config.getPanelLabelPosition() : ScaleBarRenderer.Position.UPPER_LEFT;
        this.labelFontSize = Math.max(0, config.getPanelLabelFontSize());
        this.fixedCellSize = config.isFixedCellSize();
        this.fixedCellWidth = config.getCellWidth();
        this.fixedCellHeight = config.getCellHeight();
        this.matchScale = config.isMatchScale();
        this.scaleBarMode = config.getScaleBarMode();
        this.scaleBarPosition = config.getScaleBarPosition();
        this.scaleBarLength = config.getScaleBarLengthMicrons();
        this.scaleBarFontSize = config.getScaleBarFontSize();
        this.scaleBarBold = config.isScaleBarBold();
        var barAwt = config.getScaleBarColor();
        if (barAwt != null) {
            this.scaleBarColor = Color.rgb(barAwt.getRed(), barAwt.getGreen(), barAwt.getBlue());
        }
        this.labelBold = config.isPanelLabelBold();
        var labelAwt = config.getPanelLabelColor();
        if (labelAwt != null) {
            this.labelColor = new Color(
                    labelAwt.getRed() / 255.0, labelAwt.getGreen() / 255.0,
                    labelAwt.getBlue() / 255.0, labelAwt.getAlpha() / 255.0);
        }
        redraw();
    }

    // ------------------------------------------------------------------
    // Thumbnail loading (off the FX thread)
    // ------------------------------------------------------------------

    private void loadThumbnailsAsync() {
        var snapshot = new ArrayList<>(entries);
        int limit = Math.min(snapshot.size(), MAX_THUMBNAILS);
        if (limit == 0) {
            return;
        }
        Thread loader = new Thread(() -> {
            for (int i = 0; i < limit; i++) {
                ProjectImageEntry<BufferedImage> entry = snapshot.get(i);
                ImageInfo info = readInfo(entry);
                Image fxImage = null;
                try {
                    BufferedImage thumb = entry.getThumbnail();
                    if (thumb != null) {
                        fxImage = SwingFXUtils.toFXImage(thumb, null);
                    }
                } catch (Exception e) {
                    logger.debug("Failed to load thumbnail for {}: {}",
                            entry.getImageName(), e.getMessage());
                }
                final int index = i;
                final Image loaded = fxImage;
                Platform.runLater(() -> {
                    // The entry list may have changed while loading -- only
                    // store the thumbnail if it still belongs at this index.
                    if (index < entries.size() && index < snapshot.size()
                            && entries.get(index) == snapshot.get(index)) {
                        thumbnails.set(index, loaded);
                        infos.set(index, info);
                        notifySize();
                        redraw();
                    }
                });
            }
        }, "quiet-panel-preview-thumbnails");
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * Read an image's size and pixel size from its server builder, without loading
     * its objects. A zero size if the server cannot be built.
     */
    private static ImageInfo readInfo(ProjectImageEntry<BufferedImage> entry) {
        try (var server = entry.getServerBuilder().build()) {
            var cal = server.getPixelCalibration();
            double px = cal.hasPixelSizeMicrons() ? cal.getAveragedPixelSizeMicrons() : Double.NaN;
            return new ImageInfo(server.getWidth(), server.getHeight(), px);
        } catch (Exception e) {
            logger.debug("Failed to read size of {}: {}", entry.getImageName(), e.getMessage());
            return new ImageInfo(0, 0, Double.NaN);
        }
    }

    /** Rendered size {w, h} and microns per rendered pixel of cell {@code idx}, or null. */
    private double[] renderedCell(int idx) {
        ImageInfo info = idx < infos.size() ? infos.get(idx) : null;
        if (info == null || info.width() <= 0 || info.height() <= 0) {
            return null;
        }
        double ds = Math.max(1e-6, PanelImageExporter.recipeDownsample(recipe, info.pixelSizeMicrons()));
        double um = info.pixelSizeMicrons() * ds;
        if (recipe instanceof ObjectCropConfig occ) {
            // One crop per image, not the whole image
            double side = occ.getCropSize() + 2.0 * occ.getPadding();
            return new double[] {side, side, um};
        }
        return new double[] {Math.ceil(info.width() / ds), Math.ceil(info.height() / ds), um};
    }

    /** Report the largest rendered cell size once every image's size is known. */
    private void notifySize() {
        int n = Math.min(entries.size(), MAX_THUMBNAILS);
        int w = 0;
        int h = 0;
        for (int i = 0; i < n; i++) {
            if (infos.get(i) == null) {
                return; // still loading
            }
            double[] cell = renderedCell(i);
            if (cell == null) {
                continue;
            }
            w = Math.max(w, (int) cell[0]);
            h = Math.max(h, (int) cell[1]);
        }
        if (w > 0 && h > 0) {
            sizeListener.accept(w, h);
        }
    }

    /** The cell size in figure pixels: the fixed size, or the largest rendered image. */
    private int[] cellSize() {
        if (fixedCellSize) {
            return new int[] {fixedCellWidth, fixedCellHeight};
        }
        return new int[] {cellWidth, cellHeight};
    }

    /** Microns per figure pixel shared by every placed cell, or NaN. */
    private double commonMicrons(int filled, int cw, int ch) {
        if (!matchScale || filled == 0) {
            return Double.NaN;
        }
        double[] w = new double[filled];
        double[] h = new double[filled];
        double[] um = new double[filled];
        for (int i = 0; i < filled; i++) {
            double[] cell = renderedCell(i);
            if (cell == null) {
                return Double.NaN;
            }
            w[i] = cell[0];
            h[i] = cell[1];
            um[i] = cell[2];
        }
        return PanelCellGeometry.commonMicronsPerPixel(cellFitMode, cw, ch, w, h, um);
    }

    /** Where cell {@code idx}'s image lands, in figure pixels relative to the cell. */
    private PanelCellGeometry.Placement placement(int idx, int cw, int ch, double common) {
        double[] cell = renderedCell(idx);
        Image img = idx < thumbnails.size() ? thumbnails.get(idx) : null;
        if (cell == null) {
            if (img == null) {
                return null;
            }
            cell = new double[] {img.getWidth(), img.getHeight(), Double.NaN};
        }
        return PanelCellGeometry.place(cellFitMode, cw, ch, cell[0], cell[1], cell[2], common);
    }

    /** The bar length shared by every cell when they are at one scale, else the requested length. */
    private double sharedBarLength(int cw, int ch, double common) {
        if (common > 0) {
            double len = ScaleBarRenderer.resolveLengthMicrons(cw, ch, common, scaleBarLength);
            return len > 0 ? len : 0;
        }
        return scaleBarLength;
    }

    private String describeCell(int idx) {
        if (idx < 0 || idx >= entries.size() || idx >= rows * cols) {
            return "";
        }
        int filled = Math.min(entries.size(), rows * cols);
        int[] cs = cellSize();
        double common = commonMicrons(filled, cs[0], cs[1]);
        var p = placement(idx, cs[0], cs[1], common);
        var sb = new StringBuilder(entries.get(idx).getImageName());
        if (p == null) {
            return sb.append(" -- loading...").toString();
        }
        if (p.micronsPerPixel() > 0) {
            sb.append(String.format(" -- %.3f um per figure pixel", p.micronsPerPixel()));
            if (matchScale && !(common > 0)) {
                sb.append(" (not matched yet)");
            }
        } else {
            sb.append(" -- no pixel size");
        }
        int[] v = p.visible(cs[0], cs[1]);
        if (v[2] < cs[0] || v[3] < cs[1]) {
            sb.append(String.format("; image %d x %d px of a %d x %d cell", v[2], v[3], cs[0], cs[1]));
        }
        if (PanelComposer.showsScaleBar(toConfigMode(), idx, filled) && p.micronsPerPixel() > 0) {
            double len = ScaleBarRenderer.resolveLengthMicrons(v[2], v[3], p.micronsPerPixel(),
                    sharedBarLength(cs[0], cs[1], common));
            if (len > 0) {
                sb.append("; scale bar ").append(ScaleBarRenderer.formatLabel(len));
            }
        }
        return sb.toString();
    }

    /** A minimal config carrying the scale bar mode, for {@link PanelComposer#showsScaleBar}. */
    private PanelExportConfig toConfigMode() {
        return new PanelExportConfig.Builder().scaleBarMode(scaleBarMode).buildForLayout();
    }

    // ------------------------------------------------------------------
    // Sizing
    // ------------------------------------------------------------------

    /**
     * Recompute the logical canvas dimensions: the true composed-figure size
     * mapped so the longer side is {@value #LOGICAL_BOUND} px.
     */
    private void recomputeLogicalSize() {
        int[] cs = cellSize();
        int slotHeight = cs[1] + captionBandHeight();
        double figW = (double) cols * cs[0] + (double) (cols + 1) * gutterX;
        double figH = (double) rows * slotHeight + (double) (rows + 1) * gutterY;
        if (figW <= 0 || figH <= 0) {
            logicalWidth = LOGICAL_BOUND;
            logicalHeight = LOGICAL_BOUND;
            return;
        }
        double scale = LOGICAL_BOUND / Math.max(figW, figH);
        logicalWidth = Math.max(1, figW * scale);
        logicalHeight = Math.max(1, figH * scale);
    }

    /** The caption band height in logical figure pixels. */
    private int captionBandHeight() {
        if (captionLines <= 0) {
            return 0;
        }
        int lineHeight = Math.round(captionFontSize * 1.35f);
        return captionLines * lineHeight + 8;
    }

    /**
     * Recompute the on-screen canvas size: the logical figure scaled to fit
     * the available pane width, capped in height, keeping the aspect ratio.
     */
    private void updateDisplaySize() {
        recomputeLogicalSize();
        double available = getWidth();
        if (available <= 0) {
            available = getPrefWidth() > 0 ? getPrefWidth() : 600;
        }
        double scale = available / logicalWidth;
        double displayHeight = logicalHeight * scale;
        // Grow with the window it sits in, so a bigger window shows each cell bigger
        double maxHeight = getHeight() > 0 ? Math.max(120, getHeight()) : MAX_DISPLAY_HEIGHT;
        if (displayHeight > maxHeight) {
            scale = maxHeight / logicalHeight;
        }
        // Whole pixels: a fractional edge row is never cleared and keeps the previous drawing
        double displayW = Math.floor(logicalWidth * scale);
        double displayH = Math.floor(logicalHeight * scale);
        canvas.setWidth(displayW);
        canvas.setHeight(displayH);
        // Centre the canvas in the available width.
        canvas.setLayoutX(Math.max(0, (available - displayW) / 2.0));
        canvas.setLayoutY(0);
        // Drive the pane's preferred height from the canvas height. Request a
        // fresh layout pass only when the height actually changed, so this
        // does not loop when called from layoutChildren().
        if (Math.abs(displayH - lastDisplayHeight) > 0.5) {
            lastDisplayHeight = displayH;
            requestLayout();
        }
        redrawCanvas();
    }

    /** The last canvas display height, used to gate layout re-requests. */
    private double lastDisplayHeight = -1;

    @Override
    protected double computePrefHeight(double width) {
        return lastDisplayHeight > 0 ? lastDisplayHeight : getMinHeight();
    }

    @Override
    protected double computeMinHeight(double width) {
        return Math.min(lastDisplayHeight > 0 ? lastDisplayHeight : 120, 120);
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        updateDisplaySize();
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /** Recompute sizing then repaint. */
    private void redraw() {
        updateDisplaySize();
    }

    /**
     * Paint the grid onto the canvas. All drawing is in on-screen pixels --
     * logical figure coordinates are scaled by {@link #displayScale}.
     */
    private void redrawCanvas() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);
        if (w <= 0 || h <= 0) {
            return;
        }
        // Background fills the whole figure (and therefore the gutters).
        gc.setFill(background);
        gc.fillRect(0, 0, w, h);

        // Logical-to-screen scale: logicalWidth maps to canvas width.
        double s = w / logicalWidth;
        int[] cs = cellSize();
        double figScale = (LOGICAL_BOUND / Math.max(
                (double) cols * cs[0] + (double) (cols + 1) * gutterX,
                (double) rows * (cs[1] + captionBandHeight())
                        + (double) (rows + 1) * gutterY));

        double cellW = cs[0] * figScale * s;
        double cellH = cs[1] * figScale * s;
        double gx = gutterX * figScale * s;
        double gy = gutterY * figScale * s;
        double band = captionBandHeight() * figScale * s;
        double slotH = cellH + band;

        int capacity = rows * cols;
        int filled = Math.min(entries.size(), capacity);
        double common = commonMicrons(filled, cs[0], cs[1]);
        double barLength = sharedBarLength(cs[0], cs[1], common);
        double k = figScale * s;

        for (int idx = 0; idx < capacity; idx++) {
            int row = idx / cols;
            int col = idx % cols;
            double slotX = gx + col * (cellW + gx);
            double slotY = gy + row * (slotH + gy);
            double imageAreaY = captionAbove ? slotY + band : slotY;
            double captionY = captionAbove ? slotY : slotY + cellH;

            if (idx < filled) {
                var placement = placement(idx, cs[0], cs[1], common);
                drawCellImage(gc, idx, slotX, imageAreaY, cellW, cellH, placement, k);
                if (placement != null
                        && PanelComposer.showsScaleBar(toConfigMode(), idx, filled)) {
                    drawScaleBar(gc, slotX, imageAreaY, cs, placement, barLength, k);
                }
                if (band > 0) {
                    drawCaptionBars(gc, slotX, captionY, cellW, band);
                }
                if (labelStyle != PanelLabelRenderer.PanelLabelStyle.NONE) {
                    String text = PanelLabelRenderer.labelForIndex(idx, labelStyle);
                    drawCellLabel(gc, text, slotX, imageAreaY, cellW, cellH, figScale * s);
                }
            }
            // Cell outline -- a thin frame so empty/filled cells read clearly.
            gc.setStroke(idx < filled ? Color.gray(0.55) : Color.gray(0.78));
            gc.setLineWidth(1.0);
            gc.strokeRect(slotX + 0.5, slotY + 0.5,
                    Math.max(0, cellW - 1), Math.max(0, slotH - 1));

            // Drag highlights.
            if (idx == dragSourceIndex) {
                gc.setStroke(Color.web("#0078d7"));
                gc.setLineWidth(2.0);
                gc.strokeRect(slotX + 1, slotY + 1,
                        Math.max(0, cellW - 2), Math.max(0, slotH - 2));
            } else if (idx == dragHoverIndex && idx < filled) {
                gc.setStroke(Color.web("#ff8c00"));
                gc.setLineWidth(2.0);
                gc.strokeRect(slotX + 1, slotY + 1,
                        Math.max(0, cellW - 2), Math.max(0, slotH - 2));
            }
        }
    }

    /**
     * Draw one cell's thumbnail at its placement, clipped to the cell. Falls back to a
     * neutral placeholder block when the thumbnail has not loaded yet.
     *
     * @param k figure pixels to screen pixels
     */
    private void drawCellImage(GraphicsContext gc, int idx,
                               double areaX, double areaY,
                               double areaW, double areaH,
                               PanelCellGeometry.Placement placement, double k) {
        if (areaW <= 0 || areaH <= 0) {
            return;
        }
        Image img = idx < thumbnails.size() ? thumbnails.get(idx) : null;
        if (img == null || placement == null) {
            // Placeholder for a not-yet-loaded (or unreadable) thumbnail.
            gc.setFill(Color.gray(0.88));
            gc.fillRect(areaX, areaY, areaW, areaH);
            return;
        }
        gc.save();
        gc.beginPath();
        gc.rect(areaX, areaY, areaW, areaH);
        gc.clip();
        gc.drawImage(img, areaX + placement.x() * k, areaY + placement.y() * k,
                placement.width() * k, placement.height() * k);
        gc.restore();
    }

    /**
     * Draw a cell's scale bar with the same length, thickness, margin and font size
     * {@link ScaleBarRenderer} uses, scaled to the screen.
     */
    private void drawScaleBar(GraphicsContext gc, double areaX, double areaY, int[] cs,
                              PanelCellGeometry.Placement placement, double lengthMicrons,
                              double k) {
        double um = placement.micronsPerPixel();
        if (!(um > 0)) {
            return;
        }
        int[] v = placement.visible(cs[0], cs[1]);
        int vw = v[2];
        int vh = v[3];
        double len = ScaleBarRenderer.resolveLengthMicrons(vw, vh, um, lengthMicrons);
        if (!(len > 0)) {
            return;
        }
        int barPx = (int) Math.round(len / um);
        int barH = ScaleBarRenderer.barHeight(vh);
        int margin = ScaleBarRenderer.margin(vw, vh);
        int font = TextRenderUtils.resolveFontSize(scaleBarFontSize, Math.min(vw, vh));
        double ascent = font * 0.75;
        double bx;
        double by;
        switch (scaleBarPosition) {
            case LOWER_LEFT -> {
                bx = margin;
                by = vh - margin - barH;
            }
            case UPPER_RIGHT -> {
                bx = vw - margin - barPx;
                by = margin + ascent + 4;
            }
            case UPPER_LEFT -> {
                bx = margin;
                by = margin + ascent + 4;
            }
            default -> {
                bx = vw - margin - barPx;
                by = vh - margin - barH;
            }
        }
        double ox = areaX + v[0] * k;
        double oy = areaY + v[1] * k;
        double lum = 0.299 * scaleBarColor.getRed() + 0.587 * scaleBarColor.getGreen()
                + 0.114 * scaleBarColor.getBlue();
        Color outline = lum > 0.5 ? Color.BLACK : Color.WHITE;

        gc.save();
        gc.beginPath();
        gc.rect(ox, oy, vw * k, vh * k);
        gc.clip();
        gc.setFill(outline);
        gc.fillRect(ox + (bx - 1) * k, oy + (by - 1) * k, (barPx + 2) * k, (barH + 2) * k);
        gc.setFill(scaleBarColor);
        gc.fillRect(ox + bx * k, oy + by * k, barPx * k, barH * k);
        double fontPx = Math.max(6, font * k);
        gc.setFont(Font.font("System", scaleBarBold ? FontWeight.BOLD : FontWeight.NORMAL, fontPx));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(javafx.geometry.VPos.BASELINE);
        String label = ScaleBarRenderer.formatLabel(len);
        double tx = ox + (bx + barPx / 2.0) * k;
        double ty = oy + (by - 4) * k;
        gc.setLineWidth(Math.max(1.0, fontPx * 0.12));
        gc.setStroke(outline);
        gc.strokeText(label, tx, ty);
        gc.setFill(scaleBarColor);
        gc.fillText(label, tx, ty);
        gc.restore();
    }

    /**
     * Draw the per-cell label (A, B, C... / a, b, c... / 1, 2, 3...) over the
     * cell image area, mirroring the layout in {@link PanelLabelRenderer}.
     * Font size is in on-screen pixels; the auto path (figureFontPx == 0) scales
     * from the figure-pixel cell size {@link #cellWidth} / {@link #cellHeight}
     * mapped through {@code figureToScreen}.
     */
    private void drawCellLabel(GraphicsContext gc, String text,
                               double areaX, double areaY,
                               double areaW, double areaH,
                               double figureToScreen) {
        if (text == null || text.isEmpty() || areaW <= 0 || areaH <= 0) {
            return;
        }
        int[] cs = cellSize();
        int minFigureDim = Math.min(cs[0], cs[1]);
        double fontPx;
        if (labelFontSize > 0) {
            fontPx = labelFontSize * figureToScreen;
        } else {
            fontPx = Math.max(14, minFigureDim / 25.0) * figureToScreen;
        }
        if (fontPx < 6) {
            fontPx = 6;
        }
        double margin = Math.max(2, minFigureDim / 40.0) * figureToScreen;

        gc.save();
        gc.beginPath();
        gc.rect(areaX, areaY, areaW, areaH);
        gc.clip();
        gc.setFont(Font.font("System", labelBold ? FontWeight.BOLD : FontWeight.NORMAL,
                fontPx));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(javafx.geometry.VPos.BASELINE);

        // Approximate the text bbox so we can place upper-vs-lower / left-vs-right.
        double ascent = fontPx * 0.8;
        double approxWidth = fontPx * 0.6 * text.length();

        double textX;
        double textY;
        switch (labelPosition != null ? labelPosition : ScaleBarRenderer.Position.UPPER_LEFT) {
            case UPPER_RIGHT -> {
                textX = areaX + areaW - margin - approxWidth;
                textY = areaY + margin + ascent;
            }
            case LOWER_LEFT -> {
                textX = areaX + margin;
                textY = areaY + areaH - margin;
            }
            case LOWER_RIGHT -> {
                textX = areaX + areaW - margin - approxWidth;
                textY = areaY + areaH - margin;
            }
            case UPPER_LEFT -> {
                textX = areaX + margin;
                textY = areaY + margin + ascent;
            }
            default -> {
                textX = areaX + margin;
                textY = areaY + margin + ascent;
            }
        }

        // Contrast outline (black for light text, white for dark text).
        double lum = 0.299 * labelColor.getRed()
                + 0.587 * labelColor.getGreen()
                + 0.114 * labelColor.getBlue();
        Color outline = lum > 0.5 ? Color.BLACK : Color.WHITE;
        gc.setLineWidth(Math.max(1.0, fontPx * 0.08));
        gc.setStroke(outline);
        gc.strokeText(text, textX, textY);
        gc.setFill(labelColor);
        gc.fillText(text, textX, textY);
        gc.restore();
    }

    /**
     * Draw the caption placeholder bars: one grey horizontal line per caption
     * line. No real text is rendered -- the bars only show where caption text
     * would sit.
     */
    private void drawCaptionBars(GraphicsContext gc, double bandX, double bandY,
                                 double bandW, double bandH) {
        if (captionLines <= 0 || bandW <= 0 || bandH <= 0) {
            return;
        }
        gc.setFill(Color.gray(0.62));
        double pad = Math.max(2, bandW * 0.08);
        double lineH = Math.max(2, bandH / (captionLines + 1) * 0.55);
        double step = bandH / (captionLines + 1);
        for (int i = 0; i < captionLines; i++) {
            double y = bandY + step * (i + 1) - lineH / 2.0;
            // Stagger bar widths slightly so the band reads as text lines.
            double barW = (bandW - 2 * pad) * (i == 0 ? 0.8 : 0.6);
            gc.fillRect(bandX + pad, y, barW, lineH);
        }
    }

    // ------------------------------------------------------------------
    // Drag-to-reorder (swap)
    // ------------------------------------------------------------------

    private void installDragHandlers() {
        canvas.setOnMousePressed(e -> {
            int idx = cellAt(e.getX(), e.getY());
            if (idx >= 0 && idx < entries.size()) {
                dragSourceIndex = idx;
                dragHoverIndex = idx;
                redrawCanvas();
            }
        });
        canvas.setOnMouseDragged(e -> {
            if (dragSourceIndex < 0) {
                return;
            }
            int idx = cellAt(e.getX(), e.getY());
            if (idx != dragHoverIndex) {
                dragHoverIndex = idx;
                redrawCanvas();
            }
        });
        canvas.setOnMouseReleased(e -> {
            if (dragSourceIndex < 0) {
                return;
            }
            int target = cellAt(e.getX(), e.getY());
            if (target >= 0 && target < entries.size()
                    && target != dragSourceIndex) {
                // Swap -- predictable and order-stable.
                java.util.Collections.swap(entries, dragSourceIndex, target);
                java.util.Collections.swap(thumbnails, dragSourceIndex, target);
                java.util.Collections.swap(infos, dragSourceIndex, target);
                logger.debug("Panel preview: swapped cells {} and {}",
                        dragSourceIndex, target);
                dragSourceIndex = -1;
                dragHoverIndex = -1;
                redrawCanvas();
                reorderListener.run();
            } else {
                dragSourceIndex = -1;
                dragHoverIndex = -1;
                redrawCanvas();
            }
        });
    }

    /**
     * Map an on-screen canvas coordinate to a grid cell index, or -1 if the
     * point is outside every cell. Mirrors the layout arithmetic in
     * {@link #redrawCanvas()} so hit-testing is correct under display scaling.
     *
     * @param px on-screen x within the canvas
     * @param py on-screen y within the canvas
     * @return the cell index, or -1
     */
    private int cellAt(double px, double py) {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) {
            return -1;
        }
        double s = w / logicalWidth;
        int[] cs = cellSize();
        double figScale = LOGICAL_BOUND / Math.max(
                (double) cols * cs[0] + (double) (cols + 1) * gutterX,
                (double) rows * (cs[1] + captionBandHeight())
                        + (double) (rows + 1) * gutterY);
        double cellW = cs[0] * figScale * s;
        double cellH = cs[1] * figScale * s;
        double gx = gutterX * figScale * s;
        double gy = gutterY * figScale * s;
        double band = captionBandHeight() * figScale * s;
        double slotH = cellH + band;
        if (cellW + gx <= 0 || slotH + gy <= 0) {
            return -1;
        }
        // Subtract the leading gutter, then divide by the slot pitch.
        double localX = px - gx;
        double localY = py - gy;
        if (localX < 0 || localY < 0) {
            return -1;
        }
        int col = (int) (localX / (cellW + gx));
        int row = (int) (localY / (slotH + gy));
        if (col < 0 || col >= cols || row < 0 || row >= rows) {
            return -1;
        }
        // Reject points that fell in the trailing gutter of the slot.
        double withinX = localX - col * (cellW + gx);
        double withinY = localY - row * (slotH + gy);
        if (withinX > cellW || withinY > slotH) {
            return -1;
        }
        return row * cols + col;
    }
}
