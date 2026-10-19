package qupath.ext.quiet.export;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the ImageDescription tag of a TIFF file's first image (classic TIFF and
 * BigTIFF), and the channel units a description may list.
 */
public final class TiffDescription {

    private static final int TAG_IMAGE_DESCRIPTION = 270;
    private static final int MAX_DESCRIPTION_BYTES = 16 * 1024 * 1024;

    /** One row per channel: {@code [index] name=...; unit=...}, extra {@code ; key=value} fields allowed. */
    private static final Pattern UNIT_ROW = Pattern.compile(
            "\\[(\\d+)]\\s*name=[^\\r\\n]*?;\\s*unit=([^;\\r\\n<]+)");

    private TiffDescription() {
    }

    /**
     * Read the description of the first image in a TIFF file.
     *
     * @param file the file
     * @return the description, or null if the file is not a TIFF or has none
     */
    public static String read(File file) {
        if (file == null || !file.isFile()) return null;
        try (var raf = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[16];
            if (raf.read(header) < 8) return null;
            ByteOrder order;
            if (header[0] == 'I' && header[1] == 'I') order = ByteOrder.LITTLE_ENDIAN;
            else if (header[0] == 'M' && header[1] == 'M') order = ByteOrder.BIG_ENDIAN;
            else return null;
            var hb = ByteBuffer.wrap(header).order(order);
            int magic = hb.getShort(2) & 0xFFFF;
            boolean big = magic == 43;
            if (!big && magic != 42) return null;
            long ifd = big ? hb.getLong(8) : hb.getInt(4) & 0xFFFFFFFFL;

            raf.seek(ifd);
            long count = big ? readBuf(raf, 8, order).getLong() : readBuf(raf, 2, order).getShort() & 0xFFFF;
            int entrySize = big ? 20 : 12;
            for (long i = 0; i < count; i++) {
                ByteBuffer e = readBuf(raf, entrySize, order);
                int tag = e.getShort() & 0xFFFF;
                e.getShort(); // field type: ASCII for this tag
                long n = big ? e.getLong() : e.getInt() & 0xFFFFFFFFL;
                if (tag != TAG_IMAGE_DESCRIPTION) continue;
                if (n <= 0 || n > MAX_DESCRIPTION_BYTES) return null;
                byte[] text = new byte[(int) n];
                int inline = big ? 8 : 4;
                if (n <= inline) {
                    e.get(text);
                } else {
                    long offset = big ? e.getLong() : e.getInt() & 0xFFFFFFFFL;
                    raf.seek(offset);
                    raf.readFully(text);
                }
                int len = text.length;
                while (len > 0 && text[len - 1] == 0) len--;
                return new String(text, 0, len, StandardCharsets.UTF_8);
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Channel units listed in a description as rows {@code [index] name=...; unit=...}.
     *
     * @param description the description text, may be null
     * @return unit by zero-based channel index; empty if none are listed
     */
    public static Map<Integer, String> channelUnits(String description) {
        Map<Integer, String> units = new LinkedHashMap<>();
        if (description == null) return units;
        Matcher m = UNIT_ROW.matcher(description);
        while (m.find()) {
            String unit = m.group(2).trim();
            if (!unit.isEmpty()) {
                units.put(Integer.parseInt(m.group(1)), unescapeXml(unit));
            }
        }
        return units;
    }

    private static ByteBuffer readBuf(RandomAccessFile raf, int n, ByteOrder order) throws IOException {
        byte[] b = new byte[n];
        raf.readFully(b);
        return ByteBuffer.wrap(b).order(order);
    }

    private static String unescapeXml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }
}
