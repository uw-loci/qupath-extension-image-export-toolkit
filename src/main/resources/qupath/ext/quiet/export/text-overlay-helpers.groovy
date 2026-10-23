
// ---------- Text and legends, drawn as QuIET draws them ----------
// A copy of QuIET's TextRenderUtils, ScaleBarRenderer, ColorScaleBarRenderer, LegendRenderer
// and LegendContent; a test checks both draw the same pixels.
// Text background: 'OUTLINE', 'BOX' or 'AUTO' (outline where the text reaches 4.5:1 contrast
// on all of the image under it, otherwise a box). Text color null = Auto (black on light
// areas, white on dark ones).

@groovy.transform.Field double[] quietLinear = (0..255).collect { int i ->
    double c = i / 255.0d
    c <= 0.04045d ? c / 12.92d : Math.pow((c + 0.055d) / 1.055d, 2.4d)
} as double[]

double quietLuminance(int rgb) {
    return 0.2126d * quietLinear[(rgb >> 16) & 0xFF] + 0.7152d * quietLinear[(rgb >> 8) & 0xFF] + 0.0722d * quietLinear[rgb & 0xFF]
}

double quietContrast(double a, double b) {
    double hi = Math.max(a, b)
    double lo = Math.min(a, b)
    return (hi + 0.05d) / (lo + 0.05d)
}

double[] quietSampleLuminance(java.awt.image.BufferedImage img, int x, int y, int w, int h) {
    if (img == null) return new double[0]
    int x0 = Math.max(0, x)
    int y0 = Math.max(0, y)
    int x1 = Math.min(img.getWidth(), x + w)
    int y1 = Math.min(img.getHeight(), y + h)
    if (x1 <= x0 || y1 <= y0) return new double[0]
    double area = (double) (x1 - x0) * (y1 - y0)
    int step = (int) Math.max(1, Math.ceil(Math.sqrt(area / 16384)))
    int nx = (x1 - x0 + step - 1).intdiv(step)
    int ny = (y1 - y0 + step - 1).intdiv(step)
    double[] lum = new double[nx * ny]
    int k = 0
    for (int yy = y0; yy < y1; yy += step) {
        for (int xx = x0; xx < x1; xx += step) {
            lum[k++] = quietLuminance(img.getRGB(xx, yy))
        }
    }
    return lum
}

java.awt.Color quietOutlineColor(java.awt.Color c) {
    double luminance = (0.299d * c.getRed() + 0.587d * c.getGreen() + 0.114d * c.getBlue()) / 255.0d
    return luminance > 0.5d ? java.awt.Color.BLACK : java.awt.Color.WHITE
}

java.awt.Color quietColor(def hex) {
    if (hex == null) return null
    if (hex instanceof java.awt.Color) return hex
    String s = hex.toString().trim()
    if (s.isEmpty()) return null
    try { return java.awt.Color.decode(s) } catch (NumberFormatException e) { return null }
}

Map quietInk(String background, java.awt.Color fixedText, java.awt.image.BufferedImage backdrop, int x, int y, int w, int h) {
    double[] lum = quietSampleLuminance(backdrop, x, y, w, h)
    java.awt.Color text = fixedText
    if (text == null) {
        double mean = 0
        for (double v : lum) mean += v
        boolean light = lum.length > 0 && mean / lum.length > 0.179d
        text = light ? java.awt.Color.BLACK : java.awt.Color.WHITE
    }
    java.awt.Color outline = quietOutlineColor(text)
    boolean box
    if (background == 'BOX') {
        box = true
    } else if (background == 'OUTLINE') {
        box = false
    } else if (lum.length == 0) {
        box = true
    } else {
        double t = quietLuminance(text.getRGB())
        box = false
        for (double v : lum) {
            if (quietContrast(t, v) < 4.5d) { box = true; break }
        }
    }
    return [text: text, outline: outline, box: box ? outline : null]
}

void quietFillBox(java.awt.Graphics2D g2d, Map ink, int x, int y, int w, int h, int imgW, int imgH, int arc) {
    if (ink.box == null) return
    int left = Math.max(0, x)
    int top = Math.max(0, y)
    int right = Math.min(imgW, x + w)
    int bottom = Math.min(imgH, y + h)
    if (right <= left || bottom <= top) return
    def prev = g2d.getComposite()
    g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.65f))
    g2d.setColor(ink.box)
    g2d.fillRoundRect(left, top, right - left, bottom - top, arc, arc)
    g2d.setComposite(prev)
}

