package qupath.ext.quiet.export;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.jfree.svg.SVGGraphics2D;
import org.jfree.svg.SVGHints;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.analysis.heatmaps.DensityMaps;
import qupath.lib.analysis.heatmaps.DensityMaps.DensityMapBuilder;
import qupath.lib.classifiers.pixel.PixelClassificationImageServer;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.color.ColorMaps;
import qupath.lib.common.GeneralTools;
import qupath.lib.display.ImageDisplay;
import qupath.lib.display.settings.DisplaySettingUtils;
import qupath.lib.gui.images.servers.ChannelDisplayTransformServer;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.overlays.HierarchyOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.writers.ImageWriterTools;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImageRegion;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

/**
 * Exports a single image with overlays rendered on top.
 * <p>
 * Supports three modes:
 * <ul>
 *   <li>Classifier overlay: composites a pixel classifier result onto the base image</li>
 *   <li>Object overlay: renders annotations and/or detections onto the base image</li>
 *   <li>Density map overlay: renders a colorized density map with optional color scale bar</li>
 * </ul>
 * Uses direct compositing for performance: reads the base image and overlay
 * as single downsampled BufferedImages and composites with Java2D AlphaComposite.
 */
public class RenderedImageExporter {

    private static final Logger logger = LoggerFactory.getLogger(RenderedImageExporter.class);

    private RenderedImageExporter() {
        // Utility class
    }

    /**
     * Exports a single image with a pixel classifier overlay rendered on top.
     *
     * @param imageData  the image data to export (caller is responsible for closing)
     * @param classifier the pixel classifier to apply as overlay
     * @param config     rendered export configuration
     * @param entryName  the image entry name (used for filename generation)
     * @param panelIndex zero-based index for auto-incrementing panel labels
     * @throws IOException if the export fails
     * @throws IllegalArgumentException if the classifier does not support this image
     */
    public static void exportWithClassifier(ImageData<BufferedImage> imageData,
                                            PixelClassifier classifier,
                                            RenderedExportConfig config,
                                            String entryName,
                                            int panelIndex) throws IOException {

        if (!classifier.supportsImage(imageData)) {
            throw new IllegalArgumentException(
                    "Classifier does not support image: " + entryName);
        }

        ImageServer<BufferedImage> baseServer = imageData.getServer();
        PixelClassificationImageServer classificationServer = null;
        ImageServer<BufferedImage> displayServer = null;

        try {
            classificationServer = new PixelClassificationImageServer(imageData, classifier);
            displayServer = resolveDisplayServer(imageData, baseServer, config);

            String panelLabel = resolvePanelLabel(config, panelIndex);
            String filename = config.buildOutputFilename(entryName);
            File outputFile = new File(config.getOutputDirectory(), filename);

            if (config.getFormat() == OutputFormat.SVG) {
                String svg = renderRegionToSvg(imageData, baseServer,
                        classificationServer, null, displayServer, config,
                        0, 0, baseServer.getWidth(), baseServer.getHeight(), panelLabel);
                writeSvg(svg, outputFile);
            } else {
                BufferedImage result = renderClassifierComposite(
                        imageData, baseServer, classificationServer, displayServer,
                        config, panelLabel, entryName);
                ImageWriterTools.writeImage(result, outputFile.getAbsolutePath());
            }

            logger.info("Exported: {}", outputFile.getAbsolutePath());

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to export image: " + entryName, e);
        } finally {
            closeQuietly(displayServer, entryName);
            closeQuietly(classificationServer, entryName);
        }
    }

    /**
     * Exports a single image with object overlays (annotations and/or detections)
     * rendered on top, without requiring a pixel classifier.
     *
     * @param imageData  the image data to export (caller is responsible for closing)
     * @param config     rendered export configuration
     * @param entryName  the image entry name (used for filename generation)
     * @param panelIndex zero-based index for auto-incrementing panel labels
     * @throws IOException if the export fails
     */
    public static void exportWithObjectOverlay(ImageData<BufferedImage> imageData,
                                               RenderedExportConfig config,
                                               String entryName,
                                               int panelIndex) throws IOException {

        ImageServer<BufferedImage> baseServer = imageData.getServer();
        ImageServer<BufferedImage> displayServer = null;

        try {
            displayServer = resolveDisplayServer(imageData, baseServer, config);

            String panelLabel = resolvePanelLabel(config, panelIndex);
            String filename = config.buildOutputFilename(entryName);
            File outputFile = new File(config.getOutputDirectory(), filename);

            if (config.getFormat() == OutputFormat.SVG) {
                String svg = renderRegionToSvg(imageData, baseServer,
                        null, null, displayServer, config,
                        0, 0, baseServer.getWidth(), baseServer.getHeight(), panelLabel);
                writeSvg(svg, outputFile);
            } else {
                BufferedImage result = renderObjectComposite(
                        imageData, baseServer, displayServer, config, panelLabel, entryName);
                ImageWriterTools.writeImage(result, outputFile.getAbsolutePath());
            }

            logger.info("Exported: {}", outputFile.getAbsolutePath());

        } catch (Exception e) {
            throw new IOException("Failed to export image: " + entryName, e);
        } finally {
            closeQuietly(displayServer, entryName);
        }
    }

    /**
     * Exports a single image with a density map overlay colorized by a LUT.
     *
     * @param imageData      the image data to export (caller is responsible for closing)
     * @param densityBuilder the density map builder (loaded from project resources)
     * @param config         rendered export configuration
     * @param entryName      the image entry name (used for filename generation)
     * @param panelIndex     zero-based index for auto-incrementing panel labels
     * @throws IOException if the export fails
     */
    public static void exportWithDensityMap(ImageData<BufferedImage> imageData,
                                             DensityMapBuilder densityBuilder,
                                             RenderedExportConfig config,
                                             String entryName,
                                             int panelIndex) throws IOException {

        ImageServer<BufferedImage> baseServer = imageData.getServer();
        ImageServer<BufferedImage> displayServer = null;
        ImageServer<BufferedImage> densityServer = null;

        try {
            densityServer = densityBuilder.buildServer(imageData);
            displayServer = resolveDisplayServer(imageData, baseServer, config);

            String panelLabel = resolvePanelLabel(config, panelIndex);
            String filename = config.buildOutputFilename(entryName);
            File outputFile = new File(config.getOutputDirectory(), filename);

            if (config.getFormat() == OutputFormat.SVG) {
                String svg = renderRegionToSvg(imageData, baseServer,
                        null, densityServer, displayServer, config,
                        0, 0, baseServer.getWidth(), baseServer.getHeight(), panelLabel);
                writeSvg(svg, outputFile);
            } else {
                BufferedImage result = renderDensityMapComposite(
                        imageData, baseServer, densityServer, displayServer,
                        config, panelLabel, entryName);
                ImageWriterTools.writeImage(result, outputFile.getAbsolutePath());
            }

            logger.info("Exported: {}", outputFile.getAbsolutePath());

        } catch (Exception e) {
            throw new IOException("Failed to export density map image: " + entryName, e);
        } finally {
            closeQuietly(displayServer, entryName);
            closeQuietly(densityServer, entryName);
        }
    }

