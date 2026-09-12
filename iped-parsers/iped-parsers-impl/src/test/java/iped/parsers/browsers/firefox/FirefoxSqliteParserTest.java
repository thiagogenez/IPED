package iped.parsers.browsers.firefox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.Test;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import iped.parsers.browsers.AbstractPkgTest;
import iped.parsers.standard.StandardParser;
import iped.properties.ExtraProperties;

public class FirefoxSqliteParserTest extends AbstractPkgTest {

    private static InputStream getStream(String name) {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(name);
    }

    @Test
    public void testFirefoxDownloadsFromExistingFixture() throws Exception {
        try (InputStream stream = getStream("test-files/test_places.sqlite")) {
            assertDownloads(stream);
        }
    }

    @Test
    public void testFirefoxDownloadsWithLegacyAnnotationIds() throws Exception {
        assertDownloadsWithAnnotationIds(3, 4);
    }

    @Test
    public void testFirefoxDownloadsWithDifferentAnnotationIds() throws Exception {
        assertDownloadsWithAnnotationIds(103, 104);
    }

    private void assertDownloadsWithAnnotationIds(int destinationId, int metadataId) throws Exception {
        Path database = Files.createTempFile("firefox-downloads-", ".sqlite");
        try {
            try (InputStream stream = getStream("test-files/test_places.sqlite")) {
                Files.copy(stream, database, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                    Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=DELETE");
                statement.executeUpdate("UPDATE moz_anno_attributes SET id = CASE id WHEN 1 THEN "
                        + destinationId + " WHEN 2 THEN " + metadataId + " ELSE id END");
                statement.executeUpdate("UPDATE moz_annos SET anno_attribute_id = CASE anno_attribute_id WHEN 1 THEN "
                        + destinationId + " WHEN 2 THEN " + metadataId + " ELSE anno_attribute_id END");
            }
            try (InputStream stream = Files.newInputStream(database)) {
                assertDownloads(stream);
            }
        } finally {
            Files.deleteIfExists(database);
        }
    }

    private void assertDownloads(InputStream stream) throws Exception {
        List<Metadata> downloads = new ArrayList<>();
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return true;
            }

            @Override
            public void parseEmbedded(InputStream stream, ContentHandler handler, Metadata metadata,
                    boolean outputHtml) {
                if (FirefoxSqliteParser.MOZ_DOWNLOADS_REG.toString()
                        .equals(metadata.get(StandardParser.INDEXER_CONTENT_TYPE))) {
                    downloads.add(metadata);
                }
            }
        });
        new FirefoxSqliteParser().parse(stream, new BodyContentHandler(), new Metadata(), context);

        assertEquals(3, downloads.size());
        downloads.sort(Comparator.comparing(metadata -> metadata.get(ExtraProperties.DOWNLOAD_DATE)));
        String[] names = { "processo-pf", "streeg", "PTFRONTEND" };
        long[] dates = { 1620839706660L, 1620839733695L, 1620839758333L };
        String[] sizes = { "53274", "858", "4675214" };
        for (int i = 0; i < downloads.size(); i++) {
            Metadata download = downloads.get(i);
            assertEquals("https://codeload.github.com/streeg/" + names[i] + "/zip/refs/heads/master",
                    download.get(ExtraProperties.URL));
            assertEquals("file:///C:/Users/guilh/AppData/Local/Temp/" + names[i] + "-master.zip",
                    download.get(ExtraProperties.LOCAL_PATH));
            // Tika date metadata is serialized with second precision.
            assertEquals(dates[i] / 1000 * 1000, download.getDate(ExtraProperties.DOWNLOAD_DATE).getTime());
            assertEquals(sizes[i], download.get(ExtraProperties.DOWNLOAD_TOTAL_BYTES));
        }
    }

    @Test
    public void testFirefoxSqliteBookMarkParsing() throws IOException, SAXException, TikaException {

        FirefoxSqliteParser parser = new FirefoxSqliteParser();
        Metadata metadata = new Metadata();
        metadata.add(Metadata.CONTENT_TYPE, FirefoxSqliteParser.MOZ_PLACES.toString());
        ContentHandler handler = new BodyContentHandler();
        ParseContext context = new ParseContext();
        parser.getSupportedTypes(context);
        parser.setExtractEntries(true);
        try (InputStream stream = getStream("test-files/test_places.sqlite")) {
            parser.parse(stream, handler, metadata, firefoxContext);

            assertEquals(47, firefoxtracker.bookmarktitle.size());
            // This tracker also collects URL/creation metadata from the three downloads.
            assertEquals(52, firefoxtracker.bookmarkurl.size());
            assertEquals(30, firefoxtracker.bookmarkcreated.size());
            assertEquals(27, firefoxtracker.bookmarkmodified.size());

            assertEquals("Help and Tutorials", firefoxtracker.bookmarktitle.get(0));
            assertEquals("Customize Firefox", firefoxtracker.bookmarktitle.get(1));
            assertEquals("Get Involved", firefoxtracker.bookmarktitle.get(2));
            assertEquals("About Us", firefoxtracker.bookmarktitle.get(3));
            assertEquals("Ubuntu", firefoxtracker.bookmarktitle.get(4));

            assertEquals("https://support.mozilla.org/en-US/products/firefox", firefoxtracker.bookmarkurl.get(0));
            assertEquals(
                    "https://support.mozilla.org/en-US/kb/customize-firefox-controls-buttons-and-toolbars?utm_source=firefox-browser&utm_medium="
                            + "default-bookmarks&utm_campaign=customize",
                    firefoxtracker.bookmarkurl.get(1));
            assertEquals("https://www.mozilla.org/en-US/contribute/", firefoxtracker.bookmarkurl.get(2));
            assertEquals("https://www.mozilla.org/en-US/about/", firefoxtracker.bookmarkurl.get(3));
            assertEquals("http://www.ubuntu.com/", firefoxtracker.bookmarkurl.get(4));

            assertEquals("2020-07-22T19:54:38Z", firefoxtracker.bookmarkcreated.get(0));
            assertEquals("2020-07-22T19:54:38Z", firefoxtracker.bookmarkcreated.get(1));
            assertEquals("2020-07-22T19:54:38Z", firefoxtracker.bookmarkcreated.get(2));
            assertEquals("2020-07-22T19:54:38Z", firefoxtracker.bookmarkcreated.get(3));
            assertEquals("2020-07-22T19:54:38Z", firefoxtracker.bookmarkcreated.get(4));

            assertEquals("2020-10-19T18:54:02Z", firefoxtracker.bookmarkmodified.get(0));
            assertEquals("2020-10-19T18:54:02Z", firefoxtracker.bookmarkmodified.get(1));
            assertEquals("2020-10-19T18:54:02Z", firefoxtracker.bookmarkmodified.get(2));
            assertEquals("2020-10-19T18:54:02Z", firefoxtracker.bookmarkmodified.get(3));
            assertEquals("2020-10-19T18:54:02Z", firefoxtracker.bookmarkmodified.get(4));

        }

    }
}
