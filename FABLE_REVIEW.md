# EfficientLogger code review

| | |
|---|---|
| Date | 2026-10-18 |
| Code reviewed | commit `a9d61b3` (master), clean working tree |
| Scope | the library (`src/main`, 24 files), the tests (`src/test`, 19 files), the benchmark (`src/jmh`), the build, and the documents (`README.md`, `PLAN.md`, `SECURITY_AUDIT.md`) |
| Method | a full read of every source and test file; the design (queue memory model, wake-up protocol, close and flush, the file table's state machine) re-derived from the code and checked against `PLAN.md` and the README; each suspected defect reproduced with a small program run against the compiled classes; the full build run |
| Build | `gradlew build` passes on Windows 11, JDK 17: 379 tests, 5 skipped (POSIX-only), 0 failures. `allocationTest` and `allocationTestNoEA` rerun fresh: 5 of 5 each. `-Xlint:all` reports nothing. |

## Summary

This is a carefully built library. The lock-free queue's reasoning holds up: the admission word
bounds the ring so a claim can never land on an unconsumed slot, the two Dekker re-checks make a
lost wake-up impossible, and slots are nulled before their admission is released so a reused slot
can't race its reader. The file table balances its open-file budget on every error path, the
UTF-8 encoder matches the reference byte for byte including when it writes in pieces, and the
tests are unusually good: deterministic manual-mode tests on a fake clock, real-thread stress
tests with exact accounting, a child-JVM test for the shutdown hook, and allocation tests that
run with escape analysis off. The hot-path rules in `PLAN.md` §4 are followed.

The defects are at the edges, and most of them are in the code added after the security audit.
The two that matter most:

- **`flush()` undoes the F7 fix.** A ring slot that a cut-off `log()` call never published is now
  survived by the main loop, but `Worker.serveFlush` still spins on it forever. One `flush()`
  after such an event pins a core for the life of the process, and `close()` can no longer finish
  at all, not even with the new 2-second give-up, because the worker never returns to the loop
  that implements it (R1).
- **The 2-second give-up at close trades a hang for a silent loss, and `close()` still says
  true.** A producer stalled for 2 s between admission and publishing (OS scheduling, cgroup
  throttling, a debugger) publishes into a ring nobody drains any more. `log()` returned true,
  `close()` returned true, the line is not on disk, and `stats()` does not count it. The README's
  exactness guarantee and the `accepted == written + droppedInvalidId + lostIoError` identity no
  longer hold (R2, D1).

Also verified: `maxFiles` leaks a unit on every failed open, so an outage can permanently cap a
long-running logger (R3); the JMH benchmark has been unable to run EfficientLogger since the
symlink hardening, because the null device is refused as "not a regular file" (R4); and the
audit's "F18 fixed" claim is wrong, since IDs with equal `String.hashCode()` land on one worker
whatever the seed (R5).

None of this needs a redesign. R1 and R3 are a few lines each; R2 is mostly a decision about what
`close()` should report.

### Findings

Severities: **Medium** is a correctness or availability defect under a realistic precondition, or
a broken deliverable; **Low** needs a narrow precondition or is a contract drift; **Info** is
worth knowing. "Verified" means reproduced here against the compiled classes.

| # | Finding | Severity | Verified |
|---|---|---|---|
| R1 | `flush()` with an unpublished slot spins the worker forever and makes `close()` unable to finish | Medium | Yes |
| R2 | The 2 s close give-up loses a slow producer's message; `close()` returns true and `stats()` doesn't count it | Low (contract) | Yes |
| R3 | `maxFiles` leaks a unit on every failed open and charges existing empty files | Medium | Yes |
| R4 | The benchmark can't run EfficientLogger since the F2 hardening: the null device is refused | Medium (project) | Yes |
| R5 | Equal `String.hashCode()` IDs pin one worker or stripe for every seed; audit F18 isn't fixed | Low | Yes |
| R6 | `normalizeIds()` copies and normalizes a huge ID before the length check that F4 added | Low | By reading |
| R7 | After close, a worker spins at 100% CPU for up to 2 s on an unpublished slot | Low | By reading |
| R8 | A sibling worker stalled 2 s in I/O during close produces a false "fatal error" warning | Low | By reading |
| R9 | `flush()` and `close()` still report success after a worker thread dies (audit F13, third bullet) | Low | By reading |
| D1 | README, javadoc and `PLAN.md` promise an exact `close()` and a stats identity the code no longer keeps | Doc | Yes |
| D2 | `WarningHandler` isn't documented as being called concurrently | Doc | By reading |
| D3 | The directory check flags only other-writable, not group-writable, directories | Info | By reading |
| Q1 | `lastByte` treats "permission denied" as "missing" | Info | By reading |
| Q2 | With `filePermissions`, the chmod after create follows symlinks | Info | By reading |
| Q3 | `this` escapes the `AsyncLogger` constructor to the worker threads | Info | By reading |
| Q4 | Repository hygiene: tracked IDE files, Kotlin leftovers, resolver plugin, signatures off | Info | Yes |

### Status since the review

R1 to R4 were fixed in the commit after this review's, R5 to R9 in the one after that, and D1 to
Q4 in the one after that, each with tests. Each probe was re-run against the new classes; the full
build passes (399 tests, 7 of them POSIX-only and skipped on Windows; both allocation suites; no
compiler warnings). A later change, not a finding, moved the workers onto a shared thread pool; the
last row says how it relates to the findings.

| # | Status | What changed | Probe now |
|---|---|---|---|
| R1 | Fixed | `Worker.serveFlush` returns to the main loop when the head slot isn't published, leaving the request pending, instead of spinning; the loop's back-off and the close give-up then apply, and the request is served once the slot is published. Tests: `AsyncThreadsTest.aFlushBehindAnUnpublishedSlotTimesOutAndTheWorkerStillBacksOff` (real threads, CPU measured), `AsyncLoggerTest.aFlushRequestBehindAnUnpublishedSlotDoesNotStopTheWorkerLoop` (deterministic, including the slow producer publishing late and the flush then completing). | `flush(2 s)` false; worker `TIMED_WAITING`, 0 ms CPU in 1 s; `close(6 s)` false after 2.0 s; worker terminated |
| R2 | Fixed | `close()` returns false when messages were left behind (`AsyncLogger.nothingLeft`). `LoggerStats` gained `abandoned`: slots claimed but never consumed, read once the worker has stopped, so `accepted == written + droppedInvalidId + lostIoError + abandoned` holds after close even when a stalled producer publishes late. The warning is emitted once, by the last worker to finish, with the exact count, and no longer asserts a fatal error; a sibling that merely stalled finishes with nothing left, so R8's false alarm is gone too. The 2 s give-up stays. README, the `close` and `LoggerStats` javadoc and `PLAN.md` §3.5 now describe it (D1). Test: `AsyncThreadsTest.closeGivesUpOnALogCallThatNeverArrivesAndReportsIt`; the two F7 tests now assert the new return value, warning and accounting. | `close(10 s)` false after 2.0 s; after the late publish `accepted=2, written=1, abandoned=1, queuedMessages=1`, and the identity holds |
| R3 | Fixed | A failed open gives its `maxFiles` unit back (`FileCreationBudget.release`). `SinkFactory.lastByte` now distinguishes `MISSING` from `EMPTY`, so an existing empty file isn't charged. Tests: `FileTableTest.aFailedOpenGivesItsMaxFilesReservationBack`, `anExistingEmptyFileDoesNotCountAgainstMaxFiles`, `FileOutputStreamSinkTest.tellsAnEmptyFileFromAMissingOne`. | with `maxFiles(2)` and one failed open, `a` is created on the retry: files `[a.json, b.json]` |
| R4 | Fixed | The benchmark's sink is now `NullDeviceSinks` (in `src/jmh`), which opens the null device itself instead of through the production factory. `NullDeviceSinksTest` opens it, and builds a logger on it in both modes to check that nothing is lost; for that, `test` compiles the benchmark sources (`testImplementation(sourceSets["jmh"].output)`). The README's figures were re-measured on 2026-10-19 with the full benchmark against the current code, on Windows and on Ubuntu 24.04 under WSL 2 (Temurin 17.0.20.1); the written rates and latencies are within the old noise, and the one-thread call rate shows the keyed hash (R5). | `gradlew jmhJar` then `throughput1` with `-p impl=efficient-async,efficient-sync -p output=null` (1 s warm-up, 1 s measured) completes with no "messages were lost" error: 10.8 M accepted/s async, 3.2 M/s sync, in line with the README |
| R5 | Fixed | Routing now uses `Hashing.bucket(id, key, n)`: a multiply-xor chain over the ID's characters with a per-logger random 64-bit key, finished with splitmix64's mixer, instead of `String.hashCode()` XOR a seed. Two IDs with equal hash codes no longer share a worker, and without the key IDs can't be chosen to land together (it isn't a cryptographic MAC, but nothing exposes the key or the mapping). It allocates nothing, which the allocation suites confirm. Cost on the caller: about 0.7 ns per ID character; with the benchmark's 7-character IDs the flat-out call rate on one thread fell from about 128 M to 75 M calls/s (both ±50% in 3 × 1 s samples), while the paced latency at 1.3 M calls/s was unchanged (p50 700 ns, p99 900 ns) and the written rate, which the worker bounds, was unaffected. Tests: `HashingTest.idsWithEqualHashCodesAreSpreadOverBuckets` (64 IDs with one hash code, five keys), `theKeyChangesTheMapping`, `AsyncLoggerTest.idsWithEqualHashCodesAreSpreadOverWorkers`, `theShardKeyDecidesWhichWorkerAnIdGoesTo`. The audit's F18 status row now says what happened. | 64 colliding IDs use at least 8 of 16 buckets under every key tried; 32 of them reach at least 3 of 4 workers |
| R6 | Fixed | With `normalizeIds()`, an ID longer than `FileNamePattern.MAX_NORMALIZATION_SHRINK` (8) times the limit is rejected before it is normalized, since nothing shrinks that much: the longest canonical composition is four code points into one (`FileNamePatternTest.normalizationShrinksAnIdByAtMostTheDocumentedFactor` pins the known 4:1, 3:1 and 2:1 cases). Tests count the caller's allocation while a 20-million-char ID is rejected, in both modes, and check that a 280-char ID which normalizes to 70 is still accepted and written. | rejecting the huge ID allocates under 1 MB (it allocated 80 MB) |
| R7 | Fixed | In the closed branch, a worker whose head slot is claimed but unpublished now uses the same `awaitPublish` back-off as when open (spin, then a 1 ms poll) instead of calling `runOnce` back to back until the give-up. `aSlotLeftUnpublishedDoesNotWedgeOrBurnTheWorker` now measures the worker's CPU time while `close()` is waiting. | under 250 ms of CPU in 500 ms during close (it was a full core) |
| R8 | Fixed by R2 | The warning is emitted by the last worker to finish, with the exact logger-wide count, so a sibling that gives up while another worker is merely stalled in I/O reports nothing once that worker completes. Test: `aSiblingStalledInIoDuringCloseIsNotReportedAsALoss`, with one worker blocked inside an open for longer than the give-up. | `close()` true, both lines on disk, no warning |
| R9 | Fixed | `Worker.finish` marks pending flush requests done only if its ring is empty, and `awaitFlush` on a stopped worker answers true only if the ring is empty at that moment, so `flush()` returns false after an abandoned drain, and after a late publish into a stopped worker's ring; `close()` was already fixed by R2. A worker can't actually die while the logger is open (every `Throwable` in the loop is caught and the loop can only exit by `break`), so the last-resort handler in `AsyncLogger.task` stays as a guard. The F7 tests assert the new `flush()` results. | `flush()` false after the give-up and after the late publish; true after a clean close |
| D1 | Fixed with R2 | The README's `close()` and `stats()` paragraphs, the `close` and `LoggerStats` javadoc and `PLAN.md` §3.5 describe the give-up, the `abandoned` counter and the four-term identity. | the tests for R2 assert the documented identity |
| D2 | Fixed | `WarningHandler`'s contract says it must be thread-safe, since several workers (or several callers in sync mode) call it at once; the README's option table and "Warnings" bullet say the same. `WarningHandlerTest.stderrKeepsConcurrentWarningsWhole` has 8 threads emit 1,600 warnings with stack traces through the default handler and checks every header is followed by its own cause line. | no torn output |
| D3 | Fixed | `directoryExposedReason` flags a directory, or a parent, that is writable by its group as well as by other users (without the sticky bit), and the warning says which. The README and the audit's F2 row say so. `FileOutputStreamSinkTest.flagsADirectoryWritableByItsGroupOrByOthers` covers private, read-only, group-, other- and world-writable, sticky world-writable (like `/tmp`) and an exposed parent. | passes on Ubuntu 24.04 under WSL 2 (POSIX-only; skipped on Windows) |
| Q1 | Fixed | `lastByte` tells "removed since the stat" from "can't read": after a `FileNotFoundException` it re-checks with a NOFOLLOW stat and, if the file is still there, throws "can't read ... to see how its last line ended", so the file is treated as an I/O error rather than as new. Test: `refusesAFileItCannotRead` (a `-w-------` file; skipped as root). | passes on Linux |
| Q2 | Fixed | The chmod after `createFile` and `createDirectories` is gone: permissions go on at creation, less what the umask removes, so a symlink swapped in meanwhile has nothing to redirect. The umask can only make the result more private, which the javadoc, README and the audit's F10 row now say. Test: `withPermissionsRefusesASymlinkAndLeavesItsTargetAlone` checks a planted link's target keeps its content and permissions. | passes on Linux; `withPermissionsCreatesFilesAndDirectoriesRestricted` still sees exactly `rw-------` and `rwx------` under umask 022 |
| Q3 | Fixed | `AsyncLogger` is built in two steps: the constructor creates the worker threads without starting them, and `start()`, which the builder calls once construction has finished, starts them and registers the shutdown hook, so nothing can run against a half-built logger. The thread-start failure path moved with it. `AsyncThreadsTest.workerThreadsAreAllCreatedBeforeAnyIsStarted` checks the order a `ThreadFactory` can observe; the existing factory-failure tests cover the moved error path. | 3 workers created, then started; a failed `start()` still stops the ones already running |
| (tests) | Fixed | Running the suite on Linux for D3, Q1 and Q2 showed a pre-existing race in `aSleepingWorkerIsWokenOnceAQuarterOfTheQueueIsUsed`: it checked the worker's sleep flag right after it was set, but when the previous round's wake-up lands while the worker is between announcing TIMED and parking, the worker skips that park and the unpark permit stays pending, so its next park returns at once and the flag flips for an instant. The test now waits until the thread is actually blocked in its timed park (a pending permit prevents that) before logging. One failure in a full Linux run; five of five passes alone; the worker's behaviour is correct, since the extra loop costs one empty pass. | the full suite passes on Linux |
| Q4 | Fixed | `.idea/kotlinc.xml` (a template leftover) is deleted and the Kotlin entries left `.gitignore`; the other `.idea` files stay, since they hold shared project settings. The foojay toolchain resolver plugin is gone from `settings.gradle.kts`, one plugin less to trust, since toolchain download is off and the build uses the installed JDK 17 (its checksums stay in the verification metadata, harmlessly). The metadata file now says in a comment that it checks checksums only, by choice. | `gradlew build` passes without the plugin |
| (pool) | Added after the review | Not a finding, but a design change that touches R2, R7, R8, R9 and Q3: worker threads are now shared between loggers. A public `WorkerPool` has at most `maxThreads` daemon threads, each serving the workers of any number of loggers in turn (`Worker.step()` per worker, then the most urgent wait any of them asked for) and sleeping only when none has work; each worker still announces and re-checks on its own ring, so the lost-wake-up argument is unchanged. Workers go to the least loaded thread, so threads = min(workers, pool size). Every logger uses `WorkerPool.shared()` by default (the smaller of 4 and the processor count, or `-Defficientlogger.sharedWorkerThreads=n`); `workerPool(WorkerPool.create(n[, factory]))` replaces the builder's `threadFactory`. The give-up, the stuck-slot back-off, the flush answers after a give-up and the two-step construction (Q3) all carry over: the back-off is per worker, so a trickle on one ring doesn't keep the thread spinning for another ring's stuck slot, and the pool creates every thread a registration needs before starting any. One JVM-wide `ShutdownHook` closes every open logger at exit, all draining at once, so it waits at most the longest `closeTimeout`. Tests: `WorkerPoolTest` (10 loggers on 2 threads, workers spread over threads, 4 workers on 1 thread in order, a thread sleeping only when all its loggers are idle, a closed logger's thread serving the rest, flush and close from a handler on a shared thread, the single hook and its total wait); `AsyncThreadsTest` gives each test a private pool. | `gradlew build`: 419 tests, 0 failures, 7 POSIX-only skipped; both allocation suites pass on a pool thread; full benchmarks on Windows and Linux within the pre-pool noise (README) |

