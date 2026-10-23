
// --- Matched display range helpers (the same procedure as QuIET's GlobalDisplayRangeScanner) ---

// The kind an image is matched within: pixel type and channel names, e.g. "UINT8 [Red, Green, Blue]".
String quietImageKind(metadata) {
    return metadata.getPixelType().toString() + ' ' + metadata.getChannels().collect { it.getName() }.toString()
}

// Display settings per image kind: images are grouped by kind, and each group gets one
// minimum and maximum per channel over all of its images (built on its first image).
Map quietMatchedSettingsByKind(entries, double percentile, double ds) {
    def byKind = new LinkedHashMap()
    def channelsByKind = [:]
    for (entry in entries) {
        try {
            def server = entry.getServerBuilder().build()
            def kind = quietImageKind(server.getMetadata())
            if (!byKind.containsKey(kind)) {
                byKind[kind] = []
                channelsByKind[kind] = server.getMetadata().getChannels().size()
            }
            byKind[kind] << entry
            server.close()
        } catch (Exception e) {
            println "  WARNING: Cannot open ${entry.getImageName()}: ${e.getMessage()}"
        }
    }
    def result = [:]
    byKind.each { kind, group ->
        println "Matching ${group.size()} image(s) of kind ${kind}"
        double[][] ranges = quietRangesFromImages(group.size(),
                { int j -> quietReadScanImage(group[j], ds) }, (int) channelsByKind[kind], percentile)
        println '  Ranges: ' + ranges.collect { String.format('%.6g to %.6g', it[0], it[1]) }.join(', ')
        def data = group[0].readImageData()
        def display = ImageDisplay.create(data)
        def channels = display.selectedChannels()
        for (int c = 0; c < Math.min(channels.size(), ranges.length); c++) {
            display.setMinMaxDisplay(channels[c], (float) ranges[c][0], (float) ranges[c][1])
        }
        result[kind] = DisplaySettingUtils.displayToSettings(display, 'global_matched')
        data.getServer().close()
    }
    return result
}

// One image at the scan downsample as [raster, pixelType], or null if it cannot be read.
def quietReadScanImage(entry, double ds) {
    try {
        def server = entry.getServerBuilder().build()
        def request = qupath.lib.regions.RegionRequest.createInstance(
                server.getPath(), ds, 0, 0, server.getWidth(), server.getHeight())
        def raster = server.readRegion(request).getRaster()
        def type = server.getPixelType()
        server.close()
        return [raster, type]
    } catch (Exception e) {
        println "  WARNING: Scan failed for ${entry.getImageName()}: ${e.getMessage()}"
        return null
    }
}

