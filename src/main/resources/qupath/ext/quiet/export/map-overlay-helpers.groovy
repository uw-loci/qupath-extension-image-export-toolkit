// ---------- Map overlay helpers (same rules as QuIET's own export) ----------

// File-safe image name for the {imageSafe} token
def mapSafeName(String name) {
    if (name == null || name.isBlank()) return 'image'
    String s = name.replaceAll('[\\\\/:*?"<>|\\s]+', '_').replaceAll('[. ]+$', '')
    return s.isBlank() ? 'image' : s
}

// The one file matching the pattern for an image
def resolveMapFile(String pattern, File projectDir, File imageDir, String imageName) {
    def m = (pattern.trim() =~ /\{(\w+)(?::(\d+))?\}/)
    def sb = new StringBuffer()
    while (m.find()) {
        String token = m.group(1)
        String v
        switch (token) {
            case 'project':
                if (projectDir == null) throw new IllegalArgumentException('{project} used but no project is open')
                v = projectDir.getAbsolutePath().replace('\\', '/')
                break
            case 'imageDir':
                if (imageDir == null) throw new IllegalArgumentException('{imageDir} used but the image is not a local file')
                v = imageDir.getAbsolutePath().replace('\\', '/')
                break
            case 'image': v = imageName; break
            case 'imageBase': v = GeneralTools.stripExtension(imageName) ?: imageName; break
            case 'imageSafe': v = mapSafeName(imageName); break
            default: throw new IllegalArgumentException('unknown token {' + token + '}')
        }
        if (m.group(2) != null) {
            int n = Integer.parseInt(m.group(2))
            if (v.length() > n) v = v.substring(0, n)
        }
        m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v))
    }
    m.appendTail(sb)
    String expanded = sb.toString().replace('\\', '/')
    int slash = expanded.lastIndexOf('/')
    String namePart = slash >= 0 ? expanded.substring(slash + 1) : expanded
    String dirPart = slash >= 0 ? expanded.substring(0, slash) : ''
    if (dirPart.contains('*') || dirPart.contains('?')) {
        throw new IllegalArgumentException('wildcards are allowed only in the file name, not in folders: ' + pattern)
    }
    File dir = slash < 0 ? null : new File(slash == 0 ? '/' : expanded.substring(0, slash))
    if (dir == null || !dir.isAbsolute()) {
        if (projectDir == null) throw new IllegalArgumentException('the pattern is relative but there is no project folder')
        dir = dir == null ? projectDir : new File(projectDir, dir.getPath())
    }
    dir = dir.toPath().normalize().toFile()
    if (!namePart.contains('*') && !namePart.contains('?')) {
        def f = new File(dir, namePart)
        if (!f.isFile()) throw new IllegalArgumentException('no map file ' + f.getAbsolutePath())
        return f
    }
    // Glob characters other than * and ? in a name are literal, as in QuIET
    def escaped = new StringBuilder()
    for (char c : namePart.toCharArray()) {
        if ('[]{}\\,'.indexOf((int) c) >= 0) escaped.append('\\')
        escaped.append(c)
    }
    def glob = java.nio.file.FileSystems.getDefault().getPathMatcher('glob:' + escaped)
    def matches = dir.isDirectory()
            ? dir.listFiles().findAll { it.isFile() && glob.matches(it.toPath().getFileName()) }
            : []
    if (matches.size() != 1) {
        throw new IllegalArgumentException(matches.size() + ' map files match ' + new File(dir, namePart).getAbsolutePath())
    }
    return matches[0]
}

// The ImageDescription of a TIFF file's first image (classic TIFF and BigTIFF), or null
def readTiffDescription(File file) {
    if (file == null || !file.isFile()) return null
    try {
        def raf = new RandomAccessFile(file, 'r')
        try {
            return mapTiffDescription(raf)
        } finally {
            raf.close()
        }
    } catch (Exception e) {
        return null
    }
}

