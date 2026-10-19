# EfficientLogger review 21: the process-wide limits

| | |
|---|---|
| Date | 2026-10-19 |
| Code reviewed | commit `b589d9a` on `master`, clean working tree: 25 files, +885 / −434 lines |
| Scope | `ProcessLimits`, `DiskMark`, `RateMeter` (new); `DiskWatch`, `RateLimit`, `FileCreationBudget` (rewritten as per-logger views and a shared budget); the CAPPED retry and FULL reopen rules in `FileTable`; `EfficientLoggerBuilder.build()` and `TestHooks`; the wiring in `AsyncLogger`, `SyncLogger` and `Worker`; the new and changed tests; the README, PLAN and benchmark text |
| Method | a line-by-line read of every changed file against the stated semantics (the latest build wins for every logger; an omitted setting is left alone; each logger counts its own drops) and against the library's standing promises (nothing allocates on the hot path, the worker allocates only when something goes wrong, warnings mean what they say); each suspected defect reproduced with a small program against the compiled classes |
| Build | `gradlew build` passes at `b589d9a` on Windows 11, JDK 17.0.12: 455 tests (12 new), 7 POSIX-only skipped, 0 failures; `allocationTest` and `allocationTestNoEA` 8 of 8 each |

## Summary

The sharing does what the README now says. One `ProcessLimits` per JVM holds the four settings;
every `build()` applies what its builder set, so the latest build wins for every logger including
those built earlier, and an omitted optional setting is left alone. The split between shared state
(`DiskMark` with one `Volume` per file store, `RateMeter`, `FileCreationBudget`) and per-logger
views (`DiskWatch`, `RateLimit`) keeps counting and reporting with the logger whose handler should
hear about it, the hot path still costs one volatile read per guard, the allocation suites pass
with the caps on, and the test isolation (a fresh instance per `TestHooks`, the shared one only
through the public `build()`) is the right shape and caught the five tests that would otherwise
have leaked caps into each other.

One defect matters:

- **A capped ID's once-a-second retry is a real open attempt**, so at the open-file cap it evicts a
  healthy file to make room and then finds the process is still at `maxFiles`. Reproduced: the one
  live file was closed and reopened eleven times in ten seconds, and the "more than maxOpenFiles
  files are in use at once" warning fired although only one file was ever in use. Each retry is also
  a `stat` and an allocation on the worker, once a second per recurring capped ID, which is exactly
  the untrusted-ID flood `maxFiles` exists for. The fix is a two-field check before the open (S1,
  Medium).

The rest is small: a directory whose file store can't be found at build never gets the mark
enforced afterwards (S2), the two thresholds are written as two volatiles (S3), a failed build has
already changed the process's limits (S4), and two documentation points about the mandatory file
cap being reset by every build and about how to turn a shared cap off (S5).

### Findings

Severities as in the earlier reviews: **Medium** is a correctness or availability defect under a
realistic precondition; **Low** needs a narrow precondition or is a contract drift; **Info** is
worth knowing. "Verified" means reproduced against the compiled classes.

| # | Finding | Severity | Verified |
|---|---|---|---|
| S1 | A capped ID's retry evicts a healthy file at the open-file cap, raises a false thrash warning, and costs a `stat` and an allocation per second per capped ID | Medium | Yes |
| S2 | A directory whose file store can't be found at build gets a volume that fails forever, keyed by the directory: the mark is never enforced for it, even once the volume is back | Low | By reading |
| S3 | `DiskMark.set` writes the two thresholds as separate volatiles, so one reading can see a mixed pair | Low | By reading |
| S4 | `ProcessLimits.apply` runs before the logger is constructed, so a build that then fails has still changed the process's limits | Info | By reading |
| S5 | Every build resets the mandatory file cap, so two loggers built with different values silently fight; and nothing says how to turn a shared cap off | Doc | By reading |
| S6 | The accepted path now reads two volatiles even when no cap is set; measured as nothing | Info | Benchmark |
| S7 | The public `build()` allocates a throwaway `ProcessLimits` through `TestHooks`' default, and `TestHooks`' class comment is now inexact | Info | By reading |

### Status since the review

Everything below was addressed in the working tree after the review, with a test for each
behaviour that could be pinned. The full build passes afterwards: 459 tests (4 new), 7 POSIX-only
skipped, 0 failures; both allocation suites 8 of 8.