void quietDrawText(java.awt.Graphics2D g2d, String text, int x, int y, Map ink) {
    g2d.setColor(ink.outline)
    for (int dx = -1; dx <= 1; dx++) {
        for (int dy = -1; dy <= 1; dy++) {
            if (dx != 0 || dy != 0) g2d.drawString(text, x + dx, y + dy)
        }
    }
    g2d.setColor(ink.text)
    g2d.drawString(text, x, y)
}

int quietBoxPad(int fontSize) {
    return Math.max(3, fontSize.intdiv(5))
}

int quietFontSize(int requested, int minDim) {
    return requested <= 0 ? Math.max(12, minDim.intdiv(50)) : Math.max(4, Math.min(requested, 200))
}

// One line of text in a corner: the info label and the panel label
void quietCornerText(java.awt.Graphics2D g2d, int imgW, int imgH, String text, String pos, int fontSize, int margin,
                     boolean bold, java.awt.Color fixedText, String background, java.awt.image.BufferedImage backdrop) {
    if (imgW <= 0 || imgH <= 0 || text == null || text.isEmpty()) return
    def savedComposite = g2d.getComposite()
    def savedAA = g2d.getRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING)
    def savedFont = g2d.getFont()
    def savedColor = g2d.getColor()
    try {
        g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f))
        g2d.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2d.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, bold ? java.awt.Font.BOLD : java.awt.Font.PLAIN, fontSize))
        def fm = g2d.getFontMetrics()
        int textWidth = fm.stringWidth(text)
        int ascent = fm.getAscent()
        int textX
        int textY
        switch (pos) {
            case 'LOWER_LEFT': textX = margin; textY = imgH - margin; break
            case 'UPPER_RIGHT': textX = imgW - margin - textWidth; textY = margin + ascent; break
            case 'UPPER_LEFT': textX = margin; textY = margin + ascent; break
            default: textX = imgW - margin - textWidth; textY = imgH - margin; break
        }
        int pad = quietBoxPad(fontSize)
        int boxX = textX - pad
        int boxY = textY - ascent - pad
        int boxW = textWidth + 2 * pad
        int boxH = ascent + fm.getDescent() + 2 * pad
        def ink = quietInk(background, fixedText, backdrop, boxX, boxY, boxW, boxH)
        quietFillBox(g2d, ink, boxX, boxY, boxW, boxH, imgW, imgH, pad)
        quietDrawText(g2d, text, textX, textY, ink)
    } finally {
        g2d.setComposite(savedComposite)
        if (savedAA != null) g2d.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, savedAA)
        g2d.setFont(savedFont)
        g2d.setColor(savedColor)
    }
}

void quietDrawInfoLabel(java.awt.Graphics2D g2d, int imgW, int imgH, String text, String pos, int fSize, boolean bold,
                        java.awt.Color fixedText, String background, java.awt.image.BufferedImage backdrop) {
    int minDim = Math.min(imgW, imgH)
    int fontSize = fSize <= 0 ? Math.max(12, minDim.intdiv(40)) : quietFontSize(fSize, minDim)
    quietCornerText(g2d, imgW, imgH, text, pos, fontSize, Math.max(10, minDim.intdiv(40)), bold, fixedText, background, backdrop)
}

void quietDrawPanelLabel(java.awt.Graphics2D g2d, int imgW, int imgH, String text, String pos, int fSize, boolean bold,
                         java.awt.Color fixedText, String background, java.awt.image.BufferedImage backdrop) {
    int minDim = Math.min(imgW, imgH)
    int fontSize = fSize <= 0 ? Math.max(14, minDim.intdiv(25)) : quietFontSize(fSize, minDim)
    quietCornerText(g2d, imgW, imgH, text, pos, fontSize, Math.max(10, minDim.intdiv(40)), bold, fixedText, background, backdrop)
}

@groovy.transform.Field double[] quietNiceLengths = [0.1d, 0.25d, 0.5d, 1d, 2d, 5d, 10d, 20d, 50d, 100d,
        200d, 250d, 500d, 1000d, 2000d, 5000d, 10000d, 20000d, 50000d] as double[]