def mapTiffDescription(RandomAccessFile raf) {
    byte[] header = new byte[16]
    if (raf.read(header) < 8) return null
    java.nio.ByteOrder order = null
    if (header[0] == 0x49 && header[1] == 0x49) order = java.nio.ByteOrder.LITTLE_ENDIAN
    if (header[0] == 0x4D && header[1] == 0x4D) order = java.nio.ByteOrder.BIG_ENDIAN
    if (order == null) return null
    def hb = java.nio.ByteBuffer.wrap(header).order(order)
    int magic = hb.getShort(2) & 0xFFFF
    boolean big = magic == 43
    if (!big && magic != 42) return null
    long ifd = big ? hb.getLong(8) : (hb.getInt(4) & 0xFFFFFFFFL)
    raf.seek(ifd)
    long count = big ? mapReadBuf(raf, 8, order).getLong() : (mapReadBuf(raf, 2, order).getShort() & 0xFFFF)
    for (long i = 0; i < count; i++) {
        def e = mapReadBuf(raf, big ? 20 : 12, order)
        int tag = e.getShort() & 0xFFFF
        e.getShort()
        long n = big ? e.getLong() : (e.getInt() & 0xFFFFFFFFL)
        if (tag != 270) continue
        if (n <= 0 || n > 16L * 1024 * 1024) return null
        byte[] text = new byte[(int) n]
        if (n <= (big ? 8 : 4)) {
            e.get(text)
        } else {
            raf.seek(big ? e.getLong() : (e.getInt() & 0xFFFFFFFFL))
            raf.readFully(text)
        }
        int len = text.length
        while (len > 0 && text[len - 1] == 0) len--
        return new String(text, 0, len, java.nio.charset.StandardCharsets.UTF_8)
    }
    return null
}

def mapReadBuf(RandomAccessFile raf, int n, order) {
    byte[] b = new byte[n]
    raf.readFully(b)
    return java.nio.ByteBuffer.wrap(b).order(order)
}

// Where a map's grid sits on its image, from a 'Placement:' line in its description; null if none
def parseMapPlacement(String description) {
    if (description == null) return null
    String line = description.readLines().collect { it.trim() }.find { it.startsWith('Placement:') }
    if (line == null) return null
    def kv = [:]
    def m = (line.substring('Placement:'.length()) =~ /(\w+)=([^;\s]+)/)
    while (m.find()) kv[m.group(1)] = m.group(2)
    try {
        String[] grid = kv['grid'].toLowerCase().split('x')
        String render = (kv['render'] ?: 'nearest').toLowerCase()
        if (render != 'nearest' && render != 'bilinear') throw new IllegalArgumentException("unknown render '" + render + "'")
        def p = [sourceWidth: Integer.parseInt(kv['source_width']), sourceHeight: Integer.parseInt(kv['source_height']),
                 cellPx: Double.parseDouble(kv['cell_px']),
                 x0: Double.parseDouble(kv['x0'] ?: '0'), y0: Double.parseDouble(kv['y0'] ?: '0'),
                 gridWidth: Integer.parseInt(grid[0]), gridHeight: Integer.parseInt(grid[1]),
                 bilinear: render == 'bilinear']
        if (!(p.cellPx > 0) || p.gridWidth <= 0 || p.gridHeight <= 0 || p.sourceWidth <= 0 || p.sourceHeight <= 0)
            throw new IllegalArgumentException('the geometry is not usable')
        return p
    } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("the map's Placement line cannot be read (" + e.getMessage() + "):" + line.substring(10), e)
    } catch (RuntimeException e) {
        throw new IllegalArgumentException("the map's Placement line cannot be read (" + e + "):" + line.substring(10), e)
    }
}

// The period of a channel marked '[c] name=...; axial=P' in a description, or 0
def mapAxialPeriod(String description, int c) {
    if (description == null) return 0d
    def m = (description =~ /\[(\d+)]\s*name=[^\r\n]*?;\s*axial=([0-9.]+)/)
    while (m.find()) {
        if (Integer.parseInt(m.group(1)) == c) {
            try { double p = Double.parseDouble(m.group(2)); return p > 0 ? p : 0d } catch (NumberFormatException e) { return 0d }
        }
    }
    return 0d
}