// Per-channel {min, max} over n images of one kind. Unsigned images of up to 16 bits share an exact
// 65536-bin histogram, whatever the first image's depth; if any image is signed, floating
// point or deeper, every image is read again in two passes (min and max, then 10000 bins).
@groovy.transform.CompileStatic
double[][] quietRangesFromImages(int n, Closure reader, int nChannels, double percentile) {
    long[][] histograms = new long[nChannels][65536]
    int maxBits = 0
    boolean integer = true
    for (int i = 0; i < n && integer; i++) {
        println "  Scanning ${i + 1} of ${n}..."
        List img = (List) reader.call(i)
        if (img == null) continue
        def type = (qupath.lib.images.servers.PixelType) img[1]
        if (!type.isUnsignedInteger() || type.getBitsPerPixel() > 16) {
            integer = false
            break
        }
        maxBits = Math.max(maxBits, type.getBitsPerPixel())
        def raster = (java.awt.image.Raster) img[0]
        int bands = Math.min(raster.getNumBands(), nChannels)
        for (int y = 0; y < raster.getHeight(); y++) {
            for (int x = 0; x < raster.getWidth(); x++) {
                for (int c = 0; c < bands; c++) {
                    int val = raster.getSample(x, y, c)
                    if (val >= 0 && val < 65536) histograms[c][val]++
                }
            }
        }
    }
    if (integer) {
        double fullScale = maxBits > 8 ? 65535 : 255
        double[][] result = new double[nChannels][]
        for (int c = 0; c < nChannels; c++) {
            long total = 0
            for (long count : histograms[c]) total += count
            if (total == 0) {
                result[c] = [0d, fullScale] as double[]
                continue
            }
            double clipCount = total * percentile / 100.0d
            double minVal = quietPercentileBin(histograms[c], clipCount, true)
            double maxVal = quietPercentileBin(histograms[c], clipCount, false)
            if (minVal >= maxVal) maxVal = minVal + 1
            result[c] = [minVal, maxVal] as double[]
        }
        return result
    }

    println '  Not every image is unsigned 8- or 16-bit; scanning again with binned values...'
    int bins = 10000
    double[] globalMin = new double[nChannels]
    double[] globalMax = new double[nChannels]
    for (int c = 0; c < nChannels; c++) {
        globalMin[c] = Double.MAX_VALUE
        globalMax[c] = -Double.MAX_VALUE
    }
    for (int i = 0; i < n; i++) {
        List img = (List) reader.call(i)
        if (img == null) continue
        def raster = (java.awt.image.Raster) img[0]
        int bands = Math.min(raster.getNumBands(), nChannels)
        for (int y = 0; y < raster.getHeight(); y++) {
            for (int x = 0; x < raster.getWidth(); x++) {
                for (int c = 0; c < bands; c++) {
                    double val = raster.getSampleDouble(x, y, c)
                    if (Double.isFinite(val)) {
                        if (val < globalMin[c]) globalMin[c] = val
                        if (val > globalMax[c]) globalMax[c] = val
                    }
                }
            }
        }
    }
    long[][] binned = new long[nChannels][bins]
    double[] binWidth = new double[nChannels]
    for (int c = 0; c < nChannels; c++) {
        double range = globalMax[c] - globalMin[c]
        binWidth[c] = range > 0 ? range / bins : 1.0d
    }
    for (int i = 0; i < n; i++) {
        List img = (List) reader.call(i)
        if (img == null) continue
        def raster = (java.awt.image.Raster) img[0]
        int bands = Math.min(raster.getNumBands(), nChannels)
        for (int y = 0; y < raster.getHeight(); y++) {
            for (int x = 0; x < raster.getWidth(); x++) {
                for (int c = 0; c < bands; c++) {
                    double val = raster.getSampleDouble(x, y, c)
                    if (Double.isFinite(val)) {
                        int bin = (int) ((val - globalMin[c]) / binWidth[c])
                        bin = Math.max(0, Math.min(bin, bins - 1))
                        binned[c][bin]++
                    }
                }
            }
        }
    }
    double[][] result = new double[nChannels][]
    for (int c = 0; c < nChannels; c++) {
        long total = 0
        for (long count : binned[c]) total += count
        if (total == 0) {
            result[c] = [globalMin[c], globalMax[c]] as double[]
            continue
        }
        double clipCount = total * percentile / 100.0d
        double minVal = globalMin[c] + quietPercentileBin(binned[c], clipCount, true) * binWidth[c]
        double maxVal = globalMin[c] + quietPercentileBin(binned[c], clipCount, false) * binWidth[c]
        if (minVal >= maxVal) maxVal = minVal + binWidth[c]
        result[c] = [minVal, maxVal] as double[]
    }
    return result
}

// The bin where the count from one end first exceeds clipCount.
@groovy.transform.CompileStatic
double quietPercentileBin(long[] histogram, double clipCount, boolean fromLow) {
    long cumulative = 0
    if (fromLow) {
        for (int i = 0; i < histogram.length; i++) {
            cumulative += histogram[i]
            if (cumulative > clipCount) return i
        }
        return histogram.length - 1
    }
    for (int i = histogram.length - 1; i >= 0; i--) {
        cumulative += histogram[i]
        if (cumulative > clipCount) return i
    }
    return 0
}
