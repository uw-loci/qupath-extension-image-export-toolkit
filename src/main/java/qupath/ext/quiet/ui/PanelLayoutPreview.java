package qupath.ext.quiet.ui;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import javafx.application.Platform;
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
import qupath.ext.quiet.export.PanelExportConfig;
import qupath.ext.quiet.export.PanelLabelRenderer;
import qupath.ext.quiet.export.ScaleBarRenderer;
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
        if (newEntries != null) {
            entries.addAll(newEntries);
        }
        for (int i = 0; i < entries.size(); i++) {
            thumbnails.add(null);
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
                        redraw();
                    }
                });
            }
        }, "quiet-panel-preview-thumbnails");
        loader.setDaemon(true);
        loader.start();
    }

    // ------------------------------------------------------------------
    // Sizing
    // ------------------------------------------------------------------

    /**
     * Recompute the logical canvas dimensions: the true composed-figure size
     * mapped so the longer side is {@value #LOGICAL_BOUND} px.
     */
    private void recomputeLogicalSize() {
        int slotHeight = cellHeight + captionBandHeight();
        double figW = (double) cols * cellWidth + (double) (cols + 1) * gutterX;
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
        if (displayHeight > MAX_DISPLAY_HEIGHT) {
            scale = MAX_DISPLAY_HEIGHT / logicalHeight;
        }
        double displayW = logicalWidth * scale;
        double displayH = logicalHeight * scale;
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
        double figScale = (LOGICAL_BOUND / Math.max(
                (double) cols * cellWidth + (double) (cols + 1) * gutterX,
                (double) rows * (cellHeight + captionBandHeight())
                        + (double) (rows + 1) * gutterY));

        double cellW = cellWidth * figScale * s;
        double cellH = cellHeight * figScale * s;
        double gx = gutterX * figScale * s;
        double gy = gutterY * figScale * s;
        double band = captionBandHeight() * figScale * s;
        double slotH = cellH + band;

        int capacity = rows * cols;
        int filled = Math.min(entries.size(), capacity);

        for (int idx = 0; idx < capacity; idx++) {
            int row = idx / cols;
            int col = idx % cols;
            double slotX = gx + col * (cellW + gx);
            double slotY = gy + row * (slotH + gy);
            double imageAreaY = captionAbove ? slotY + band : slotY;
            double captionY = captionAbove ? slotY : slotY + cellH;

            if (idx < filled) {
                drawCellImage(gc, idx, slotX, imageAreaY, cellW, cellH);
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
     * Draw one cell's thumbnail into its image-area rectangle, applying the
     * selected {@link CellFitMode}. Falls back to a neutral placeholder block
     * when the thumbnail has not loaded yet.
     */
    private void drawCellImage(GraphicsContext gc, int idx,
                               double areaX, double areaY,
                               double areaW, double areaH) {
        if (areaW <= 0 || areaH <= 0) {
            return;
        }
        Image img = idx < thumbnails.size() ? thumbnails.get(idx) : null;
        if (img == null) {
            // Placeholder for a not-yet-loaded (or unreadable) thumbnail.
            gc.setFill(Color.gray(0.88));
            gc.fillRect(areaX, areaY, areaW, areaH);
            return;
        }
        double imgW = img.getWidth();
        double imgH = img.getHeight();
        if (imgW <= 0 || imgH <= 0) {
            return;
        }
        gc.save();
        gc.beginPath();
        gc.rect(areaX, areaY, areaW, areaH);
        gc.clip();
        switch (cellFitMode) {
            case FIT_LETTERBOX -> {
                double scale = Math.min(areaW / imgW, areaH / imgH);
                double drawW = imgW * scale;
                double drawH = imgH * scale;
                gc.drawImage(img, areaX + (areaW - drawW) / 2,
                        areaY + (areaH - drawH) / 2, drawW, drawH);
            }
            case FILL_CROP -> {
                double scale = Math.max(areaW / imgW, areaH / imgH);
                double drawW = imgW * scale;
                double drawH = imgH * scale;
                gc.drawImage(img, areaX + (areaW - drawW) / 2,
                        areaY + (areaH - drawH) / 2, drawW, drawH);
            }
            case ACTUAL_SIZE -> {
                // The preview cell size is the on-screen mapping of the figure
                // cell; "actual size" maps the figure cell pixels to the cell
                // box, so the thumbnail is shown filling the box centred.
                double scale = Math.min(areaW / imgW, areaH / imgH);
                double drawW = imgW * scale;
                double drawH = imgH * scale;
                gc.drawImage(img, areaX + (areaW - drawW) / 2,
                        areaY + (areaH - drawH) / 2, drawW, drawH);
            }
            default -> gc.drawImage(img, areaX, areaY, areaW, areaH);
        }
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
        int minFigureDim = Math.min(cellWidth, cellHeight);
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
        double figScale = LOGICAL_BOUND / Math.max(
                (double) cols * cellWidth + (double) (cols + 1) * gutterX,
                (double) rows * (cellHeight + captionBandHeight())
                        + (double) (rows + 1) * gutterY);
        double cellW = cellWidth * figScale * s;
        double cellH = cellHeight * figScale * s;
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