    /**
     * Exports one cropped rendered image per annotation in the image hierarchy.
     * Annotations can be filtered by classification. All three render modes
     * (classifier, object overlay, density map) are supported.
     *
     * @param imageData      the image data to export (caller is responsible for closing)
     * @param classifier     the pixel classifier (null for non-CLASSIFIER modes)
     * @param densityBuilder the density map builder (null for non-DENSITY_MAP modes)
     * @param config         rendered export configuration (must have regionType ALL_ANNOTATIONS)
     * @param entryName      the image entry name (used for filename generation)
     * @param panelIndex     zero-based starting index for auto-incrementing panel labels
     * @return the number of annotation regions successfully exported
     * @throws IOException if the export fails
     */
    public static int exportPerAnnotation(ImageData<BufferedImage> imageData,
                                           PixelClassifier classifier,
                                           DensityMapBuilder densityBuilder,
                                           RenderedExportConfig config,
                                           String entryName,
                                           int panelIndex) throws IOException {

        ImageServer<BufferedImage> baseServer = imageData.getServer();
        int serverW = baseServer.getWidth();
        int serverH = baseServer.getHeight();

        // Collect annotations and filter by classification
        Collection<PathObject> annotations = imageData.getHierarchy()
                .getAnnotationObjects().stream()
                .map(p -> (PathObject) p)
                .toList();

        var selectedClasses = config.getSelectedClassifications();
        if (selectedClasses != null) {
            Set<String> classSet = Set.copyOf(selectedClasses);
            annotations = annotations.stream()
                    .filter(a -> {
                        PathClass pc = a.getPathClass();
                        String name = (pc == null || pc == PathClass.NULL_CLASS)
                                ? "Unclassified" : pc.toString();
                        return classSet.contains(name);
                    })
                    .toList();
        }

        if (annotations.isEmpty()) {
            logger.warn("No matching annotations found for: {}", entryName);
            return 0;
        }

        // Set up servers for the render mode
        PixelClassificationImageServer classificationServer = null;
        ImageServer<BufferedImage> displayServer = null;
        ImageServer<BufferedImage> densityServer = null;

        try {
            displayServer = resolveDisplayServer(imageData, baseServer, config);

            if (config.getRenderMode() == RenderedExportConfig.RenderMode.CLASSIFIER_OVERLAY
                    && classifier != null) {
                if (!classifier.supportsImage(imageData)) {
                    throw new IllegalArgumentException(
                            "Classifier does not support image: " + entryName);
                }
                classificationServer = new PixelClassificationImageServer(imageData, classifier);
            } else if (config.getRenderMode() == RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY
                    && densityBuilder != null) {
                densityServer = densityBuilder.buildServer(imageData);
            }

            // Per-class index counters for filename suffixes
            Map<String, AtomicInteger> classCounters = new LinkedHashMap<>();
            int padding = config.getPaddingPixels();
            int exported = 0;
            int annotationIndex = 0;

            for (PathObject annotation : annotations) {
                ROI roi = annotation.getROI();
                if (roi == null) continue;

                int x = (int) roi.getBoundsX();
                int y = (int) roi.getBoundsY();
                int w = (int) Math.ceil(roi.getBoundsWidth());
                int h = (int) Math.ceil(roi.getBoundsHeight());

                // Apply padding
                if (padding > 0) {
                    x -= padding;
                    y -= padding;
                    w += 2 * padding;
                    h += 2 * padding;
                }

                // Clamp to image bounds
                x = Math.max(0, x);
                y = Math.max(0, y);
                w = Math.min(w, serverW - x);
                h = Math.min(h, serverH - y);

                if (w <= 0 || h <= 0) continue;

                try {
                    String panelLabel = resolvePanelLabel(config, panelIndex + annotationIndex);

                    // Build classification-based filename suffix
                    PathClass pc = annotation.getPathClass();
                    String className = (pc == null || pc == PathClass.NULL_CLASS)
                            ? "Unclassified" : pc.toString();
                    String safeName = GeneralTools.stripInvalidFilenameChars(className);
                    if (safeName == null || safeName.isBlank()) safeName = "Unknown";

                    int idx = classCounters.computeIfAbsent(safeName,
                            k -> new AtomicInteger(0)).getAndIncrement();
                    String suffix = "_" + safeName + "_" + idx;

                    String filename = config.buildOutputFilename(entryName, suffix);
                    File outputFile = new File(config.getOutputDirectory(), filename);

                    if (config.getFormat() == OutputFormat.SVG) {
                        String svg = renderRegionToSvg(imageData, baseServer,
                                classificationServer, densityServer, displayServer,
                                config, x, y, w, h, panelLabel);
                        writeSvg(svg, outputFile);
                    } else {
                        BufferedImage regionImage = renderRegion(
                                imageData, baseServer,
                                classificationServer, densityServer, displayServer,
                                config, x, y, w, h, panelLabel);
                        ImageWriterTools.writeImage(regionImage, outputFile.getAbsolutePath());
                    }

                    logger.debug("Exported annotation region: {}", outputFile.getAbsolutePath());
                    exported++;
                    annotationIndex++;

                } catch (Exception e) {
                    logger.warn("Failed to export annotation for {}: {}",
                            entryName, e.getMessage());
                }
            }

            logger.info("Exported {} annotation regions for: {}", exported, entryName);
            return exported;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to export per-annotation images: " + entryName, e);
        } finally {
            closeQuietly(displayServer, entryName);
            closeQuietly(classificationServer, entryName);
            closeQuietly(densityServer, entryName);
        }
    }

    /**
     * Render the raster layers (base image + classifier/density overlay) for a region.
     * Does NOT draw vector overlays (objects, scale bar, panel label).
     */
    private static BufferedImage renderRasterLayers(
            ImageData<BufferedImage> imageData,
            ImageServer<BufferedImage> baseServer,
            PixelClassificationImageServer classificationServer,
            ImageServer<BufferedImage> densityServer,
            ImageServer<BufferedImage> displayServer,
            RenderedExportConfig config,
            int x, int y, int w, int h) throws Exception {

        double downsample = resolveEffectiveDownsample(config, baseServer);

        // Read base image region
        ImageServer<BufferedImage> readServer =
                displayServer != null ? displayServer : baseServer;
        RegionRequest request = RegionRequest.createInstance(
                readServer.getPath(), downsample, x, y, w, h);
        BufferedImage baseImage = readServer.readRegion(request);

        BufferedImage result = new BufferedImage(
                baseImage.getWidth(), baseImage.getHeight(),
                BufferedImage.TYPE_INT_RGB);

        Graphics2D g2d = result.createGraphics();
        g2d.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        g2d.drawImage(baseImage, 0, 0, null);

        float opacity = (float) config.getOverlayOpacity();

        // Composite classifier or density map overlay
        if (classificationServer != null && opacity > 0) {
            RegionRequest classRequest = RegionRequest.createInstance(
                    classificationServer.getPath(), downsample, x, y, w, h);
            BufferedImage classImage = classificationServer.readRegion(classRequest);
            if (classImage != null) {
                g2d.setComposite(AlphaComposite.getInstance(
                        AlphaComposite.SRC_OVER, opacity));
                g2d.drawImage(classImage,
                        0, 0, baseImage.getWidth(), baseImage.getHeight(), null);
            }
        } else if (densityServer != null && opacity > 0) {
            RegionRequest densityRequest = RegionRequest.createInstance(
                    densityServer.getPath(), downsample, x, y, w, h);
            BufferedImage densityImage = densityServer.readRegion(densityRequest);

            ColorMaps.ColorMap colorMap = resolveColorMap(config.overlays().colormapName());
            double[] minMax = computeMinMax(densityImage);
            BufferedImage colorized = colorizeDensityMap(
                    densityImage, colorMap, minMax[0], minMax[1]);

            if (colorized != null) {
                g2d.setComposite(AlphaComposite.getInstance(
                        AlphaComposite.SRC_OVER, opacity));
                g2d.drawImage(colorized,
                        0, 0, baseImage.getWidth(), baseImage.getHeight(), null);
            }
        }

        g2d.dispose();
        return result;
    }

    /**
     * Draw all vector-appropriate overlays onto a Graphics2D context.
     * This includes object overlays, scale bar, color scale bar, and panel label.
     */
    private static void drawVectorOverlays(
            Graphics2D g2d,
            ImageData<BufferedImage> imageData,
            ImageServer<BufferedImage> densityServer,
            RenderedExportConfig config,
            int regionX, int regionY, int regionW, int regionH,
            int imageWidth, int imageHeight,
            String panelLabel,
            double downsample) throws Exception {
        drawVectorOverlays(g2d, imageData, densityServer, config,
                regionX, regionY, regionW, regionH,
                imageWidth, imageHeight, panelLabel, downsample, false);
    }

    /**
     * Draw all vector-appropriate overlays onto a Graphics2D context.
     *
     * @param skipObjectPainting if true, skip painting objects (used when SVG vector
     *                           objects are painted separately via paintObjectsAsSvg)
     */
    private static void drawVectorOverlays(
            Graphics2D g2d,
            ImageData<BufferedImage> imageData,
            ImageServer<BufferedImage> densityServer,
            RenderedExportConfig config,
            int regionX, int regionY, int regionW, int regionH,
            int imageWidth, int imageHeight,
            String panelLabel,
            double downsample,
            boolean skipObjectPainting) throws Exception {
        drawVectorOverlays(g2d, imageData, densityServer, config,
                regionX, regionY, regionW, regionH,
                imageWidth, imageHeight, panelLabel, downsample,
                skipObjectPainting, null, null);
    }