## Correctness and robustness

### R1. `flush()` with an unpublished slot spins the worker forever and makes `close()` unable to finish (Medium)

**Where:** `Worker.serveFlush` (`Worker.java:234-248`), the loop at lines 237-241. The F7 remedies
are `Worker.awaitPublish` (`Worker.java:302-308`), which backs off to a 1 ms poll, and the
give-up in `Worker.run` (`Worker.java:97-105`). Neither applies inside `serveFlush`.

**What happens:** `serveFlush` snapshots `target = ring.tail()` and loops
`while (head - target < 0) { if (drainBatch(..) == 0) spinThenYield(); }`. A slot that was
claimed but never published (the F7 precondition: `log()` cut off by a `StackOverflowError`
between the tail claim and the publish) lies before `target`, so `drain` returns 0 every time and
the loop never ends. Because the worker never returns to `run()`:

- the caller's `flush()` times out and returns false, as it should;
- the worker uses a full core until the process exits;
- `close()` can't finish: the 2 s give-up lives in `run()`, which is never reached again, so
  `close()` waits its whole timeout and returns false, and the shutdown hook waits the whole
  `closeTimeout` at JVM exit.

Reproduced with the probe described at the end (one stuck slot, one message behind it, then
`flush(2 s)` and `close(6 s)`):

