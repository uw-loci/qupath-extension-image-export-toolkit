package qupath.ext.quiet.export;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.analysis.heatmaps.DensityMaps;
import qupath.lib.analysis.heatmaps.DensityMaps.DensityMapBuilder;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Where a map overlay comes from: opens the {@link MapLayer} for one image.
 */
public interface MapOverlaySource {

    /**
     * Open the map layer for one image. The caller closes it.
     *
     * @param imageData the image the map is drawn over
     * @return the layer
     * @throws MapFilePattern.MapFileNotFoundException if this image has no map (the image is skipped)
     * @throws IllegalArgumentException if the map cannot be used for this image (the image is skipped)
     * @throws IOException if the map cannot be read
     */
    MapLayer open(ImageData<BufferedImage> imageData) throws IOException;

    /** A one-line description for logs and the export metadata. */
    String describe();

    /**
     * A source drawing channel 0 of a QuPath density map.
     *
     * @param name    the density map's name in the project
     * @param builder the density map builder
     * @return the source
     */
    static MapOverlaySource ofDensityMap(String name, DensityMapBuilder builder) {
        return new MapOverlaySource() {
            @Override
            public MapLayer open(ImageData<BufferedImage> imageData) {
                var server = builder.buildServer(imageData);
                var base = imageData.getServer();
                return new MapLayer(server, true, 0, null, base.getWidth(), base.getHeight());
            }

            @Override
            public String describe() {
                return "project density map '" + name + "'";
            }
        };
    }

    /**
     * A source reading one map file per image, found with a {@link MapFilePattern}.
     *
     * @param project the project, used for {@code {project}} and for image names; may be null
     * @param pattern the file pattern
     * @param channel the channel name to draw; blank for the first channel
     * @return the source
     */
    static MapOverlaySource ofImageFiles(Project<BufferedImage> project, String pattern, String channel) {
        return new ImageFileSource(project, pattern, channel);
    }

    /**
     * The source a configuration asks for, loading a project density map by name.
     *
     * @param project the open project; may be null for a map-file source
     * @param config  the rendered export configuration
     * @return the source, or null if the configuration draws no map
     * @throws IOException if the named density map cannot be loaded
     */
    static MapOverlaySource forConfig(Project<BufferedImage> project, RenderedExportConfig config)
            throws IOException {
        if (config.getRenderMode() != RenderedExportConfig.RenderMode.DENSITY_MAP_OVERLAY) {
            return null;
        }
        var map = config.mapOverlay();
        if (map.source() == RenderedExportConfig.MapSource.IMAGE_FILE) {
            return ofImageFiles(project, map.filePattern(), map.channel());
        }
        String name = config.overlays().densityMapName();
        if (name == null || name.isBlank()) {
            throw new IOException("no density map is selected");
        }
        if (project == null) {
            throw new IOException("a project density map needs an open project");
        }
        DensityMapBuilder builder = project.getResources(
                DensityMaps.PROJECT_LOCATION, DensityMapBuilder.class, "json").get(name);
        if (builder == null) {
            throw new IOException("density map not found in the project: " + name);
        }
        return ofDensityMap(name, builder);
    }

    /**
     * The token values for one image of a project.
     *
     * @param project   the project, may be null
     * @param imageData the image
     * @return the context
     */
    static MapFilePattern.ImageContext imageContext(Project<BufferedImage> project,
                                                    ImageData<BufferedImage> imageData) {
        var entry = project == null ? null : project.getEntry(imageData);
        if (entry != null) return imageContext(project, entry);
        var server = imageData.getServer();
        return new MapFilePattern.ImageContext(projectDir(project),
                firstLocalFolder(server.getURIs()), server.getMetadata().getName());
    }

    /**
     * The token values for one project image, without opening it.
     *
     * @param project the project, may be null
     * @param entry   the project image
     * @return the context
     */
    static MapFilePattern.ImageContext imageContext(Project<BufferedImage> project,
                                                    ProjectImageEntry<BufferedImage> entry) {
        Collection<URI> uris;
        try {
            uris = entry.getURIs();
        } catch (IOException e) {
            uris = List.of();
        }
        return new MapFilePattern.ImageContext(projectDir(project),
                firstLocalFolder(uris), entry.getImageName());
    }

    private static File projectDir(Project<BufferedImage> project) {
        if (project == null || project.getPath() == null || project.getPath().getParent() == null) {
            return null;
        }
        return project.getPath().getParent().toFile();
    }

    private static File firstLocalFolder(Collection<URI> uris) {
        for (URI uri : uris) {
            if ("file".equals(uri.getScheme())) {
                var parent = Paths.get(uri).getParent();
                return parent == null ? null : parent.toFile();
            }
        }
        return null;
    }

    /** Map files found by pattern, one per image. */
    final class ImageFileSource implements MapOverlaySource {

