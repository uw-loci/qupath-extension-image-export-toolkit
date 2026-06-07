package qupath.ext.quiet.export;

import java.util.List;

import com.google.gson.Gson;
import qupath.ext.quiet.export.RenderedExportConfig;
import qupath.ext.quiet.export.RenderedExportConfig.DisplaySettingsMode;

import static qupath.ext.quiet.export.ScriptGenerator.appendLine;
import static qupath.ext.quiet.export.ScriptGenerator.quote;

/**
 * Generates self-contained Groovy scripts for rendered image export.
 */
class RenderedScriptGenerator {

    private static final Gson GSON = new Gson();

    private RenderedScriptGenerator() {
        // Utility class
    }

    /**
     * Emit the output format config line, falling back to PNG if SVG is selected
     * (SVG requires JFreeSVG which may not be on the script classpath).
     */
    private static void emitOutputFormat(StringBuilder sb, RenderedExportConfig config) {
        if (config.getFormat() == OutputFormat.SVG) {
            appendLine(sb, "// NOTE: SVG export requires the QuIET extension. Falling back to PNG for script.");
            appendLine(sb, "def outputFormat = " + quote(OutputFormat.PNG.getExtension()));
        } else {
            appendLine(sb, "def outputFormat = " + quote(config.getFormat().getExtension()));
        }
    }

    static String generate(RenderedExportConfig config) {
        return switch (config.getRenderMode()) {
            case OBJECT_OVERLAY, NONE -> generateObjectOverlayScript(config);
            case DENSITY_MAP_OVERLAY -> generateDensityMapScript(config);
            case CLASSIFIER_OVERLAY -> generateClassifierScript(config);
        };
    }

    /**
     * Serialize display settings to JSON for embedding in scripts.
     * Returns null if no settings are captured.
     */
    private static String serializeDisplaySettings(RenderedExportConfig config) {
        var settings = config.getCapturedDisplaySettings();
        if (settings == null) return null;
        return GSON.toJson(settings);
    }

    /**
     * Emit display-related imports (only for non-RAW modes).
     */
    private static void emitDisplayImports(StringBuilder sb, DisplaySettingsMode mode) {
        if (mode == DisplaySettingsMode.RAW) return;
        appendLine(sb, "import qupath.lib.display.ImageDisplay");
        appendLine(sb, "import qupath.lib.display.settings.DisplaySettingUtils");
        appendLine(sb, "import qupath.lib.gui.images.servers.ChannelDisplayTransformServer");
        if (mode == DisplaySettingsMode.CURRENT_VIEWER) {
            appendLine(sb, "import com.google.gson.JsonParser");
        }
        if (mode == DisplaySettingsMode.GLOBAL_MATCHED) {
            appendLine(sb, "import java.awt.image.Raster");
        }
    }

    /**
     * Emit display settings configuration variables.
     */
    private static void emitDisplayConfig(StringBuilder sb, RenderedExportConfig config) {
        var mode = config.getDisplaySettingsMode();
        appendLine(sb, "def displaySettingsMode = " + quote(mode.name()));
        if (mode == DisplaySettingsMode.CURRENT_VIEWER) {
            String json = serializeDisplaySettings(config);
            appendLine(sb, "def displaySettingsJson = " + (json != null ? quote(json) : "null"));
        } else if (mode == DisplaySettingsMode.SAVED_PRESET) {
            String presetName = config.getDisplayPresetName();
            appendLine(sb, "def displayPresetName = " + quote(presetName != null ? presetName : ""));
        } else if (mode == DisplaySettingsMode.GLOBAL_MATCHED) {
            appendLine(sb, "def matchedPercentile = " + config.getMatchedDisplayPercentile());
        }
    }

    /**
     * Emit display settings resolution code (after project is loaded, before the loop).
     */
    private static void emitDisplaySetup(StringBuilder sb, DisplaySettingsMode mode) {
        if (mode == DisplaySettingsMode.RAW) return;

        appendLine(sb, "// Resolve display settings");
        appendLine(sb, "def displaySettings = null");
        if (mode == DisplaySettingsMode.CURRENT_VIEWER) {
            appendLine(sb, "if (displaySettingsJson != null) {");
            appendLine(sb, "    def jsonElement = JsonParser.parseString(displaySettingsJson)");
            appendLine(sb, "    displaySettings = DisplaySettingUtils.parseDisplaySettings(jsonElement).orElse(null)");
            appendLine(sb, "}");
        } else if (mode == DisplaySettingsMode.SAVED_PRESET) {
            appendLine(sb, "def presetManager = DisplaySettingUtils.getResourcesForProject(project)");
            appendLine(sb, "displaySettings = presetManager.get(displayPresetName)");
            appendLine(sb, "if (displaySettings == null) {");
            appendLine(sb, "    println \"WARNING: Display preset not found: ${displayPresetName}\"");
            appendLine(sb, "}");
        } else if (mode == DisplaySettingsMode.GLOBAL_MATCHED) {
            emitGlobalMatchedScanPass(sb);
        }
        appendLine(sb, "");
    }

    /**
     * Emit a pre-scan pass that computes global per-channel histogram-based
     * percentile ranges, then builds displaySettings from the computed ranges.
     */
    private static void emitGlobalMatchedScanPass(StringBuilder sb) {
        appendLine(sb, "// --- Global matched display range scan ---");
        appendLine(sb, "println 'Scanning images for global display range matching...'");
        appendLine(sb, "def allEntries = project.getImageList()");
        appendLine(sb, "// Determine histogram size from first image");
        appendLine(sb, "def firstData = allEntries[0].readImageData()");
        appendLine(sb, "def firstServer = firstData.getServer()");
        appendLine(sb, "int nChannels = firstServer.getMetadata().getChannels().size()");
        appendLine(sb, "int bpp = firstServer.getPixelType().getBitsPerPixel()");
        appendLine(sb, "int histSize = bpp <= 8 ? 256 : 65536");
        appendLine(sb, "long[][] histograms = new long[nChannels][histSize]");
        appendLine(sb, "firstServer.close()");
        appendLine(sb, "");
        appendLine(sb, "for (int si = 0; si < allEntries.size(); si++) {");
        appendLine(sb, "    println \"  Scanning ${si + 1} of ${allEntries.size()}...\"");
        appendLine(sb, "    try {");
        appendLine(sb, "        def scanData = allEntries[si].readImageData()");
        appendLine(sb, "        def scanServer = scanData.getServer()");
        appendLine(sb, "        def scanRequest = qupath.lib.regions.RegionRequest.createInstance(");
        appendLine(sb, "                scanServer.getPath(), 32.0, 0, 0, scanServer.getWidth(), scanServer.getHeight())");
        appendLine(sb, "        def scanImg = scanServer.readRegion(scanRequest)");
        appendLine(sb, "        Raster raster = scanImg.getRaster()");
        appendLine(sb, "        int w = raster.getWidth()");
        appendLine(sb, "        int h = raster.getHeight()");
        appendLine(sb, "        int bands = Math.min(raster.getNumBands(), nChannels)");
        appendLine(sb, "        for (int y = 0; y < h; y++) {");
        appendLine(sb, "            for (int x = 0; x < w; x++) {");
        appendLine(sb, "                for (int c = 0; c < bands; c++) {");
        appendLine(sb, "                    int val = raster.getSample(x, y, c)");
        appendLine(sb, "                    if (val >= 0 && val < histSize) histograms[c][val]++");
        appendLine(sb, "                }");
        appendLine(sb, "            }");
        appendLine(sb, "        }");
        appendLine(sb, "        scanServer.close()");
        appendLine(sb, "    } catch (Exception e) {");
        appendLine(sb, "        println \"  WARNING: Scan failed for ${allEntries[si].getImageName()}: ${e.getMessage()}\"");
        appendLine(sb, "    }");
        appendLine(sb, "}");
        appendLine(sb, "");
        appendLine(sb, "// Compute percentile-based ranges");
        appendLine(sb, "def computedRanges = []");
        appendLine(sb, "for (int c = 0; c < nChannels; c++) {");
        appendLine(sb, "    long total = 0");
        appendLine(sb, "    for (long count : histograms[c]) total += count");
        appendLine(sb, "    if (total == 0) { computedRanges.add([0, histSize - 1]); continue }");
        appendLine(sb, "    double clipCount = total * matchedPercentile / 100.0");
        appendLine(sb, "    long cumLow = 0; int minVal = 0");
        appendLine(sb, "    for (int i = 0; i < histSize; i++) {");
        appendLine(sb, "        cumLow += histograms[c][i]");
        appendLine(sb, "        if (cumLow > clipCount) { minVal = i; break }");
        appendLine(sb, "    }");
        appendLine(sb, "    long cumHigh = 0; int maxVal = histSize - 1");
        appendLine(sb, "    for (int i = histSize - 1; i >= 0; i--) {");
        appendLine(sb, "        cumHigh += histograms[c][i]");
        appendLine(sb, "        if (cumHigh > clipCount) { maxVal = i; break }");
        appendLine(sb, "    }");
        appendLine(sb, "    if (minVal >= maxVal) maxVal = minVal + 1");
        appendLine(sb, "    computedRanges.add([minVal, maxVal])");
        appendLine(sb, "}");
        appendLine(sb, "println \"Global ranges computed for ${nChannels} channels\"");
        appendLine(sb, "");
        appendLine(sb, "// Build display settings from computed ranges");
        appendLine(sb, "def matchedData = allEntries[0].readImageData()");
        appendLine(sb, "def matchedDisplay = ImageDisplay.create(matchedData)");
        appendLine(sb, "def matchedChannels = matchedDisplay.selectedChannels()");
        appendLine(sb, "for (int c = 0; c < Math.min(matchedChannels.size(), computedRanges.size()); c++) {");
        appendLine(sb, "    matchedDisplay.setMinMaxDisplay(matchedChannels[c], (float) computedRanges[c][0], (float) computedRanges[c][1])");
        appendLine(sb, "}");
        appendLine(sb, "displaySettings = DisplaySettingUtils.displayToSettings(matchedDisplay, 'global_matched')");
        appendLine(sb, "matchedData.getServer().close()");
    }

    // ------------------------------------------------------------------
    // Scale bar helpers
    // ------------------------------------------------------------------

    /**
     * Emit scale bar imports (only when scale bar is enabled).
     */
    private static void emitScaleBarImports(StringBuilder sb) {
        appendLine(sb, "import java.awt.Color");
        appendLine(sb, "import java.awt.Font");
        appendLine(sb, "import java.awt.FontMetrics");
    }

    /**
     * Emit scale bar configuration variables.
     */
    private static void emitScaleBarConfig(StringBuilder sb, RenderedExportConfig config) {
        appendLine(sb, "def showScaleBar = " + config.scaleBar().show());
        appendLine(sb, "def scaleBarPosition = " + quote(config.scaleBar().position().name()));

        // Parse hex color into RGB components for the Groovy script
        java.awt.Color awtColor = config.scaleBar().colorAsAwt();
        appendLine(sb, "def scaleBarColorR = " + awtColor.getRed());
        appendLine(sb, "def scaleBarColorG = " + awtColor.getGreen());
        appendLine(sb, "def scaleBarColorB = " + awtColor.getBlue());
        appendLine(sb, "def scaleBarFontSize = " + config.scaleBar().fontSize());
        appendLine(sb, "def scaleBarBoldText = " + config.scaleBar().bold());
        appendLine(sb, "def scaleBarBackgroundBox = " + config.scaleBar().backgroundBox());
    }