double quietScaleBarLength(int imgW, int imgH, double pxSize) {
    double target = imgW * pxSize * 0.15d
    double bar = quietNiceLengths[0]
    double bestDiff = Math.abs(target - bar)
    for (double c : quietNiceLengths) {
        double diff = Math.abs(target - c)
        if (diff < bestDiff) { bar = c; bestDiff = diff }
    }
    int barPx = (int) Math.round(bar / pxSize)
    if (barPx < 2) return Double.NaN
    int margin = Math.max(10, Math.min(imgW, imgH).intdiv(40))
    int maxBarPx = Math.max(2, imgW - 2 * margin)
    while (barPx > maxBarPx) {
        double best = quietNiceLengths[0]
        for (double c : quietNiceLengths) if (c < bar && c > best) best = c
        double shrunk = best < bar ? best : bar
        if (!(shrunk < bar)) return maxBarPx * pxSize
        bar = shrunk
        barPx = (int) Math.round(bar / pxSize)
        if (barPx < 2) return Double.NaN
    }
    return bar
}

String quietScaleBarLabel(double microns) {
    if (microns >= 1000) {
        double mm = microns / 1000.0d
        return Math.abs(mm - Math.floor(mm)) < 1e-9d ? String.format('%d mm', (int) mm) : String.format('%.1f mm', mm)
    }
    if (Math.abs(microns - Math.floor(microns)) < 1e-9d) return String.format('%d \u00B5m', (int) microns)
    if (microns >= 1) return String.format('%.1f \u00B5m', microns)
    return String.format('%.2f \u00B5m', microns)
}

void quietDrawScaleBar(java.awt.Graphics2D g2d, int imgW, int imgH, double pxSize, String pos, java.awt.Color barColor,
                       int fSize, boolean bold, String background, java.awt.image.BufferedImage backdrop) {
    if (pxSize <= 0 || imgW <= 0 || imgH <= 0) return
    def savedComposite = g2d.getComposite()
    def savedAA = g2d.getRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING)
    def savedFont = g2d.getFont()
    def savedColor = g2d.getColor()
    try {
        g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f))
        g2d.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        double barUm = quietScaleBarLength(imgW, imgH, pxSize)
        if (!(barUm > 0)) return
        int barPx = (int) Math.round(barUm / pxSize)
        int barH = Math.max(4, imgH.intdiv(150))
        int minDim = Math.min(imgW, imgH)
        int fontSize = quietFontSize(fSize, minDim)
        int margin = Math.max(10, minDim.intdiv(40))
        String label = quietScaleBarLabel(barUm)
        g2d.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, bold ? java.awt.Font.BOLD : java.awt.Font.PLAIN, fontSize))
        def fm = g2d.getFontMetrics()
        int tw = fm.stringWidth(label)
        int th = fm.getAscent()
        int bx
        int by
        switch (pos) {
            case 'LOWER_LEFT': bx = margin; by = imgH - margin - barH; break
            case 'UPPER_RIGHT': bx = imgW - margin - barPx; by = margin + th + 4; break
            case 'UPPER_LEFT': bx = margin; by = margin + th + 4; break
            default: bx = imgW - margin - barPx; by = imgH - margin - barH; break
        }
        int tx = bx + (barPx - tw).intdiv(2)
        tx = Math.max(margin, Math.min(tx, imgW - tw - margin))
        int ty = by - 4
        def primary = barColor != null ? barColor : java.awt.Color.WHITE
        int boxPad = Math.max(6, margin.intdiv(4))
        int boxLeft = Math.min(bx, tx) - boxPad
        int boxTop = ty - th - boxPad
        int boxRight = Math.max(bx + barPx, tx + tw) + boxPad
        int boxBottom = by + barH + boxPad
        def ink = quietInk(background, primary, backdrop, boxLeft, boxTop, boxRight - boxLeft, boxBottom - boxTop)
        quietFillBox(g2d, ink, boxLeft, boxTop, boxRight - boxLeft, boxBottom - boxTop, imgW, imgH, boxPad)
        g2d.setColor(ink.outline)
        g2d.fillRect(bx - 1, by - 1, barPx + 2, barH + 2)
        g2d.setColor(primary)
        g2d.fillRect(bx, by, barPx, barH)
        quietDrawText(g2d, label, tx, ty, [text: primary, outline: ink.outline])
    } finally {
        g2d.setComposite(savedComposite)
        if (savedAA != null) g2d.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, savedAA)
        g2d.setFont(savedFont)
        g2d.setColor(savedColor)
    }
}