// Class names for a '... class index' channel: the map's P(<class>) channels in order; null otherwise
def mapClassNames(server, int c) {
    String name = server.getChannel(c).getName()
    if (name == null || !name.trim().toLowerCase().endsWith('class index')) return null
    def names = []
    for (def ch : server.getMetadata().getChannels()) {
        String n = ch.getName() == null ? '' : ch.getName().trim()
        if (n.startsWith('P(') && n.endsWith(')') && n.length() > 3) names << n.substring(2, n.length() - 1)
    }
    return names.isEmpty() ? null : names
}

// Open the map for one image. A map with a Placement line is a grid placed on the image;
// one without is stretched over the whole image.
def openMapLayer(project, entry, imageData, String source, densityBuilder, String pattern, String channelName) {
    def base = imageData.getServer()
    if (source != 'IMAGE_FILE') {
        def server = densityBuilder.buildServer(imageData)
        return [server: server, channel: 0, originX: 0d, originY: 0d,
                cellW: base.getWidth() / (double) server.getWidth(), cellH: base.getHeight() / (double) server.getHeight(),
                bilinear: true, period: 0d, classNames: null, classColors: null]
    }
    File projectDir = project.getPath()?.getParent()?.toFile()
    File imageDir = null
    for (def uri : entry.getURIs()) {
        if (uri.getScheme() == 'file') {
            imageDir = new File(uri).getParentFile()
            break
        }
    }
    def file = resolveMapFile(pattern, projectDir, imageDir, entry.getImageName())
    def server = ImageServers.buildServer(file.toURI())
    try {
        int c = 0
        if (channelName != null && !channelName.isBlank()) {
            def names = server.getMetadata().getChannels().collect { it.getName() }
            c = names.indexOf(channelName)
            if (c < 0) c = names.findIndexOf { it.equalsIgnoreCase(channelName) }
            if (c < 0) throw new IllegalArgumentException("the map has no channel '" + channelName + "' (it has " + names.join(', ') + ")")
        }
        String description = readTiffDescription(file)
        def placement
        try {
            placement = parseMapPlacement(description)
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(file.getName() + ': ' + e.getMessage(), e)
        }
        def classNames = mapClassNames(server, c)
        def classColors = classNames?.collect {
            Integer rgb = qupath.lib.objects.classes.PathClass.fromString(it).getColor()
            rgb == null ? 0x808080 : (rgb & 0xFFFFFF)
        }
        def layer = [server: server, channel: c, period: mapAxialPeriod(description, c),
                     classNames: classNames, classColors: classColors]
        if (placement != null) {
            if (placement.gridWidth != server.getWidth() || placement.gridHeight != server.getHeight())
                throw new IllegalArgumentException(file.getName() + ': the map is ' + server.getWidth() + ' x ' + server.getHeight() +
                        ' but its Placement line says the grid is ' + placement.gridWidth + 'x' + placement.gridHeight)
            if (placement.sourceWidth != base.getWidth() || placement.sourceHeight != base.getHeight())
                throw new IllegalArgumentException(file.getName() + ': the map was made for a ' + placement.sourceWidth + ' x ' +
                        placement.sourceHeight + ' image but this image is ' + base.getWidth() + ' x ' + base.getHeight())
            layer.originX = placement.x0
            layer.originY = placement.y0
            layer.cellW = placement.cellPx
            layer.cellH = placement.cellPx
            layer.bilinear = placement.bilinear
        } else {
            layer.originX = 0d
            layer.originY = 0d
            layer.cellW = base.getWidth() / (double) server.getWidth()
            layer.cellH = base.getHeight() / (double) server.getHeight()
            layer.bilinear = true
        }
        return layer
    } catch (Exception e) {
        server.close()
        throw e
    }
}

