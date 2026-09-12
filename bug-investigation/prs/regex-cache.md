When regex cache loading or saving fails, RegexTask currently aborts initialization even when it could rebuild the automata or use the ones already in memory. A failed save can also truncate a previously usable cache, causing later runs to fail while reading it.

Addresses [sepinf-inc/IPED#2940](https://github.com/sepinf-inc/IPED/issues/2940).

## Reproduction and observed behavior

The new `RegexTaskCacheTest` exercises the production initialization and matching paths with temporary cache files and a real `RegexTaskConfig` containing `TEST, false = foo[0-9]{3}`. Successful initialization must extract `foo123` from a real Item, with offset 0.

| Scenario | Before | After |
| --- | --- | --- |
| Empty cache | EOFException aborts initialization | Rebuild; matching works |
| Truncated second cache payload | EOFException, after publishing the first part | Cache miss without partial state; rebuild |
| Negative or oversized length fields | Invalid allocation / initialization failure | Reject lengths before allocation; rebuild |
| Invalid serialized list payload | Invalid state can reach matching | Reject incomplete state; rebuild |
| Regular file used as cache parent | FileNotFoundException aborts initialization | Log warning; in-memory matching works |
| StackOverflowError during second serialization | Initialization aborts and previous cache is truncated | Log warning; preserve previous cache and continue matching |

The overflow is injected at the serialization boundary, without exhausting the thread stack. Filesystem failures, cache reads, FST round trips, regex compilation and Item matching are real. The tests do not change user.home or touch the user's cache.

## Change

- Treat cache I/O and runtime failures as cache misses or optional save failures; handle StackOverflowError specifically for recursive cache serialization/deserialization. Other fatal errors, including OutOfMemoryError, still propagate.
- Write to a sibling temporary file and atomically replace the cache only after a successful close. If atomic replacement is unavailable, log the failure and keep the previous cache. Remove temporary files on failure when possible.
- Validate serialized lengths against the file size before allocating, and publish both loaded automata only after the complete cache has been read and validated.
- Preserve the cache format and configuration fingerprint. Invalid regex compilation remains fatal.
- Add package-private cache-path and serialization hooks for isolated tests; the public no-argument constructor retains the existing cache location.

## Validation

Maven 3.9.9 and Liberica JDK 11.0.28 Full on macOS ARM64. Local Maven settings route speech repository IDs to Maven Central; repository POMs and dependency versions are unchanged.

```sh
mvn -B -pl iped-engine -am -Dtest=RegexTaskCacheTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -pl iped-engine -am '-Dtest=RegexTaskCacheTest,*ValidatorServiceTest,SwiftCodeServiceTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

Before the correction, with only the cache-path and serialization delegation hooks added: **10 tests, 0 failures, 6 errors**. With the fix: **10 tests, 0 failures, 0 errors**. Including the neighboring validator tests: **71 tests, 0 failures, 0 errors; BUILD SUCCESS**.

Controls verify valid-cache reuse without reserialization, rebuilding after configuration changes, propagation of other fatal errors, and rejection of invalid regex configuration. The tests restore the static task/configuration state they temporarily replace.

## Expanded validation

- Full macOS ARM64 `mvn verify` reached 196 parser tests: 179 passed, 15 skipped and two SevenZip tests failed because the native library does not support this OS/architecture. Re-running the parser suite against unmodified master produced exactly the same failing test names, exception types and messages.
- Continuing `verify` with only `SevenZipParserTest` excluded completed successfully: 194 parser tests (15 skipped) and 100 engine tests (none skipped), zero failures/errors, and all reactor modules built. The command added `'-Dtest=!SevenZipParserTest' -Dsurefire.failIfNoSpecifiedTests=false`; no repository build files were changed.
- [Windows Server 2022 / Liberica JDK 11 validation](https://github.com/thiagogenez/IPED/actions/runs/34719146467): all ten cache regression tests passed, followed by a portable application build and four complete synthetic case-processing runs.
- The Windows runs used two text files and `REGRESSION2940, false = RX2940-[0-9]{5}`, with a reduced profile enabling parsing, regex matching and text indexing. Fresh, valid, empty and non-replaceable cache scenarios each exited successfully. Direct Lucene index inspection found exactly one `Regex:REGRESSION2940 = RX2940-12345` occurrence, on `match.txt`, in every case.
- Logs confirm valid-cache loading, empty-cache rebuilding, and processing continuing after the blocked save. The blocked path was a non-empty directory named `regexAutomata.cache`, avoiding permission tests that can behave differently for privileged users.

CI scripts are preserved on the separate `validation/regex-cache-windows` branch; they are not part of this PR's diff. The tested production code is unchanged from this PR. The macOS processing attempt was blocked by native FFmpeg/Zstandard requirements; the complete synthetic processing proof was executed on Windows instead.

The GUI, disk-image ingestion and the reporter's large regex configuration were not tested. Fifteen parser tests were skipped by the existing suite/environment. This does not change regex syntax handling or address #2939.