    /**
     * Emit a self-contained drawScaleBar Groovy function.
     * Accepts RGB ints, font size (0 = auto), and bold flag.
     */
    private static void emitScaleBarFunction(StringBuilder sb) {
        appendLine(sb, "// Scale bar drawing function");
        appendLine(sb, "def drawScaleBar(Graphics2D g2d, int imgW, int imgH, double pxSize, String pos, int colR, int colG, int colB, int fSize, boolean bold, boolean bgBox) {");
        appendLine(sb, "    if (pxSize <= 0) return");
        appendLine(sb, "    def niceLengths = [0.1, 0.25, 0.5, 1, 2, 5, 10, 20, 50, 100, 200, 250, 500, 1000, 2000, 5000, 10000, 20000, 50000]");
        appendLine(sb, "    double target = imgW * pxSize * 0.15");
        appendLine(sb, "    double barUm = niceLengths.min { Math.abs(it - target) }");
        appendLine(sb, "    int barPx = (int) Math.round(barUm / pxSize)");
        appendLine(sb, "    if (barPx < 2) return");
        appendLine(sb, "    int barH = Math.max(4, imgH / 150)");
        appendLine(sb, "    int minDim = Math.min(imgW, imgH)");
        appendLine(sb, "    int fontSize = fSize > 0 ? Math.max(4, Math.min(fSize, 200)) : Math.max(12, minDim / 50)");
        appendLine(sb, "    int margin = Math.max(10, minDim / 40)");
        appendLine(sb, "    String label = barUm >= 1000 ? String.format('%d mm', (int)(barUm / 1000)) : (barUm == Math.floor(barUm) ? String.format('%d um', (int)barUm) : String.format('%.1f um', barUm))");
        appendLine(sb, "    int fontStyle = bold ? Font.BOLD : Font.PLAIN");
        appendLine(sb, "    g2d.setFont(new Font(Font.SANS_SERIF, fontStyle, fontSize))");
        appendLine(sb, "    def fm = g2d.getFontMetrics()");
        appendLine(sb, "    int tw = fm.stringWidth(label)");
        appendLine(sb, "    int th = fm.getAscent()");
        appendLine(sb, "    int bx, by");
        appendLine(sb, "    switch (pos) {");
        appendLine(sb, "        case 'LOWER_LEFT': bx = margin; by = imgH - margin - barH; break");
        appendLine(sb, "        case 'UPPER_RIGHT': bx = imgW - margin - barPx; by = margin + th + 4; break");
        appendLine(sb, "        case 'UPPER_LEFT': bx = margin; by = margin + th + 4; break");
        appendLine(sb, "        default: bx = imgW - margin - barPx; by = imgH - margin - barH; break");
        appendLine(sb, "    }");
        appendLine(sb, "    int tx = bx + (barPx - tw) / 2");
        appendLine(sb, "    int ty = by - 4");
        appendLine(sb, "    def primary = new Color(colR, colG, colB)");
        appendLine(sb, "    double lum = (0.299 * colR + 0.587 * colG + 0.114 * colB) / 255.0");
        appendLine(sb, "    def outline = lum > 0.5 ? Color.BLACK : Color.WHITE");
        appendLine(sb, "    if (bgBox) {");
        appendLine(sb, "        int boxPad = Math.max(6, margin / 4)");
        appendLine(sb, "        int boxL = Math.max(0, Math.min(bx, tx) - boxPad)");
        appendLine(sb, "        int boxT = Math.max(0, ty - th - boxPad)");
        appendLine(sb, "        int boxR = Math.min(imgW, Math.max(bx + barPx, tx + tw) + boxPad)");
        appendLine(sb, "        int boxB = Math.min(imgH, by + barH + boxPad)");
        appendLine(sb, "        def prevComp = g2d.getComposite()");
        appendLine(sb, "        g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.65f))");
        appendLine(sb, "        g2d.setColor(outline)");
        appendLine(sb, "        g2d.fillRoundRect(boxL, boxT, boxR - boxL, boxB - boxT, boxPad, boxPad)");
        appendLine(sb, "        g2d.setComposite(prevComp)");
        appendLine(sb, "    }");
        appendLine(sb, "    g2d.setColor(outline)");
        appendLine(sb, "    g2d.fillRect(bx - 1, by - 1, barPx + 2, barH + 2)");
        appendLine(sb, "    g2d.setColor(primary)");
        appendLine(sb, "    g2d.fillRect(bx, by, barPx, barH)");
        appendLine(sb, "    for (int dx = -1; dx <= 1; dx++) {");
        appendLine(sb, "        for (int dy = -1; dy <= 1; dy++) {");
        appendLine(sb, "            if (dx != 0 || dy != 0) { g2d.setColor(outline); g2d.drawString(label, tx + dx, ty + dy) }");
        appendLine(sb, "        }");
        appendLine(sb, "    }");
        appendLine(sb, "    g2d.setColor(primary)");
        appendLine(sb, "    g2d.drawString(label, tx, ty)");
        appendLine(sb, "}");
        appendLine(sb, "");
    }

    /**
     * Emit per-image scale bar drawing code (after overlay painting, before g2d.dispose).
     * Expects variables: baseServer, imageData, g2d, downsample, showScaleBar,
     * scaleBarPosition, scaleBarColorR/G/B, scaleBarFontSize, scaleBarBoldText
     * and outW, outH (output image dimensions).
     */
    private static void emitScaleBarDrawing(StringBuilder sb) {
        appendLine(sb, "        if (showScaleBar) {");
        appendLine(sb, "            def cal = imageData.getServer().getPixelCalibration()");
        appendLine(sb, "            if (cal.hasPixelSizeMicrons()) {");
        appendLine(sb, "                double pxSize = cal.getAveragedPixelSizeMicrons() * downsample");
        appendLine(sb, "                g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f))");
        appendLine(sb, "                drawScaleBar(g2d, outW, outH, pxSize, scaleBarPosition, scaleBarColorR, scaleBarColorG, scaleBarColorB, scaleBarFontSize, scaleBarBoldText, scaleBarBackgroundBox)");
        appendLine(sb, "            } else {");
        appendLine(sb, "                println \"  WARNING: Scale bar skipped -- no pixel calibration\"");
        appendLine(sb, "            }");
        appendLine(sb, "        }");
    }

    /**
     * Emit per-image display server wrapping code (inside the try block, after baseServer).
     * Sets up readServer variable that should be used instead of baseServer for image reads.
     */
    private static void emitDisplayServerWrapping(StringBuilder sb, DisplaySettingsMode mode) {
        if (mode == DisplaySettingsMode.RAW) {
            appendLine(sb, "        def readServer = baseServer");
            return;
        }

        appendLine(sb, "        // Apply display settings (brightness/contrast, channel visibility)");
        appendLine(sb, "        def display = ImageDisplay.create(imageData)");
        if (mode != DisplaySettingsMode.PER_IMAGE_SAVED) {
            appendLine(sb, "        if (displaySettings != null) {");
            appendLine(sb, "            DisplaySettingUtils.applySettingsToDisplay(display, displaySettings)");
            appendLine(sb, "        }");
        }
        appendLine(sb, "        def readServer = ChannelDisplayTransformServer.createColorTransformServer(");
        appendLine(sb, "                baseServer, display.selectedChannels())");
    }

    // ------------------------------------------------------------------
    // Color scale bar helpers
    // ------------------------------------------------------------------

    /**
     * Emit color scale bar configuration variables.
     */
    private static void emitColorScaleBarConfig(StringBuilder sb, RenderedExportConfig config) {
        appendLine(sb, "def showColorScaleBar = " + config.colorScaleBar().show());
        appendLine(sb, "def colorScaleBarPosition = " + quote(config.colorScaleBar().position().name()));
        appendLine(sb, "def colorScaleBarFontSize = " + config.colorScaleBar().fontSize());
        appendLine(sb, "def colorScaleBarBoldText = " + config.colorScaleBar().bold());
    }

    /**
     * Emit a self-contained drawColorScaleBar Groovy function.
     * Draws a vertical gradient bar with tick labels using the colorMap.
     */
    private static void emitColorScaleBarFunction(StringBuilder sb) {
        appendLine(sb, "// Color scale bar drawing function");
        appendLine(sb, "def drawColorScaleBar(Graphics2D g2d, int imgW, int imgH, colorMap, double minVal, double maxVal, String pos, int fSize, boolean bold) {");
        appendLine(sb, "    if (imgW <= 0 || imgH <= 0) return");
        appendLine(sb, "    int minDim = Math.min(imgW, imgH)");
        appendLine(sb, "    int fontSize = fSize > 0 ? Math.max(4, Math.min(fSize, 200)) : Math.max(12, minDim / 50)");
        appendLine(sb, "    int margin = Math.max(10, minDim / 40)");
        appendLine(sb, "    int barH = Math.min(imgH / 4, Math.max(60, imgH / 4))");
        appendLine(sb, "    int barW = Math.max(10, barH / 6)");
        appendLine(sb, "    int fontStyle = bold ? Font.BOLD : Font.PLAIN");
        appendLine(sb, "    g2d.setFont(new Font(Font.SANS_SERIF, fontStyle, fontSize))");
        appendLine(sb, "    def fm = g2d.getFontMetrics()");
        appendLine(sb, "    int tickCount = 5");
        appendLine(sb, "    def tickLabels = []");
        appendLine(sb, "    int maxLabelW = 0");
        appendLine(sb, "    for (int i = 0; i < tickCount; i++) {");
        appendLine(sb, "        double t = (double) i / (tickCount - 1)");
        appendLine(sb, "        double v = minVal + t * (maxVal - minVal)");
        appendLine(sb, "        String lbl = (v == 0) ? '0' : (Math.abs(v) >= 1 && v == Math.floor(v) ? String.format('%d', (long)v) : (Math.abs(v) < 0.01 ? String.format('%.1e', v) : String.format('%.2f', v)))");
        appendLine(sb, "        tickLabels.add(lbl)");
        appendLine(sb, "        maxLabelW = Math.max(maxLabelW, fm.stringWidth(lbl))");
        appendLine(sb, "    }");
        appendLine(sb, "    int tickLen = 4");
        appendLine(sb, "    int labelGap = 4");
        appendLine(sb, "    int totalW = barW + tickLen + labelGap + maxLabelW");
        appendLine(sb, "    int th = fm.getAscent()");
        appendLine(sb, "    int bx, by");
        appendLine(sb, "    switch (pos) {");
        appendLine(sb, "        case 'LOWER_LEFT': bx = margin; by = imgH - margin - barH; break");
        appendLine(sb, "        case 'UPPER_RIGHT': bx = imgW - margin - totalW; by = margin; break");
        appendLine(sb, "        case 'UPPER_LEFT': bx = margin; by = margin; break");
        appendLine(sb, "        default: bx = imgW - margin - totalW; by = imgH - margin - barH; break");
        appendLine(sb, "    }");
        appendLine(sb, "    double range = maxVal - minVal");
        appendLine(sb, "    for (int row = 0; row < barH; row++) {");
        appendLine(sb, "        double t = 1.0 - (double) row / Math.max(1, barH - 1)");
        appendLine(sb, "        double v = minVal + t * range");
        appendLine(sb, "        int rgb = colorMap.getColor(v, minVal, maxVal)");
        appendLine(sb, "        g2d.setColor(new Color(rgb))");
        appendLine(sb, "        g2d.fillRect(bx, by + row, barW, 1)");
        appendLine(sb, "    }");
        appendLine(sb, "    g2d.setColor(Color.WHITE)");
        appendLine(sb, "    g2d.drawRect(bx - 1, by - 1, barW + 1, barH + 1)");
        appendLine(sb, "    for (int i = 0; i < tickCount; i++) {");
        appendLine(sb, "        double t = (double) i / (tickCount - 1)");
        appendLine(sb, "        int ty = by + barH - 1 - (int) Math.round(t * (barH - 1))");
        appendLine(sb, "        g2d.setColor(Color.WHITE)");
        appendLine(sb, "        g2d.drawLine(bx + barW, ty, bx + barW + tickLen, ty)");
        appendLine(sb, "        int lx = bx + barW + tickLen + labelGap");
        appendLine(sb, "        int ly = Math.max(by + th, Math.min(ty + th / 2 - 1, by + barH))");
        appendLine(sb, "        def lbl = tickLabels[i]");
        appendLine(sb, "        g2d.setColor(Color.BLACK)");
        appendLine(sb, "        for (int dx = -1; dx <= 1; dx++) {");
        appendLine(sb, "            for (int dy = -1; dy <= 1; dy++) {");
        appendLine(sb, "                if (dx != 0 || dy != 0) g2d.drawString(lbl, lx + dx, ly + dy)");
        appendLine(sb, "            }");
        appendLine(sb, "        }");
        appendLine(sb, "        g2d.setColor(Color.WHITE)");
        appendLine(sb, "        g2d.drawString(lbl, lx, ly)");
        appendLine(sb, "    }");
        appendLine(sb, "}");
        appendLine(sb, "");
    }

    /**
     * Emit per-image color scale bar drawing code.
     */
    private static void emitColorScaleBarDrawing(StringBuilder sb) {
        appendLine(sb, "        if (showColorScaleBar) {");
        appendLine(sb, "            g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f))");
        appendLine(sb, "            drawColorScaleBar(g2d, outW, outH, colorMap, densityMin, densityMax, colorScaleBarPosition, colorScaleBarFontSize, colorScaleBarBoldText)");
        appendLine(sb, "        }");
    }

    /**
     * Emit a self-contained colorizeDensityMap Groovy function.
     */
    private static void emitColorizeDensityMapFunction(StringBuilder sb) {
        appendLine(sb, "// Colorize density map using color map");
        appendLine(sb, "def colorizeDensityMap(BufferedImage densityImg, colorMap, double minV, double maxV) {");
        appendLine(sb, "    int w = densityImg.getWidth()");
        appendLine(sb, "    int h = densityImg.getHeight()");
        appendLine(sb, "    def raster = densityImg.getRaster()");
        appendLine(sb, "    def colorized = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)");
        appendLine(sb, "    for (int y = 0; y < h; y++) {");
        appendLine(sb, "        for (int x = 0; x < w; x++) {");
        appendLine(sb, "            double val = raster.getSampleDouble(x, y, 0)");
        appendLine(sb, "            if (Double.isNaN(val)) {");
        appendLine(sb, "                colorized.setRGB(x, y, 0x00000000)");
        appendLine(sb, "            } else {");
        appendLine(sb, "                int rgb = colorMap.getColor(val, minV, maxV)");
        appendLine(sb, "                colorized.setRGB(x, y, 0xFF000000 | (rgb & 0x00FFFFFF))");
        appendLine(sb, "            }");
        appendLine(sb, "        }");
        appendLine(sb, "    }");
        appendLine(sb, "    return colorized");
        appendLine(sb, "}");
        appendLine(sb, "");
    }

    // ------------------------------------------------------------------
    // Panel label helpers
    // ------------------------------------------------------------------

    /**
     * Emit panel label configuration variables.
     */
    private static void emitPanelLabelConfig(StringBuilder sb, RenderedExportConfig config) {
        appendLine(sb, "def showPanelLabel = " + config.panelLabel().show());
        String text = config.panelLabel().text();
        appendLine(sb, "def panelLabelText = " + (text != null && !text.isBlank() ? quote(text) : "null"));
        appendLine(sb, "def panelLabelPosition = " + quote(config.panelLabel().position().name()));
        appendLine(sb, "def panelLabelFontSize = " + config.panelLabel().fontSize());
        appendLine(sb, "def panelLabelBold = " + config.panelLabel().bold());
    }