// Read one channel of a map rectangle at a downsample: [data (float[]), width, height].
// A region too large to read with all its channels at once is read in strips that
// overlap by a few rows (cropped off), so there is no seam between strips.
def readMapPixels(layer, int mx, int my, int mw, int mh, double mds) {
    def server = layer.server
    long bytes = (long) Math.ceil(mw / mds) * (long) Math.ceil(mh / mds) *
            server.nChannels() * Math.max(1, server.getPixelType().getBytesPerPixel())
    if (bytes <= 128L * 1024 * 1024) {
        def img = server.readRegion(RegionRequest.createInstance(server.getPath(), mds, mx, my, mw, mh))
        if (img == null) throw new IOException('the map returned no pixels for this region')
        def raster = img.getRaster()
        return [data: raster.getSamples(0, 0, raster.getWidth(), raster.getHeight(), layer.channel, (float[]) null),
                width: raster.getWidth(), height: raster.getHeight()]
    }
    int stripRows = (int) Math.max(1, Math.round(256 * mds))
    int marginRows = 2
    for (int k = 2; k <= 16; k++) {
        if (Math.abs(k * mds - Math.rint(k * mds)) < 1e-6) { marginRows = k; break }
    }
    int marginMap = (int) Math.ceil(marginRows * mds)
    def strips = []
    int width = -1
    int height = 0
    for (int sy0 = my; sy0 < my + mh; sy0 += stripRows) {
        int sh = Math.min(stripRows, my + mh - sy0)
        int top = Math.min(marginMap, sy0 - my)
        int bottom = Math.min(marginMap, my + mh - (sy0 + sh))
        def img = server.readRegion(RegionRequest.createInstance(server.getPath(), mds, mx, sy0 - top, mw, sh + top + bottom))
        if (img == null) continue
        def raster = img.getRaster()
        int cropTop = (int) Math.round(top / mds)
        int cropBottom = (int) Math.round(bottom / mds)
        int rows = raster.getHeight() - cropTop - cropBottom
        if (rows <= 0) continue
        if (width < 0) width = raster.getWidth()
        int cw = Math.min(width, raster.getWidth())
        float[] vals = raster.getSamples(0, cropTop, cw, rows, layer.channel, (float[]) null)
        float[] row = new float[width * rows]
        java.util.Arrays.fill(row, Float.NaN)
        for (int r = 0; r < rows; r++) System.arraycopy(vals, r * cw, row, r * width, cw)
        strips.add(row)
        height += rows
    }
    if (width <= 0 || height <= 0) throw new IOException('the map returned no pixels for this region')
    float[] data = new float[width * height]
    int off = 0
    for (float[] s : strips) {
        System.arraycopy(s, 0, data, off, s.length)
        off += s.length
    }
    return [data: data, width: width, height: height]
}

// The map cells under an image region plus a margin for blending:
// [band, mapX, mapY, fx, fy (map pixels per band pixel), core: [x0, y0, x1, y1] of the region itself]
def readMapRegion(layer, int x, int y, int w, int h, double downsample) {
    def server = layer.server
    int mapW = server.getWidth(), mapH = server.getHeight()
    int cx0 = Math.max(0, (int) Math.floor((x - layer.originX) / layer.cellW))
    int cy0 = Math.max(0, (int) Math.floor((y - layer.originY) / layer.cellH))
    int cx1 = Math.min(mapW, (int) Math.ceil((x + w - layer.originX) / layer.cellW))
    int cy1 = Math.min(mapH, (int) Math.ceil((y + h - layer.originY) / layer.cellH))
    if (cx1 <= cx0 || cy1 <= cy0) {
        return [band: [data: new float[0], width: 0, height: 0], mapX: 0, mapY: 0, fx: 1d, fy: 1d, core: [0, 0, 0, 0]]
    }
    // Never read finer than the output; angles and class indices at full resolution
    double mds = layer.period > 0 || layer.classNames != null ? 1.0d :
            Math.max(1.0d, downsample / Math.sqrt(layer.cellW * layer.cellH))
    int margin = (int) Math.ceil(mds) + 1
    int mx = Math.max(0, cx0 - margin), my = Math.max(0, cy0 - margin)
    int mw = Math.min(mapW, cx1 + margin) - mx, mh = Math.min(mapH, cy1 + margin) - my
    def band = readMapPixels(layer, mx, my, mw, mh, mds)
    double fx = mw / (double) band.width, fy = mh / (double) band.height
    int bx0 = Math.max(0, Math.min(band.width - 1, (int) Math.floor((cx0 - mx) / fx)))
    int by0 = Math.max(0, Math.min(band.height - 1, (int) Math.floor((cy0 - my) / fy)))
    int bx1 = Math.max(bx0 + 1, Math.min(band.width, (int) Math.ceil((cx1 - mx) / fx)))
    int by1 = Math.max(by0 + 1, Math.min(band.height, (int) Math.ceil((cy1 - my) / fy)))
    return [band: band, mapX: mx, mapY: my, fx: fx, fy: fy, core: [bx0, by0, bx1, by1]]
}

