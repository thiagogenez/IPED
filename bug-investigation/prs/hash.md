When a file read fails after some bytes have been hashed, `HashTask` keeps the partially updated digests. The same task instance then hashes another item using that state. An intact item can consequently receive the hash of the failed item's readable prefix concatenated with its own content, without being marked as an I/O error.

This change waits for submitted digest updates to finish and resets the failed operation's digest and ED2K state before the task can be reused.

## Reproduction and observed behavior

Use one HashTask instance for these two items:

1. Item A returns 123 bytes containing `A`, then throws IOException after those bytes have been incorporated into the digest.
2. Item B contains the UTF-8 bytes of `intact evidence B` and reads successfully.

| Value | MD5 |
| --- | --- |
| Expected for B: MD5(B) | C08E81E668BC621EF8882D8297C8D226 |
| Before this change: MD5(prefix(A) + B) | 356695504A8EB6D3CFE7592D69D03788 |
| After this change | C08E81E668BC621EF8882D8297C8D226 |

A is marked `ioError=true`; before the fix, B receives the incorrect hash without that error flag. A subsequent successful digest resets the state, so the problem depends on the processing history of the worker. Hash-based lookup and deduplication consumers can therefore receive an incorrect identifier for an intact item.

The regression tests inject the I/O failure with a controlled stream and coordinate execution with latches. They exercise the production HashTask and Item classes, JDK digests and Bouncy Castle MD4; no test-specific hashing algorithm is used.

## Change

- Keep the outstanding completion latch available to failure cleanup. Wait for submitted updates to finish before resetting mutable digest state; resetting immediately in the catch block would race with those updates.
- Clear each digest, ED2K chunk counters and accumulated chunk hashes after an unsuccessful operation.
- Preserve the caller's interrupted status after cleanup and avoid publishing a partial hash when interrupted.
- Check for a digest exception after the final chunk completes, before publishing hashes.
- Account for rejected submissions so cleanup cannot wait indefinitely for a task that never started.

## Regression coverage

- MD5 and SHA-256 after a partial read followed by IOException.
- ED2K after a completed 9,728,000-byte chunk plus a partial next chunk, covering both the digest and accumulated chunk hashes.
- I/O failure while a digest update remains pending, verifying that cleanup waits and never resets a running digest.
- Interruption while a digest update remains pending, verifying cleanup and preservation of the interrupt flag.
- An asynchronous failure in the final digest update, verifying that no hash is published and the next item hashes correctly.

The reuse tests compare subsequent successful items with independently computed hashes, including a second successful reuse.

## Validation

```sh
mvn -B -pl iped-engine -am -Dtest=HashTaskTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Test environment: Maven 3.9.9 and Liberica JDK 11.0.28 Full on macOS ARM64. The required reactor modules are built. Local Maven settings route the speech repository IDs to Maven Central; dependency versions and repository build files are unchanged. The full application test suite and GUI were not run.

Before the production change: **6 tests run, 6 failures, 0 errors**. After the change: **6 tests run, 0 failures, 0 errors; BUILD SUCCESS**. The baseline was re-run with the final test file to verify all six regressions against the original HashTask.

## Related work

[sepinf-inc/IPED#2918](https://github.com/sepinf-inc/IPED/pull/2918) is an open performance PR modifying HashTask. Its inspected diff does not clear failed digest state. This correctness change overlaps that file and should be considered when preparing an upstream submission.
