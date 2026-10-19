package qupath.ext.quiet.export;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TiffDescriptionTest {

    @TempDir
    File tempDir;

    private static final String DESCRIPTION = "Some map. Pixel type: float32; NaN = no data.\n"
            + "Channels:\n"
            + "  [0] name=Fiber coverage (%); unit=%\n"
            + "  [1] name=HDM\n"
            + "  [2] name=Legacy; scale=0.5; offset=1; unit=um\n"
            + "  [3] name=Pixel positive: Channel 1 &gt; 2000; unit=fraction\n";

    /** A TIFF holding only an IFD with an ImageDescription tag. */
    private File writeTiff(String name, boolean big, ByteOrder order, String text) throws IOException {
        byte[] desc = (text + "\0").getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(64 + desc.length).order(order);
        b.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        b.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        if (big) {
            b.putShort((short) 43).putShort((short) 8).putShort((short) 0).putLong(16);
            b.position(16);
            b.putLong(1);                       // entry count
            b.putShort((short) 270).putShort((short) 2).putLong(desc.length).putLong(64);
            b.putLong(0);                       // next IFD
        } else {
            b.putShort((short) 42).putInt(8);
            b.putShort((short) 1);              // entry count
            b.putShort((short) 270).putShort((short) 2).putInt(desc.length).putInt(64);
            b.putInt(0);
        }
        b.position(64);
        b.put(desc);
        File f = new File(tempDir, name);
        Files.write(f.toPath(), b.array());
        return f;
    }

    @Test
    void readsClassicAndBigTiff() throws IOException {
        for (var order : new ByteOrder[]{ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            assertEquals(DESCRIPTION, TiffDescription.read(writeTiff("c.tif", false, order, DESCRIPTION)));
            assertEquals(DESCRIPTION, TiffDescription.read(writeTiff("b.tif", true, order, DESCRIPTION)));
        }
    }

    @Test
    void notATiffGivesNull() throws IOException {
        File f = new File(tempDir, "x.txt");
        Files.writeString(f.toPath(), "hello world, not a tiff");
        assertNull(TiffDescription.read(f));
        assertNull(TiffDescription.read(new File(tempDir, "missing.tif")));
    }

    @Test
    void parsesChannelUnits() {
        var units = TiffDescription.channelUnits(DESCRIPTION);
        assertEquals("%", units.get(0));
        assertNull(units.get(1));
        assertEquals("um", units.get(2));
        assertEquals("fraction", units.get(3));
        assertTrue(TiffDescription.channelUnits(null).isEmpty());
    }
}
