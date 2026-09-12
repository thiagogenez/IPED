import java.nio.file.*;
import org.apache.lucene.store.*;
import org.apache.lucene.index.*;
import org.apache.lucene.document.*;
public class CheckIndex {
    public static void main(String[] args) throws Exception {
        try (Directory d = FSDirectory.open(Path.of(args[0])); DirectoryReader r = DirectoryReader.open(d)) {
            int hits = 0;
            for (int i = 0; i < r.maxDoc(); i++) {
                Document doc = r.document(i);
                for (IndexableField f : doc.getFields()) {
                    if (f.name().startsWith("Regex:")) {
                        System.out.println(doc.get("name") + " | " + f.name() + " | " + f.stringValue());
                        if (f.name().equals("Regex:REGRESSION2940") && f.stringValue().equals("RX2940-12345")) hits++;
                    }
                }
            }
            System.out.println("REGRESSION_HITS=" + hits);
            if (hits != 1) throw new AssertionError("Expected exactly one indexed regex hit, got " + hits);
        }
    }
}