String quietScaleValue(double value) {
    if (value == 0) return '0'
    double abs = Math.abs(value)
    if (abs >= 1 && value == Math.floor(value) && !Double.isInfinite(value)) return String.format('%d', (long) value)
    if (abs < 0.01d && abs > 0) return String.format('%.1e', value)
    return String.format('%.2f', value)
}

void quietDrawColorScaleBar(java.awt.Graphics2D g2d, int imgW, int imgH, colorMap, double minV, double maxV, String pos,
                            int fSize, boolean bold, String title, String background, java.awt.Color fixedText,
                            java.awt.image.BufferedImage backdrop) {
    if (imgW <= 0 || imgH <= 0 || colorMap == null) return
    def savedComposite = g2d.getComposite()
    def savedAA = g2d.getRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING)
    def savedFont = g2d.getFont()
    def savedColor = g2d.getColor()
    try {
        g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f))
        g2d.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        int minDim = Math.min(imgW, imgH)
        int fontSize = quietFontSize(fSize, minDim)
        int margin = Math.max(10, minDim.intdiv(40))
        int barH = Math.min(imgH.intdiv(4), Math.max(60, imgH.intdiv(4)))
        int barW = Math.max(10, barH.intdiv(6))
        g2d.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, bold ? java.awt.Font.BOLD : java.awt.Font.PLAIN, fontSize))
        def fm = g2d.getFontMetrics()
        String[] labels = new String[5]
        int maxLabelW = 0
        for (int i = 0; i < 5; i++) {
            double t = (double) i / 4
            labels[i] = quietScaleValue(minV + t * (maxV - minV))
            maxLabelW = Math.max(maxLabelW, fm.stringWidth(labels[i]))
        }
        int tickLen = 4
        int labelGap = 4
        int totalW = barW + tickLen + labelGap + maxLabelW
        int th = fm.getAscent()
        int bx
        int by
        switch (pos) {
            case 'LOWER_LEFT': bx = margin; by = imgH - margin - barH; break
            case 'UPPER_RIGHT': bx = imgW - margin - totalW; by = margin; break
            case 'UPPER_LEFT': bx = margin; by = margin; break
            default: bx = imgW - margin - totalW; by = imgH - margin - barH; break
        }
        boolean hasTitle = title != null && !title.isBlank()
        int titleX = 0
        int titleY = 0
        if (hasTitle) {
            int titleGap = Math.max(4, th.intdiv(2))
            int titleW = fm.stringWidth(title)
            boolean upper = pos == 'UPPER_LEFT' || pos == 'UPPER_RIGHT'
            boolean right = !(pos == 'UPPER_LEFT' || pos == 'LOWER_LEFT')
            if (upper) {
                titleY = by + barH + titleGap + th
            } else {
                by -= th + titleGap
                titleY = by - titleGap
            }
            titleX = right ? bx + totalW - titleW : bx
            titleX = Math.max(margin, Math.min(titleX, imgW - margin - titleW))
        }
        int pad = quietBoxPad(fontSize)
        int boxLeft = bx - 1
        int boxRight = bx + totalW
        int boxTop = by - 1
        int boxBottom = by + barH + 1 + fm.getDescent()
        if (hasTitle) {
            boxLeft = Math.min(boxLeft, titleX)
            boxRight = Math.max(boxRight, titleX + fm.stringWidth(title))
            boxTop = Math.min(boxTop, titleY - th)
            boxBottom = Math.max(boxBottom, titleY + fm.getDescent())
        }
        boxLeft -= pad
        boxTop -= pad
        boxRight += pad
        boxBottom += pad
        def ink = quietInk(background, fixedText, backdrop, boxLeft, boxTop, boxRight - boxLeft, boxBottom - boxTop)
        quietFillBox(g2d, ink, boxLeft, boxTop, boxRight - boxLeft, boxBottom - boxTop, imgW, imgH, pad)
        double range = maxV - minV
        for (int row = 0; row < barH; row++) {
            double t = 1.0d - (double) row / Math.max(1, barH - 1)
            double v = minV + t * range
            g2d.setColor(new java.awt.Color((int) colorMap.getColor(v, minV, maxV)))
            g2d.fillRect(bx, by + row, barW, 1)
        }
        g2d.setColor(ink.text)
        g2d.drawRect(bx - 1, by - 1, barW + 1, barH + 1)
        for (int i = 0; i < 5; i++) {
            double t = (double) i / 4
            int tickY = by + barH - 1 - (int) Math.round(t * (barH - 1))
            int tickStartX = bx + barW
            g2d.setColor(ink.text)
            g2d.drawLine(tickStartX, tickY, tickStartX + tickLen, tickY)
            int lx = tickStartX + tickLen + labelGap
            int ly = tickY + th.intdiv(2) - 1
            ly = Math.max(by + th, Math.min(ly, by + barH))
            quietDrawText(g2d, labels[i], lx, ly, ink)
        }
        if (hasTitle) quietDrawText(g2d, title, titleX, titleY, ink)
    } finally {
        g2d.setComposite(savedComposite)
        if (savedAA != null) g2d.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, savedAA)
        g2d.setFont(savedFont)
        g2d.setColor(savedColor)
    }
}

