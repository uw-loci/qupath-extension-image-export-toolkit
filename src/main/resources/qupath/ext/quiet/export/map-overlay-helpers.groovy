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
            case 'imageBase': v = GeneralTools.stripExtension(imageName); break
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
    def glob = java.nio.file.FileSystems.getDefault().getPathMatcher('glob:' + namePart)
    def matches = dir.isDirectory()
            ? dir.listFiles().findAll { it.isFile() && glob.matches(it.toPath().getFileName()) }
            : []
    if (matches.size() != 1) {
        throw new IllegalArgumentException(matches.size() + ' map files match ' + new File(dir, namePart).getAbsolutePath())
    }
    return matches[0]
}

// Open the map for one image: [server, channel, sx, sy] (map pixels per image pixel)
def openMapLayer(project, entry, imageData, String source, densityBuilder, String pattern, String channelName) {
    def base = imageData.getServer()
    if (source != 'IMAGE_FILE') {
        return [server: densityBuilder.buildServer(imageData), channel: 0, sx: 1.0d, sy: 1.0d]
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
    int c = 0
    if (channelName != null && !channelName.isBlank()) {
        def names = server.getMetadata().getChannels().collect { it.getName() }
        c = names.indexOf(channelName)
        if (c < 0) c = names.findIndexOf { it.equalsIgnoreCase(channelName) }
        if (c < 0) {
            server.close()
            throw new IllegalArgumentException("the map has no channel '" + channelName + "' (it has " + names.join(', ') + ")")
        }
    }
    return [server: server, channel: c,
            sx: server.getWidth() / (double) base.getWidth(),
            sy: server.getHeight() / (double) base.getHeight()]
}

// Read one map channel over an image region: [data (float[]), width, height].
// A region too large to read with all its channels at once is read in strips that
// overlap by a few rows (cropped off), so there is no seam between strips.
def readMapBand(layer, int x, int y, int w, int h, double downsample) {
    def server = layer.server
    double sx = layer.sx, sy = layer.sy
    int mx = (int) Math.floor(x * sx)
    int my = (int) Math.floor(y * sy)
    int mw = Math.max(1, Math.min(server.getWidth(), (int) Math.ceil((x + w) * sx)) - mx)
    int mh = Math.max(1, Math.min(server.getHeight(), (int) Math.ceil((y + h) * sy)) - my)
    double mds = Math.max(1.0d, downsample * Math.sqrt(sx * sy))
    long bytes = (long) Math.ceil(mw / mds) * (long) Math.ceil(mh / mds) *
            server.nChannels() * Math.max(1, server.getPixelType().getBytesPerPixel())
    if (bytes <= 128L * 1024 * 1024) {
        def img = server.readRegion(RegionRequest.createInstance(server.getPath(), mds, mx, my, mw, mh))
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

// Color range for a band: fixed/resolved values, own percentiles (SHARED), or own min to max
def mapRangeFor(band, String mode, double fixedMin, double fixedMax, double lowP, double highP, boolean resolved) {
    if (mode == 'FIXED' || (mode == 'SHARED' && resolved)) return [fixedMin, fixedMax]
    float[] data = band.data
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

// Color a band: NaN transparent, values outside the range take the end colors
def colorizeBand(band, colorMap, double minV, double maxV) {
    int w = band.width, h = band.height
    float[] data = band.data
    int[] argb = new int[w * h]
    double lo = Math.min(minV, maxV), hi = Math.max(minV, maxV)
    for (int i = 0; i < argb.length; i++) {
        float v = data[i]
        if (Float.isNaN(v)) continue
        double c = Math.max(lo, Math.min(hi, (double) v))
        argb[i] = 0xFF000000 | (colorMap.getColor(c, minV, maxV) & 0x00FFFFFF)
    }
    def img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, w, h, argb, 0, w)
    return img
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
            def band = readMapBand(layer, 0, 0, base.getWidth(), base.getHeight(), ds)
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