    /**
     * Emit a self-contained drawPanelLabel Groovy function.
     * Uses the same 8-direction outlined text technique as the scale bar.
     */
    private static void emitPanelLabelFunction(StringBuilder sb) {
        appendLine(sb, "// Panel label drawing function");
        appendLine(sb, "def drawPanelLabel(Graphics2D g2d, int imgW, int imgH, String label, String pos, int fSize, boolean bold) {");
        appendLine(sb, "    if (label == null || label.isEmpty()) return");
        appendLine(sb, "    int minDim = Math.min(imgW, imgH)");
        appendLine(sb, "    int fontSize = fSize > 0 ? Math.max(4, Math.min(fSize, 200)) : Math.max(14, minDim / 25)");
        appendLine(sb, "    int margin = Math.max(10, minDim / 40)");
        appendLine(sb, "    int fontStyle = bold ? Font.BOLD : Font.PLAIN");
        appendLine(sb, "    g2d.setFont(new Font(Font.SANS_SERIF, fontStyle, fontSize))");
        appendLine(sb, "    def fm = g2d.getFontMetrics()");
        appendLine(sb, "    int tw = fm.stringWidth(label)");
        appendLine(sb, "    int ta = fm.getAscent()");
        appendLine(sb, "    int tx, ty");
        appendLine(sb, "    switch (pos) {");
        appendLine(sb, "        case 'LOWER_LEFT': tx = margin; ty = imgH - margin; break");
        appendLine(sb, "        case 'UPPER_RIGHT': tx = imgW - margin - tw; ty = margin + ta; break");
        appendLine(sb, "        case 'UPPER_LEFT': tx = margin; ty = margin + ta; break");
        appendLine(sb, "        default: tx = imgW - margin - tw; ty = imgH - margin; break");
        appendLine(sb, "    }");
        appendLine(sb, "    def primary = Color.WHITE");
        appendLine(sb, "    def outline = Color.BLACK");
        appendLine(sb, "    for (int dx = -1; dx <= 1; dx++) {");
        appendLine(sb, "        for (int dy = -1; dy <= 1; dy++) {");
        appendLine(sb, "            if (dx != 0 || dy != 0) { g2d.setColor(outline); g2d.drawString(label, tx + dx, ty + dy) }");
        appendLine(sb, "        }");
        appendLine(sb, "    }");
        appendLine(sb, "    g2d.setColor(primary)");
        appendLine(sb, "    g2d.drawString(label, tx, ty)");
        appendLine(sb, "}");
        appendLine(sb, "");
    }

    /**
     * Emit per-image panel label drawing code (whole-image path).
     * Uses variable 'i' from the for loop as the auto-increment index.
     */
    private static void emitPanelLabelDrawing(StringBuilder sb) {
        appendLine(sb, "        if (showPanelLabel) {");
        appendLine(sb, "            def label = panelLabelText != null ? panelLabelText : String.valueOf((char)('A' + (i % 26)))");
        appendLine(sb, "            g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f))");
        appendLine(sb, "            drawPanelLabel(g2d, outW, outH, label, panelLabelPosition, panelLabelFontSize, panelLabelBold)");
        appendLine(sb, "        }");
    }

    /**
     * Emit per-annotation panel label drawing code.
     * Uses a running 'annotationIndex' counter for auto-increment.
     */
    private static void emitAnnotationPanelLabelDrawing(StringBuilder sb) {
        appendLine(sb, "            if (showPanelLabel) {");
        appendLine(sb, "                def label = panelLabelText != null ? panelLabelText : String.valueOf((char)('A' + (annotationIndex % 26)))");
        appendLine(sb, "                g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f))");
        appendLine(sb, "                drawPanelLabel(g2d, outW, outH, label, panelLabelPosition, panelLabelFontSize, panelLabelBold)");
        appendLine(sb, "                annotationIndex++");
        appendLine(sb, "            }");
    }

    // ------------------------------------------------------------------
    // Info label helpers
    // ------------------------------------------------------------------

    /**
     * Emit info label configuration variables.
     */
    private static void emitInfoLabelConfig(StringBuilder sb, RenderedExportConfig config) {
        appendLine(sb, "def showInfoLabel = " + config.infoLabel().show());
        String template = config.infoLabel().text();
        appendLine(sb, "def infoLabelTemplate = " + (template != null && !template.isBlank() ? quote(template) : "null"));
        appendLine(sb, "def infoLabelPosition = " + quote(config.infoLabel().position().name()));
        appendLine(sb, "def infoLabelFontSize = " + config.infoLabel().fontSize());
        appendLine(sb, "def infoLabelBold = " + config.infoLabel().bold());
    }

    /**
     * Emit a self-contained drawInfoLabel Groovy function.
     * Uses the same 8-direction outlined text technique but smaller default size.
     */
    private static void emitInfoLabelFunction(StringBuilder sb) {
        appendLine(sb, "// Info label drawing function");
        appendLine(sb, "def drawInfoLabel(Graphics2D g2d, int imgW, int imgH, String label, String pos, int fSize, boolean bold) {");
        appendLine(sb, "    if (label == null || label.isEmpty()) return");
        appendLine(sb, "    int minDim = Math.min(imgW, imgH)");
        appendLine(sb, "    int fontSize = fSize > 0 ? Math.max(4, Math.min(fSize, 200)) : Math.max(12, minDim / 40)");
        appendLine(sb, "    int margin = Math.max(10, minDim / 40)");
        appendLine(sb, "    int fontStyle = bold ? Font.BOLD : Font.PLAIN");
        appendLine(sb, "    g2d.setFont(new Font(Font.SANS_SERIF, fontStyle, fontSize))");
        appendLine(sb, "    def fm = g2d.getFontMetrics()");
        appendLine(sb, "    int tw = fm.stringWidth(label)");
        appendLine(sb, "    int ta = fm.getAscent()");
        appendLine(sb, "    int tx, ty");
        appendLine(sb, "    switch (pos) {");
        appendLine(sb, "        case 'LOWER_LEFT': tx = margin; ty = imgH - margin; break");
        appendLine(sb, "        case 'UPPER_RIGHT': tx = imgW - margin - tw; ty = margin + ta; break");
        appendLine(sb, "        case 'UPPER_LEFT': tx = margin; ty = margin + ta; break");
        appendLine(sb, "        default: tx = imgW - margin - tw; ty = imgH - margin; break");
        appendLine(sb, "    }");
        appendLine(sb, "    def primary = Color.WHITE");
        appendLine(sb, "    def outline = Color.BLACK");
        appendLine(sb, "    for (int dx = -1; dx <= 1; dx++) {");
        appendLine(sb, "        for (int dy = -1; dy <= 1; dy++) {");
        appendLine(sb, "            if (dx != 0 || dy != 0) { g2d.setColor(outline); g2d.drawString(label, tx + dx, ty + dy) }");
        appendLine(sb, "        }");
        appendLine(sb, "    }");
        appendLine(sb, "    g2d.setColor(primary)");
        appendLine(sb, "    g2d.drawString(label, tx, ty)");
        appendLine(sb, "}");
        appendLine(sb, "");
    }

    /**
     * Emit per-image info label template resolution and drawing code.
     * Resolves template placeholders ({imageName}, {pixelSize}, etc.) from imageData.
     */
    private static void emitInfoLabelDrawing(StringBuilder sb) {
        appendLine(sb, "        if (showInfoLabel && infoLabelTemplate != null) {");
        appendLine(sb, "            def infoText = infoLabelTemplate");
        appendLine(sb, "            infoText = infoText.replace('{imageName}', entryName ?: '')");
        appendLine(sb, "            def cal = imageData.getServer().getPixelCalibration()");
        appendLine(sb, "            def pxStr = cal.hasPixelSizeMicrons() ? String.format('%.3f um/px', cal.getAveragedPixelSizeMicrons()) : 'uncalibrated'");
        appendLine(sb, "            infoText = infoText.replace('{pixelSize}', pxStr)");
        appendLine(sb, "            infoText = infoText.replace('{date}', java.time.LocalDate.now().toString())");
        appendLine(sb, "            infoText = infoText.replace('{time}', java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern('HH:mm')))");
        appendLine(sb, "            infoText = infoText.replace('{width}', String.valueOf(imageData.getServer().getWidth()))");
        appendLine(sb, "            infoText = infoText.replace('{height}', String.valueOf(imageData.getServer().getHeight()))");
        appendLine(sb, "            infoText = infoText.replace('{classifier}', classifierName ?: '')");
        appendLine(sb, "            g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f))");
        appendLine(sb, "            drawInfoLabel(g2d, outW, outH, infoText, infoLabelPosition, infoLabelFontSize, infoLabelBold)");
        appendLine(sb, "        }");
    }

    /**
     * Emit per-image info label drawing for object overlay scripts (no classifierName variable).
     */
    private static void emitInfoLabelDrawingNoClassifier(StringBuilder sb) {
        appendLine(sb, "        if (showInfoLabel && infoLabelTemplate != null) {");
        appendLine(sb, "            def infoText = infoLabelTemplate");
        appendLine(sb, "            infoText = infoText.replace('{imageName}', entryName ?: '')");
        appendLine(sb, "            def cal = imageData.getServer().getPixelCalibration()");
        appendLine(sb, "            def pxStr = cal.hasPixelSizeMicrons() ? String.format('%.3f um/px', cal.getAveragedPixelSizeMicrons()) : 'uncalibrated'");
        appendLine(sb, "            infoText = infoText.replace('{pixelSize}', pxStr)");
        appendLine(sb, "            infoText = infoText.replace('{date}', java.time.LocalDate.now().toString())");
        appendLine(sb, "            infoText = infoText.replace('{time}', java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern('HH:mm')))");
        appendLine(sb, "            infoText = infoText.replace('{width}', String.valueOf(imageData.getServer().getWidth()))");
        appendLine(sb, "            infoText = infoText.replace('{height}', String.valueOf(imageData.getServer().getHeight()))");
        appendLine(sb, "            infoText = infoText.replace('{classifier}', '')");
        appendLine(sb, "            g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f))");
        appendLine(sb, "            drawInfoLabel(g2d, outW, outH, infoText, infoLabelPosition, infoLabelFontSize, infoLabelBold)");
        appendLine(sb, "        }");
    }

    /**
     * Emit per-annotation info label drawing code.
     */
    private static void emitAnnotationInfoLabelDrawing(StringBuilder sb, boolean hasClassifier) {
        appendLine(sb, "            if (showInfoLabel && infoLabelTemplate != null) {");
        appendLine(sb, "                def infoText = infoLabelTemplate");
        appendLine(sb, "                infoText = infoText.replace('{imageName}', entryName ?: '')");
        appendLine(sb, "                def cal = imageData.getServer().getPixelCalibration()");
        appendLine(sb, "                def pxStr = cal.hasPixelSizeMicrons() ? String.format('%.3f um/px', cal.getAveragedPixelSizeMicrons()) : 'uncalibrated'");
        appendLine(sb, "                infoText = infoText.replace('{pixelSize}', pxStr)");
        appendLine(sb, "                infoText = infoText.replace('{date}', java.time.LocalDate.now().toString())");
        appendLine(sb, "                infoText = infoText.replace('{time}', java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern('HH:mm')))");
        appendLine(sb, "                infoText = infoText.replace('{width}', String.valueOf(imageData.getServer().getWidth()))");
        appendLine(sb, "                infoText = infoText.replace('{height}', String.valueOf(imageData.getServer().getHeight()))");
        appendLine(sb, "                infoText = infoText.replace('{classifier}', " + (hasClassifier ? "classifierName ?: ''" : "''") + ")");
        appendLine(sb, "                g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f))");
        appendLine(sb, "                drawInfoLabel(g2d, outW, outH, infoText, infoLabelPosition, infoLabelFontSize, infoLabelBold)");
        appendLine(sb, "            }");
    }

    // ------------------------------------------------------------------
    // DPI / downsample helpers
    // ------------------------------------------------------------------

    /**
     * Emit DPI configuration and per-image downsample resolution.
     * When targetDpi > 0, computes downsample from pixel calibration each image.
     */
    private static void emitDpiConfig(StringBuilder sb, RenderedExportConfig config) {
        int dpi = config.getTargetDpi();
        appendLine(sb, "def targetDpi = " + dpi);
    }

    /**
     * Emit per-image DPI-based downsample resolution code.
     * Must be placed inside the per-image try block, after imageData/baseServer are set.
     * Overrides the `downsample` variable if DPI is configured and pixel size is available.
     */
    private static void emitPerImageDpiResolution(StringBuilder sb) {
        appendLine(sb, "        if (targetDpi > 0) {");
        appendLine(sb, "            def cal = imageData.getServer().getPixelCalibration()");
        appendLine(sb, "            if (cal.hasPixelSizeMicrons()) {");
        appendLine(sb, "                double targetPixelSize = 25400.0 / targetDpi");
        appendLine(sb, "                downsample = Math.max(1.0, targetPixelSize / cal.getAveragedPixelSizeMicrons())");
        appendLine(sb, "                println \"  DPI ${targetDpi} -> effective downsample: ${String.format('%.2f', downsample)}\"");
        appendLine(sb, "            } else {");
        appendLine(sb, "                println \"  WARNING: No pixel calibration -- using manual downsample ${downsample}\"");
        appendLine(sb, "            }");
        appendLine(sb, "        }");
    }

    // ------------------------------------------------------------------
    // Inset/zoom helpers
    // ------------------------------------------------------------------