// The values under the region itself (no margin): [data, width, height]
def mapCoreBand(region) {
    def band = region.band
    if (band.width == 0) return band
    def c = region.core
    int cw = c[2] - c[0], ch = c[3] - c[1]
    float[] out = new float[cw * ch]
    for (int r = 0; r < ch; r++) System.arraycopy(band.data, (c[1] + r) * band.width + c[0], out, r * cw, cw)
    return [data: out, width: cw, height: ch]
}

// Finite values, thinned by an even stride to at most max
def mapFiniteSample(float[] values, int max) {
    int finite = 0
    for (float v : values) if (Float.isFinite(v)) finite++
    int stride = Math.max(1, (int) Math.ceil(finite / (double) max))
    float[] out = new float[(int) ((finite + stride - 1) / stride)]
    int seen = 0, n = 0
    for (float v : values) {
        if (!Float.isFinite(v)) continue
        if (seen++ % stride == 0 && n < out.length) out[n++] = v
    }
    return n == out.length ? out : java.util.Arrays.copyOf(out, n)
}

// Percentile of sorted values, linear interpolation
def mapPercentile(float[] sorted, double p) {
    double pos = Math.max(0d, Math.min(100d, p)) / 100.0d * (sorted.length - 1)
    int lo = (int) Math.floor(pos)
    int hi = Math.min(sorted.length - 1, lo + 1)
    double t = pos - lo
    return sorted[lo] * (1 - t) + sorted[hi] * t
}

// Color range for a region: an angle channel spans 0 to its period unless FIXED, a class
// channel its classes; otherwise fixed/resolved values, own percentiles (SHARED), or own min to max
def mapRangeFor(layer, region, String mode, double fixedMin, double fixedMax, double lowP, double highP, boolean resolved) {
    if (layer.classNames != null) return [0d, (double) (layer.classNames.size() - 1)]
    if (layer.period > 0 && mode != 'FIXED') return [0d, (double) layer.period]
    if (mode == 'FIXED' || (mode == 'SHARED' && resolved)) return [fixedMin, fixedMax]
    float[] data = mapCoreBand(region).data
    if (mode == 'SHARED') {
        float[] s = mapFiniteSample(data, 2000000)
        if (s.length == 0) return [0d, 1d]
        java.util.Arrays.sort(s)
        return [mapPercentile(s, lowP), mapPercentile(s, highP)]
    }
    double mn = Double.POSITIVE_INFINITY, mx = Double.NEGATIVE_INFINITY
    for (float v : data) {
        if (!Float.isFinite(v)) continue
        if (v < mn) mn = v
        if (v > mx) mx = v
    }
    return mn > mx ? [0d, 1d] : [mn, mx]
}

// Where each output column (or row) samples the band
def mapAxis(int start, int length, int out, double origin, double cell, int mapSize, int bandStart, double perBandPixel, int bandSize) {
    boolean[] inside = new boolean[out]
    int[] n = new int[out], i0 = new int[out], i1 = new int[out]
    double[] t = new double[out]
    double scale = length / (double) out
    double end = origin + mapSize * cell
    for (int o = 0; o < out; o++) {
        double b = start + (o + 0.5d) * scale
        if (b < origin || b >= end) continue
        inside[o] = true
        double u = ((b - origin) / cell - bandStart) / perBandPixel
        n[o] = Math.max(0, Math.min(bandSize - 1, (int) Math.floor(u)))
        double f = u - 0.5d
        int lo = (int) Math.floor(f)
        t[o] = f - lo
        i0[o] = Math.max(0, Math.min(bandSize - 1, lo))
        i1[o] = Math.max(0, Math.min(bandSize - 1, lo + 1))
    }
    return [inside: inside, n: n, i0: i0, i1: i1, t: t]
}