// One legend swatch: FILL, OUTLINE, LINE or POINT, as the entry is drawn on the image
void quietDrawSwatch(java.awt.Graphics2D g2d, Map entry, int x, int y, int size, java.awt.Color border) {
    def color = new java.awt.Color(((int) entry.rgb) & 0xFFFFFF)
    def savedStroke = g2d.getStroke()
    def savedAA = g2d.getRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING)
    try {
        switch (entry.kind) {
            case 'OUTLINE':
                int ot = Math.max(2, size.intdiv(6))
                g2d.setColor(color)
                g2d.setStroke(new java.awt.BasicStroke((float) ot))
                g2d.drawRect(x + ot.intdiv(2), y + ot.intdiv(2), size - ot, size - ot)
                break
            case 'LINE':
                int t = Math.max(2, size.intdiv(5))
                g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.setColor(color)
                g2d.setStroke(new java.awt.BasicStroke((float) t, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND))
                g2d.drawLine(x + t, y + size - t, x + size - t, y + t)
                break
            case 'POINT':
                g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                int d = Math.max(4, (size * 2).intdiv(3))
                int o = (size - d).intdiv(2)
                g2d.setColor(color)
                g2d.fillOval(x + o, y + o, d, d)
                g2d.setColor(border)
                g2d.drawOval(x + o, y + o, d, d)
                break
            default:
                g2d.setColor(color)
                g2d.fillRect(x, y, size, size)
                g2d.setColor(border)
                g2d.drawRect(x, y, size, size)
        }
    } finally {
        g2d.setStroke(savedStroke)
        if (savedAA != null) g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, savedAA)
    }
}

// A legend of swatches and names in a corner, wrapped into columns, at most half the image each way
void quietDrawCornerLegend(java.awt.Graphics2D g2d, List entries, String pos, int w, int h, String background,
                           java.awt.Color fixedText, java.awt.image.BufferedImage backdrop) {
    if (entries == null || entries.isEmpty() || w <= 0 || h <= 0) return
    int minDim = Math.min(w, h)
    int baseSwatch = Math.max(8, minDim.intdiv(30))
    int margin = Math.max(6, minDim.intdiv(50))
    int baseFont = Math.max(10, minDim.intdiv(45))
    int baseLineSpacing = Math.max(2, baseSwatch.intdiv(4))
    int n = entries.size()
    int maxLegendH = Math.max(1, (int) (h * 0.5d))
    int maxLegendW = Math.max(1, (int) (w * 0.5d))
    int baseRow = baseSwatch + baseLineSpacing
    int rowsPerColAtBase = Math.max(1, maxLegendH.intdiv(baseRow))
    int columns = Math.max(1, Math.min(4, (n + rowsPerColAtBase - 1).intdiv(rowsPerColAtBase)))
    int rowsPerCol = (n + columns - 1).intdiv(columns)
    g2d.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, baseFont))
    def fmProbe = g2d.getFontMetrics()
    int widestText = 0
    for (def e : entries) widestText = Math.max(widestText, fmProbe.stringWidth((String) e.name))
    int legendWAtBase = columns * (baseSwatch + margin + widestText + margin)
    int legendHAtBase = rowsPerCol * baseRow
    double scale = Math.min((double) maxLegendW / Math.max(1, legendWAtBase), (double) maxLegendH / Math.max(1, legendHAtBase))
    scale = Math.min(1.0d, scale)
    int swatchSize = Math.max(6, (int) Math.round(baseSwatch * scale))
    int lineSpacing = Math.max(1, (int) Math.round(baseLineSpacing * scale))
    int fontSize = Math.max(8, (int) Math.round(baseFont * scale))
    g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f))
    g2d.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g2d.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, fontSize))
    def fm = g2d.getFontMetrics()
    int actualWidest = 0
    for (def e : entries) actualWidest = Math.max(actualWidest, fm.stringWidth((String) e.name))
    int colWidth = swatchSize + margin.intdiv(2) + actualWidest + margin.intdiv(2)
    int rowPitch = swatchSize + lineSpacing
    int legendW = columns * colWidth
    int legendH = rowsPerCol * rowPitch
    boolean right = pos == 'UPPER_RIGHT' || pos == 'LOWER_RIGHT'
    boolean lower = pos == 'LOWER_LEFT' || pos == 'LOWER_RIGHT'
    int originX = right ? Math.max(margin, w - margin - legendW) : margin
    int originY = lower ? Math.max(margin, h - margin - legendH) : margin
    int pad = Math.max(3, margin.intdiv(2))
    int boxX = originX - pad
    int boxY = originY - pad
    int boxW = legendW + 2 * pad
    int boxH = legendH - lineSpacing + 2 * pad
    def ink = quietInk(background, fixedText, backdrop, boxX, boxY, boxW, boxH)
    quietFillBox(g2d, ink, boxX, boxY, boxW, boxH, w, h, pad)
    for (int i = 0; i < n; i++) {
        int x0 = originX + i.intdiv(rowsPerCol) * colWidth
        int y0 = originY + (i % rowsPerCol) * rowPitch
        quietDrawSwatch(g2d, (Map) entries[i], x0, y0, swatchSize, (java.awt.Color) ink.text)
        quietDrawText(g2d, (String) entries[i].name, x0 + swatchSize + margin.intdiv(2), y0 + swatchSize - 2, ink)
    }
}