```
flush(2s) -> false after 2006 ms
worker state RUNNABLE, used 1000 ms CPU in 1 s
close(6s) -> false after 6008 ms (give-up is 2 s)
worker alive: true, state RUNNABLE
warnings: [close didn't finish within 6000 ms; the remaining 1 message will be written in the background]
```

Without the `flush()`, the same scenario closes in about 2 s, as
`AsyncThreadsTest.aSlotLeftUnpublishedDoesNotWedgeOrBurnTheWorker` shows. Any application that
calls `flush()` at checkpoints, in tests, or before exit reaches this path after an F7 event.

**Fix:** let `serveFlush` give up like the main loop does. When `drainBatch` returns 0 and the
head slot is unpublished for more than the `STALL_SPINS` passes `awaitPublish` already uses,
leave the request pending and return to `run()`; the main loop then polls the slot at 1 ms and
the close give-up works. The waiter's `awaitFlush` times out, which is the right answer: not
everything was flushed. Also return early when `admission` is closed, so `close()` is never
blocked behind a flush. Add a test that is
`aSlotLeftUnpublishedDoesNotWedgeOrBurnTheWorker` with a `flush(Duration)` between the stuck
slot and the `close()`.

### R2. The 2 s close give-up loses a slow producer's message, and `close()` still returns true (Low, contract)

