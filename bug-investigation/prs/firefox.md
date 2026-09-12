Firefox download extraction currently assumes that the destination and metadata annotations always have IDs 3 and 4. These are database-generated IDs, so valid downloads disappear from the specialized parser output when the IDs differ. This change resolves the IDs by their names in `moz_anno_attributes`.

## Reproduction and observed behavior

The existing `test-files/test_places.sqlite` fixture already reproduces the defect; no new binary fixture is needed. Its annotation mapping is:

| ID | Annotation |
| --- | --- |
| 1 | downloads/destinationFileURI |
| 2 | downloads/metaData |

Parse that fixture with `FirefoxSqliteParser` and collect embedded items whose content type is `application/x-firefox-downloads-registry`.

Expected: three download entries, with their source URL, local destination, date and size. Before this change: zero entries, without a SQL error.

| Download in the existing fixture | Bytes | Before | After |
| --- | ---: | --- | --- |
| processo-pf-master.zip | 53,274 | omitted | extracted |
| streeg-master.zip | 858 | omitted | extracted |
| PTFRONTEND-master.zip | 4,675,214 | omitted | extracted |

The new tests also renumber both sides of the annotation relationship in temporary copies. IDs 3/4 continue to work, while IDs 103/104 now return the same downloads. This checks that extraction follows the annotation meaning rather than another set of numeric IDs.

## Change

- Resolve `downloads/destinationFileURI` and `downloads/metaData` through their unique names in the existing annotation table.
- Add three tests through the complete parser entry point, asserting count, URL, destination, timestamp and byte size.
- Update two aggregate expectations in the existing bookmark test: its shared tracker also collects URLs and creation dates from download entries, so recovering three downloads adds three values to each collection. Bookmark-specific assertions are retained.

Mozilla's [annotation implementation](https://searchfox.org/firefox-main/source/toolkit/components/places/History.sys.mjs) likewise resolves annotation IDs by name.

## Validation

```sh
mvn -B -pl iped-parsers/iped-parsers-impl -am -Dtest=FirefoxSqliteParserTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The tests were first run against the original parser: the existing-fixture and renumbered-ID regressions failed with `expected:<3> but was:<0>`, while the 3/4 control passed. With the fix, all four tests pass, including the existing bookmark test. Maven builds the required reactor modules; no parser mocks or SQLite substitutes are used.

No new dependencies or fixture files are introduced. This PR is scoped to download annotation lookup.
