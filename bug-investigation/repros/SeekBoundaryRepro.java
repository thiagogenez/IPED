import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import iped.utils.LimitedSeekableInputStream;
import iped.utils.SeekableFileInputStream;

public class SeekBoundaryRepro {
    private static int failures;
    private static void check(String name, String expected, String actual) {
        boolean ok = expected.equals(actual);
        System.out.printf("%s %s: expected=%s actual=%s%n", ok ? "PASS" : "FAIL", name, expected, actual);
        if (!ok) failures++;
    }
    private static LimitedSeekableInputStream open(Path path) throws Exception {
        return new LimitedSeekableInputStream(new SeekableFileInputStream(path), 4, 5);
    }
    private static String read(LimitedSeekableInputStream stream) throws Exception {
        return new String(stream.readAllBytes(), StandardCharsets.US_ASCII);
    }
    public static void main(String[] args) throws Exception {
        Path parent = Files.createTempFile("iped-parent-", ".bin");
        try {
            Files.writeString(parent, "HEADABCDESECRET", StandardCharsets.US_ASCII);
            try (var stream = open(parent)) {
                check("sequential control", "ABCDE", read(stream));
                stream.seek(0);
                check("reread after seek(0)", "ABCDE", read(stream));
            }
            try (var stream = open(parent)) {
                stream.seek(4);
                check("remaining bytes after seek(4)", "1", String.valueOf(stream.available()));
                check("stay inside carved item", "E", read(stream));
            }
            try (var stream = open(parent)) {
                stream.seek(5);
                check("EOF at item size", "-1", String.valueOf(stream.read()));
            }
            // ByteArraySeekData uses 4096-byte pages and a 2000-page FIFO cache.
            // After reading 2001 pages, revisiting page zero requires another read.
            byte[] pages = new byte[2001 * 4096];
            Arrays.fill(pages, (byte) 'A');
            Files.write(parent, pages);
            try (var stream = new LimitedSeekableInputStream(new SeekableFileInputStream(parent), 0, pages.length)) {
                byte[] page = new byte[4096];
                for (int i = 0; i < 2001; i++) {
                    stream.seek((long) i * page.length);
                    if (stream.readNBytes(page, 0, page.length) != page.length)
                        throw new AssertionError("Initial page read failed");
                }
                stream.seek(0);
                int count = stream.readNBytes(page, 0, page.length);
                check("hex viewer reread after FIFO eviction", "4096", String.valueOf(count));
            }
        } finally {
            Files.deleteIfExists(parent);
        }
        if (failures > 0) throw new AssertionError(failures + " failed checks");
    }
}