**Where:** `Worker.run` (`Worker.java:97-105`) with `STUCK_CLOSE_NANOS` (`Worker.java:35`);
`AsyncLogger.close` returns `done` (`AsyncLogger.java:220`); `Warnings.reportIncompleteClose`
(`Warnings.java:166`).

**What happens:** after close, a worker gives up when the logger-wide admitted count has not
changed for 2 s. That condition can't tell a producer that died inside `log()` from one that is
merely stalled there. Reproduced: a message admitted (the first half of `log()`), then `close()`
called while the producer is stalled.

```
close(10s) -> true after 2007 ms
file: [{"n":1}]                       (the late message, {"n":2}, is not on disk)
stats: LoggerStats[accepted=2, written=1, ..., queuedMessages=1, queuedChars=7, openFiles=0]
accepted == written + droppedInvalidId + lostIoError ? false
warnings: [close stopped waiting with messages still queued; a log() call was probably cut off
           by a fatal error such as StackOverflowError, so its message never arrived]
```

The producer's `log()` then returned true, having published into a ring whose worker had already
closed its files and terminated. A 2 s stall of a thread inside `log()` is rare but real: a
thread descheduled on an oversubscribed host, a container throttled by cgroups, `SIGSTOP`, a
breakpoint, or a stop-the-world pause whose end the worker reaches first.