        private static final Logger logger = LoggerFactory.getLogger(ImageFileSource.class);

        private final Project<BufferedImage> project;
        private final String pattern;
        private final String channel;

        ImageFileSource(Project<BufferedImage> project, String pattern, String channel) {
            this.project = project;
            this.pattern = pattern;
            this.channel = channel == null ? "" : channel.trim();
        }

        /**
         * The map file for an image.
         *
         * @param imageData the image
         * @return the file
         * @throws MapFilePattern.MapFileNotFoundException if no single file matches
         */
        public File findFile(ImageData<BufferedImage> imageData) {
            return MapFilePattern.resolve(pattern, imageContext(project, imageData));
        }

        /**
         * The map file for a project image, without opening the image.
         *
         * @param entry the project image
         * @return the file
         * @throws MapFilePattern.MapFileNotFoundException if no single file matches
         */
        public File findFile(ProjectImageEntry<BufferedImage> entry) {
            return MapFilePattern.resolve(pattern, imageContext(project, entry));
        }

        @Override
        public MapLayer open(ImageData<BufferedImage> imageData) throws IOException {
            File file = findFile(imageData);
            ImageServer<BufferedImage> server = ImageServers.buildServer(file.toURI());
            try {
                int c = channelIndex(server, channel);
                var base = imageData.getServer();
                String description = TiffDescription.read(file);
                MapPlacement placement;
                try {
                    placement = MapPlacement.parse(description);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(file.getName() + ": " + e.getMessage(), e);
                }
                if (placement == null) {
                    double ax = base.getWidth() / (double) base.getHeight();
                    double am = server.getWidth() / (double) server.getHeight();
                    if (Math.abs(am / ax - 1) > 0.02) {
                        logger.warn("Map {} is {} x {} but the image is {} x {}; it is stretched"
                                        + " over the whole image", file.getName(), server.getWidth(),
                                server.getHeight(), base.getWidth(), base.getHeight());
                    }
                }
                String unit = TiffDescription.channelUnits(description).get(c);
                double period = TiffDescription.channelAxialPeriods(description).getOrDefault(c, 0.0);
                List<String> classes = classNames(server, c);
                logger.debug("Map overlay {} channel {} ({}){}{}{}", file, c, server.getChannel(c).getName(),
                        placement == null ? ", stretched" : ", " + placement.describe(),
                        period > 0 ? ", angle of period " + period : "",
                        classes == null ? "" : ", classes " + classes);
                try {
                    return new MapLayer(server, true, c, unit, base.getWidth(), base.getHeight(),
                            placement, period, classes);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(file.getName() + ": " + e.getMessage(), e);
                }
            } catch (RuntimeException e) {
                closeQuietly(server);
                throw e;
            }
        }

        private static void closeQuietly(ImageServer<BufferedImage> server) {
            try {
                server.close();
            } catch (Exception e) {
                logger.debug("Error closing map server: {}", e.getMessage());
            }
        }

        @Override
        public String describe() {
            return "map files '" + pattern + "'"
                    + (channel.isEmpty() ? "" : ", channel '" + channel + "'");
        }
    }

    /**
     * Class names for a class-index channel: a channel named "... class index" whose classes
     * are the map's {@code P(<class>)} channels, in channel order.
     *
     * @param server  the map server
     * @param channel the drawn channel
     * @return the class names by index, or null if the channel is not a class index
     */
    public static List<String> classNames(ImageServer<BufferedImage> server, int channel) {
        String name = server.getChannel(channel).getName();
        if (name == null || !name.trim().toLowerCase(java.util.Locale.ROOT).endsWith("class index")) {
            return null;
        }
        List<String> names = new java.util.ArrayList<>();
        for (var ch : server.getMetadata().getChannels()) {
            String n = ch.getName() == null ? "" : ch.getName().trim();
            if (n.startsWith("P(") && n.endsWith(")") && n.length() > 3) {
                names.add(n.substring(2, n.length() - 1));
            }
        }
        return names.isEmpty() ? null : names;
    }

    /**
     * The index of a channel by name; blank means channel 0.
     *
     * @param server the map server
     * @param name   the channel name
     * @return the zero-based index
     * @throws IllegalArgumentException if no channel has that name
     */
    static int channelIndex(ImageServer<BufferedImage> server, String name) {
        if (name == null || name.isBlank()) return 0;
        var channels = server.getMetadata().getChannels();
        for (int i = 0; i < channels.size(); i++) {
            if (name.equals(channels.get(i).getName())) return i;
        }
        for (int i = 0; i < channels.size(); i++) {
            if (name.equalsIgnoreCase(channels.get(i).getName())) return i;
        }
        var names = channels.stream().map(ch -> ch.getName()).toList();
        throw new IllegalArgumentException("the map has no channel '" + name + "' (it has "
                + String.join(", ", names) + ")");
    }
}