// Legend rows for the image's channels: brightfield stains, else the visible channels in their display colors
List quietChannelEntries(imageData, display) {
    def entries = []
    def stains = imageData.getColorDeconvolutionStains()
    if (stains != null) {
        for (int i = 1; i <= 3; i++) {
            def stain = stains.getStain(i)
            if (stain != null && !stain.isResidual()) entries << [name: stain.getName(), rgb: stain.getColor() & 0xFFFFFF, kind: 'FILL']
        }
    }
    if (entries.isEmpty() && display != null && display.selectedChannels() != null) {
        def server = imageData.getServer()
        for (def ch : display.selectedChannels()) {
            if (ch == null) continue
            String name = ch.getName()
            if (ch instanceof qupath.lib.display.DirectServerChannelInfo) {
                int c = ch.getChannel()
                if (c >= 0 && c < server.nChannels()) {
                    def n = server.getChannel(c).getName()
                    if (n != null && !n.isBlank()) name = n
                }
            }
            def color = ch.getColor()
            entries << [name: name, rgb: color == null ? 0xFFFFFF : (color & 0xFFFFFF), kind: 'FILL']
        }
    }
    return entries
}

// Legend rows for the object classes drawn in a region: annotations first, named classes alphabetically
List quietObjectEntries(imageData, int x, int y, int w, int h, boolean annotations, boolean detections, boolean fillAnnotations) {
    if (!annotations && !detections) return []
    def region = qupath.lib.regions.ImageRegion.createInstance(x, y, w, h, 0, 0)
    def objects = imageData.getHierarchy().getObjectsForRegion(null, region, null)
    boolean fillDetections = new qupath.lib.gui.viewer.OverlayOptions().getFillDetections()
    def groups = new LinkedHashMap<String, Map>()
    for (def o : objects) {
        if (!o.hasROI()) continue
        boolean annotation = o.isAnnotation()
        if (annotation ? !annotations : !(detections && o.isDetection())) continue
        String name = o.getPathClass() == null ? '' : o.getPathClass().toString()
        def group = groups.computeIfAbsent((annotation ? 'A' : 'D') + name) { k ->
            [annotation: annotation, name: name, colors: new LinkedHashMap<Integer, Integer>(), count: 0, points: 0, lines: 0]
        }
        Integer rgb = qupath.lib.gui.tools.ColorToolsFX.getDisplayedColorARGB(o)
        group.colors.merge(rgb == null ? 0xFFFFFF : (rgb & 0xFFFFFF), 1) { a, b -> a + b }
        def roi = o.getROI()
        if (roi.isPoint()) group.points++
        else if (roi.isLine()) group.lines++
        group.count++
    }
    def sorted = new ArrayList<Map>(groups.values())
    sorted.sort { Map a, Map b ->
        if (a.annotation != b.annotation) return a.annotation ? -1 : 1
        if (a.name.isEmpty() != b.name.isEmpty()) return a.name.isEmpty() ? 1 : -1
        return a.name.compareTo(b.name)
    }
    def entries = []
    for (def g : sorted) {
        String label = g.name.isEmpty() ? (g.annotation ? 'Annotations' : 'Detections') : g.name
        if (!g.name.isEmpty() && groups.containsKey('A' + g.name) && groups.containsKey('D' + g.name)) {
            label += g.annotation ? ' (annotations)' : ' (detections)'
        }
        String kind
        if (g.points == g.count) kind = 'POINT'
        else if (g.lines == g.count) kind = 'LINE'
        else if (g.annotation ? fillAnnotations : fillDetections) kind = 'FILL'
        else kind = 'OUTLINE'
        int best = 0
        int bestCount = -1
        for (def e : g.colors.entrySet()) {
            int c = e.key
            int n = e.value
            if (n > bestCount || (n == bestCount && c < best)) { best = c; bestCount = n }
        }
        entries << [name: label, rgb: best, kind: kind]
    }
    return entries
}