The same give-up applies to the F7 case itself.
`AsyncThreadsTest.aSlotLeftUnpublishedDoesNotWedgeOrBurnTheWorker` asserts both that `close()`
returns true and that the accepted message was never written. The javadoc of `close` says true
means "everything was written and closed within the timeout" (`EfficientLogger.java:82`).

**Impact:** the README's guarantee under "What to expect" (`README.md:70-73`) that after
`close()` returns true the lines on disk are exactly the accepted calls less what `stats()` counts
as lost, and the `LoggerStats` identity (`LoggerStats.java:3-4`, `README.md:102-103`), are both
broken. The only signal left is `queuedMessages() != 0` after close, which nothing documents.

**Fix:**
- Make `close()` return false when a drain was abandoned (set a flag in
  `reportIncompleteClose`, check it with `done`), and count the abandoned messages in `stats()`
  (fold them into `lostIoError`, or add a field), so the identity holds again.
- Tie the give-up to the caller's patience rather than a fixed 2 s: the caller already agreed to
  wait `timeout`, so don't abandon before it has elapsed. For the direct `close()` case the
  daemon worker could keep polling at a low rate (once a second) until the JVM exits, which is
  the pre-fix behaviour without the spin; only the shutdown hook needs to give up.
- Reword the warning: it asserts a fatal error that the code can't know happened (see also R8).
- Document the behaviour in the README and the `close` javadoc (D1).

### R3. `maxFiles` leaks a unit on every failed open and charges existing empty files (Medium)

**Where:** `FileTable.open` (`FileTable.java:284-291` and `301-309`);
`FileCreationBudget.tryCreate` (`FileCreationBudget.java:27-36`); the `maxFiles` javadoc
(`EfficientLoggerBuilder.java:106-116`).

**What happens:** a unit is taken at line 285 when `lastByte` returns -1 and `tryCreate()`
succeeds. If `openSink` then throws (line 293, caught at 301), the unit is never returned, and no
file was created. The entry becomes FAILED and is retried every second; each retry sees
`last < 0` again and takes another unit. During any outage in which opens fail (permission
denied, an unwritable or missing parent, descriptor exhaustion with nothing left to evict, an
antivirus lock on Windows), each affected new ID burns one unit per second. With `maxFiles(10000)`
and 100 new IDs, a two-minute outage exhausts the cap; from then on every new ID is CAPPED for the
life of the process, and CAPPED never retries (`FileTable.java:267-273`). This is the option
meant as the backstop for untrusted IDs, and it turns a transient fault into a permanent one.

Separately, `lastByte` returns -1 for an existing but empty file as well as a missing one
(`FileOutputStreamSink.java:100-102`), so reopening an existing empty file is charged, which
contradicts "Reopening a file that already exists doesn't count".

Reproduced with a sink that fails the first open of `a.json` once, `maxFiles(2)`, sync mode:

```
a (open fails once): false
b: true
a again, retry due, only 1 file exists: false
files: [b.json]
warnings: [can't open ...\a.json; dropping its messages for 1 s,
           the logger has reached its limit of 2 files (maxFiles); dropping messages for ids without a file yet ...]
```

**Fix:** give `FileCreationBudget` a `release()` and call it in the catch block when a unit was
taken in this call; then the count is of files actually created. Have `lastByte` distinguish
"missing" from "empty" (for example -1 and -2), so an empty file isn't charged. Test both.

### R4. The benchmark can't run EfficientLogger since the F2 hardening (Medium, project)

**Where:** `LoggerBenchmark.NULL_SINKS` (`LoggerBenchmark.java:258-273`) opens the OS null device
through `FileOutputStreamSink.FACTORY.open(NULL_DEVICE)` (line 261). Since commit `55c109a`,
`Factory.open` refuses anything that isn't a regular file (`FileOutputStreamSink.java:79` and
`163-168`), and so does `lastByte` (`97-99`).

**What happens:** every open of the null device fails. Verified on Windows:

```
OPEN REFUSED: java.nio.file.FileSystemException: NUL: The parameter is incorrect
lastByte REFUSED: java.nio.file.FileSystemException: NUL: The parameter is incorrect
```

On Linux `readAttributes("/dev/null")` succeeds and `isRegularFile()` is false, with the same
result. `FileTable` marks the file FAILED, every message is counted in `lostIoError`, and the
benchmark's `Target.close()` (`LoggerBenchmark.java:247-253`) throws "messages were lost, so the
results are wrong". So `gradlew jmh`, as documented under "Building and testing"
(`README.md:273-275`), fails for all three EfficientLogger variants with the default output; only
`-p output=disk` still works. The README's figure that the message and ID checks cost "about 7%"
(`README.md:136-139`) was measured before `55c109a` and can't be re-measured as documented.