    /**
     * Emit inset configuration variables.
     */
    private static void emitInsetConfig(StringBuilder sb, RenderedExportConfig config) {
        appendLine(sb, "def showInset = " + config.inset().show());
        appendLine(sb, "def insetSourceX = " + config.inset().sourceX());
        appendLine(sb, "def insetSourceY = " + config.inset().sourceY());
        appendLine(sb, "def insetSourceW = " + config.inset().sourceW());
        appendLine(sb, "def insetSourceH = " + config.inset().sourceH());
        appendLine(sb, "def insetMagnification = " + config.inset().magnification());
        appendLine(sb, "def insetPosition = " + quote(config.inset().position().name()));
        java.awt.Color frameColor = config.inset().frameColorAsAwt();
        appendLine(sb, "def insetFrameR = " + frameColor.getRed());
        appendLine(sb, "def insetFrameG = " + frameColor.getGreen());
        appendLine(sb, "def insetFrameB = " + frameColor.getBlue());
        appendLine(sb, "def insetFrameWidth = " + config.inset().frameWidth());
        appendLine(sb, "def insetConnectingLines = " + config.inset().connectingLines());
    }

    /**
     * Emit inset-specific imports (BasicStroke).
     */
    private static void emitInsetImports(StringBuilder sb) {
        appendLine(sb, "import java.awt.BasicStroke");
    }

    /**
     * Emit a self-contained drawInset Groovy function.
     */
    private static void emitInsetFunction(StringBuilder sb) {
        appendLine(sb, "// Inset/zoom panel drawing function");
        appendLine(sb, "def drawInset(BufferedImage fullImage, Graphics2D g2d, double srcX, double srcY, double srcW, double srcH, int mag, String pos, int fR, int fG, int fB, int fWidth, boolean lines) {");
        appendLine(sb, "    int imgW = fullImage.getWidth()");
        appendLine(sb, "    int imgH = fullImage.getHeight()");
        appendLine(sb, "    if (imgW <= 0 || imgH <= 0) return");
        appendLine(sb, "    int sx = (int) Math.round(srcX * imgW)");
        appendLine(sb, "    int sy = (int) Math.round(srcY * imgH)");
        appendLine(sb, "    int sw = (int) Math.round(srcW * imgW)");
        appendLine(sb, "    int sh = (int) Math.round(srcH * imgH)");
        appendLine(sb, "    sx = Math.max(0, Math.min(sx, imgW - 1))");
        appendLine(sb, "    sy = Math.max(0, Math.min(sy, imgH - 1))");
        appendLine(sb, "    sw = Math.max(1, Math.min(sw, imgW - sx))");
        appendLine(sb, "    sh = Math.max(1, Math.min(sh, imgH - sy))");
        appendLine(sb, "    if (sw < 10 || sh < 10) return");
        appendLine(sb, "    mag = Math.max(2, Math.min(mag, 16))");
        appendLine(sb, "    int inW = sw * mag; int inH = sh * mag");
        appendLine(sb, "    if (inW > 2048 || inH > 2048) { mag = Math.max(2, 2048 / Math.max(sw, sh)); inW = sw * mag; inH = sh * mag }");
        appendLine(sb, "    if (inW > imgW / 2 || inH > imgH / 2) { mag = Math.max(2, Math.min(imgW / 2, imgH / 2) / Math.max(sw, sh)); inW = sw * mag; inH = sh * mag }");
        appendLine(sb, "    if (inW < 10 || inH < 10) return");
        appendLine(sb, "    def cropped = fullImage.getSubimage(sx, sy, sw, sh)");
        appendLine(sb, "    def magnified = new BufferedImage(inW, inH, BufferedImage.TYPE_INT_RGB)");
        appendLine(sb, "    def mg = magnified.createGraphics()");
        appendLine(sb, "    mg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)");
        appendLine(sb, "    mg.drawImage(cropped, 0, 0, inW, inH, null); mg.dispose()");
        appendLine(sb, "    int minDim = Math.min(imgW, imgH)");
        appendLine(sb, "    int effectiveFW = fWidth > 0 ? fWidth : Math.max(2, minDim / 300)");
        appendLine(sb, "    int margin = Math.max(10, minDim / 40)");
        appendLine(sb, "    def color = new Color(fR, fG, fB)");
        appendLine(sb, "    g2d.setColor(color)");
        appendLine(sb, "    g2d.setStroke(new BasicStroke(effectiveFW))");
        appendLine(sb, "    g2d.drawRect(sx, sy, sw, sh)");
        appendLine(sb, "    int ix, iy");
        appendLine(sb, "    switch (pos) {");
        appendLine(sb, "        case 'LOWER_LEFT': ix = margin; iy = imgH - margin - inH; break");
        appendLine(sb, "        case 'UPPER_LEFT': ix = margin; iy = margin; break");
        appendLine(sb, "        case 'LOWER_RIGHT': ix = imgW - margin - inW; iy = imgH - margin - inH; break");
        appendLine(sb, "        default: ix = imgW - margin - inW; iy = margin; break");
        appendLine(sb, "    }");
        appendLine(sb, "    g2d.drawImage(magnified, ix, iy, null)");
        appendLine(sb, "    g2d.drawRect(ix, iy, inW, inH)");
        appendLine(sb, "    if (lines) {");
        appendLine(sb, "        float dashLen = Math.max(4, minDim / 100.0f)");
        appendLine(sb, "        g2d.setStroke(new BasicStroke(Math.max(1, effectiveFW / 2), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, [dashLen, dashLen] as float[], 0.0f))");
        appendLine(sb, "        int scx = sx + sw / 2; int scy = sy + sh / 2");
        appendLine(sb, "        int icx = ix + inW / 2; int icy = iy + inH / 2");
        appendLine(sb, "        if (icx > scx) {");
        appendLine(sb, "            if (icy > scy) { g2d.drawLine(sx + sw, sy + sh, ix, iy); g2d.drawLine(sx + sw, sy, ix, iy) }");
        appendLine(sb, "            else { g2d.drawLine(sx + sw, sy, ix, iy + inH); g2d.drawLine(sx + sw, sy + sh, ix, iy + inH) }");
        appendLine(sb, "        } else {");
        appendLine(sb, "            if (icy > scy) { g2d.drawLine(sx, sy + sh, ix + inW, iy); g2d.drawLine(sx, sy, ix + inW, iy) }");
        appendLine(sb, "            else { g2d.drawLine(sx, sy, ix + inW, iy + inH); g2d.drawLine(sx, sy + sh, ix + inW, iy + inH) }");
        appendLine(sb, "        }");
        appendLine(sb, "    }");
        appendLine(sb, "}");
        appendLine(sb, "");
    }

    /**
     * Emit per-image inset drawing code (after g2d overlays, using result BufferedImage).
     */
    private static void emitInsetDrawing(StringBuilder sb) {
        appendLine(sb, "        if (showInset) {");
        appendLine(sb, "            g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f))");
        appendLine(sb, "            drawInset(result, g2d, insetSourceX, insetSourceY, insetSourceW, insetSourceH, insetMagnification, insetPosition, insetFrameR, insetFrameG, insetFrameB, insetFrameWidth, insetConnectingLines)");
        appendLine(sb, "        }");
    }

    /**
     * Emit per-annotation inset drawing code.
     */
    private static void emitAnnotationInsetDrawing(StringBuilder sb) {
        appendLine(sb, "            if (showInset) {");
        appendLine(sb, "                g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f))");
        appendLine(sb, "                drawInset(result, g2d, insetSourceX, insetSourceY, insetSourceW, insetSourceH, insetMagnification, insetPosition, insetFrameR, insetFrameG, insetFrameB, insetFrameWidth, insetConnectingLines)");
        appendLine(sb, "            }");
    }

    // ------------------------------------------------------------------
    // Per-annotation region helpers
    // ------------------------------------------------------------------

    private static boolean isPerAnnotation(RenderedExportConfig config) {
        return config.getRegionType() == RenderedExportConfig.RegionType.ALL_ANNOTATIONS;
    }

    /**
     * Emit region type and per-annotation configuration variables.
     */
    private static void emitRegionTypeConfig(StringBuilder sb, RenderedExportConfig config) {
        appendLine(sb, "def regionType = " + quote(config.getRegionType().name()));
        if (isPerAnnotation(config)) {
            appendLine(sb, "def paddingPixels = " + config.getPaddingPixels());
            var classes = config.getSelectedClassifications();
            if (classes != null) {
                sb.append("def selectedClassifications = [");
                for (int i = 0; i < classes.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(quote(classes.get(i)));
                }
                sb.append("]\n");
            } else {
                appendLine(sb, "def selectedClassifications = null");
            }
        }
    }

    /**
     * Emit per-annotation import statements.
     */
    private static void emitPerAnnotationImports(StringBuilder sb) {
        appendLine(sb, "import qupath.lib.common.GeneralTools");
        appendLine(sb, "import qupath.lib.objects.classes.PathClass");
    }

    /**
     * Emit the annotation filtering + loop open code.
     * After this, the script is inside a loop with variables: annotation, x, y, w, h, suffix.
     */
    private static void emitAnnotationLoopOpen(StringBuilder sb) {
        appendLine(sb, "        int annotationIndex = 0");
        appendLine(sb, "        // Collect and filter annotations");
        appendLine(sb, "        def allAnnotations = imageData.getHierarchy().getAnnotationObjects()");
        appendLine(sb, "        def annotations = allAnnotations");
        appendLine(sb, "        if (selectedClassifications != null) {");
        appendLine(sb, "            def classSet = selectedClassifications.toSet()");
        appendLine(sb, "            annotations = allAnnotations.findAll { a ->");
        appendLine(sb, "                def pc = a.getPathClass()");
        appendLine(sb, "                def name = (pc == null || pc == PathClass.NULL_CLASS) ? 'Unclassified' : pc.toString()");
        appendLine(sb, "                classSet.contains(name)");
        appendLine(sb, "            }");
        appendLine(sb, "        }");
        appendLine(sb, "        if (annotations.isEmpty()) {");
        appendLine(sb, "            println \"  SKIP: No matching annotations\"");
        appendLine(sb, "            baseServer.close()");
        appendLine(sb, "            continue");
        appendLine(sb, "        }");
        appendLine(sb, "");
        appendLine(sb, "        def classCounters = [:]");
        appendLine(sb, "        for (annotation in annotations) {");
        appendLine(sb, "            def roi = annotation.getROI()");
        appendLine(sb, "            if (roi == null) continue");
        appendLine(sb, "            int ax = (int) roi.getBoundsX()");
        appendLine(sb, "            int ay = (int) roi.getBoundsY()");
        appendLine(sb, "            int aw = (int) Math.ceil(roi.getBoundsWidth())");
        appendLine(sb, "            int ah = (int) Math.ceil(roi.getBoundsHeight())");
        appendLine(sb, "            if (paddingPixels > 0) { ax -= paddingPixels; ay -= paddingPixels; aw += 2 * paddingPixels; ah += 2 * paddingPixels }");
        appendLine(sb, "            ax = Math.max(0, ax); ay = Math.max(0, ay)");
        appendLine(sb, "            aw = Math.min(aw, baseServer.getWidth() - ax)");
        appendLine(sb, "            ah = Math.min(ah, baseServer.getHeight() - ay)");
        appendLine(sb, "            if (aw <= 0 || ah <= 0) continue");
        appendLine(sb, "");
        appendLine(sb, "            def pc = annotation.getPathClass()");
        appendLine(sb, "            def className = (pc == null || pc == PathClass.NULL_CLASS) ? 'Unclassified' : pc.toString()");
        appendLine(sb, "            def safeName = GeneralTools.stripInvalidFilenameChars(className)");
        appendLine(sb, "            if (safeName == null || safeName.isBlank()) safeName = 'Unknown'");
        appendLine(sb, "            def idx = classCounters.getOrDefault(safeName, 0)");
        appendLine(sb, "            classCounters[safeName] = idx + 1");
        appendLine(sb, "            def suffix = '_' + safeName + '_' + idx");
        appendLine(sb, "");
    }

    /**
     * Emit per-annotation region read code. Sets up outW, outH, request, baseImage
     * using ax, ay, aw, ah variables from the annotation loop.
     */
    private static void emitAnnotationRegionRead(StringBuilder sb, DisplaySettingsMode displayMode) {
        appendLine(sb, "            int outW = (int) Math.ceil(aw / downsample)");
        appendLine(sb, "            int outH = (int) Math.ceil(ah / downsample)");
        appendLine(sb, "            def request = RegionRequest.createInstance(");
        appendLine(sb, "                    readServer.getPath(), downsample, ax, ay, aw, ah)");
        appendLine(sb, "            def baseImage = readServer.readRegion(request)");
        appendLine(sb, "");
    }

    /**
     * Emit per-annotation object overlay painting with region offset.
     */
    private static void emitAnnotationObjectOverlay(StringBuilder sb) {
        appendLine(sb, "            if (includeAnnotations || includeDetections) {");
        appendLine(sb, "                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f))");
        appendLine(sb, "                def overlayOptions = new OverlayOptions()");
        appendLine(sb, "                overlayOptions.setShowAnnotations(includeAnnotations)");
        appendLine(sb, "                overlayOptions.setShowDetections(includeDetections)");
        appendLine(sb, "                overlayOptions.setFillAnnotations(fillAnnotations)");
        appendLine(sb, "                overlayOptions.setShowNames(showNames)");
        appendLine(sb, "                def hierOverlay = new HierarchyOverlay(null, overlayOptions, imageData)");
        appendLine(sb, "                def gCopy = (Graphics2D) g2d.create()");
        appendLine(sb, "                gCopy.scale(1.0 / downsample, 1.0 / downsample)");
        appendLine(sb, "                gCopy.translate(-ax, -ay)");
        appendLine(sb, "                def region = qupath.lib.regions.ImageRegion.createInstance(ax, ay, aw, ah, 0, 0)");
        appendLine(sb, "                hierOverlay.paintOverlay(gCopy, region, downsample, imageData, true)");
        appendLine(sb, "                gCopy.dispose()");
        appendLine(sb, "            }");
    }