| # | Status | What changed | Probe now |
|---|---|---|---|
| S1 | Fixed, and a pre-existing cousin with it | `reopenIfDue` retries a CAPPED entry only when `fileBudget.created() < fileBudget.max()`: two volatile reads, no `stat`, no slot. Writing the test showed the *first* capped attempt had the same flaw since `maxFiles` was added: `open()` took an open-file slot and evicted a live file before consulting either budget. `open()` now reads the file and consults `maxFiles` and the size cap first, and takes a slot (evicting if it must) only when it is going to open; the catch releases the slot only if one was taken. Test: `FileTableTest.aCappedIdsRetryDoesNotDisturbTheOpenFiles`, at `maxOpenFiles` 1: the live file is opened once in ten seconds of a capped ID's retries, no thrash warning, and a raised `maxFiles` then lets the ID through. | the probe's scenario is the test; it asserts one open of `a` (it was eleven) and no thrash warning |
| S2 | Fixed | A directory with no reachable file store at build gets a volume whose probe keeps looking: it retries `Files.getFileStore` on each reading and reads from the store once found. Test: `DiskWatchTest.aDirectoryWithoutAFileStoreYetGetsAVolumeThatKeepsLooking`, which builds on a directory that doesn't exist, sees the one warning, creates the directory, and gets a reading on the next check. | by test |
| S3 | Fixed | The thresholds are one immutable `Marks` record behind one volatile reference, read once per reading. | by reading |
| S4 | Fixed | `build(TestHooks)` constructs the logger first, then applies the limits, then takes a first reading under them (`readLimits`) before starting the workers, so a build that fails leaves the process's limits alone and a logger built on a nearly full volume still sheds from its first message. `DiskMark.set` also resets every volume's next reading, so a new mark applies at the next check rather than up to a second later. Tests: `AsyncLoggerTest.rejectionsAfterCloseWithTheCapsOnCountAsRejectedClosed` (which failed when `apply` was moved without the first reading, and pins the first-message promise), `DiskWatchTest.settingTheMarkMakesEveryVolumeReadAtOnce`. | by test |
| S5 | Fixed | README's guards section says to use one constant for the mandatory file cap across the process, and how to turn a shared cap off: `maxFiles(Integer.MAX_VALUE)`, `maxCharsPerSecond(Long.MAX_VALUE)`, `minFreeDiskSpace(1, 1)`. | by reading |
| S6 | Noted | No change; the cost is two volatile reads and the benchmark can't see it. | |
| S7 | Fixed | `TestHooks` has a constructor taking the limits; `build()` passes `ProcessLimits.shared()` to it, so nothing is allocated and discarded. The class comment says what `build()` does. | by reading |
| (tests) | Added | `AsyncThreadsTest.twoLoggersShareOneRateBucketUnderConcurrentProducers`: two loggers sharing a `TestHooks` on real threads, 200,000 calls between them against a 10,000 chars/s cap; every call is accepted or counted by its own logger, the total accepted is within rate × (elapsed + 1 s), and the logger that set no rate is capped too. `ConfigTest` asserts `maxFilesSet`. Four new tests in all. | |

## Correctness and robustness

### S1. A capped ID's retry evicts a healthy file and costs a `stat` a second (Medium)

**Where:** `FileTable.reopenIfDue` (`FileTable.java:329-360`) now lets a `CAPPED` entry through to
`open()` once its retry is due, with the comment "the process's maxFiles may have been raised;
open() checks". `open()` (`FileTable.java:363-383`) begins with `budget.acquire()` and, if that puts
the logger over `maxOpenFiles`, evicts its least recently used open file (with the thrash warning
when that file was used within the last second), and only then calls `lastByte` and
`fileBudget.tryCreate()`, which fails, releases the slot and marks the entry `CAPPED` again.

**What happens:** reproduced (Probe 5): `maxFiles` of 1 and `maxOpenFiles` of 1, ID `a` with the
one file, ID `b` capped, both logging once a second for ten seconds:

```
opens of a: 1 after b was first capped, 11 after 10 more seconds of a and b logging
a written=11, b droppedInvalidId=11, warnings=2: [more than maxOpenFiles=1 files are in use at once, so files ...,
                                                   this process has reached its limit of 1 files (maxFiles, sha...]
```

Every retry of `b` closed `a`'s file, which the next message to `a` reopened, and the thrash
warning told the operator that more than one file was in use. Without the open-file cap being
reached, the retry still costs a `stat` (`lastByte`) and its `BasicFileAttributes` allocation on
the worker, once a second per capped ID whose messages keep arriving. `maxFiles` is the guard for
untrusted IDs, so recurring capped IDs are its normal load: a few thousand of them is a few
thousand system calls a second on a worker that is supposed to allocate nothing. Before this
commit a `CAPPED` entry was never retried, so the cost was one `stat` per new ID.