**Fix:** have `NULL_SINKS` open the null device directly, `new FileOutputStream(NULL_DEVICE,
true)` wrapped in a `Sink`, so the write system call is still made but the regular-file check,
which the benchmark deliberately sidesteps, isn't. Add a plain unit test that
`NULL_SINKS.open(...)` and a write succeed, or run one tiny JMH iteration in `check`; nothing in
the build exercises `src/jmh` beyond compiling it, which is why this went unnoticed through five
commits.

### R5. Equal `String.hashCode()` IDs pin one worker or stripe for every seed; audit F18 isn't fixed (Low)

**Where:** `Hashing.index` (`Hashing.java:14`), `AsyncLogger.java:109`, `SyncLogger.java:81`,
`TestHooks.shardSeed` (`TestHooks.java:17`); `SECURITY_AUDIT.md:89` says F18 is fixed because IDs
"can't be crafted to all land on one worker or one sync lock without knowing the seed".

**What happens:** the seed is XORed into `String.hashCode()` before the mix. Two IDs with equal
hash codes are therefore equal after the XOR for every seed. Equal hash codes are trivial to
generate in bulk: `"Aa"` and `"BB"` collide, and concatenating colliding pairs gives 2^n
colliding strings of any length. Verified:

```
seed 0:           Aa->4  BB->4  AaAa->13 BBBB->13 AaBB->13 BBAa->13
seed 1374497303:  Aa->5  BB->5  AaAa->0  BBBB->0  AaBB->0  BBAa->0
seed -987654321:  Aa->12 BB->12 AaAa->7  BBBB->7  AaBB->7  BBAa->7
```

The seed only randomizes which worker a collision set lands on, not whether it is one. Within a
worker the `HashMap` treeifies colliding `String` keys, so lookups stay logarithmic; the effect is
the one the audit described: extra workers stop helping, and in sync mode those callers share a
lock.

**Fix:** either state the limit honestly (F18 was Info before; the audit's status row and the
README's routing note should say that IDs with equal `String.hashCode()` share a worker) or hash
the ID's characters with a per-logger key. The second costs a pass over the ID per call (about a
nanosecond per character, no allocation), which is the only way to be independent of the cached
hash code. Keep `id.hashCode()` for the `HashMap`.

### R6. `normalizeIds()` copies and normalizes a huge ID before the length check (Low)

**Where:** `AsyncLogger.log` (`AsyncLogger.java:89-92`) and `SyncLogger.log`
(`SyncLogger.java:68-71`).

**What happens:** `FileNamePattern.normalize` (NFC, then lower-case; two allocations and two
passes over the string) runs before the `maxIdLength` comparison that F4 added to keep a huge ID
out before anything copies it. With the option on, a 50-million-character ID is normalized on the
caller's thread, then rejected. The exact length can only be checked after normalization, because
NFC and lower-casing can change the length, but a generous bound can be checked first.

**Fix:** reject `id.length() > maxIdLength * K` before normalizing, with K a small constant that
covers NFC and ROOT lower-casing expansion (8 is safe), then normalize and apply the exact check.

### R7. After close, a worker spins at 100% CPU for up to 2 s on an unpublished slot (Low)

**Where:** the closed branch of `Worker.run` (`Worker.java:106-109`) and `waitWhileClosing`
(`Worker.java:277-285`).

**What happens:** `waitWhileClosing` spins and parks only while `head == tail`. When the head
slot is claimed but unpublished, `head != tail`, so it returns at once and the loop calls
`runOnce()` back to back until the 2 s give-up. Bounded, but the `awaitPublish` back-off that
protects the open-state loop should apply here too. The CPU assertion in
`aSlotLeftUnpublishedDoesNotWedgeOrBurnTheWorker` measures before `close()`, so this isn't seen.

### R8. A sibling worker stalled 2 s in I/O during close produces a false "fatal error" warning (Low)

**Where:** `Worker.run` (`Worker.java:97-105`).

**What happens:** the give-up condition is logger-wide: "the admitted count hasn't changed in
2 s". With several workers, a worker whose own ring is empty gives up and emits
`reportIncompleteClose` whenever no worker releases anything for 2 s, which a sibling blocked in
one slow `write()` (a disk stall, a network file system, an antivirus scan) causes without any
loss. The sibling then finishes normally and `close()` returns true, but the operator has been
told a `log()` call was "probably cut off by a fatal error such as StackOverflowError". Reword the
message to what is known (how many messages were still queued after how long without progress),
and consider letting only the worker whose own ring is blocked, or the last worker, report.

### R9. `flush()` and `close()` still report success after a worker thread dies (Low)

**Where:** `AsyncLogger.task` (`AsyncLogger.java:255-273`), `Worker.awaitFlush`
(`Worker.java:193`), `AsyncLogger.close` (`AsyncLogger.java:220`); `SECURITY_AUDIT.md:92` marks
F13 "Fixed (with F7)".

