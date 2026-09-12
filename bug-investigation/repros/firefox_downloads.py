#!/usr/bin/env python3
"""Execute the exact production SQL against IPED's existing, unmodified fixture.

This validates the query feeding getDownloads(), not the complete Tika parser.
Additional variants live only in memory and change surrogate IDs consistently.
"""
from pathlib import Path
import json
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "iped-parsers/iped-parsers-impl/src/main/java/iped/parsers/browsers/firefox/FirefoxSqliteParser.java"
FIXTURE = ROOT / "iped-parsers/iped-parsers-impl/src/test/resources/test-files/test_places.sqlite"
REPORT = ROOT / "bug-investigation/evidence/FirefoxDownloadsRepro.txt"

REFERENCE_SQL = """
SELECT p.id, p.url, destination.content, metadata.content
FROM moz_places p
JOIN moz_annos destination ON destination.place_id = p.id
JOIN moz_anno_attributes destination_name ON destination_name.id = destination.anno_attribute_id
JOIN moz_annos metadata ON metadata.place_id = p.id
JOIN moz_anno_attributes metadata_name ON metadata_name.id = metadata.anno_attribute_id
WHERE destination_name.name = 'downloads/destinationFileURI'
  AND metadata_name.name = 'downloads/metaData'
"""

def main():
    source = SOURCE.read_text()
    method = source.split("private List<Download> getDownloads(", 1)[1]
    declaration = method.split("String sql =", 1)[1].split("ResultSet rs", 1)[0]
    production_sql = "".join(json.loads(s) for s in re.findall(r'"(?:[^"\\]|\\.)*"', declaration))
    lines = ["Production query extracted verbatim from FirefoxSqliteParser.getDownloads():", production_sql]
    failures = 0
    # The committed fixture has no pending journal. immutable prevents SQLite from
    # creating WAL/SHM sidecars next to it even though its journal mode is WAL.
    with sqlite3.connect(FIXTURE.resolve().as_uri() + "?mode=ro&immutable=1", uri=True) as original:
        expected_original = sorted(original.execute(REFERENCE_SQL).fetchall())
        assert len(expected_original) == 3, "Recheck fixture: expected three known downloads"
        for label, mapping in [("unaltered repository fixture", None), ("control IDs 3 and 4", (3, 4)), ("valid IDs 103 and 104", (103, 104))]:
            with sqlite3.connect(":memory:") as db:
                original.backup(db)
                if mapping:
                    db.execute("UPDATE moz_anno_attributes SET id = CASE id WHEN 1 THEN ? WHEN 2 THEN ? ELSE id END", mapping)
                    db.execute("UPDATE moz_annos SET anno_attribute_id = CASE anno_attribute_id WHEN 1 THEN ? WHEN 2 THEN ? ELSE anno_attribute_id END", mapping)
                expected = sorted(db.execute(REFERENCE_SQL).fetchall())
                assert expected == expected_original, "Renumbering must preserve download information"
                actual = sorted(db.execute(production_sql).fetchall())
                ok = actual == expected
                failures += not ok
                lines += [f"{'PASS' if ok else 'FAIL'} {label}",
                          f"  annotation IDs: {db.execute('SELECT id, name FROM moz_anno_attributes ORDER BY id').fetchall()}",
                          f"  expected download count: {len(expected)}", f"  actual download count:   {len(actual)}"]
        lines.append("Downloads omitted from the unmodified fixture:")
        for ident, url, destination, metadata in expected_original:
            data = json.loads(metadata)
            assert all(k in data for k in ("endTime", "fileSize"))
            lines.append(f"  id={ident}; file={destination.rsplit('/', 1)[-1]}; bytes={data['fileSize']}; endTime={data['endTime']}")
        lines += ["Existing FirefoxSqliteParserTest asserts bookmark data; it contains no download assertions.",
                  f"{failures} product expectation(s) failed; one compatible-ID control passed."]
    output = "\n".join(lines) + "\n"
    REPORT.parent.mkdir(exist_ok=True)
    REPORT.write_text(output)
    print(output)
    return int(failures != 0)

if __name__ == "__main__":
    raise SystemExit(main())