**Fix:** retry only when the budget has room, which is the only thing that can have changed:

```java
if (f.state == FileState.CAPPED && fileBudget.created() >= fileBudget.max()) {
    f.retryAtNanos = now + RETRY_NANOS; // still at maxFiles: nothing to try
    return false;
}
```

before the `open(f, now)` at the end of `reopenIfDue`. Two volatile reads, no system call, no
budget slot taken, no eviction. The existing `aRaisedMaxFilesLetsCappedIdsThrough` still passes
(the raise makes room); add a test with `maxOpenFiles` of 1 that asserts the live file's open
count and the absence of the thrash warning, which is what the probe checks.

### S2. A directory whose file store can't be found at build never gets the mark (Low)

**Where:** `DiskMark.volume` (`DiskMark.java:67-83`): when `Files.getFileStore(directory)` throws,
the directory gets a `Volume` of its own whose probe always throws, cached in the map by directory.

**What happens:** the failure is reported once, as intended, and nothing is shed, which is also
intended. But the volume is never replaced: a logger built while its network share was unmounted,
or whose directory was briefly unreadable, keeps that throwing probe for the life of the process,
so the mark is never enforced for it even after the volume is back, and the "can't read the free
space" warning never recurs to say so. The earlier per-logger design resolved the store lazily
inside the probe and so recovered.

**Fix:** resolve lazily inside the probe and cache the store once found, keeping the directory as
the key only until then; or simpler, on failure return a volume whose probe retries
`Files.getFileStore(directory)` on each reading and, once it succeeds, reads from the store it
found (sharing with other loggers on that store is lost for this directory, which is the lesser
evil). `DiskWatchTest.aProbeThatFailsWarnsOnceAndShedsNothing` already covers the probe's own
recovery; this needs a test of the lookup's.

### S3. The two thresholds are written as two volatiles (Low)

**Where:** `DiskMark.set` (`DiskMark.java:42-46`) writes `resumeAbove` then `shedBelow`;
`Volume.check` reads them one after the other (`DiskMark.java:135-136`).

**What happens:** a reading that lands between the two writes evaluates a mixed pair, for example
the old floor with a new, lower resume mark, which can momentarily make the resume mark lower than
the floor. The effect is one reading judged against a pair nobody configured, corrected a second
later. Harmless in practice, but the invariant `resumeAbove >= shedBelow` that the builder
enforces isn't preserved for readers. Pack the pair into one immutable object behind a single
volatile reference.

## Smaller observations

### S4. A failed build has already changed the process's limits (Info)