    /**
     * Emit per-annotation scale bar drawing (uses outW, outH).
     */
    private static void emitAnnotationScaleBarDrawing(StringBuilder sb) {
        appendLine(sb, "            if (showScaleBar) {");
        appendLine(sb, "                def cal = imageData.getServer().getPixelCalibration()");
        appendLine(sb, "                if (cal.hasPixelSizeMicrons()) {");
        appendLine(sb, "                    double pxSize = cal.getAveragedPixelSizeMicrons() * downsample");
        appendLine(sb, "                    g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f))");
        appendLine(sb, "                    drawScaleBar(g2d, outW, outH, pxSize, scaleBarPosition, scaleBarColorR, scaleBarColorG, scaleBarColorB, scaleBarFontSize, scaleBarBoldText)");
        appendLine(sb, "                }");
        appendLine(sb, "            }");
    }

    /**
     * Emit per-annotation file writing, optional split-channel loop, and loop close.
     */
    private static void emitAnnotationLoopClose(StringBuilder sb, RenderedExportConfig config) {
        String fileLabel = config.splitChannel().enabled() ? "_merge" : "";
        appendLine(sb, "            g2d.dispose()");
        appendLine(sb, "");
        appendLine(sb, "            def sanitized = entryName.replaceAll('[^a-zA-Z0-9._\\\\-]', '_')");
        appendLine(sb, "            def outputPath = new File(outDir, sanitized + suffix + '" + fileLabel + ".' + outputFormat).getAbsolutePath()");
        appendLine(sb, "            ImageWriterTools.writeImage(result, outputPath)");
        appendLine(sb, "            println \"  OK: ${outputPath}\"");
        if (config.splitChannel().enabled() && config.getDisplaySettingsMode() != DisplaySettingsMode.RAW) {
            emitSplitChannelAnnotationLoop(sb);
        }
        appendLine(sb, "        } // end annotation loop");
        appendLine(sb, "        succeeded++");
    }

    // ------------------------------------------------------------------
    // Split-channel helpers
    // ------------------------------------------------------------------

    /**
     * Emit split-channel configuration variables.
     */
    private static void emitSplitChannelConfig(StringBuilder sb, RenderedExportConfig config) {
        if (!config.splitChannel().enabled()) return;
        appendLine(sb, "def splitChannels = true");
        appendLine(sb, "def splitChannelsGrayscale = " + config.splitChannel().grayscale());
        appendLine(sb, "def splitChannelColorBorder = " + config.splitChannel().colorBorder());
        appendLine(sb, "def channelColorLegend = " + config.splitChannel().colorLegend());
    }

    /**
     * Emit a config-record block for split-stains (color deconvolution).
     * Re-run via the QuIET wizard -- the wizard wires the
     * {@code TransformedServerBuilder.deconvolveStains} path; the script is a
     * settings snapshot, not a runnable export.
     */
    static void emitSplitStainsConfig(StringBuilder sb, RenderedExportConfig config) {
        if (!config.splitStains().enabled()) return;
        appendLine(sb, "// ---------- Split stains (color deconvolution) ----------");
        appendLine(sb, "def splitStains = true");
        appendLine(sb, "def splitStainsIncludeResidual = "
                + config.splitStains().includeResidual());
        appendLine(sb, "def splitStainsGrayscale = " + config.splitStains().grayscale());
        appendLine(sb, "def splitStainsColorBorder = " + config.splitStains().colorBorder());
        appendLine(sb, "def splitStainsColorLegend = " + config.splitStains().colorLegend());
        appendLine(sb, "// Re-run via the QuIET wizard (Rendered category) -- the wizard");
        appendLine(sb, "// invokes TransformedServerBuilder.deconvolveStains() per stain.");
    }

    /**
     * Emit additional imports needed for split-channel export.
     */
    private static void emitSplitChannelImports(StringBuilder sb, RenderedExportConfig config) {
        if (!config.splitChannel().enabled()) return;
        appendLine(sb, "import qupath.lib.common.GeneralTools");
        // Color/Font may already be imported for scale bar; duplicate imports are harmless in Groovy
        appendLine(sb, "import java.awt.Color");
        appendLine(sb, "import java.awt.Font");
    }

    /**
     * Emit helper functions for split-channel export (grayscale conversion,
     * color border, color legend).
     */
    private static void emitSplitChannelHelperFunctions(StringBuilder sb, RenderedExportConfig config) {
        if (!config.splitChannel().enabled()) return;

        appendLine(sb, "// Convert image to grayscale");
        appendLine(sb, "def convertToGrayscale(BufferedImage src) {");
        appendLine(sb, "    def gray = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY)");
        appendLine(sb, "    def g = gray.createGraphics()");
        appendLine(sb, "    g.drawImage(src, 0, 0, null)");
        appendLine(sb, "    g.dispose()");
        appendLine(sb, "    return gray");
        appendLine(sb, "}");
        appendLine(sb, "");

        appendLine(sb, "// Draw channel color border around image");
        appendLine(sb, "def drawChannelColorBorder(Graphics2D g2d, int imgW, int imgH, int chColor) {");
        appendLine(sb, "    int bw = Math.max(2, Math.min(imgW, imgH) / 100)");
        appendLine(sb, "    g2d.setColor(new Color(chColor))");
        appendLine(sb, "    g2d.fillRect(0, 0, imgW, bw)");
        appendLine(sb, "    g2d.fillRect(0, imgH - bw, imgW, bw)");
        appendLine(sb, "    g2d.fillRect(0, 0, bw, imgH)");
        appendLine(sb, "    g2d.fillRect(imgW - bw, 0, bw, imgH)");
        appendLine(sb, "}");
        appendLine(sb, "");

        appendLine(sb, "// Draw channel color legend (swatch + name) in upper-left");
        appendLine(sb, "def drawChannelColorLegend(Graphics2D g2d, int imgW, int imgH, String chName, int chColor) {");
        appendLine(sb, "    int minDim = Math.min(imgW, imgH)");
        appendLine(sb, "    int fontSize = Math.max(12, minDim / 30)");
        appendLine(sb, "    int margin = Math.max(8, minDim / 50)");
        appendLine(sb, "    int swatchSize = fontSize");
        appendLine(sb, "    g2d.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize))");
        appendLine(sb, "    def fm = g2d.getFontMetrics()");
        appendLine(sb, "    int textX = margin + swatchSize + 4");
        appendLine(sb, "    int textY = margin + fm.getAscent()");
        appendLine(sb, "    g2d.setColor(new Color(chColor))");
        appendLine(sb, "    g2d.fillRect(margin, margin, swatchSize, swatchSize)");
        appendLine(sb, "    g2d.setColor(Color.WHITE)");
        appendLine(sb, "    g2d.drawRect(margin, margin, swatchSize, swatchSize)");
        appendLine(sb, "    for (int dx = -1; dx <= 1; dx++) {");
        appendLine(sb, "        for (int dy = -1; dy <= 1; dy++) {");
        appendLine(sb, "            if (dx != 0 || dy != 0) { g2d.setColor(Color.BLACK); g2d.drawString(chName, textX + dx, textY + dy) }");
        appendLine(sb, "        }");
        appendLine(sb, "    }");
        appendLine(sb, "    g2d.setColor(Color.WHITE)");
        appendLine(sb, "    g2d.drawString(chName, textX, textY)");
        appendLine(sb, "}");
        appendLine(sb, "");
    }

    /**
     * Emit the per-channel export loop for whole-image split-channel export.
     * Must be called after the merge image has been written.
     * Requires: display, baseServer, downsample, sanitized, outputFormat, outDir in scope.
     */
    private static void emitSplitChannelWholeImageLoop(StringBuilder sb) {
        appendLine(sb, "");
        appendLine(sb, "        // --- Per-channel split export ---");
        appendLine(sb, "        def splitChannelList = display.selectedChannels()");
        appendLine(sb, "        def chMetadata = baseServer.getMetadata().getChannels()");
        appendLine(sb, "        for (int ch = 0; ch < splitChannelList.size(); ch++) {");
        appendLine(sb, "            def singleCh = [splitChannelList[ch]]");
        appendLine(sb, "            def chServer = ChannelDisplayTransformServer.createColorTransformServer(baseServer, singleCh)");
        appendLine(sb, "            def chRequest = RegionRequest.createInstance(chServer.getPath(), downsample, 0, 0, chServer.getWidth(), chServer.getHeight())");
        appendLine(sb, "            def chImg = chServer.readRegion(chRequest)");
        appendLine(sb, "            if (splitChannelsGrayscale) { chImg = convertToGrayscale(chImg) }");
        appendLine(sb, "            def chResult = new BufferedImage(chImg.getWidth(), chImg.getHeight(), BufferedImage.TYPE_INT_RGB)");
        appendLine(sb, "            def chG2d = chResult.createGraphics()");
        appendLine(sb, "            chG2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)");
        appendLine(sb, "            chG2d.drawImage(chImg, 0, 0, null)");
        appendLine(sb, "            def chColor = chMetadata[ch].getColor()");
        appendLine(sb, "            if (splitChannelColorBorder) { drawChannelColorBorder(chG2d, chResult.getWidth(), chResult.getHeight(), chColor) }");
        appendLine(sb, "            if (channelColorLegend) { drawChannelColorLegend(chG2d, chResult.getWidth(), chResult.getHeight(), chMetadata[ch].getName(), chColor) }");
        appendLine(sb, "            chG2d.dispose()");
        appendLine(sb, "            def chName = GeneralTools.stripInvalidFilenameChars(chMetadata[ch].getName())");
        appendLine(sb, "            if (chName == null || chName.isBlank()) chName = 'ch' + ch");
        appendLine(sb, "            def chPath = new File(outDir, sanitized + '_Ch' + (ch + 1) + '_' + chName + '.' + outputFormat).getAbsolutePath()");
        appendLine(sb, "            ImageWriterTools.writeImage(chResult, chPath)");
        appendLine(sb, "            println \"    Channel ${ch + 1}: ${chPath}\"");
        appendLine(sb, "            chServer.close()");
        appendLine(sb, "        }");
    }

    /**
     * Emit the per-channel export loop for per-annotation split-channel export.
     * Must be called inside the annotation loop, after the merge image has been written.
     * Requires: display, baseServer, downsample, sanitized, suffix, outputFormat, outDir,
     * ax, ay, aw, ah in scope.
     */
    private static void emitSplitChannelAnnotationLoop(StringBuilder sb) {
        appendLine(sb, "");
        appendLine(sb, "            // --- Per-channel split export ---");
        appendLine(sb, "            def splitChannelList = display.selectedChannels()");
        appendLine(sb, "            def chMetadata = baseServer.getMetadata().getChannels()");
        appendLine(sb, "            for (int ch = 0; ch < splitChannelList.size(); ch++) {");
        appendLine(sb, "                def singleCh = [splitChannelList[ch]]");
        appendLine(sb, "                def chServer = ChannelDisplayTransformServer.createColorTransformServer(baseServer, singleCh)");
        appendLine(sb, "                def chRequest = RegionRequest.createInstance(chServer.getPath(), downsample, ax, ay, aw, ah)");
        appendLine(sb, "                def chImg = chServer.readRegion(chRequest)");
        appendLine(sb, "                if (splitChannelsGrayscale) { chImg = convertToGrayscale(chImg) }");
        appendLine(sb, "                def chResult = new BufferedImage(chImg.getWidth(), chImg.getHeight(), BufferedImage.TYPE_INT_RGB)");
        appendLine(sb, "                def chG2d = chResult.createGraphics()");
        appendLine(sb, "                chG2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)");
        appendLine(sb, "                chG2d.drawImage(chImg, 0, 0, null)");
        appendLine(sb, "                def chColor = chMetadata[ch].getColor()");
        appendLine(sb, "                if (splitChannelColorBorder) { drawChannelColorBorder(chG2d, chResult.getWidth(), chResult.getHeight(), chColor) }");
        appendLine(sb, "                if (channelColorLegend) { drawChannelColorLegend(chG2d, chResult.getWidth(), chResult.getHeight(), chMetadata[ch].getName(), chColor) }");
        appendLine(sb, "                chG2d.dispose()");
        appendLine(sb, "                def chName = GeneralTools.stripInvalidFilenameChars(chMetadata[ch].getName())");
        appendLine(sb, "                if (chName == null || chName.isBlank()) chName = 'ch' + ch");
        appendLine(sb, "                def chPath = new File(outDir, sanitized + suffix + '_Ch' + (ch + 1) + '_' + chName + '.' + outputFormat).getAbsolutePath()");
        appendLine(sb, "                ImageWriterTools.writeImage(chResult, chPath)");
        appendLine(sb, "                println \"      Channel ${ch + 1}: ${chPath}\"");
        appendLine(sb, "                chServer.close()");
        appendLine(sb, "            }");
    }

    // ------------------------------------------------------------------
    // Density map script
    // ------------------------------------------------------------------

