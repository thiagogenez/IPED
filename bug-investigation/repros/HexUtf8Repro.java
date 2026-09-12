package iped.app.ui.viewers;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.swing.JLabel;
import iped.utils.SeekableFileInputStream;
import iped.viewers.HexViewerPlus.Hits;
import org.exbin.deltahex.highlight.swing.HighlightCodeAreaPainter;
import org.exbin.deltahex.swing.CodeArea;

/** Calls the actual production search worker synchronously; only UI collaborators are stand-ins. */
public class HexUtf8Repro {
    static int failures;
    static void run(String term, int offset, boolean textMode) throws Exception {
        byte[] bytes = new byte[8192];
        Arrays.fill(bytes, (byte) '.');
        byte[] pattern = (textMode ? term : "éé").getBytes(StandardCharsets.UTF_8);
        System.arraycopy(pattern, 0, bytes, offset, pattern.length);
        Path fixture = Files.createTempFile("iped-hex-", ".bin");
        try {
            Files.write(fixture, bytes);
            try (var stream = new SeekableFileInputStream(fixture)) {
                var painter = new HighlightCodeAreaPainter();
                var hits = new Hits();
                var worker = new HexSearcherImpl.GetResultsSearch(new CodeArea(), painter, hits, stream,
                    StandardCharsets.UTF_8, Set.of(term), 0, textMode, false, new JLabel(), 100);
                worker.doInBackground();
                List<Long> positions = new ArrayList<>();
                List<Integer> lengths = new ArrayList<>();
                for (var match : painter.getMatches()) { positions.add(match.getPosition()); lengths.add(match.getLength()); }
                boolean ok = positions.equals(List.of((long) offset)) && lengths.equals(List.of(pattern.length));
                System.out.printf("%s term=%s mode=%s%n  expected offsets=%s lengths=%s%n  actual   offsets=%s lengths=%s%n",
                    ok ? "PASS" : "FAIL", term, textMode ? "UTF-8 text" : "hex", List.of(offset), List.of(pattern.length), positions, lengths);
                if (!ok) failures++;
            }
        } finally { Files.deleteIfExists(fixture); }
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        run("ABCD", 4094, true);
        run("éé", 100, true);
        run("éé", 4094, true);
        run("C3A9C3A9", 4094, false);
        if (failures != 0) throw new AssertionError(failures + " search check(s) failed");
    }
}
