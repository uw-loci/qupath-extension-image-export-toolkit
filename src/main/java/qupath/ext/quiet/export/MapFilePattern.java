package qupath.ext.quiet.export;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import qupath.lib.common.GeneralTools;

/**
 * Finds the map file that belongs to a project image from a file-name pattern.
 * <p>
 * Tokens (each may take a length limit, e.g. {@code {imageSafe:60}}):
 * <ul>
 *   <li>{@code {project}} -- the project folder</li>
 *   <li>{@code {imageDir}} -- the folder holding the image file</li>
 *   <li>{@code {image}} -- the image name as shown in the project</li>
 *   <li>{@code {imageBase}} -- the image name without its file extension</li>
 *   <li>{@code {imageSafe}} -- the image name with each run of {@code \ / : * ? " < > |}
 *       or white space replaced by {@code _}, and trailing dots and spaces removed</li>
 * </ul>
 * A relative pattern is resolved against the project folder. The file-name part
 * may contain the wildcards {@code *} and {@code ?}; exactly one file must match.
 */
public final class MapFilePattern {

    private static final Pattern TOKEN = Pattern.compile("\\{(\\w+)(?::(\\d+))?}");

    private MapFilePattern() {
    }

    /**
     * The values the tokens stand for, for one image.
     *
     * @param projectDir the project folder, or null
     * @param imageDir   the folder holding the image file, or null
     * @param imageName  the image name in the project
     */
    public record ImageContext(File projectDir, File imageDir, String imageName) {}

    /** Thrown when no single file matches the pattern for an image. */
    public static class MapFileNotFoundException extends IllegalArgumentException {
        public MapFileNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Replace the tokens of {@code pattern} for one image.
     *
     * @param pattern the file pattern
     * @param ctx     the image's token values
     * @return the pattern with every token replaced (wildcards kept)
     * @throws IllegalArgumentException if a token is unknown or has no value for this image
     */
    public static String substitute(String pattern, ImageContext ctx) {
        Matcher m = TOKEN.matcher(pattern);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = tokenValue(m.group(1), ctx);
            if (m.group(2) != null) {
                int limit = Integer.parseInt(m.group(2));
                if (value.length() > limit) value = value.substring(0, limit);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Find the one file matching {@code pattern} for an image.
     *
     * @param pattern the file pattern
     * @param ctx     the image's token values
     * @return the matching file
     * @throws MapFileNotFoundException if no file, or more than one, matches
     */
    public static File resolve(String pattern, ImageContext ctx) {
        if (pattern == null || pattern.isBlank()) {
            throw new MapFileNotFoundException("no map file pattern is set");
        }
        String expanded = substitute(pattern.trim(), ctx).replace('\\', '/');
        int slash = expanded.lastIndexOf('/');
        String dirPart = slash >= 0 ? expanded.substring(0, slash) : "";
        String namePart = slash >= 0 ? expanded.substring(slash + 1) : expanded;
        if (dirPart.contains("*") || dirPart.contains("?")) {
            throw new IllegalArgumentException(
                    "wildcards are allowed only in the file name, not in folders: " + pattern);
        }

        File dir = dirPart.isEmpty() && slash < 0 ? null : new File(slash == 0 ? "/" : dirPart);
        if (dir == null || !dir.isAbsolute()) {
            if (ctx.projectDir() == null) {
                throw new MapFileNotFoundException(
                        "the pattern is relative but there is no project folder: " + pattern);
            }
            dir = dir == null ? ctx.projectDir() : new File(ctx.projectDir(), dir.getPath());
        }
        dir = dir.toPath().normalize().toFile();

        if (!namePart.contains("*") && !namePart.contains("?")) {
            File file = new File(dir, namePart);
            if (!file.isFile()) {
                throw new MapFileNotFoundException("no map file " + file.getAbsolutePath());
            }
            return file;
        }

        List<File> matches = new ArrayList<>();
        if (dir.isDirectory()) {
            var glob = FileSystems.getDefault().getPathMatcher("glob:" + escapeGlob(namePart));
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir.toPath())) {
                for (Path p : stream) {
                    if (Files.isRegularFile(p) && glob.matches(p.getFileName())) {
                        matches.add(p.toFile());
                    }
                }
            } catch (IOException e) {
                throw new MapFileNotFoundException("cannot list " + dir.getAbsolutePath()
                        + ": " + e.getMessage());
            }
        }
        if (matches.isEmpty()) {
            throw new MapFileNotFoundException("no map file matches "
                    + new File(dir, namePart).getAbsolutePath());
        }
        if (matches.size() > 1) {
            matches.sort(null);
            throw new MapFileNotFoundException(matches.size() + " map files match "
                    + new File(dir, namePart).getAbsolutePath() + " (first two: "
                    + matches.get(0).getName() + ", " + matches.get(1).getName()
                    + "); make the pattern more specific");
        }
        return matches.get(0);
    }

    /**
     * The file-safe form of an image name used by the {@code {imageSafe}} token.
     *
     * @param name the image name
     * @return the safe name; {@code image} if nothing is left
     */
    public static String safeName(String name) {
        if (name == null || name.isBlank()) return "image";
        String s = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        s = s.replaceAll("[. ]+$", "");
        return s.isBlank() ? "image" : s;
    }

    private static String tokenValue(String token, ImageContext ctx) {
        return switch (token) {
            case "project" -> {
                if (ctx.projectDir() == null) {
                    throw new MapFileNotFoundException("{project} used but no project is open");
                }
                yield ctx.projectDir().getAbsolutePath().replace('\\', '/');
            }
            case "imageDir" -> {
                if (ctx.imageDir() == null) {
                    throw new MapFileNotFoundException(
                            "{imageDir} used but the image is not a local file");
                }
                yield ctx.imageDir().getAbsolutePath().replace('\\', '/');
            }
            case "image" -> nonNullName(ctx);
            case "imageBase" -> {
                String base = GeneralTools.stripExtension(nonNullName(ctx));
                yield base == null ? nonNullName(ctx) : base;
            }
            case "imageSafe" -> safeName(ctx.imageName());
            default -> throw new IllegalArgumentException("unknown token {" + token
                    + "}; use {project}, {imageDir}, {image}, {imageBase} or {imageSafe}");
        };
    }

    private static String nonNullName(ImageContext ctx) {
        return ctx.imageName() == null ? "" : ctx.imageName();
    }

    /** Escape glob metacharacters other than {@code *} and {@code ?}. */
    private static String escapeGlob(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if ("[]{}\\,".indexOf(c) >= 0) sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }
}