    private static String generateDensityMapScript(RenderedExportConfig config) {
        var sb = new StringBuilder();
        var displayMode = config.getDisplaySettingsMode();
        boolean perAnnotation = isPerAnnotation(config);

        appendLine(sb, "/**");
        appendLine(sb, " * Density Map Overlay Export Script");
        appendLine(sb, " * Generated by QuIET (QuPath Image Export Toolkit)");
        appendLine(sb, " *");
        if (perAnnotation) {
            appendLine(sb, " * Exports per-annotation cropped images with a density map overlay.");
        } else {
            appendLine(sb, " * Exports all project images with a density map overlay");
            appendLine(sb, " * colorized using a LUT, at the specified downsample and opacity.");
        }
        appendLine(sb, " *");
        appendLine(sb, " * Parameters below can be modified before re-running.");
        appendLine(sb, " */");
        appendLine(sb, "");

        // Imports
        appendLine(sb, "import qupath.lib.analysis.heatmaps.DensityMaps");
        appendLine(sb, "import qupath.lib.color.ColorMaps");
        appendLine(sb, "import qupath.lib.gui.viewer.OverlayOptions");
        appendLine(sb, "import qupath.lib.gui.viewer.overlays.HierarchyOverlay");
        appendLine(sb, "import qupath.lib.images.writers.ImageWriterTools");
        appendLine(sb, "import qupath.lib.regions.RegionRequest");
        appendLine(sb, "import java.awt.AlphaComposite");
        appendLine(sb, "import java.awt.Color");
        appendLine(sb, "import java.awt.Font");
        appendLine(sb, "import java.awt.FontMetrics");
        appendLine(sb, "import java.awt.Graphics2D");
        appendLine(sb, "import java.awt.RenderingHints");
        appendLine(sb, "import java.awt.image.BufferedImage");
        emitDisplayImports(sb, displayMode);
        if (config.scaleBar().show() || config.infoLabel().show()) {
            emitScaleBarImports(sb);
        }
        if (config.inset().show()) {
            emitInsetImports(sb);
        }
        emitSplitChannelImports(sb, config);
        if (perAnnotation) {
            emitPerAnnotationImports(sb);
        }
        appendLine(sb, "");

        // Configuration parameters
        appendLine(sb, "// ========== CONFIGURATION (modify as needed) ==========");
        appendLine(sb, "def densityMapName = " + quote(config.overlays().densityMapName()));
        appendLine(sb, "def colormapName = " + quote(config.overlays().colormapName()));
        emitRegionTypeConfig(sb, config);
        emitDisplayConfig(sb, config);
        emitSplitChannelConfig(sb, config);
        emitSplitStainsConfig(sb, config);
        appendLine(sb, "def overlayOpacity = " + config.getOverlayOpacity());
        appendLine(sb, "def downsample = " + config.getDownsample());
        emitDpiConfig(sb, config);
        emitOutputFormat(sb, config);
        appendLine(sb, "def outputDir = " + quote(config.getOutputDirectory().getAbsolutePath()));
        appendLine(sb, "def includeAnnotations = " + config.overlays().includeAnnotations());
        appendLine(sb, "def includeDetections = " + config.overlays().includeDetections());
        appendLine(sb, "def fillAnnotations = " + config.overlays().fillAnnotations());
        appendLine(sb, "def showNames = " + config.overlays().showNames());
        emitScaleBarConfig(sb, config);
        emitColorScaleBarConfig(sb, config);
        emitPanelLabelConfig(sb, config);
        emitInfoLabelConfig(sb, config);
        emitInsetConfig(sb, config);
        appendLine(sb, "// =======================================================");
        appendLine(sb, "");

        // Project and density map loading
        appendLine(sb, "def project = getProject()");
        appendLine(sb, "if (project == null) {");
        appendLine(sb, "    println 'ERROR: No project is open'");
        appendLine(sb, "    return");
        appendLine(sb, "}");
        appendLine(sb, "");
        appendLine(sb, "// Load density map builder from project resources");
        appendLine(sb, "def densityResources = project.getResources(");
        appendLine(sb, "        DensityMaps.PROJECT_LOCATION, DensityMaps.DensityMapBuilder.class, \"json\")");
        appendLine(sb, "def densityBuilder = densityResources.get(densityMapName)");
        appendLine(sb, "if (densityBuilder == null) {");
        appendLine(sb, "    println \"ERROR: Density map not found: ${densityMapName}\"");
        appendLine(sb, "    return");
        appendLine(sb, "}");
        appendLine(sb, "");
        appendLine(sb, "// Resolve color map");
        appendLine(sb, "def allMaps = ColorMaps.getColorMaps()");
        appendLine(sb, "def colorMap = allMaps.get(colormapName)");
        appendLine(sb, "if (colorMap == null) {");
        appendLine(sb, "    // Case-insensitive fallback");
        appendLine(sb, "    colorMap = allMaps.find { it.key.equalsIgnoreCase(colormapName) }?.value");
        appendLine(sb, "}");
        appendLine(sb, "if (colorMap == null) {");
        appendLine(sb, "    println \"WARNING: Colormap '${colormapName}' not found, using first available\"");
        appendLine(sb, "    colorMap = allMaps.values().iterator().next()");
        appendLine(sb, "}");
        appendLine(sb, "");

        // Display settings resolution
        emitDisplaySetup(sb, displayMode);

        // Output directory
        appendLine(sb, "def outDir = new File(outputDir)");
        appendLine(sb, "outDir.mkdirs()");
        appendLine(sb, "");

        // Helper functions (before the main loop)
        emitColorizeDensityMapFunction(sb);
        if (config.scaleBar().show()) {
            emitScaleBarFunction(sb);
        }
        if (config.colorScaleBar().show()) {
            emitColorScaleBarFunction(sb);
        }
        if (config.panelLabel().show()) {
            emitPanelLabelFunction(sb);
        }
        if (config.infoLabel().show()) {
            emitInfoLabelFunction(sb);
        }
        if (config.inset().show()) {
            emitInsetFunction(sb);
        }
        emitSplitChannelHelperFunctions(sb, config);

        // Main processing loop
        appendLine(sb, "def entries = project.getImageList()");
        appendLine(sb, "println \"Processing ${entries.size()} images...\"");
        appendLine(sb, "");
        appendLine(sb, "int succeeded = 0");
        appendLine(sb, "int failed = 0");
        appendLine(sb, "");
        appendLine(sb, "for (int i = 0; i < entries.size(); i++) {");
        appendLine(sb, "    def entry = entries[i]");
        appendLine(sb, "    def entryName = entry.getImageName()");
        appendLine(sb, "    println \"[${i + 1}/${entries.size()}] Processing: ${entryName}\"");
        appendLine(sb, "");
        appendLine(sb, "    def densityServer = null");
        appendLine(sb, "    try {");
        appendLine(sb, "        def imageData = entry.readImageData()");
        appendLine(sb, "        def baseServer = imageData.getServer()");
        appendLine(sb, "");
        if (config.getTargetDpi() > 0) {
            emitPerImageDpiResolution(sb);
            appendLine(sb, "");
        }

        // Display server wrapping
        emitDisplayServerWrapping(sb, displayMode);
        appendLine(sb, "");

        appendLine(sb, "        densityServer = densityBuilder.buildServer(imageData)");
        appendLine(sb, "");

        if (perAnnotation) {
            // Per-annotation density map rendering path
            emitAnnotationLoopOpen(sb);
            emitAnnotationRegionRead(sb, displayMode);

            // Read density region for this annotation
            appendLine(sb, "            def densityRequest = RegionRequest.createInstance(densityServer.getPath(), downsample, ax, ay, aw, ah)");
            appendLine(sb, "            def densityImage = densityServer.readRegion(densityRequest)");
            appendLine(sb, "");

            // Compute min/max from density raster for this region
            appendLine(sb, "            def raster = densityImage.getRaster()");
            appendLine(sb, "            double densityMin = Double.MAX_VALUE");
            appendLine(sb, "            double densityMax = -Double.MAX_VALUE");
            appendLine(sb, "            for (int y = 0; y < raster.getHeight(); y++) {");
            appendLine(sb, "                for (int x = 0; x < raster.getWidth(); x++) {");
            appendLine(sb, "                    double v = raster.getSampleDouble(x, y, 0)");
            appendLine(sb, "                    if (!Double.isNaN(v)) {");
            appendLine(sb, "                        if (v < densityMin) densityMin = v");
            appendLine(sb, "                        if (v > densityMax) densityMax = v");
            appendLine(sb, "                    }");
            appendLine(sb, "                }");
            appendLine(sb, "            }");
            appendLine(sb, "            if (densityMin > densityMax) { densityMin = 0; densityMax = 1 }");
            appendLine(sb, "");
            appendLine(sb, "            def colorized = colorizeDensityMap(densityImage, colorMap, densityMin, densityMax)");
            appendLine(sb, "");
            appendLine(sb, "            def result = new BufferedImage(baseImage.getWidth(), baseImage.getHeight(), BufferedImage.TYPE_INT_RGB)");
            appendLine(sb, "            def g2d = result.createGraphics()");
            appendLine(sb, "            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)");
            appendLine(sb, "            g2d.drawImage(baseImage, 0, 0, null)");
            appendLine(sb, "");
            appendLine(sb, "            if (overlayOpacity > 0 && colorized != null) {");
            appendLine(sb, "                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) overlayOpacity))");
            appendLine(sb, "                g2d.drawImage(colorized, 0, 0, baseImage.getWidth(), baseImage.getHeight(), null)");
            appendLine(sb, "            }");
            appendLine(sb, "");
            emitAnnotationObjectOverlay(sb);
            appendLine(sb, "");
            if (config.scaleBar().show()) {
                emitAnnotationScaleBarDrawing(sb);
                appendLine(sb, "");
            }
            if (config.colorScaleBar().show()) {
                // Color scale bar at annotation level (uses outW, outH from annotation region read)
                appendLine(sb, "            if (showColorScaleBar) {");
                appendLine(sb, "                g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f))");
                appendLine(sb, "                drawColorScaleBar(g2d, outW, outH, colorMap, densityMin, densityMax, colorScaleBarPosition, colorScaleBarFontSize, colorScaleBarBoldText)");
                appendLine(sb, "            }");
                appendLine(sb, "");
            }
            if (config.panelLabel().show()) {
                emitAnnotationPanelLabelDrawing(sb);
                appendLine(sb, "");
            }
            if (config.infoLabel().show()) {
                emitAnnotationInfoLabelDrawing(sb, false);
                appendLine(sb, "");
            }
            if (config.inset().show()) {
                emitAnnotationInsetDrawing(sb);
                appendLine(sb, "");
            }
            emitAnnotationLoopClose(sb, config);
        } else {
            // Whole-image density map rendering path (existing code)
            appendLine(sb, "        int outW = (int) Math.ceil(baseServer.getWidth() / downsample)");
            appendLine(sb, "        int outH = (int) Math.ceil(baseServer.getHeight() / downsample)");
            appendLine(sb, "");
            appendLine(sb, "        def request = RegionRequest.createInstance(");
            appendLine(sb, "                readServer.getPath(), downsample,");
            appendLine(sb, "                0, 0, readServer.getWidth(), readServer.getHeight())");
            appendLine(sb, "        def baseImage = readServer.readRegion(request)");
            appendLine(sb, "");
            appendLine(sb, "        def densityRequest = RegionRequest.createInstance(");
            appendLine(sb, "                densityServer.getPath(), downsample,");
            appendLine(sb, "                0, 0, densityServer.getWidth(), densityServer.getHeight())");
            appendLine(sb, "        def densityImage = densityServer.readRegion(densityRequest)");
            appendLine(sb, "");
            appendLine(sb, "        // Compute min/max from density raster");
            appendLine(sb, "        def raster = densityImage.getRaster()");
            appendLine(sb, "        double densityMin = Double.MAX_VALUE");
            appendLine(sb, "        double densityMax = -Double.MAX_VALUE");
            appendLine(sb, "        for (int y = 0; y < raster.getHeight(); y++) {");
            appendLine(sb, "            for (int x = 0; x < raster.getWidth(); x++) {");
            appendLine(sb, "                double v = raster.getSampleDouble(x, y, 0)");
            appendLine(sb, "                if (!Double.isNaN(v)) {");
            appendLine(sb, "                    if (v < densityMin) densityMin = v");
            appendLine(sb, "                    if (v > densityMax) densityMax = v");
            appendLine(sb, "                }");
            appendLine(sb, "            }");
            appendLine(sb, "        }");
            appendLine(sb, "        if (densityMin > densityMax) { densityMin = 0; densityMax = 1 }");
            appendLine(sb, "");
            appendLine(sb, "        def colorized = colorizeDensityMap(densityImage, colorMap, densityMin, densityMax)");
            appendLine(sb, "");
            appendLine(sb, "        def result = new BufferedImage(");
            appendLine(sb, "                baseImage.getWidth(), baseImage.getHeight(),");
            appendLine(sb, "                BufferedImage.TYPE_INT_RGB)");
            appendLine(sb, "        def g2d = result.createGraphics()");
            appendLine(sb, "        g2d.setRenderingHint(");
            appendLine(sb, "                RenderingHints.KEY_INTERPOLATION,");
            appendLine(sb, "                RenderingHints.VALUE_INTERPOLATION_BILINEAR)");
            appendLine(sb, "");
            appendLine(sb, "        g2d.drawImage(baseImage, 0, 0, null)");
            appendLine(sb, "");
            appendLine(sb, "        if (overlayOpacity > 0 && colorized != null) {");
            appendLine(sb, "            g2d.setComposite(AlphaComposite.getInstance(");
            appendLine(sb, "                    AlphaComposite.SRC_OVER, (float) overlayOpacity))");
            appendLine(sb, "            g2d.drawImage(colorized,");
            appendLine(sb, "                    0, 0, baseImage.getWidth(), baseImage.getHeight(), null)");
            appendLine(sb, "        }");
            appendLine(sb, "");
            appendLine(sb, "        if (includeAnnotations || includeDetections) {");
            appendLine(sb, "            g2d.setComposite(AlphaComposite.getInstance(");
            appendLine(sb, "                    AlphaComposite.SRC_OVER, 1.0f))");
            appendLine(sb, "            def overlayOptions = new OverlayOptions()");
            appendLine(sb, "            overlayOptions.setShowAnnotations(includeAnnotations)");
            appendLine(sb, "            overlayOptions.setShowDetections(includeDetections)");
            appendLine(sb, "            overlayOptions.setFillAnnotations(fillAnnotations)");
            appendLine(sb, "            overlayOptions.setShowNames(showNames)");
            appendLine(sb, "            def hierOverlay = new HierarchyOverlay(null, overlayOptions, imageData)");
            appendLine(sb, "            def gCopy = (Graphics2D) g2d.create()");
            appendLine(sb, "            gCopy.scale(1.0 / downsample, 1.0 / downsample)");
            appendLine(sb, "            def region = qupath.lib.regions.ImageRegion.createInstance(");
            appendLine(sb, "                    0, 0, baseServer.getWidth(), baseServer.getHeight(), 0, 0)");
            appendLine(sb, "            hierOverlay.paintOverlay(gCopy, region, downsample, imageData, true)");
            appendLine(sb, "            gCopy.dispose()");
            appendLine(sb, "        }");
            appendLine(sb, "");
            if (config.scaleBar().show()) {
                emitScaleBarDrawing(sb);
                appendLine(sb, "");
            }
            if (config.colorScaleBar().show()) {
                emitColorScaleBarDrawing(sb);
                appendLine(sb, "");
            }
            if (config.panelLabel().show()) {
                emitPanelLabelDrawing(sb);
                appendLine(sb, "");
            }
            if (config.infoLabel().show()) {
                emitInfoLabelDrawingNoClassifier(sb);
                appendLine(sb, "");
            }
            if (config.inset().show()) {
                emitInsetDrawing(sb);
                appendLine(sb, "");
            }
            appendLine(sb, "        g2d.dispose()");
            appendLine(sb, "");
            appendLine(sb, "        def sanitized = entryName.replaceAll('[^a-zA-Z0-9._\\\\-]', '_')");
            String densityFileLabel = config.splitChannel().enabled() ? "_merge" : "";
            appendLine(sb, "        def outputPath = new File(outDir, sanitized + '" + densityFileLabel + ".' + outputFormat).getAbsolutePath()");
            appendLine(sb, "        ImageWriterTools.writeImage(result, outputPath)");
            appendLine(sb, "        println \"  OK: ${outputPath}\"");
            if (config.splitChannel().enabled() && displayMode != DisplaySettingsMode.RAW) {
                emitSplitChannelWholeImageLoop(sb);
            }
            appendLine(sb, "        succeeded++");
        }

        appendLine(sb, "");
        appendLine(sb, "        densityServer.close()");
        appendLine(sb, "        densityServer = null");
        appendLine(sb, "        baseServer.close()");
        appendLine(sb, "");
        appendLine(sb, "    } catch (Exception e) {");
        appendLine(sb, "        println \"  FAIL: ${e.getMessage()}\"");
        appendLine(sb, "        failed++");
        appendLine(sb, "        if (densityServer != null) {");
        appendLine(sb, "            try { densityServer.close() } catch (Exception ignored) {}");
        appendLine(sb, "        }");
        appendLine(sb, "    }");
        appendLine(sb, "}");
        appendLine(sb, "");
        appendLine(sb, "println ''");
        appendLine(sb, "println \"Export complete: ${succeeded} succeeded, ${failed} failed\"");

        return sb.toString();
    }