    /**
     * Draw all vector-appropriate overlays onto a Graphics2D context.
     *
     * @param skipObjectPainting if true, skip painting objects (used when SVG vector
     *                           objects are painted separately via paintObjectsAsSvg)
     * @param resolvedInfoLabel  pre-resolved info label text (null if not applicable)
     * @param entryName          image entry name for info label template resolution
     */
    private static void drawVectorOverlays(
            Graphics2D g2d,
            ImageData<BufferedImage> imageData,
            ImageServer<BufferedImage> densityServer,
            RenderedExportConfig config,
            int regionX, int regionY, int regionW, int regionH,
            int imageWidth, int imageHeight,
            String panelLabel,
            double downsample,
            boolean skipObjectPainting,
            String resolvedInfoLabel,
            String entryName) throws Exception {

        // Paint object overlays with region offset (skip when done via SVG vector path)
        if (!skipObjectPainting && (config.overlays().includeAnnotations() || config.overlays().includeDetections())) {
            g2d.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, 1.0f));
            paintObjectsInRegion(g2d, imageData, downsample,
                    regionX, regionY, regionW, regionH,
                    config.overlays().includeAnnotations(), config.overlays().includeDetections(),
                    config.overlays().fillAnnotations(), config.overlays().showNames());
        }

        // Scale bar -- must use effective downsample for correct pixel size
        maybeDrawScaleBar(g2d, imageData, config, imageWidth, imageHeight, downsample);

        // Color scale bar for density map mode
        if (densityServer != null && config.colorScaleBar().show()) {
            ColorMaps.ColorMap colorMap = resolveColorMap(config.overlays().colormapName());
            RegionRequest densityRequest = RegionRequest.createInstance(
                    densityServer.getPath(), downsample,
                    regionX, regionY, regionW, regionH);
            BufferedImage densityImage = densityServer.readRegion(densityRequest);
            double[] minMax = computeMinMax(densityImage);
            maybeDrawColorScaleBar(g2d, config, colorMap, minMax[0], minMax[1],
                    imageWidth, imageHeight);
        }

        maybeDrawPanelLabel(g2d, config, panelLabel, imageWidth, imageHeight);

        // Info label (resolve template if not pre-resolved)
        String infoText = resolvedInfoLabel;
        if (infoText == null && config.infoLabel().show() && entryName != null) {
            infoText = resolveInfoLabelTemplate(
                    config.infoLabel().text(), entryName, imageData, config);
        }
        maybeDrawChannelLegend(g2d, imageData, config, imageWidth, imageHeight);
        maybeDrawInfoLabel(g2d, config, infoText, imageWidth, imageHeight);
    }

    /**
     * Render a specific region of the image with overlays composited on top.
     * Handles all three render modes (classifier, object overlay, density map).
     */
    private static BufferedImage renderRegion(
            ImageData<BufferedImage> imageData,
            ImageServer<BufferedImage> baseServer,
            PixelClassificationImageServer classificationServer,
            ImageServer<BufferedImage> densityServer,
            ImageServer<BufferedImage> displayServer,
            RenderedExportConfig config,
            int x, int y, int w, int h,
            String panelLabel) throws Exception {
        return renderRegion(imageData, baseServer, classificationServer, densityServer,
                displayServer, config, x, y, w, h, panelLabel, null);
    }

    private static BufferedImage renderRegion(
            ImageData<BufferedImage> imageData,
            ImageServer<BufferedImage> baseServer,
            PixelClassificationImageServer classificationServer,
            ImageServer<BufferedImage> densityServer,
            ImageServer<BufferedImage> displayServer,
            RenderedExportConfig config,
            int x, int y, int w, int h,
            String panelLabel,
            String entryName) throws Exception {

        BufferedImage raster = renderRasterLayers(
                imageData, baseServer, classificationServer, densityServer,
                displayServer, config, x, y, w, h);

        double effectiveDs = resolveEffectiveDownsample(config, baseServer);

        Graphics2D g2d = raster.createGraphics();
        g2d.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        drawVectorOverlays(g2d, imageData, densityServer, config,
                x, y, w, h,
                raster.getWidth(), raster.getHeight(),
                panelLabel, effectiveDs,
                false, null, entryName);

        maybeDrawInset(g2d, raster, config);

        g2d.dispose();
        return raster;
    }

    /**
     * Render a region as an SVG document with raster base embedded as PNG
     * and vector overlays (annotations, scale bar, labels) as native SVG elements.
     * <p>
     * Object overlays are rendered as true SVG path elements (via
     * {@link #paintObjectsAsSvg}) rather than rasterized through HierarchyOverlay,
     * making them individually selectable and editable in vector editors
     * (Illustrator, Inkscape).
     */
    private static String renderRegionToSvg(
            ImageData<BufferedImage> imageData,
            ImageServer<BufferedImage> baseServer,
            PixelClassificationImageServer classificationServer,
            ImageServer<BufferedImage> densityServer,
            ImageServer<BufferedImage> displayServer,
            RenderedExportConfig config,
            int x, int y, int w, int h,
            String panelLabel) throws Exception {

        BufferedImage raster = renderRasterLayers(
                imageData, baseServer, classificationServer, densityServer,
                displayServer, config, x, y, w, h);

        SVGGraphics2D svgG2d = new SVGGraphics2D(raster.getWidth(), raster.getHeight());
        svgG2d.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // Embed raster base as PNG inside SVG
        svgG2d.drawImage(raster, 0, 0, null);

        double effectiveDs = resolveEffectiveDownsample(config,
                displayServer != null ? displayServer : baseServer);

        // Paint object overlays as true SVG vector path elements
        if (config.overlays().includeAnnotations() || config.overlays().includeDetections()) {
            paintObjectsAsSvg(svgG2d, imageData, config,
                    x, y, w, h, effectiveDs);
        }

        // Draw remaining overlays (scale bar, color scale bar, panel label, info label)
        // but skip object painting since we already did it as SVG vectors
        drawVectorOverlays(svgG2d, imageData, densityServer, config,
                x, y, w, h,
                raster.getWidth(), raster.getHeight(),
                panelLabel, effectiveDs,
                true, null, null);  // skipObjectPainting = true

        svgG2d.dispose();
        return svgG2d.getSVGDocument();
    }

    /**
     * Write an SVG document string to a file.
     */
    private static void writeSvg(String svgDocument, File outputFile) throws IOException {
        Files.writeString(outputFile.toPath(), svgDocument, StandardCharsets.UTF_8);
    }

    /**
     * Paint QuPath objects as native SVG vector elements using ROI shapes.
     * <p>
     * Each PathClass is grouped into an SVG {@code <g>} element via JFreeSVG
     * {@link SVGHints}. Individual objects become {@code <path>} elements with
     * proper fill/stroke attributes, making them individually selectable and
     * editable in vector editors (Illustrator, Inkscape).
     *
     * @param g2d       the SVGGraphics2D context
     * @param imageData the image data containing the object hierarchy
     * @param config    rendered export config (for annotation/detection inclusion)
     * @param regionX   left pixel coordinate of the region
     * @param regionY   top pixel coordinate of the region
     * @param regionW   width of the region in pixels
     * @param regionH   height of the region in pixels
     * @param downsample downsample factor
     */
    private static void paintObjectsAsSvg(SVGGraphics2D g2d,
                                            ImageData<BufferedImage> imageData,
                                            RenderedExportConfig config,
                                            int regionX, int regionY,
                                            int regionW, int regionH,
                                            double downsample) {
        try {
            var hierarchy = imageData.getHierarchy();
            var region = ImageRegion.createInstance(regionX, regionY, regionW, regionH, 0, 0);
            var allObjects = hierarchy.getObjectsForRegion(null, region, null);

            // Filter by annotation/detection inclusion settings
            var objects = allObjects.stream()
                    .filter(o -> o.hasROI())
                    .filter(o -> {
                        if (o.isAnnotation()) return config.overlays().includeAnnotations();
                        if (o.isDetection()) return config.overlays().includeDetections();
                        return false;
                    })
                    .toList();

            if (objects.isEmpty()) return;

            // Group by PathClass
            Map<PathClass, List<PathObject>> byClass = objects.stream()
                    .collect(Collectors.groupingBy(
                            o -> o.getPathClass() != null ? o.getPathClass() : PathClass.NULL_CLASS,
                            LinkedHashMap::new, Collectors.toList()));

            double offsetX = -regionX;
            double offsetY = -regionY;
            double scale = 1.0 / downsample;

            for (var entry : byClass.entrySet()) {
                PathClass pathClass = entry.getKey();
                String className = pathClass == PathClass.NULL_CLASS
                        ? "Unclassified" : pathClass.getName();

                // Begin SVG group
                g2d.setRenderingHint(SVGHints.KEY_BEGIN_GROUP, className);
                g2d.setRenderingHint(SVGHints.KEY_ELEMENT_TITLE, className);

                for (var obj : entry.getValue()) {
                    Shape shape = obj.getROI().getShape();

                    // Transform to output coordinates
                    AffineTransform tx = new AffineTransform();
                    tx.scale(scale, scale);
                    tx.translate(offsetX, offsetY);
                    Shape transformed = tx.createTransformedShape(shape);

                    // Resolve color from object or class
                    Integer color = obj.getColor();
                    if (color == null && obj.getPathClass() != null) {
                        color = obj.getPathClass().getColor();
                    }
                    Color awtColor = (color != null)
                            ? makeAwtColor(color) : Color.YELLOW;

                    // Set element ID for individual selectability
                    String objId = className + "_" + obj.getID();
                    g2d.setRenderingHint(SVGHints.KEY_ELEMENT_ID, objId);

                    // Fill with transparency
                    boolean shouldFill = obj.isAnnotation() && config.overlays().fillAnnotations();
                    if (shouldFill) {
                        g2d.setColor(new Color(
                                awtColor.getRed(), awtColor.getGreen(),
                                awtColor.getBlue(), 64));
                        g2d.fill(transformed);
                    }

                    // Stroke
                    g2d.setColor(awtColor);
                    g2d.setStroke(new BasicStroke(1.5f));
                    g2d.draw(transformed);
                }

                // End SVG group
                g2d.setRenderingHint(SVGHints.KEY_END_GROUP, "true");
            }
        } catch (Exception e) {
            logger.warn("Failed to paint SVG vector objects: {}", e.getMessage());
        }
    }

    /**
     * Convert a QuPath packed ARGB integer color to a java.awt.Color.
     */
    private static Color makeAwtColor(int packedColor) {
        int a = (packedColor >> 24) & 0xFF;
        int r = (packedColor >> 16) & 0xFF;
        int g = (packedColor >> 8) & 0xFF;
        int b = packedColor & 0xFF;
        return new Color(r, g, b, a > 0 ? a : 255);
    }

    /**
     * Paint object overlays onto a graphics context for a specific region.
     * The graphics origin is translated so objects are drawn at the correct offset.
     */
    private static void paintObjectsInRegion(Graphics2D g2d,
                                              ImageData<BufferedImage> imageData,
                                              double downsample,
                                              int regionX, int regionY,
                                              int regionW, int regionH,
                                              boolean showAnnotations,
                                              boolean showDetections,
                                              boolean fillAnnotations,
                                              boolean showNames) {
        try {
            var overlayOptions = new OverlayOptions();
            overlayOptions.setShowAnnotations(showAnnotations);
            overlayOptions.setShowDetections(showDetections);
            overlayOptions.setFillAnnotations(fillAnnotations);
            overlayOptions.setShowNames(showNames);
            var hierarchyOverlay = new HierarchyOverlay(null, overlayOptions, imageData);

            var gCopy = (Graphics2D) g2d.create();
            gCopy.scale(1.0 / downsample, 1.0 / downsample);
            gCopy.translate(-regionX, -regionY);

            var region = ImageRegion.createInstance(
                    regionX, regionY, regionW, regionH, 0, 0);

            hierarchyOverlay.paintOverlay(gCopy, region, downsample, imageData, true);
            gCopy.dispose();
        } catch (Exception e) {
            logger.warn("Failed to paint objects in region: {}", e.getMessage());
        }
    }

    /**
     * Renders a preview of the current image with the given config.
     * Returns a BufferedImage sized to fit within {@code maxDimension} on its longest side.
     *
     * @param imageData      the image data to preview
     * @param classifier     the pixel classifier (null for non-CLASSIFIER modes)
     * @param densityBuilder the density map builder (null for non-DENSITY_MAP modes)
     * @param config         rendered export configuration
     * @param maxDimension   maximum pixel dimension for the longest side of the preview
     * @return the rendered preview image
     * @throws IOException if rendering fails
     */
    public static BufferedImage renderPreview(ImageData<BufferedImage> imageData,
                                              PixelClassifier classifier,
                                              DensityMapBuilder densityBuilder,
                                              RenderedExportConfig config,
                                              int maxDimension) throws IOException {

        ImageServer<BufferedImage> baseServer = imageData.getServer();
        int serverW = baseServer.getWidth();
        int serverH = baseServer.getHeight();

        // For per-annotation mode, find the first matching annotation and preview its region
        if (config.getRegionType() == RenderedExportConfig.RegionType.ALL_ANNOTATIONS) {
            PathObject firstAnnotation = findFirstMatchingAnnotation(imageData, config);
            if (firstAnnotation != null && firstAnnotation.getROI() != null) {
                ROI roi = firstAnnotation.getROI();
                int x = (int) roi.getBoundsX();
                int y = (int) roi.getBoundsY();
                int w = (int) Math.ceil(roi.getBoundsWidth());
                int h = (int) Math.ceil(roi.getBoundsHeight());
                int padding = config.getPaddingPixels();
                if (padding > 0) {
                    x -= padding;
                    y -= padding;
                    w += 2 * padding;
                    h += 2 * padding;
                }
                x = Math.max(0, x);
                y = Math.max(0, y);
                w = Math.min(w, serverW - x);
                h = Math.min(h, serverH - y);

                if (w > 0 && h > 0) {
                    double longestSide = Math.max(w, h);
                    double previewDownsample = Math.max(config.getDownsample(),
                            longestSide / maxDimension);
                    RenderedExportConfig previewConfig = buildPreviewConfig(config, previewDownsample);
                    return renderRegionPreview(imageData, baseServer, classifier,
                            densityBuilder, previewConfig, x, y, w, h);
                }
            }
            // Fall through to whole-image preview if no annotation found
        }

        // Compute downsample to fit within maxDimension
        double longestSide = Math.max(serverW, serverH);
        double previewDownsample = Math.max(config.getDownsample(),
                longestSide / maxDimension);

        RenderedExportConfig previewConfig = buildPreviewConfig(config, previewDownsample);

        ImageServer<BufferedImage> displayServer = null;
        PixelClassificationImageServer classificationServer = null;
        ImageServer<BufferedImage> densityServer = null;

        try {
            displayServer = resolveDisplayServer(imageData, baseServer, previewConfig);

            // For preview, use fixed text from config or "A" as default
            String previewLabel = resolvePanelLabel(previewConfig, 0);

            if (config.getRenderMode() == RenderedExportConfig.RenderMode.CLASSIFIER_OVERLAY
                    && classifier != null) {
                classificationServer = new PixelClassificationImageServer(imageData, classifier);
                try {
                    return renderClassifierComposite(
                            imageData, baseServer, classificationServer, displayServer,
                            previewConfig, previewLabel);
                } catch (Exception e) {
                    throw new IOException("Failed to render classifier preview", e);
                }
            } else if (config.getRenderMode() == RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY
                    && densityBuilder != null) {
                densityServer = densityBuilder.buildServer(imageData);
                try {
                    return renderDensityMapComposite(
                            imageData, baseServer, densityServer, displayServer,
                            previewConfig, previewLabel);
                } catch (Exception e) {
                    throw new IOException("Failed to render density map preview", e);
                }
            } else {
                try {
                    return renderObjectComposite(
                            imageData, baseServer, displayServer, previewConfig, previewLabel);
                } catch (Exception e) {
                    throw new IOException("Failed to render object overlay preview", e);
                }
            }
        } finally {
            closeQuietly(displayServer, "preview");
            closeQuietly(classificationServer, "preview");
            closeQuietly(densityServer, "preview");
        }
    }

    /**
     * Build a preview config with the specified downsample, copying all other settings.
     */
    private static RenderedExportConfig buildPreviewConfig(RenderedExportConfig config,
                                                            double previewDownsample) {
        return new RenderedExportConfig.Builder()
                // Core fields
                .regionType(config.getRegionType())
                .selectedClassifications(config.getSelectedClassifications())
                .paddingPixels(config.getPaddingPixels())
                .renderMode(config.getRenderMode())
                .displaySettingsMode(config.getDisplaySettingsMode())
                .capturedDisplaySettings(config.getCapturedDisplaySettings())
                .displayPresetName(config.getDisplayPresetName())
                .overlayOpacity(config.getOverlayOpacity())
                .downsample(previewDownsample)
                .targetDpi(config.getTargetDpi())
                .format(config.getFormat())
                .outputDirectory(config.getOutputDirectory())
                .matchedDisplayPercentile(config.getMatchedDisplayPercentile())
                // Sub-configs copied wholesale
                .overlays(config.overlays())
                .scaleBar(config.scaleBar())
                .colorScaleBar(config.colorScaleBar())
                .panelLabel(config.panelLabel())
                .infoLabel(config.infoLabel())
                .splitChannel(config.splitChannel())
                .inset(config.inset())
                .build();
    }

    /**
     * Find the first annotation matching the classification filter.
     */
    private static PathObject findFirstMatchingAnnotation(ImageData<BufferedImage> imageData,
                                                           RenderedExportConfig config) {
        var annotations = imageData.getHierarchy().getAnnotationObjects();
        var selectedClasses = config.getSelectedClassifications();
        if (selectedClasses != null) {
            Set<String> classSet = Set.copyOf(selectedClasses);
            for (var a : annotations) {
                PathClass pc = a.getPathClass();
                String name = (pc == null || pc == PathClass.NULL_CLASS)
                        ? "Unclassified" : pc.toString();
                if (classSet.contains(name)) return a;
            }
            return null;
        }
        return annotations.isEmpty() ? null : annotations.iterator().next();
    }

    /**
     * Render a preview of a specific region using the renderRegion helper.
     */
    private static BufferedImage renderRegionPreview(
            ImageData<BufferedImage> imageData,
            ImageServer<BufferedImage> baseServer,
            PixelClassifier classifier,
            DensityMapBuilder densityBuilder,
            RenderedExportConfig config,
            int x, int y, int w, int h) throws IOException {

        PixelClassificationImageServer classificationServer = null;
        ImageServer<BufferedImage> displayServer = null;
        ImageServer<BufferedImage> densityServer = null;

        try {
            displayServer = resolveDisplayServer(imageData, baseServer, config);

            if (config.getRenderMode() == RenderedExportConfig.RenderMode.CLASSIFIER_OVERLAY
                    && classifier != null) {
                classificationServer = new PixelClassificationImageServer(imageData, classifier);
            } else if (config.getRenderMode() == RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY
                    && densityBuilder != null) {
                densityServer = densityBuilder.buildServer(imageData);
            }

            String previewLabel = resolvePanelLabel(config, 0);
            return renderRegion(imageData, baseServer,
                    classificationServer, densityServer, displayServer,
                    config, x, y, w, h, previewLabel);

        } catch (Exception e) {
            throw new IOException("Failed to render per-annotation preview", e);
        } finally {
            closeQuietly(displayServer, "preview");
            closeQuietly(classificationServer, "preview");
            closeQuietly(densityServer, "preview");
        }
    }

    /**
     * Backward-compatible overload without density map builder parameter.
     */
    public static BufferedImage renderPreview(ImageData<BufferedImage> imageData,
                                              PixelClassifier classifier,
                                              RenderedExportConfig config,
                                              int maxDimension) throws IOException {
        return renderPreview(imageData, classifier, null, config, maxDimension);
    }

    /**
     * Shared rendering logic for classifier overlay compositing.
     */
    private static BufferedImage renderClassifierComposite(
            ImageData<BufferedImage> imageData,
            ImageServer<BufferedImage> baseServer,
            PixelClassificationImageServer classificationServer,
            ImageServer<BufferedImage> displayServer,
            RenderedExportConfig config,
            String panelLabel) throws Exception {
        return renderClassifierComposite(imageData, baseServer, classificationServer,
                displayServer, config, panelLabel, null);
    }

    private static BufferedImage renderClassifierComposite(
            ImageData<BufferedImage> imageData,
            ImageServer<BufferedImage> baseServer,
            PixelClassificationImageServer classificationServer,
            ImageServer<BufferedImage> displayServer,
            RenderedExportConfig config,
            String panelLabel,
            String entryName) throws Exception {

        double downsample = resolveEffectiveDownsample(config, baseServer);
        int outputWidth = (int) Math.ceil(baseServer.getWidth() / downsample);
        int outputHeight = (int) Math.ceil(baseServer.getHeight() / downsample);

        ImageServer<BufferedImage> readServer =
                displayServer != null ? displayServer : baseServer;
        RegionRequest request = RegionRequest.createInstance(
                readServer.getPath(), downsample,
                0, 0, readServer.getWidth(), readServer.getHeight());

        BufferedImage baseImage = readServer.readRegion(request);

        RegionRequest classRequest = RegionRequest.createInstance(
                classificationServer.getPath(), downsample,
                0, 0, classificationServer.getWidth(), classificationServer.getHeight());
        BufferedImage classImage = classificationServer.readRegion(classRequest);

        BufferedImage result = new BufferedImage(
                baseImage.getWidth(), baseImage.getHeight(),
                BufferedImage.TYPE_INT_RGB);

        Graphics2D g2d = result.createGraphics();
        g2d.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        g2d.drawImage(baseImage, 0, 0, null);

        float opacity = (float) config.getOverlayOpacity();
        if (opacity > 0 && classImage != null) {
            g2d.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, opacity));
            g2d.drawImage(classImage,
                    0, 0, baseImage.getWidth(), baseImage.getHeight(), null);
        }

        if (config.overlays().includeAnnotations() || config.overlays().includeDetections()) {
            g2d.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, 1.0f));
            paintObjects(g2d, imageData, downsample, outputWidth, outputHeight,
                    config.overlays().includeAnnotations(), config.overlays().includeDetections(),
                    config.overlays().fillAnnotations(), config.overlays().showNames());
        }

        maybeDrawScaleBar(g2d, imageData, config,
                baseImage.getWidth(), baseImage.getHeight(), downsample);

        maybeDrawPanelLabel(g2d, config, panelLabel,
                baseImage.getWidth(), baseImage.getHeight());

        String infoText = entryName != null && config.infoLabel().show()
                ? resolveInfoLabelTemplate(config.infoLabel().text(), entryName, imageData, config)
                : null;
        maybeDrawChannelLegend(g2d, imageData, config,
                baseImage.getWidth(), baseImage.getHeight());
        maybeDrawInfoLabel(g2d, config, infoText,
                baseImage.getWidth(), baseImage.getHeight());

        maybeDrawInset(g2d, result, config);

        g2d.dispose();
        return result;
    }

    /**
     * Shared rendering logic for object overlay compositing.
     */
    private static BufferedImage renderObjectComposite(
            ImageData<BufferedImage> imageData,
            ImageServer<BufferedImage> baseServer,
            ImageServer<BufferedImage> displayServer,
            RenderedExportConfig config,
            String panelLabel) throws Exception {
        return renderObjectComposite(imageData, baseServer, displayServer,
                config, panelLabel, null);
    }

    private static BufferedImage renderObjectComposite(
            ImageData<BufferedImage> imageData,
            ImageServer<BufferedImage> baseServer,
            ImageServer<BufferedImage> displayServer,
            RenderedExportConfig config,
            String panelLabel,
            String entryName) throws Exception {

        double downsample = resolveEffectiveDownsample(config, baseServer);
        int outputWidth = (int) Math.ceil(baseServer.getWidth() / downsample);
        int outputHeight = (int) Math.ceil(baseServer.getHeight() / downsample);

        ImageServer<BufferedImage> readServer =
                displayServer != null ? displayServer : baseServer;
        RegionRequest request = RegionRequest.createInstance(
                readServer.getPath(), downsample,
                0, 0, readServer.getWidth(), readServer.getHeight());

        BufferedImage baseImage = readServer.readRegion(request);

        BufferedImage result = new BufferedImage(
                baseImage.getWidth(), baseImage.getHeight(),
                BufferedImage.TYPE_INT_RGB);

        Graphics2D g2d = result.createGraphics();
        g2d.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        g2d.drawImage(baseImage, 0, 0, null);

        float opacity = (float) config.getOverlayOpacity();
        if (opacity > 0) {
            g2d.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, opacity));
            paintObjects(g2d, imageData, downsample, outputWidth, outputHeight,
                    config.overlays().includeAnnotations(), config.overlays().includeDetections(),
                    config.overlays().fillAnnotations(), config.overlays().showNames());
        }

        maybeDrawScaleBar(g2d, imageData, config,
                baseImage.getWidth(), baseImage.getHeight(), downsample);

        maybeDrawPanelLabel(g2d, config, panelLabel,
                baseImage.getWidth(), baseImage.getHeight());

        String infoText = entryName != null && config.infoLabel().show()
                ? resolveInfoLabelTemplate(config.infoLabel().text(), entryName, imageData, config)
                : null;
        maybeDrawChannelLegend(g2d, imageData, config,
                baseImage.getWidth(), baseImage.getHeight());
        maybeDrawInfoLabel(g2d, config, infoText,
                baseImage.getWidth(), baseImage.getHeight());

        maybeDrawInset(g2d, result, config);

        g2d.dispose();
        return result;
    }

    /**
     * Create an ImageDisplay with config-appropriate settings applied.
     * GLOBAL_MATCHED mode uses capturedDisplaySettings (same path as CURRENT_VIEWER).
     *
     * @return the configured ImageDisplay, or null for RAW mode or on failure
     */
    static ImageDisplay resolveImageDisplay(ImageData<BufferedImage> imageData,
                                             RenderedExportConfig config) {
        var mode = config.getDisplaySettingsMode();
        if (mode == RenderedExportConfig.DisplaySettingsMode.RAW) {
            return null;
        }

        try {
            var display = ImageDisplay.create(imageData);

            if (mode == RenderedExportConfig.DisplaySettingsMode.CURRENT_VIEWER
                    || mode == RenderedExportConfig.DisplaySettingsMode.SAVED_PRESET
                    || mode == RenderedExportConfig.DisplaySettingsMode.GLOBAL_MATCHED) {
                var settings = config.getCapturedDisplaySettings();
                if (settings != null) {
                    DisplaySettingUtils.applySettingsToDisplay(display, settings);
                } else {
                    logger.warn("No display settings available for mode {}; "
                            + "using per-image defaults", mode);
                }
            }
            // PER_IMAGE_SAVED: display already loaded from imageData properties

            return display;
        } catch (Exception e) {
            logger.warn("Failed to create ImageDisplay: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Resolve display settings and wrap the base server if needed.
     *
     * @return a wrapped server applying display settings, or null for RAW mode
     *         or if display settings cannot be applied
     */
    private static ImageServer<BufferedImage> resolveDisplayServer(
            ImageData<BufferedImage> imageData,
            ImageServer<BufferedImage> baseServer,
            RenderedExportConfig config) throws IOException {

        var mode = config.getDisplaySettingsMode();
        if (mode == RenderedExportConfig.DisplaySettingsMode.RAW) {
            return null;
        }

        try {
            var display = resolveImageDisplay(imageData, config);
            if (display == null) return null;

            var channels = display.selectedChannels();
            if (channels == null || channels.isEmpty()) {
                logger.warn("No visible channels after applying display settings; "
                        + "falling back to raw pixel data");
                return null;
            }

            return ChannelDisplayTransformServer.createColorTransformServer(
                    baseServer, channels);
        } catch (Exception e) {
            logger.warn("Failed to create display transform server, "
                    + "falling back to raw pixel data: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Draw a scale bar if enabled and the image has pixel calibration.
     * Uses the provided effective downsample (which may differ from config.getDownsample()
     * when DPI mode is active).
     */
    private static void maybeDrawScaleBar(Graphics2D g2d,
                                           ImageData<BufferedImage> imageData,
                                           RenderedExportConfig config,
                                           int w, int h,
                                           double effectiveDownsample) {
        if (!config.scaleBar().show()) return;
        var cal = imageData.getServer().getPixelCalibration();
        if (!cal.hasPixelSizeMicrons()) {
            logger.warn("Scale bar skipped -- no pixel calibration");
            return;
        }
        double pxSize = cal.getAveragedPixelSizeMicrons() * effectiveDownsample;
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        ScaleBarRenderer.drawScaleBar(g2d, w, h, pxSize,
                config.scaleBar().position(),
                config.scaleBar().colorAsAwt(),
                config.scaleBar().fontSize(),
                config.scaleBar().bold(),
                config.scaleBar().backgroundBox());
    }

    /**
     * Draw a scale bar if enabled -- convenience overload using config downsample.
     */
    private static void maybeDrawScaleBar(Graphics2D g2d,
                                           ImageData<BufferedImage> imageData,
                                           RenderedExportConfig config,
                                           int w, int h) {
        maybeDrawScaleBar(g2d, imageData, config, w, h, config.getDownsample());
    }

    /**
     * Shared rendering logic for density map overlay compositing.
     */
    private static BufferedImage renderDensityMapComposite(
            ImageData<BufferedImage> imageData,
            ImageServer<BufferedImage> baseServer,
            ImageServer<BufferedImage> densityServer,
            ImageServer<BufferedImage> displayServer,
            RenderedExportConfig config,
            String panelLabel) throws Exception {
        return renderDensityMapComposite(imageData, baseServer, densityServer,
                displayServer, config, panelLabel, null);
    }

    private static BufferedImage renderDensityMapComposite(
            ImageData<BufferedImage> imageData,
            ImageServer<BufferedImage> baseServer,
            ImageServer<BufferedImage> densityServer,
            ImageServer<BufferedImage> displayServer,
            RenderedExportConfig config,
            String panelLabel,
            String entryName) throws Exception {

        double downsample = resolveEffectiveDownsample(config, baseServer);
        int outputWidth = (int) Math.ceil(baseServer.getWidth() / downsample);
        int outputHeight = (int) Math.ceil(baseServer.getHeight() / downsample);

        // Read base image
        ImageServer<BufferedImage> readServer =
                displayServer != null ? displayServer : baseServer;
        RegionRequest request = RegionRequest.createInstance(
                readServer.getPath(), downsample,
                0, 0, readServer.getWidth(), readServer.getHeight());
        BufferedImage baseImage = readServer.readRegion(request);

        // Read density map
        RegionRequest densityRequest = RegionRequest.createInstance(
                densityServer.getPath(), downsample,
                0, 0, densityServer.getWidth(), densityServer.getHeight());
        BufferedImage densityImage = densityServer.readRegion(densityRequest);

        // Resolve colormap
        ColorMaps.ColorMap colorMap = resolveColorMap(config.overlays().colormapName());

        // Compute min/max from density raster
        double[] minMax = computeMinMax(densityImage);
        double minVal = minMax[0];
        double maxVal = minMax[1];

        // Colorize density map
        BufferedImage colorized = colorizeDensityMap(densityImage, colorMap, minVal, maxVal);

        // Composite onto base
        BufferedImage result = new BufferedImage(
                baseImage.getWidth(), baseImage.getHeight(),
                BufferedImage.TYPE_INT_RGB);

        Graphics2D g2d = result.createGraphics();
        g2d.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        g2d.drawImage(baseImage, 0, 0, null);

        float opacity = (float) config.getOverlayOpacity();
        if (opacity > 0 && colorized != null) {
            g2d.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, opacity));
            g2d.drawImage(colorized,
                    0, 0, baseImage.getWidth(), baseImage.getHeight(), null);
        }

        // Object overlays on top if requested
        if (config.overlays().includeAnnotations() || config.overlays().includeDetections()) {
            g2d.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, 1.0f));
            paintObjects(g2d, imageData, downsample, outputWidth, outputHeight,
                    config.overlays().includeAnnotations(), config.overlays().includeDetections(),
                    config.overlays().fillAnnotations(), config.overlays().showNames());
        }

        maybeDrawScaleBar(g2d, imageData, config,
                baseImage.getWidth(), baseImage.getHeight(), downsample);

        maybeDrawColorScaleBar(g2d, config, colorMap, minVal, maxVal,
                baseImage.getWidth(), baseImage.getHeight());

        maybeDrawPanelLabel(g2d, config, panelLabel,
                baseImage.getWidth(), baseImage.getHeight());

        String infoText = entryName != null && config.infoLabel().show()
                ? resolveInfoLabelTemplate(config.infoLabel().text(), entryName, imageData, config)
                : null;
        maybeDrawChannelLegend(g2d, imageData, config,
                baseImage.getWidth(), baseImage.getHeight());
        maybeDrawInfoLabel(g2d, config, infoText,
                baseImage.getWidth(), baseImage.getHeight());

        maybeDrawInset(g2d, result, config);

        g2d.dispose();
        return result;
    }

    /**
     * Compute min/max float values from band 0 of a density image raster.
     */
    private static double[] computeMinMax(BufferedImage densityImage) {
        WritableRaster raster = densityImage.getRaster();
        int w = raster.getWidth();
        int h = raster.getHeight();
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double val = raster.getSampleDouble(x, y, 0);
                if (Double.isNaN(val)) continue;
                if (val < min) min = val;
                if (val > max) max = val;
            }
        }
        if (min > max) {
            // All NaN or empty
            min = 0;
            max = 1;
        }
        return new double[]{min, max};
    }

    /**
     * Look up a ColorMap by name from QuPath's registered color maps.
     * Falls back to the first available map if the name is not found.
     */
    private static ColorMaps.ColorMap resolveColorMap(String name) {
        Map<String, ColorMaps.ColorMap> maps = ColorMaps.getColorMaps();
        if (name != null && maps.containsKey(name)) {
            return maps.get(name);
        }
        // Try case-insensitive match
        for (var entry : maps.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        // Fallback to first available
        if (!maps.isEmpty()) {
            logger.warn("Colormap '{}' not found, using default", name);
            return maps.values().iterator().next();
        }
        throw new IllegalStateException("No color maps available");
    }

    /**
     * Colorize a density image using a color map.
     * Creates a TYPE_INT_ARGB image with transparent pixels for NaN values.
     */
    private static BufferedImage colorizeDensityMap(BufferedImage densityImage,
                                                     ColorMaps.ColorMap colorMap,
                                                     double min, double max) {
        int w = densityImage.getWidth();
        int h = densityImage.getHeight();
        WritableRaster raster = densityImage.getRaster();
        BufferedImage colorized = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double val = raster.getSampleDouble(x, y, 0);
                if (Double.isNaN(val)) {
                    colorized.setRGB(x, y, 0x00000000); // fully transparent
                } else {
                    int rgb = colorMap.getColor(val, min, max);
                    colorized.setRGB(x, y, 0xFF000000 | (rgb & 0x00FFFFFF));
                }
            }
        }
        return colorized;
    }

    /**
     * Draw a color scale bar if enabled in the config.
     */
    private static void maybeDrawColorScaleBar(Graphics2D g2d,
                                                RenderedExportConfig config,
                                                ColorMaps.ColorMap colorMap,
                                                double min, double max,
                                                int w, int h) {
        if (!config.colorScaleBar().show()) return;
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        ColorScaleBarRenderer.drawColorScaleBar(g2d, w, h, colorMap, min, max,
                config.colorScaleBar().position(),
                config.colorScaleBar().fontSize(),
                config.colorScaleBar().bold());
    }

    /**
     * Draw a panel label if enabled in the config.
     */
    private static void maybeDrawPanelLabel(Graphics2D g2d,
                                             RenderedExportConfig config,
                                             String label, int w, int h) {
        if (!config.panelLabel().show()) return;
        if (label == null || label.isEmpty()) return;
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        PanelLabelRenderer.drawPanelLabel(g2d, w, h, label,
                config.panelLabel().position(),
                config.panelLabel().fontSize(),
                config.panelLabel().bold(),
                Color.WHITE);
    }

    /**
     * Resolve the panel label text from config and panel index.
     * If config has fixed text, use it. Otherwise auto-increment from index.
     */
    private static String resolvePanelLabel(RenderedExportConfig config, int panelIndex) {
        if (!config.panelLabel().show()) return null;
        String text = config.panelLabel().text();
        if (text != null && !text.isBlank()) {
            return text;
        }
        return PanelLabelRenderer.labelForIndex(panelIndex);
    }

    /**
     * Export split-channel panels: one image per visible channel plus a merged composite.
     * The merge panel includes all overlays; individual channel panels are bare images
     * with optional grayscale conversion, color border, and color legend swatch.
     *
     * @param imageData      the image data to export
     * @param classifier     the pixel classifier (null for non-CLASSIFIER modes)
     * @param densityBuilder the density map builder (null for non-DENSITY_MAP modes)
     * @param config         rendered export configuration with splitChannels enabled
     * @param entryName      the image entry name (used for filename generation)
     * @param panelIndex     zero-based index for auto-incrementing panel labels
     * @return the number of files exported (channels + 1 for merge)
     * @throws IOException if the export fails
     */
    public static int exportSplitChannels(ImageData<BufferedImage> imageData,
                                           PixelClassifier classifier,
                                           DensityMaps.DensityMapBuilder densityBuilder,
                                           RenderedExportConfig config,
                                           String entryName,
                                           int panelIndex) throws IOException {
        var baseServer = imageData.getServer();
        var display = resolveImageDisplay(imageData, config);
        if (display == null) {
            logger.warn("Cannot resolve display for split-channel export; skipping");
            return 0;
        }

        var channels = display.selectedChannels();
        if (channels == null || channels.isEmpty()) {
            logger.warn("No visible channels for split-channel export; skipping");
            return 0;
        }

        double downsample = resolveEffectiveDownsample(config, baseServer);
        File outDir = config.getOutputDirectory();
        int filesExported = 0;

        // 1. Merge panel -- full composite with all channels + overlays
        try {
            var mergeServer = ChannelDisplayTransformServer.createColorTransformServer(
                    baseServer, channels);
            String mergeFilename = config.buildMergeFilename(entryName);
            File mergeFile = new File(outDir, mergeFilename);
            String panelLabel = resolvePanelLabel(config, panelIndex);

            // Render merge based on render mode (reuse existing composite methods)
            BufferedImage mergeImage;
            switch (config.getRenderMode()) {
                case CLASSIFIER_OVERLAY -> {
                    if (classifier != null && classifier.supportsImage(imageData)) {
                        var classServer = new PixelClassificationImageServer(imageData, classifier);
                        mergeImage = renderClassifierComposite(
                                imageData, baseServer, classServer, mergeServer, config, panelLabel);
                        closeQuietly(classServer, entryName);
                    } else {
                        mergeImage = renderObjectComposite(
                                imageData, baseServer, mergeServer, config, panelLabel);
                    }
                }
                case DENSITY_MAP_OVERLAY -> {
                    if (densityBuilder != null) {
                        mergeImage = renderDensityMapComposite(
                                imageData, baseServer,
                                densityBuilder.buildServer(imageData),
                                mergeServer, config, panelLabel);
                    } else {
                        mergeImage = renderObjectComposite(
                                imageData, baseServer, mergeServer, config, panelLabel);
                    }
                }
                default -> mergeImage = renderObjectComposite(
                        imageData, baseServer, mergeServer, config, panelLabel);
            }

            ImageWriterTools.writeImage(mergeImage, mergeFile.getAbsolutePath());
            logger.info("Exported merge: {}", mergeFile.getAbsolutePath());
            filesExported++;
            closeQuietly(mergeServer, entryName);
        } catch (Exception e) {
            logger.error("Failed to export merge panel for {}: {}", entryName, e.getMessage());
        }

        // 2. Per-channel panels
        for (int ch = 0; ch < channels.size(); ch++) {
            try {
                var singleChannel = List.of(channels.get(ch));
                var chServer = ChannelDisplayTransformServer.createColorTransformServer(
                        baseServer, singleChannel);

                RegionRequest request = RegionRequest.createInstance(
                        chServer.getPath(), downsample,
                        0, 0, chServer.getWidth(), chServer.getHeight());
                BufferedImage chImage = chServer.readRegion(request);

                // Get channel color (packed RGB from QuPath channel info)
                int channelColor = 0xFFFFFF; // default white
                var serverChannels = baseServer.getMetadata().getChannels();
                if (ch < serverChannels.size()) {
                    channelColor = serverChannels.get(ch).getColor();
                }

                // Grayscale conversion (default)
                if (config.splitChannel().grayscale()) {
                    chImage = convertToGrayscale(chImage);
                }

                // Wrap in RGB for drawing decorations
                BufferedImage result = new BufferedImage(
                        chImage.getWidth(), chImage.getHeight(),
                        BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = result.createGraphics();
                g2d.drawImage(chImage, 0, 0, null);

                // Optional color border
                if (config.splitChannel().colorBorder()) {
                    drawChannelColorBorder(g2d, result.getWidth(), result.getHeight(),
                            channelColor);
                }

                // Optional color legend swatch
                if (config.splitChannel().colorLegend()) {
                    String chName = channels.get(ch).getName();
                    drawChannelColorLegend(g2d, result.getWidth(), result.getHeight(),
                            channelColor, chName);
                }

                // Scale bar on per-channel panels too
                maybeDrawScaleBar(g2d, imageData, config,
                        result.getWidth(), result.getHeight());

                g2d.dispose();

                String chName = channels.get(ch).getName();
                String chFilename = config.buildSplitChannelFilename(entryName, ch, chName);
                File chFile = new File(outDir, chFilename);
                ImageWriterTools.writeImage(result, chFile.getAbsolutePath());
                logger.info("Exported channel {}: {}", chName, chFile.getAbsolutePath());
                filesExported++;

                closeQuietly(chServer, entryName);
            } catch (Exception e) {
                logger.error("Failed to export channel {} for {}: {}",
                        ch, entryName, e.getMessage());
            }
        }

        return filesExported;
    }

    /**
     * Convert a color image to grayscale while preserving dimensions.
     */
    private static BufferedImage convertToGrayscale(BufferedImage src) {
        var gray = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_BYTE_GRAY);
        var g = gray.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return gray;
    }

    /**
     * Draw a thin colored border around the image using the channel's pseudocolor.
     */
    private static void drawChannelColorBorder(Graphics2D g2d, int w, int h,
                                                int packedColor) {
        int r = (packedColor >> 16) & 0xFF;
        int g = (packedColor >> 8) & 0xFF;
        int b = packedColor & 0xFF;
        Color borderColor = new Color(r, g, b);

        int borderWidth = Math.max(2, Math.min(w, h) / 100);
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        g2d.setColor(borderColor);
        g2d.setStroke(new BasicStroke(borderWidth));
        int half = borderWidth / 2;
        g2d.drawRect(half, half, w - borderWidth, h - borderWidth);
    }

    /**
     * Draw a small colored rectangle (swatch) with the channel name in the
     * upper-left corner of the image.
     */
    private static void drawChannelColorLegend(Graphics2D g2d, int w, int h,
                                                int packedColor, String channelName) {
        int r = (packedColor >> 16) & 0xFF;
        int g = (packedColor >> 8) & 0xFF;
        int b = packedColor & 0xFF;
        Color chColor = new Color(r, g, b);

        int minDim = Math.min(w, h);
        int swatchSize = Math.max(8, minDim / 25);
        int margin = Math.max(4, minDim / 50);
        int fontSize = Math.max(10, minDim / 40);

        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));

        // Draw swatch
        g2d.setColor(chColor);
        g2d.fillRect(margin, margin, swatchSize, swatchSize);

        // Draw outline around swatch for visibility
        g2d.setColor(Color.WHITE);
        g2d.drawRect(margin, margin, swatchSize, swatchSize);

        // Draw channel name next to swatch
        if (channelName != null && !channelName.isEmpty()) {
            g2d.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, fontSize));
            int textX = margin + swatchSize + margin;
            int textY = margin + swatchSize - 2;

            // Outline for readability
            g2d.setColor(Color.BLACK);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx != 0 || dy != 0) {
                        g2d.drawString(channelName, textX + dx, textY + dy);
                    }
                }
            }
            g2d.setColor(Color.WHITE);
            g2d.drawString(channelName, textX, textY);
        }
    }

    /**
     * Resolve effective downsample from config, using target DPI if set and
     * the image has pixel calibration.
     */
    private static double resolveEffectiveDownsample(RenderedExportConfig config,
                                                       ImageServer<BufferedImage> server) {
        if (config.getTargetDpi() > 0) {
            var cal = server.getPixelCalibration();
            if (cal.hasPixelSizeMicrons()) {
                return config.computeEffectiveDownsample(cal.getAveragedPixelSizeMicrons());
            }
        }
        return config.getDownsample();
    }

    /**
     * Resolve info label template placeholders with per-image values.
     */
    private static String resolveInfoLabelTemplate(String template, String entryName,
                                                     ImageData<BufferedImage> imageData,
                                                     RenderedExportConfig config) {
        if (template == null || template.isEmpty()) return null;

        String result = template;
        result = result.replace("{imageName}", entryName != null ? entryName : "");

        var server = imageData.getServer();
        var cal = server.getPixelCalibration();
        if (cal.hasPixelSizeMicrons()) {
            String pxSize = String.format("%.3f um/px", cal.getAveragedPixelSizeMicrons());
            result = result.replace("{pixelSize}", pxSize);
        } else {
            result = result.replace("{pixelSize}", "uncalibrated");
        }

        result = result.replace("{date}",
                LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        result = result.replace("{time}",
                LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));

        // Image dimensions
        result = result.replace("{width}", String.valueOf(server.getWidth()));
        result = result.replace("{height}", String.valueOf(server.getHeight()));

        // Classifier name
        String classifierName = config.overlays().classifierName();
        result = result.replace("{classifier}",
                classifierName != null ? classifierName : "");

        return result;
    }

    /**
     * Draw a channel/stain legend if enabled in the config.
     * For fluorescence: channel names + colors.
     * For brightfield: stain names + colors from color deconvolution.
     */
    private static void maybeDrawChannelLegend(Graphics2D g2d,
                                                ImageData<BufferedImage> imageData,
                                                RenderedExportConfig config,
                                                int w, int h) {
        if (!config.isShowChannelLegend()) return;

        var server = imageData.getServer();
        var entries = new java.util.ArrayList<int[]>();
        var names = new java.util.ArrayList<String>();

        // Try color deconvolution stains first (brightfield)
        var stains = imageData.getColorDeconvolutionStains();
        if (stains != null) {
            for (int i = 1; i <= 3; i++) {
                var stain = stains.getStain(i);
                if (stain != null && !stain.isResidual()) {
                    int packed = stain.getColor();
                    entries.add(new int[]{
                            (packed >> 16) & 0xFF,
                            (packed >> 8) & 0xFF,
                            packed & 0xFF});
                    names.add(stain.getName());
                }
            }
        }

        // If no stains, use channel colors (fluorescence)
        if (entries.isEmpty()) {
            var channels = server.getMetadata().getChannels();
            for (var ch : channels) {
                int packed = ch.getColor();
                entries.add(new int[]{
                        (packed >> 16) & 0xFF,
                        (packed >> 8) & 0xFF,
                        packed & 0xFF});
                names.add(ch.getName());
            }
        }

        if (entries.isEmpty()) return;

        int minDim = Math.min(w, h);
        int swatchSize = Math.max(8, minDim / 30);
        int margin = Math.max(6, minDim / 50);
        int fontSize = Math.max(10, minDim / 45);
        int lineSpacing = Math.max(2, swatchSize / 4);

        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        g2d.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, fontSize));

        int y = margin;
        for (int i = 0; i < entries.size(); i++) {
            int[] rgb = entries.get(i);
            Color chColor = new Color(rgb[0], rgb[1], rgb[2]);

            // Swatch
            g2d.setColor(chColor);
            g2d.fillRect(margin, y, swatchSize, swatchSize);
            g2d.setColor(Color.WHITE);
            g2d.drawRect(margin, y, swatchSize, swatchSize);

            // Label
            int textX = margin + swatchSize + margin;
            int textY = y + swatchSize - 2;
            TextRenderUtils.drawOutlinedText(g2d, names.get(i),
                    textX, textY, Color.WHITE, Color.BLACK);

            y += swatchSize + lineSpacing;
        }
    }

    /**
     * Draw an info label if enabled in the config.
     */
    private static void maybeDrawInfoLabel(Graphics2D g2d,
                                            RenderedExportConfig config,
                                            String resolvedText, int w, int h) {
        if (!config.infoLabel().show()) return;
        if (resolvedText == null || resolvedText.isEmpty()) return;
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        InfoLabelRenderer.drawInfoLabel(g2d, w, h, resolvedText,
                config.infoLabel().position(),
                config.infoLabel().fontSize(),
                config.infoLabel().bold(),
                Color.WHITE);
    }

    /**
     * Draw a magnified inset panel if enabled in the config.
     * Must be called after all other overlays, as the inset crops from the
     * composited result image.
     */
    private static void maybeDrawInset(Graphics2D g2d, BufferedImage result,
                                        RenderedExportConfig config) {
        if (!config.inset().show()) return;
        InsetRenderer.drawInset(g2d, result,
                config.inset().sourceX(), config.inset().sourceY(),
                config.inset().sourceW(), config.inset().sourceH(),
                config.inset().magnification(),
                config.inset().position(),
                config.inset().frameColorAsAwt(),
                config.inset().frameWidth(),
                config.inset().connectingLines());
    }

    private static void closeQuietly(AutoCloseable closeable, String context) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                logger.warn("Error closing resource for: {}", context, e);
            }
        }
    }

    /**
     * Paint object overlays onto a graphics context.
     */
    private static void paintObjects(Graphics2D g2d,
                                     ImageData<BufferedImage> imageData,
                                     double downsample,
                                     int width, int height,
                                     boolean showAnnotations,
                                     boolean showDetections,
                                     boolean fillAnnotations,
                                     boolean showNames) {
        try {
            var overlayOptions = new OverlayOptions();
            overlayOptions.setShowAnnotations(showAnnotations);
            overlayOptions.setShowDetections(showDetections);
            overlayOptions.setFillAnnotations(fillAnnotations);
            overlayOptions.setShowNames(showNames);
            var hierarchyOverlay = new HierarchyOverlay(null, overlayOptions, imageData);

            var gCopy = (Graphics2D) g2d.create();
            gCopy.scale(1.0 / downsample, 1.0 / downsample);

            var region = ImageRegion.createInstance(
                    0, 0,
                    imageData.getServer().getWidth(),
                    imageData.getServer().getHeight(),
                    0, 0);

            hierarchyOverlay.paintOverlay(gCopy, region, downsample, imageData, true);
            gCopy.dispose();
        } catch (Exception e) {
            logger.warn("Failed to paint objects: {}", e.getMessage());
        }
    }
}