// Draw the channel over an image region at the output's size: off-grid, NaN and (with hide)
// values at or below the minimum are transparent; values outside the range take the end colors.
// Values are blended between cell centers (NaN cells left out, an angle blended as an angle)
// when the map says bilinear; a class channel is never blended and is drawn in class colors.
def paintMapRegion(layer, region, int x, int y, int w, int h, int outW, int outH, colorMap, double minV, double maxV, boolean hide) {
    int[] argb = new int[outW * outH]
    def band = region.band
    if (band.width > 0) {
        def server = layer.server
        def cols = mapAxis(x, w, outW, layer.originX, layer.cellW, server.getWidth(), region.mapX, region.fx, band.width)
        def rows = mapAxis(y, h, outH, layer.originY, layer.cellH, server.getHeight(), region.mapY, region.fy, band.height)
        boolean[] cin = cols.inside, rin = rows.inside
        int[] cn = cols.n, ci0 = cols.i0, ci1 = cols.i1, rn = rows.n, ri0 = rows.i0, ri1 = rows.i1
        double[] ct = cols.t, rt = rows.t
        float[] data = band.data
        int bw = band.width
        boolean classes = layer.classNames != null
        int[] classColors = classes ? (layer.classColors as int[]) : null
        boolean blend = layer.bilinear && !classes
        double period = layer.period
        double k = period > 0 ? 2 * Math.PI / period : 0d
        double lo = Math.min(minV, maxV), hi = Math.max(minV, maxV)
        for (int oy = 0; oy < outH; oy++) {
            if (!rin[oy]) continue
            for (int ox = 0; ox < outW; ox++) {
                if (!cin[ox]) continue
                float v
                if (blend) {
                    double sum = 0d, sumSin = 0d, wsum = 0d
                    for (int dy = 0; dy <= 1; dy++) {
                        int row = (dy == 0 ? ri0[oy] : ri1[oy]) * bw
                        double wy = dy == 0 ? 1 - rt[oy] : rt[oy]
                        for (int dx = 0; dx <= 1; dx++) {
                            double wt = wy * (dx == 0 ? 1 - ct[ox] : ct[ox])
                            float val = data[row + (dx == 0 ? ci0[ox] : ci1[ox])]
                            if (wt > 0 && !Float.isNaN(val)) {
                                if (period > 0) {
                                    sum += wt * Math.cos(k * val)
                                    sumSin += wt * Math.sin(k * val)
                                } else {
                                    sum += wt * val
                                }
                                wsum += wt
                            }
                        }
                    }
                    if (wsum <= 0) continue
                    if (period > 0) {
                        double a = Math.atan2(sumSin, sum) * period / (2 * Math.PI)
                        v = (float) (a < 0 ? a + period : (a >= period ? a - period : a))
                    } else {
                        v = (float) (sum / wsum)
                    }
                } else {
                    v = data[rn[oy] * bw + cn[ox]]
                }
                if (Float.isNaN(v)) continue
                if (classes) {
                    int ci = Math.round(v)
                    if (ci != v || ci < 0 || ci >= classColors.length) continue
                    argb[oy * outW + ox] = 0xFF000000 | classColors[ci]
                } else {
                    if (hide && v <= lo) continue
                    double c = Math.max(lo, Math.min(hi, (double) v))
                    argb[oy * outW + ox] = 0xFF000000 | (colorMap.getColor(c, minV, maxV) & 0x00FFFFFF)
                }
            }
        }
    }
    def img = new BufferedImage(Math.max(1, outW), Math.max(1, outH), BufferedImage.TYPE_INT_ARGB)
    if (outW > 0 && outH > 0) img.setRGB(0, 0, outW, outH, argb, 0, outW)
    return img
}