    private static String generateClassifierScript(RenderedExportConfig config) {
        var sb = new StringBuilder();
        var displayMode = config.getDisplaySettingsMode();
        boolean perAnnotation = isPerAnnotation(config);

        appendLine(sb, "/**");
        appendLine(sb, " * Classifier Overlay Export Script");
        appendLine(sb, " * Generated by QuIET (QuPath Image Export Toolkit)");
        appendLine(sb, " *");
        if (perAnnotation) {
            appendLine(sb, " * Exports per-annotation cropped images with a pixel classifier overlay.");
        } else {
            appendLine(sb, " * Exports all project images with a pixel classifier overlay");
            appendLine(sb, " * rendered on top, at the specified downsample and opacity.");
        }
        appendLine(sb, " *");
        appendLine(sb, " * Parameters below can be modified before re-running.");
        appendLine(sb, " */");
        appendLine(sb, "");

        // Imports
        appendLine(sb, "import qupath.lib.classifiers.pixel.PixelClassificationImageServer");
        appendLine(sb, "import qupath.lib.gui.viewer.OverlayOptions");
        appendLine(sb, "import qupath.lib.gui.viewer.overlays.HierarchyOverlay");
        appendLine(sb, "import qupath.lib.images.writers.ImageWriterTools");
        appendLine(sb, "import qupath.lib.regions.RegionRequest");
        appendLine(sb, "import java.awt.AlphaComposite");
        appendLine(sb, "import java.awt.Graphics2D");
        appendLine(sb, "import java.awt.RenderingHints");
        appendLine(sb, "import java.awt.image.BufferedImage");
        emitDisplayImports(sb, displayMode);
        if (config.scaleBar().show() || config.infoLabel().show()) {
            emitScaleBarImports(sb);
        }
        if (config.inset().show()) {
            emitInsetImports(sb);
        }
        emitSplitChannelImports(sb, config);
        if (perAnnotation) {
            emitPerAnnotationImports(sb);
        }
        appendLine(sb, "");

        // Configuration parameters
        appendLine(sb, "// ========== CONFIGURATION (modify as needed) ==========");
        appendLine(sb, "def classifierName = " + quote(config.overlays().classifierName()));
        emitRegionTypeConfig(sb, config);
        emitDisplayConfig(sb, config);
        emitSplitChannelConfig(sb, config);
        emitSplitStainsConfig(sb, config);
        appendLine(sb, "def overlayOpacity = " + config.getOverlayOpacity());
        appendLine(sb, "def downsample = " + config.getDownsample());
        emitDpiConfig(sb, config);
        emitOutputFormat(sb, config);
        appendLine(sb, "def outputDir = " + quote(config.getOutputDirectory().getAbsolutePath()));
        appendLine(sb, "def includeAnnotations = " + config.overlays().includeAnnotations());
        appendLine(sb, "def includeDetections = " + config.overlays().includeDetections());
        appendLine(sb, "def fillAnnotations = " + config.overlays().fillAnnotations());
        appendLine(sb, "def showNames = " + config.overlays().showNames());
        emitScaleBarConfig(sb, config);
        emitPanelLabelConfig(sb, config);
        emitInfoLabelConfig(sb, config);
        emitInsetConfig(sb, config);
        appendLine(sb, "// =======================================================");
        appendLine(sb, "");

        // Project and classifier loading
        appendLine(sb, "def project = getProject()");
        appendLine(sb, "if (project == null) {");
        appendLine(sb, "    println 'ERROR: No project is open'");
        appendLine(sb, "    return");
        appendLine(sb, "}");
        appendLine(sb, "");
        appendLine(sb, "def classifier = project.getPixelClassifiers().get(classifierName)");
        appendLine(sb, "if (classifier == null) {");
        appendLine(sb, "    println \"ERROR: Classifier not found: ${classifierName}\"");
        appendLine(sb, "    return");
        appendLine(sb, "}");
        appendLine(sb, "");

        // Display settings resolution
        emitDisplaySetup(sb, displayMode);

        // Output directory
        appendLine(sb, "def outDir = new File(outputDir)");
        appendLine(sb, "outDir.mkdirs()");
        appendLine(sb, "");

        // Helper functions (before the main loop)
        if (config.scaleBar().show()) {
            emitScaleBarFunction(sb);
        }
        if (config.panelLabel().show()) {
            emitPanelLabelFunction(sb);
        }
        if (config.infoLabel().show()) {
            emitInfoLabelFunction(sb);
        }
        if (config.inset().show()) {
            emitInsetFunction(sb);
        }
        emitSplitChannelHelperFunctions(sb, config);

        // Main processing loop
        appendLine(sb, "def entries = project.getImageList()");
        appendLine(sb, "println \"Processing ${entries.size()} images...\"");
        appendLine(sb, "");
        appendLine(sb, "int succeeded = 0");
        appendLine(sb, "int failed = 0");
        appendLine(sb, "int skipped = 0");
        appendLine(sb, "");
        appendLine(sb, "for (int i = 0; i < entries.size(); i++) {");
        appendLine(sb, "    def entry = entries[i]");
        appendLine(sb, "    def entryName = entry.getImageName()");
        appendLine(sb, "    println \"[${i + 1}/${entries.size()}] Processing: ${entryName}\"");
        appendLine(sb, "");
        appendLine(sb, "    def classServer = null");
        appendLine(sb, "    try {");
        appendLine(sb, "        def imageData = entry.readImageData()");
        appendLine(sb, "        def baseServer = imageData.getServer()");
        appendLine(sb, "");
        appendLine(sb, "        if (!classifier.supportsImage(imageData)) {");
        appendLine(sb, "            println \"  SKIP: Classifier does not support this image\"");
        appendLine(sb, "            baseServer.close()");
        appendLine(sb, "            skipped++");
        appendLine(sb, "            continue");
        appendLine(sb, "        }");
        appendLine(sb, "");
        if (config.getTargetDpi() > 0) {
            emitPerImageDpiResolution(sb);
            appendLine(sb, "");
        }

        // Display server wrapping
        emitDisplayServerWrapping(sb, displayMode);
        appendLine(sb, "");

        appendLine(sb, "        classServer = new PixelClassificationImageServer(imageData, classifier)");
        appendLine(sb, "");

        if (perAnnotation) {
            // Per-annotation classifier rendering path
            emitAnnotationLoopOpen(sb);
            emitAnnotationRegionRead(sb, displayMode);
            appendLine(sb, "            def classRequest = RegionRequest.createInstance(classServer.getPath(), downsample, ax, ay, aw, ah)");
            appendLine(sb, "            def classImage = classServer.readRegion(classRequest)");
            appendLine(sb, "");
            appendLine(sb, "            def result = new BufferedImage(baseImage.getWidth(), baseImage.getHeight(), BufferedImage.TYPE_INT_RGB)");
            appendLine(sb, "            def g2d = result.createGraphics()");
            appendLine(sb, "            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)");
            appendLine(sb, "            g2d.drawImage(baseImage, 0, 0, null)");
            appendLine(sb, "");
            appendLine(sb, "            if (overlayOpacity > 0 && classImage != null) {");
            appendLine(sb, "                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) overlayOpacity))");
            appendLine(sb, "                g2d.drawImage(classImage, 0, 0, baseImage.getWidth(), baseImage.getHeight(), null)");
            appendLine(sb, "            }");
            appendLine(sb, "");
            emitAnnotationObjectOverlay(sb);
            appendLine(sb, "");
            if (config.scaleBar().show()) {
                emitAnnotationScaleBarDrawing(sb);
                appendLine(sb, "");
            }
            if (config.panelLabel().show()) {
                emitAnnotationPanelLabelDrawing(sb);
                appendLine(sb, "");
            }
            if (config.infoLabel().show()) {
                emitAnnotationInfoLabelDrawing(sb, true);
                appendLine(sb, "");
            }
            if (config.inset().show()) {
                emitAnnotationInsetDrawing(sb);
                appendLine(sb, "");
            }
            emitAnnotationLoopClose(sb, config);
        } else {
            // Whole-image classifier rendering path (existing code)
            appendLine(sb, "        int outW = (int) Math.ceil(baseServer.getWidth() / downsample)");
            appendLine(sb, "        int outH = (int) Math.ceil(baseServer.getHeight() / downsample)");
            appendLine(sb, "");
            appendLine(sb, "        def request = RegionRequest.createInstance(");
            appendLine(sb, "                readServer.getPath(), downsample,");
            appendLine(sb, "                0, 0, readServer.getWidth(), readServer.getHeight())");
            appendLine(sb, "        def baseImage = readServer.readRegion(request)");
            appendLine(sb, "");
            appendLine(sb, "        def classRequest = RegionRequest.createInstance(");
            appendLine(sb, "                classServer.getPath(), downsample,");
            appendLine(sb, "                0, 0, classServer.getWidth(), classServer.getHeight())");
            appendLine(sb, "        def classImage = classServer.readRegion(classRequest)");
            appendLine(sb, "");
            appendLine(sb, "        def result = new BufferedImage(");
            appendLine(sb, "                baseImage.getWidth(), baseImage.getHeight(),");
            appendLine(sb, "                BufferedImage.TYPE_INT_RGB)");
            appendLine(sb, "        def g2d = result.createGraphics()");
            appendLine(sb, "        g2d.setRenderingHint(");
            appendLine(sb, "                RenderingHints.KEY_INTERPOLATION,");
            appendLine(sb, "                RenderingHints.VALUE_INTERPOLATION_BILINEAR)");
            appendLine(sb, "");
            appendLine(sb, "        g2d.drawImage(baseImage, 0, 0, null)");
            appendLine(sb, "");
            appendLine(sb, "        if (overlayOpacity > 0 && classImage != null) {");
            appendLine(sb, "            g2d.setComposite(AlphaComposite.getInstance(");
            appendLine(sb, "                    AlphaComposite.SRC_OVER, (float) overlayOpacity))");
            appendLine(sb, "            g2d.drawImage(classImage,");
            appendLine(sb, "                    0, 0, baseImage.getWidth(), baseImage.getHeight(), null)");
            appendLine(sb, "        }");
            appendLine(sb, "");
            appendLine(sb, "        if (includeAnnotations || includeDetections) {");
            appendLine(sb, "            g2d.setComposite(AlphaComposite.getInstance(");
            appendLine(sb, "                    AlphaComposite.SRC_OVER, 1.0f))");
            appendLine(sb, "            def overlayOptions = new OverlayOptions()");
            appendLine(sb, "            overlayOptions.setShowAnnotations(includeAnnotations)");
            appendLine(sb, "            overlayOptions.setShowDetections(includeDetections)");
            appendLine(sb, "            overlayOptions.setFillAnnotations(fillAnnotations)");
            appendLine(sb, "            overlayOptions.setShowNames(showNames)");
            appendLine(sb, "            def hierOverlay = new HierarchyOverlay(null, overlayOptions, imageData)");
            appendLine(sb, "            def gCopy = (Graphics2D) g2d.create()");
            appendLine(sb, "            gCopy.scale(1.0 / downsample, 1.0 / downsample)");
            appendLine(sb, "            def region = qupath.lib.regions.ImageRegion.createInstance(");
            appendLine(sb, "                    0, 0, baseServer.getWidth(), baseServer.getHeight(), 0, 0)");
            appendLine(sb, "            hierOverlay.paintOverlay(gCopy, region, downsample, imageData, true)");
            appendLine(sb, "            gCopy.dispose()");
            appendLine(sb, "        }");
            appendLine(sb, "");
            if (config.scaleBar().show()) {
                emitScaleBarDrawing(sb);
                appendLine(sb, "");
            }
            if (config.panelLabel().show()) {
                emitPanelLabelDrawing(sb);
                appendLine(sb, "");
            }
            if (config.infoLabel().show()) {
                emitInfoLabelDrawing(sb);
                appendLine(sb, "");
            }
            if (config.inset().show()) {
                emitInsetDrawing(sb);
                appendLine(sb, "");
            }
            appendLine(sb, "        g2d.dispose()");
            appendLine(sb, "");
            appendLine(sb, "        def sanitized = entryName.replaceAll('[^a-zA-Z0-9._\\\\-]', '_')");
            String classFileLabel = config.splitChannel().enabled() ? "_merge" : "";
            appendLine(sb, "        def outputPath = new File(outDir, sanitized + '" + classFileLabel + ".' + outputFormat).getAbsolutePath()");
            appendLine(sb, "        ImageWriterTools.writeImage(result, outputPath)");
            appendLine(sb, "        println \"  OK: ${outputPath}\"");
            if (config.splitChannel().enabled() && displayMode != DisplaySettingsMode.RAW) {
                emitSplitChannelWholeImageLoop(sb);
            }
            appendLine(sb, "        succeeded++");
        }

        appendLine(sb, "");
        appendLine(sb, "        classServer.close()");
        appendLine(sb, "        classServer = null");
        appendLine(sb, "        baseServer.close()");
        appendLine(sb, "");
        appendLine(sb, "    } catch (Exception e) {");
        appendLine(sb, "        println \"  FAIL: ${e.getMessage()}\"");
        appendLine(sb, "        failed++");
        appendLine(sb, "        if (classServer != null) {");
        appendLine(sb, "            try { classServer.close() } catch (Exception ignored) {}");
        appendLine(sb, "        }");
        appendLine(sb, "    }");
        appendLine(sb, "}");
        appendLine(sb, "");
        appendLine(sb, "println ''");
        appendLine(sb, "println \"Export complete: ${succeeded} succeeded, ${skipped} skipped, ${failed} failed\"");

        return sb.toString();
    }