// The classes of a class-index map, as legend rows
List quietMapClassEntries(layer) {
    def entries = []
    def names = layer.classNames
    def colors = layer.classColors
    for (int i = 0; i < names.size(); i++) entries << [name: names[i], rgb: ((int) colors[i]) & 0xFFFFFF, kind: 'FILL']
    return entries
}

// Draw the text in QuIET's order: scale bar, map scale, panel label, channel and object legend, info label.
// t: the textOverlay settings; mapScale: null, [classes: rows] or [colorMap:, min:, max:, title:]
void quietDrawTextOverlays(java.awt.Graphics2D g2d, java.awt.image.BufferedImage result, imageData, display, Map t,
                           Map mapScale, int x, int y, int w, int h, int outW, int outH, double downsample,
                           String panelLabel, String infoText, boolean objectsDrawn) {
    def textColor = quietColor(t.textColor)
    String background = t.background ?: 'AUTO'
    if (t.showScaleBar) {
        def cal = imageData.getServer().getPixelCalibration()
        if (cal.hasPixelSizeMicrons()) {
            g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f))
            quietDrawScaleBar(g2d, outW, outH, cal.getAveragedPixelSizeMicrons() * downsample, t.scaleBarPosition,
                    quietColor(t.scaleBarColor), t.scaleBarFontSize, t.scaleBarBold,
                    t.scaleBarBox ? 'BOX' : background, result)
        } else {
            println '  WARNING: Scale bar skipped -- no pixel calibration'
        }
    }
    def channels = t.showChannelLegend ? quietChannelEntries(imageData, display) : []
    def objects = t.showObjectLegend && objectsDrawn
            ? quietObjectEntries(imageData, x, y, w, h, t.includeAnnotations, t.includeDetections, t.fillAnnotations) : []
    if (mapScale != null) {
        g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f))
        if (mapScale.classes) {
            quietDrawCornerLegend(g2d, mapScale.classes, t.colorScaleBarPosition, outW, outH, background, textColor, result)
        } else {
            quietDrawColorScaleBar(g2d, outW, outH, mapScale.colorMap, mapScale.min, mapScale.max, t.colorScaleBarPosition,
                    t.colorScaleBarFontSize, t.colorScaleBarBold, mapScale.title, background, textColor, result)
        }
    }
    if (t.showPanelLabel && panelLabel) {
        g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f))
        quietDrawPanelLabel(g2d, outW, outH, panelLabel, t.panelLabelPosition, t.panelLabelFontSize, t.panelLabelBold,
                textColor, background, result)
    }
    def rows = channels + objects
    if (!rows.isEmpty()) {
        quietDrawCornerLegend(g2d, rows, t.legendPosition, outW, outH, background, textColor, result)
    }
    if (t.showInfoLabel && infoText) {
        g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f))
        quietDrawInfoLabel(g2d, outW, outH, infoText, t.infoLabelPosition, t.infoLabelFontSize, t.infoLabelBold,
                textColor, background, result)
    }
}