`EfficientLoggerBuilder.build(TestHooks)` calls `hooks.limits.apply(config)` before constructing
the logger. If construction then throws (a thread factory that returns null, a shutdown hook that
can't be registered, an `OutOfMemoryError`), the four limits already carry the failed builder's
values for every other logger. Applying after a successful construction, just before `start()`,
would make a failed build leave no trace; the window during which a new logger runs on the old
limits is nanoseconds.

### S5. The mandatory cap is reset by every build; and how to turn a shared cap off (Doc)

Because `maxFileSizeKb` is mandatory, every build sets it, so two loggers built with different
values each reset the other's cap at its next record: a 100 MiB logger and a 1 MiB logger built
after it leave both at 1 MiB. The README's guards section says every build sets the file cap, but
not that this means a process should use one constant for it. Also, nothing says how to turn a
shared cap off once some build has set it: `maxFiles(Integer.MAX_VALUE)` is unlimited,
`maxCharsPerSecond(Long.MAX_VALUE)` can't bite, and `minFreeDiskSpace(1, 1)` sheds only on a
volume with less than a byte free; a sentence under "The guards at a glance" would save a reader
the search.

### S6. Two volatile reads on every accepted call, cap or no cap (Info)

`AsyncLogger.log` now always reads `volume.shedding` and `meter.charsPerSecond`, where it used to
test two final fields for null. The capped and uncapped benchmark runs are within each other's
noise (2.8 to 3.6 µs at p50 across the runs recorded in the README), and the allocation suites
are unchanged, so this costs nothing the library measures. Noted so nobody looks for it later.

### S7. A throwaway `ProcessLimits` per public build, and an inexact comment (Info)

`build()` creates a `TestHooks`, whose field initializer allocates a `ProcessLimits` (a
`ConcurrentHashMap`, a padded `long[]`, two atomics) that `build()` at once replaces with
`shared()`. Harmless, since building is rare, but a `TestHooks` constructor taking the limits, or
a null default resolved in `build(TestHooks)`, would avoid it. `TestHooks`' class comment still
says `build()` "uses the defaults"; it now overrides one of them.

## Tests

The new tests pin the semantics well: the latest build sets the rate, the file count, the file cap
and the mark for loggers built before; one bucket and one volume; a raised cap reopens a full
file and a raised `maxFiles` lets capped IDs through; a rate change leaves charged output
standing; a negative clock; and that tests get limits of their own. Gaps:

- Nothing checks what a capped ID's retry costs the rest of the table (S1): a test at
  `maxOpenFiles` of 1, asserting the live file's open count and no thrash warning, would have
  caught it.
- Nothing builds a logger whose directory has no reachable file store (S2).
- The shared limits are exercised only in manual mode. One real-thread test, two loggers on a
  private pool sharing a `TestHooks`, with the rate cap biting and both loggers' drop counts adding
  up to the total, would cover the CAS on the shared meter under concurrent producers from
  different loggers (the `RateLimitTest` contention test uses one meter but no loggers).
- `ConfigTest` doesn't assert `maxFilesSet`, the one new config field.

## Checked and found sound

- **The latest-build rule** is implemented where it should be: `apply` writes the four settings,
  each a volatile or an atomic, and readers (`FileTable` per record, `Volume.check` per reading,
  `RateMeter.tryAcquire` per charge, `FileCreationBudget.tryCreate` per creation) read the current
  value every time. Nothing caches a limit in a logger.
- **An omitted setting is left alone:** `apply` tests `maxFilesSet`, `minFreeDiskBytes > 0` and
  `maxCharsPerSecond > 0`; the builder's validation (`>= 1`) guarantees a set value is positive.
- **Unset means off**, without a flag on the hot path: `shedding` stays false while the mark isn't
  set (`Volume.check` clears it and takes no reading), and `nanosPerChar == 0` makes
  `tryAcquire` return true.
- **The `UNSET` sentinels.** `nextCheckNanos` and `nextWakeNanos` start at `Long.MIN_VALUE`, so the
  first check and the first wake happen at once whatever the clock's origin; the TAT's sentinel
  makes the first charge see a full bucket even with a negative `nanoTime`, which
  `RateLimitTest.aNegativeClockIsFine` pins.
- **`RateMeter.set` ordering:** `nanosPerChar` is written before `charsPerSecond`, so a caller that
  sees `enabled()` true always charges with a non-zero rate.
- **The FULL reopen rule** with a changing cap: a raised cap reopens a file below it; a cap raised
  but still below the file's size keeps it full and remembers the smaller of the caps; a lowered
  cap keeps it full; a shrink below the cap reopens it. `aRaisedCapReopensAFullFile` and the
  review-20 tests cover the four cases.
- **Budget balance on the CAPPED path** is kept (`budget.release()` before the return), which is
  why S1 is a churn, not a leak.
- **`FileCreationBudget` counting while unlimited** makes the count right when a cap is set later;
  one atomic increment per file creation is the cost, and `release` stays symmetric.
- **Test isolation**: `TestHooks` defaults to a fresh `ProcessLimits`, `build()` substitutes the
  shared one, and the tests that build through `build()` no longer set caps. `ProcessLimitsTest`
  asserts that `shared()` is one instance and that each `TestHooks` has its own.
- **Per-logger counting under shared state:** the shared `Volume` and `RateMeter` carry no counters;
  every drop is recorded on the logger's own `DropEpisode`, so `stats()` and the handler see only
  that logger's drops. The redone benchmark shows it: 15,232 drops spread unevenly over 100
  loggers, each reporting its own.
- **Allocation:** the caller allocates nothing on the accepted, shedding and rate-refused paths
  with the shared limits (the three allocation tests, now on private limits), and the worker's
  only new allocation is S1's `stat`.

## Recommendations, in order of value for effort

1. **S1.** Two volatile reads before the open in `reopenIfDue`, and a test at `maxOpenFiles` of 1.
2. **S2.** Resolve the file store lazily inside the probe, and a test for a directory with none.
3. **S5.** Two sentences in the README.
4. **S3, S4, S7** as convenient; none is urgent.

## How the findings were verified

One program in the review session's scratch folder (not committed), compiled against
`build/classes/java/main` and `build/classes/java/test` (for `FakeSinks`) in package
`org.example.efficientlogger`:

- **Capped retry (S1):** a `FileTable` on fake sinks with `maxFiles` 1 and an open-file budget of 1;
  ID `a` creates the one file, ID `b` is capped, then both log once a second for ten seconds on a
  fake clock; the sinks' open count and the warnings are printed.

Its output is quoted in the finding. Everything else was verified by reading, and the benchmark
figures quoted under S6 are the README's.