**What happens:** if a worker thread dies (an `Error` escaping `finish()`), admission is closed and
a warning emitted, as the audit says. But `awaitFlush` treats `terminated` as "done", so `flush()`
returns true for messages that will never be written, and `close()` returns true once the other
workers give up (R2). The audit's third recommendation for item 3, "report true from `flush()`
and `close()` only when the queue is empty", wasn't implemented. The fix is the same as R2's
first bullet.

## Documentation drift

### D1. README, javadoc and `PLAN.md` promise an exact `close()` and a stats identity the code no longer keeps

`README.md:70-73` ("After `close()` returns true, the lines on disk are exactly the `log()` calls
that returned true, less any that `stats()` counts as lost..."), `README.md:102-103` and
`LoggerStats.java:3-4` ("Once a logger is closed, `accepted == written + droppedInvalidId +
lostIoError`"), `EfficientLogger.java:82` ("true if everything was written and closed within the
timeout") and `PLAN.md` §3.5 ("Guarantee: lines on disk == the number of `log()` calls that
returned true") all predate the 2 s give-up. None mentions it. R2 shows the identity failing.
Either restore the guarantee (R2's fix) or document the exception and how to detect it
(`queuedMessages() != 0` after `close()`).

### D2. `WarningHandler` isn't documented as being called concurrently

`WarningHandler.java:5-13` says the handler must return promptly, must not block, and may call
`log`, `flush` or `close`. It doesn't say it may be called from several worker threads at once
(with `workerThreads(n)`), or from several callers at once in sync mode. The default
`stderr()` handler happens to synchronize on `System.err`. Add "must be thread-safe" to the
contract, since a user's handler that appends to an unsynchronized list will corrupt it.

### D3. The directory check flags only other-writable, not group-writable, directories (Info)

`FileOutputStreamSink.Factory.directoryExposedReason` (`FileOutputStreamSink.java:138`) tests
`mode & 0002` without the sticky bit. The audit's F2 recommendation was to warn when a directory
is "writable by group or others", and its status row says "writable by other users", which an
operator may read as including the group. A log directory that is group-writable to a shared
group (Ubuntu's `/var/log` is `root:syslog 775`) isn't flagged. If that is deliberate, say so in
the warning text or the README's "On Linux" section.

## Smaller observations

### Q1. `lastByte` treats "permission denied" as "missing" (Info)

`FileOutputStreamSink.java:104-109`: `new RandomAccessFile(path, "r")` throws
`FileNotFoundException` for an unreadable file as well as a missing one, and both return -1
("removed since the stat"). For a file the process can append to but not read (mode `0200`, or
an append-only ACL), the newline repair is skipped and, with R3, a `maxFiles` unit is charged.
Check `attrs` first: the stat already said the file exists, so a `FileNotFoundException` here
means "can't read", which should be reported, not treated as absent.

### Q2. With `filePermissions`, the chmod after create follows symlinks (Info)

`FileOutputStreamSink.java:70-72`: `Files.createFile` uses `O_EXCL`, so it can't be tricked by a
symlink, but the following `Files.setPosixFilePermissions(path, ...)` follows one. If the new
file is replaced by a symlink in that instant (the F2 race, which needs write access to the
directory), the chmod lands on the symlink's target. The effect is bounded to the permissions the
operator configured, and the append itself is still refused by the check at line 79. The audit's
F2 residual describes the append race but not this one. There is no fchmod in Java; the
alternative is `Files.newByteChannel(path, CREATE_NEW, WRITE, APPEND, attrs)`, which creates and
opens atomically, at the cost of a channel's interrupt behaviour (acceptable for the async
workers, less so in sync mode).

### Q3. `this` escapes the `AsyncLogger` constructor to the worker threads (Info)

`AsyncLogger.java:79-82` starts the worker threads, whose task closes over `this`, before the
`threads` field is assigned and before the shutdown hook exists. It works today because no worker
reads `threads` until the application holds the logger reference, and the application's own
publication of that reference supplies the happens-before. It is fragile: anything invoked on a
worker during construction (a future callback, a warning emitted during start-up) would see a
half-built logger. A static factory that constructs, then starts, removes the hazard.

### Q4. Repository hygiene (Info)

- `.idea/kotlinc.xml`, `.idea/gradle.xml`, `.idea/misc.xml` and `.idea/vcs.xml` are tracked;
  the Kotlin file is a template leftover in a Java project.
- `.gitignore` still carries the Kotlin template's `.kotlin` entry.
- `settings.gradle.kts` applies the foojay toolchain resolver although `gradle.properties`
  turns toolchain download off; harmless, but one of them is redundant.
- `gradle/verification-metadata.xml` has `verify-signatures=false`: checksums only, which is a
  reasonable choice, but worth a comment saying so, since a reader may assume signatures are
  checked.

## Tests

The test suite is thorough, and the determinism tools (manual worker stepping, the fake clock,
the fault-injecting sink factory, the before-batch gate) are well chosen. Gaps that the findings
above expose:

- No test calls `flush()` while a slot is unpublished (R1). The F7 test would have caught it with
  one extra line.
- The F7 tests assert the current give-up behaviour as desired: `close()` true with an accepted
  message never written (R2). A test that publishes after the give-up and asserts the message is
  either written or counted would pin the intended contract, whichever way R2 is resolved.
- Nothing tests `maxFiles` after a failed open, or on an existing empty file (R3).
- Nothing in `check` runs any part of `src/jmh`, so the benchmark's null sink broke silently (R4).
- `theShardSeedDecidesWhichWorkerAnIdGoesTo` shows the seed moves one ID; no test asks whether
  colliding IDs are separated, which is what F18 is about (R5).
- `normalizeIds()` isn't covered by `AllocationTest` (correctly: it allocates by design) or by any
  test with a huge ID (R6).
- The CPU-time assertions in `AsyncThreadsTest` use per-thread CPU time, so they are robust to
  load on the host; the class's 2-minute timeout is the only timing exposure.

## Checked and found sound

Listed briefly, since the audit covers much of the same ground; these were re-derived from the
code rather than taken from the documents.

- **The ring can't wrap onto an unconsumed slot.** Capacity is at least the message limit,
  releases happen in position order (one `getAndAdd` per batch after the batch's slots are
  nulled), and a claim is admitted only after the release that frees its slot. An unpublished
  slot only shrinks the usable ring; it can't be reused.
- **No lost wake-up.** The worker writes PARKED, then re-reads TAIL, the admission word and the
  flush ticket, all with volatile semantics; the producer's tail claim and admission CAS precede
  its PARKED read. Either the producer sees the sleep state or the worker sees the message.
  `close()` and `flush()` unpark unconditionally. The TIMED threshold uses the logger-wide word,
  which is conservative with several workers.
- **Message routing.** The consumer reads the message with acquire semantics before the id, nulls
  both, and only then releases admission; the next producer for that slot is admitted after the
  release.
- **`FileTable.open` balances the open-file budget on every path** (CAPPED, exception, success),
  `evict` can't evict the entry being opened (it isn't linked yet, or it isn't OPEN), and the
  "buffer full to the last byte" case is handled.
- **Oversized records** are chunked without splitting a code point or an escape, and counted once,
  when the last piece is flushed. The encoder's progress guarantee (at least one char consumed
  with 6 bytes free) matches the smallest buffer (64 bytes less a 2-byte separator).
- **`Utf8.encode`** matches the test's reference definition, including DEL and tab kept, C1 and
  U+2028/9 escaped, and `?` for unpaired surrogates. The JSON-preserving argument in its javadoc
  is correct for valid JSON input.
- **`FileNamePattern`** validates the normalized ID, so `normalizeIds()` can't bypass a rule, and
  the pattern itself is validated with a sample ID.
- **`OverflowTracker`** does at most one transition per `check`, the rate limit folds counts into
  the next report, and `finalReport` is idempotent across workers.
- **Sync mode's close/log race** is closed by the volatile `closed` flag plus the per-stripe flag
  under the stripe monitor; warnings are emitted after unlocking.
- **Interrupts**: cleared at the top of the worker loop; preserved for callers in both modes;
  `FileOutputStream` is unaffected by them.
- **Build**: wrapper distribution checksum pinned; 45 components in `verification-metadata.xml`
  with SHA-256; toolchain auto-download off; `-Xlint:all` is clean on all source sets.

## Recommendations, in order of value for effort

1. **R1.** Make `serveFlush` give up on an unpublished slot and return to the main loop; add the
   flush-with-stuck-slot test. A dozen lines in `Worker`.
2. **R2, R9, D1.** Decide what `close()` means after a give-up: return false, count the abandoned
   messages in `stats()`, delay the give-up until the caller's timeout, and reword the warning.
   Then fix the README, the javadoc and `PLAN.md` §3.5 to match.
3. **R3.** Release the `maxFiles` unit when the open fails, and stop charging existing empty
   files. Two small changes plus two tests.
4. **R4.** Open the null device directly in the benchmark and add a smoke test, then re-run the
   benchmarks so the README's figures and the "7%" claim are current.
5. **R5.** Correct the F18 status and the README, or implement a keyed character hash for
   routing.
6. **R6, R7, R8, D2, D3, Q1 to Q4** as convenient; none is urgent.

## How the findings were verified

Two small programs in the review session's scratch folder (not committed) were compiled against
`build/classes/java/main` in package `org.example.efficientlogger`, so they could use the
package-private seams the tests use (`TestHooks`, `MpscRing.claimWithoutPublishingForTest`,
`Admission.tryAcquire`, `Hashing.index`, `FileOutputStreamSink.FACTORY`):

- **Null device and hash collisions (R4, R5):** open the OS null device through the production
  sink factory, and route six colliding IDs through `Hashing.index` with four seeds.
- **Flush with a stuck slot (R1):** build an async logger on a null sink, claim a slot without
  publishing, log one message behind it, `flush(2 s)`, measure the worker's CPU time over a
  second, then `close(6 s)`.
- **Slow producer at close (R2):** `log` one message, admit a second without publishing,
  `close(10 s)`, then publish the second as `log()` would and read the file and `stats()`.
- **`maxFiles` leak (R3):** a sink factory that fails the first open of one file, `maxFiles(2)`,
  sync mode, a fake clock advanced past the retry.

Their outputs are quoted in the findings. Everything else was verified by reading, and the whole
build, including both allocation suites rerun from scratch, passed before and after.