    private static String generateObjectOverlayScript(RenderedExportConfig config) {
        var sb = new StringBuilder();
        var displayMode = config.getDisplaySettingsMode();
        boolean perAnnotation = isPerAnnotation(config);

        appendLine(sb, "/**");
        appendLine(sb, " * Object Overlay Export Script");
        appendLine(sb, " * Generated by QuIET (QuPath Image Export Toolkit)");
        appendLine(sb, " *");
        if (perAnnotation) {
            appendLine(sb, " * Exports per-annotation cropped images with object overlays.");
        } else {
            appendLine(sb, " * Exports all project images with object overlays");
            appendLine(sb, " * (annotations and/or detections) rendered on top.");
        }
        appendLine(sb, " *");
        appendLine(sb, " * Parameters below can be modified before re-running.");
        appendLine(sb, " */");
        appendLine(sb, "");

        // Imports
        appendLine(sb, "import qupath.lib.gui.viewer.OverlayOptions");
        appendLine(sb, "import qupath.lib.gui.viewer.overlays.HierarchyOverlay");
        appendLine(sb, "import qupath.lib.images.writers.ImageWriterTools");
        appendLine(sb, "import qupath.lib.regions.RegionRequest");
        appendLine(sb, "import java.awt.AlphaComposite");
        appendLine(sb, "import java.awt.Graphics2D");
        appendLine(sb, "import java.awt.RenderingHints");
        appendLine(sb, "import java.awt.image.BufferedImage");
        emitDisplayImports(sb, displayMode);
        if (config.scaleBar().show() || config.infoLabel().show()) {
            emitScaleBarImports(sb);
        }
        if (config.inset().show()) {
            emitInsetImports(sb);
        }
        emitSplitChannelImports(sb, config);
        if (perAnnotation) {
            emitPerAnnotationImports(sb);
        }
        appendLine(sb, "");

        // Configuration parameters
        appendLine(sb, "// ========== CONFIGURATION (modify as needed) ==========");
        emitRegionTypeConfig(sb, config);
        emitDisplayConfig(sb, config);
        emitSplitChannelConfig(sb, config);
        emitSplitStainsConfig(sb, config);
        appendLine(sb, "def overlayOpacity = " + config.getOverlayOpacity());
        appendLine(sb, "def downsample = " + config.getDownsample());
        emitDpiConfig(sb, config);
        emitOutputFormat(sb, config);
        appendLine(sb, "def outputDir = " + quote(config.getOutputDirectory().getAbsolutePath()));
        appendLine(sb, "def includeAnnotations = " + config.overlays().includeAnnotations());
        appendLine(sb, "def includeDetections = " + config.overlays().includeDetections());
        appendLine(sb, "def fillAnnotations = " + config.overlays().fillAnnotations());
        appendLine(sb, "def showNames = " + config.overlays().showNames());
        emitScaleBarConfig(sb, config);
        emitPanelLabelConfig(sb, config);
        emitInfoLabelConfig(sb, config);
        emitInsetConfig(sb, config);
        appendLine(sb, "// =======================================================");
        appendLine(sb, "");

        // Project loading
        appendLine(sb, "def project = getProject()");
        appendLine(sb, "if (project == null) {");
        appendLine(sb, "    println 'ERROR: No project is open'");
        appendLine(sb, "    return");
        appendLine(sb, "}");
        appendLine(sb, "");

        // Display settings resolution
        emitDisplaySetup(sb, displayMode);

        // Output directory
        appendLine(sb, "def outDir = new File(outputDir)");
        appendLine(sb, "outDir.mkdirs()");
        appendLine(sb, "");

        // Helper functions (before the main loop)
        if (config.scaleBar().show()) {
            emitScaleBarFunction(sb);
        }
        if (config.panelLabel().show()) {
            emitPanelLabelFunction(sb);
        }
        if (config.infoLabel().show()) {
            emitInfoLabelFunction(sb);
        }
        if (config.inset().show()) {
            emitInsetFunction(sb);
        }
        emitSplitChannelHelperFunctions(sb, config);

        // Main processing loop
        appendLine(sb, "def entries = project.getImageList()");
        appendLine(sb, "println \"Processing ${entries.size()} images...\"");
        appendLine(sb, "");
        appendLine(sb, "int succeeded = 0");
        appendLine(sb, "int failed = 0");
        appendLine(sb, "");
        appendLine(sb, "for (int i = 0; i < entries.size(); i++) {");
        appendLine(sb, "    def entry = entries[i]");
        appendLine(sb, "    def entryName = entry.getImageName()");
        appendLine(sb, "    println \"[${i + 1}/${entries.size()}] Processing: ${entryName}\"");
        appendLine(sb, "");
        appendLine(sb, "    try {");
        appendLine(sb, "        def imageData = entry.readImageData()");
        appendLine(sb, "        def baseServer = imageData.getServer()");
        appendLine(sb, "");
        if (config.getTargetDpi() > 0) {
            emitPerImageDpiResolution(sb);
            appendLine(sb, "");
        }

        // Display server wrapping
        emitDisplayServerWrapping(sb, displayMode);
        appendLine(sb, "");

        if (perAnnotation) {
            // Per-annotation rendering path
            emitAnnotationLoopOpen(sb);
            emitAnnotationRegionRead(sb, displayMode);
            appendLine(sb, "            def result = new BufferedImage(baseImage.getWidth(), baseImage.getHeight(), BufferedImage.TYPE_INT_RGB)");
            appendLine(sb, "            def g2d = result.createGraphics()");
            appendLine(sb, "            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)");
            appendLine(sb, "            g2d.drawImage(baseImage, 0, 0, null)");
            appendLine(sb, "");
            appendLine(sb, "            if (overlayOpacity > 0) {");
            appendLine(sb, "                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) overlayOpacity))");
            emitAnnotationObjectOverlay(sb);
            appendLine(sb, "            }");
            appendLine(sb, "");
            if (config.scaleBar().show()) {
                emitAnnotationScaleBarDrawing(sb);
                appendLine(sb, "");
            }
            if (config.panelLabel().show()) {
                emitAnnotationPanelLabelDrawing(sb);
                appendLine(sb, "");
            }
            if (config.infoLabel().show()) {
                emitAnnotationInfoLabelDrawing(sb, false);
                appendLine(sb, "");
            }
            if (config.inset().show()) {
                emitAnnotationInsetDrawing(sb);
                appendLine(sb, "");
            }
            emitAnnotationLoopClose(sb, config);
        } else {
            // Whole-image rendering path (existing code)
            appendLine(sb, "        int outW = (int) Math.ceil(baseServer.getWidth() / downsample)");
            appendLine(sb, "        int outH = (int) Math.ceil(baseServer.getHeight() / downsample)");
            appendLine(sb, "");
            appendLine(sb, "        def request = RegionRequest.createInstance(");
            appendLine(sb, "                readServer.getPath(), downsample,");
            appendLine(sb, "                0, 0, readServer.getWidth(), readServer.getHeight())");
            appendLine(sb, "        def baseImage = readServer.readRegion(request)");
            appendLine(sb, "");
            appendLine(sb, "        def result = new BufferedImage(");
            appendLine(sb, "                baseImage.getWidth(), baseImage.getHeight(),");
            appendLine(sb, "                BufferedImage.TYPE_INT_RGB)");
            appendLine(sb, "        def g2d = result.createGraphics()");
            appendLine(sb, "        g2d.setRenderingHint(");
            appendLine(sb, "                RenderingHints.KEY_INTERPOLATION,");
            appendLine(sb, "                RenderingHints.VALUE_INTERPOLATION_BILINEAR)");
            appendLine(sb, "");
            appendLine(sb, "        g2d.drawImage(baseImage, 0, 0, null)");
            appendLine(sb, "");
            appendLine(sb, "        if (overlayOpacity > 0) {");
            appendLine(sb, "            g2d.setComposite(AlphaComposite.getInstance(");
            appendLine(sb, "                    AlphaComposite.SRC_OVER, (float) overlayOpacity))");
            appendLine(sb, "            def overlayOptions = new OverlayOptions()");
            appendLine(sb, "            overlayOptions.setShowAnnotations(includeAnnotations)");
            appendLine(sb, "            overlayOptions.setShowDetections(includeDetections)");
            appendLine(sb, "            overlayOptions.setFillAnnotations(fillAnnotations)");
            appendLine(sb, "            overlayOptions.setShowNames(showNames)");
            appendLine(sb, "            def hierOverlay = new HierarchyOverlay(null, overlayOptions, imageData)");
            appendLine(sb, "            def gCopy = (Graphics2D) g2d.create()");
            appendLine(sb, "            gCopy.scale(1.0 / downsample, 1.0 / downsample)");
            appendLine(sb, "            def region = qupath.lib.regions.ImageRegion.createInstance(");
            appendLine(sb, "                    0, 0, baseServer.getWidth(), baseServer.getHeight(), 0, 0)");
            appendLine(sb, "            hierOverlay.paintOverlay(gCopy, region, downsample, imageData, true)");
            appendLine(sb, "            gCopy.dispose()");
            appendLine(sb, "        }");
            appendLine(sb, "");
            if (config.scaleBar().show()) {
                emitScaleBarDrawing(sb);
                appendLine(sb, "");
            }
            if (config.panelLabel().show()) {
                emitPanelLabelDrawing(sb);
                appendLine(sb, "");
            }
            if (config.infoLabel().show()) {
                emitInfoLabelDrawingNoClassifier(sb);
                appendLine(sb, "");
            }
            if (config.inset().show()) {
                emitInsetDrawing(sb);
                appendLine(sb, "");
            }
            appendLine(sb, "        g2d.dispose()");
            appendLine(sb, "");
            appendLine(sb, "        def sanitized = entryName.replaceAll('[^a-zA-Z0-9._\\\\-]', '_')");
            String objFileLabel = config.splitChannel().enabled() ? "_merge" : "";
            appendLine(sb, "        def outputPath = new File(outDir, sanitized + '" + objFileLabel + ".' + outputFormat).getAbsolutePath()");
            appendLine(sb, "        ImageWriterTools.writeImage(result, outputPath)");
            appendLine(sb, "        println \"  OK: ${outputPath}\"");
            if (config.splitChannel().enabled() && displayMode != DisplaySettingsMode.RAW) {
                emitSplitChannelWholeImageLoop(sb);
            }
            appendLine(sb, "        succeeded++");
        }

        appendLine(sb, "");
        appendLine(sb, "        baseServer.close()");
        appendLine(sb, "");
        appendLine(sb, "    } catch (Exception e) {");
        appendLine(sb, "        println \"  FAIL: ${e.getMessage()}\"");
        appendLine(sb, "        failed++");
        appendLine(sb, "    }");
        appendLine(sb, "}");
        appendLine(sb, "");
        appendLine(sb, "println ''");
        appendLine(sb, "println \"Export complete: ${succeeded} succeeded, ${failed} failed\"");

        return sb.toString();
    }
}