// A legend of the class channel's colors and names in a corner, as QuIET draws it
def drawMapClassLegend(Graphics2D g2d, layer, int w, int h, String pos) {
    def names = layer.classNames
    def colors = layer.classColors
    int n = names.size()
    if (n == 0) return
    int minDim = Math.min(w, h)
    int baseSwatch = Math.max(8, (int) (minDim / 30))
    int margin = Math.max(6, (int) (minDim / 50))
    int baseFont = Math.max(10, (int) (minDim / 45))
    int baseLineSpacing = Math.max(2, (int) (baseSwatch / 4))
    int maxLegendH = Math.max(1, (int) (h * 0.5))
    int maxLegendW = Math.max(1, (int) (w * 0.5))
    int baseRow = baseSwatch + baseLineSpacing
    int rowsPerColAtBase = Math.max(1, (int) (maxLegendH / baseRow))
    int columns = Math.max(1, Math.min(4, (int) ((n + rowsPerColAtBase - 1) / rowsPerColAtBase)))
    int rowsPerCol = (int) ((n + columns - 1) / columns)
    g2d.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, baseFont))
    int widestText = names.collect { g2d.getFontMetrics().stringWidth(it) }.max()
    int legendWAtBase = columns * (baseSwatch + margin + widestText + margin)
    int legendHAtBase = rowsPerCol * baseRow
    double scale = Math.min(1.0d, Math.min((double) maxLegendW / Math.max(1, legendWAtBase), (double) maxLegendH / Math.max(1, legendHAtBase)))
    int swatchSize = Math.max(6, (int) Math.round(baseSwatch * scale))
    int lineSpacing = Math.max(1, (int) Math.round(baseLineSpacing * scale))
    int fontSize = Math.max(8, (int) Math.round(baseFont * scale))
    g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f))
    g2d.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, fontSize))
    def fm = g2d.getFontMetrics()
    int actualWidest = names.collect { fm.stringWidth(it) }.max()
    int colWidth = swatchSize + (int) (margin / 2) + actualWidest + (int) (margin / 2)
    int rowPitch = swatchSize + lineSpacing
    int legendW = columns * colWidth, legendH = rowsPerCol * rowPitch
    int originX = pos in ['UPPER_RIGHT', 'LOWER_RIGHT'] ? Math.max(margin, w - margin - legendW) : margin
    int originY = pos in ['LOWER_LEFT', 'LOWER_RIGHT'] ? Math.max(margin, h - margin - legendH) : margin
    for (int i = 0; i < n; i++) {
        int x0 = originX + ((int) (i / rowsPerCol)) * colWidth
        int y0 = originY + (i % rowsPerCol) * rowPitch
        g2d.setColor(new java.awt.Color((int) colors[i]))
        g2d.fillRect(x0, y0, swatchSize, swatchSize)
        g2d.setColor(java.awt.Color.WHITE)
        g2d.drawRect(x0, y0, swatchSize, swatchSize)
        int textX = x0 + swatchSize + (int) (margin / 2)
        int textY = y0 + swatchSize - 2
        g2d.setColor(java.awt.Color.BLACK)
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) if (dx != 0 || dy != 0) g2d.drawString(names[i], textX + dx, textY + dy)
        g2d.setColor(java.awt.Color.WHITE)
        g2d.drawString(names[i], textX, textY)
    }
}

// One range over all images: percentiles of their pooled map values
def computeSharedMapRange(project, entries, String source, densityBuilder, String pattern,
                          String channelName, double downsample, double lowP, double highP) {
    def samples = []
    int perImage = Math.max(10000, (int) (2000000 / Math.max(1, entries.size())))
    for (def entry : entries) {
        def imageData = null
        def layer = null
        try {
            imageData = entry.readImageData()
            def base = imageData.getServer()
            layer = openMapLayer(project, entry, imageData, source, densityBuilder, pattern, channelName)
            double ds = Math.max(Math.max(1.0d, downsample), Math.max(base.getWidth(), base.getHeight()) / 4096.0d)
            def band = mapCoreBand(readMapRegion(layer, 0, 0, base.getWidth(), base.getHeight(), ds))
            float[] s = mapFiniteSample(band.data, perImage)
            if (s.length > 0) samples.add(s)
        } catch (Exception e) {
            println "  Range scan skipped ${entry.getImageName()}: ${e.getMessage()}"
        } finally {
            try { layer?.server?.close() } catch (Exception ignored) {}
            try { imageData?.getServer()?.close() } catch (Exception ignored) {}
        }
    }
    if (samples.isEmpty()) return null
    int n = samples.sum { it.length }
    float[] pooled = new float[n]
    int off = 0
    for (float[] s : samples) {
        System.arraycopy(s, 0, pooled, off, s.length)
        off += s.length
    }
    java.util.Arrays.sort(pooled)
    return [mapPercentile(pooled, lowP), mapPercentile(pooled, highP)]
}

