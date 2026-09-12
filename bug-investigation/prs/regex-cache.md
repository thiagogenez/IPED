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

The full application suite, GUI, Windows behavior and the reporter's large regex configuration were not tested. This does not change regex syntax handling or address #2939.
