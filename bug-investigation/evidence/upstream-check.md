Checked against sepinf-inc/IPED master via GitHub's recursive git-tree API.
All five upstream blob IDs match `git hash-object` for the inspected local files:

| File | Git blob SHA-1 |
| --- | --- |
| HashTask.java | 4ca6e03315a45cd5d875b2cb86a58da60f86bafe |
| FirefoxSqliteParser.java | 35b0ee37c6d9731e103eb9c0d7104501d9799193 |
| LimitedSeekableInputStream.java | 85d687a9d03e6f21b143e93d0e8f6632c2a0b088 |
| HexSearcherImpl.java | 8cad670434cab722f55ce0c45b03d34f70eb9ff6 |
| DateUtil.java | 15560c05f6af64a541f4efdb416423a40b1142bb |

Issue/PR searches included all states, with queries combining Firefox/download,
hash/error, hash/incorrect, timezone, hex/search, and the relevant class names.
This is a targeted duplicate check, not a guarantee that no differently worded
report exists.

- [Open PR #2918](https://github.com/sepinf-inc/IPED/pull/2918) changes HashTask's threading and small-item path. Its inspected diff does not reset digest state on error. It overlaps the file and should be considered when preparing a separate correctness fix.
- [Issue #2949](https://github.com/sepinf-inc/IPED/issues/2949) concerns keeping a partially populated Item cache after a TSK error. It is distinct from reusing a failed HashTask digest for the next item.
- [Closed issue #1879](https://github.com/sepinf-inc/IPED/issues/1879) concerns fractional-second timestamps with whole-hour offsets being interpreted without their offset. The current reproduction passes the whole-hour control and still fails offsets with nonzero minutes.
- [Closed issue #1277](https://github.com/sepinf-inc/IPED/issues/1277) concerns rejecting search input in the combo field, not a missed UTF-8 match at a byte-buffer boundary.
- No equivalent Firefox-download-ID or limited-stream-seek report was identified in the searches performed.

Primary technical references:

- [Mozilla History implementation](https://searchfox.org/firefox-main/source/toolkit/components/places/History.sys.mjs): inserts annotation names without fixed numeric IDs; resolves IDs by annotation name.
- [Mozilla Places table definitions](https://searchfox.org/mozilla-central/source/toolkit/components/places/nsPlacesTables.h): moz_anno_attributes has an integer primary key and a unique name.
- [Java 11 SimpleDateFormat documentation](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/text/SimpleDateFormat.html): X, XX and XXX have different offset formats; X accepts the hour portion.
