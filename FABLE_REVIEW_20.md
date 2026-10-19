# EfficientLogger review 20: the file cap, the disk-space mark and the rate cap

| | |
|---|---|
| Date | 2026-10-19 |
| Code reviewed | commits `5a64c1e` (the three features) and `4c3c2a8` (the benchmark's cap options), on `master`, clean working tree: 34 files, +1,366 / −179 lines |
| Scope | the new classes `DropEpisode`, `DiskWatch` and `RateLimit`; the size cap in `FileTable` and `FileState`; the wiring in `EfficientLoggerBuilder`, `Config`, `AsyncLogger`, `SyncLogger` and `Worker`; `LoggerStats`, `SinkFactory.size`, `OverflowTracker` on the shared reporter; the new and changed tests; the README and PLAN text |
| Method | a line-by-line read of every changed file against the contracts the README now states (what is counted where, what the caller pays, what the worker allocates, the stats identity), with the cap arithmetic re-derived for each of the four write paths; each suspected defect reproduced, or its cost measured, with a small program against the compiled classes |
| Build | `gradlew build` passes at `5a64c1e` on Windows 11, JDK 17.0.12: 431 tests (16 new), 7 POSIX-only skipped, 0 failures; `allocationTest` and `allocationTestNoEA` 8 of 8 each (3 new) |

## Summary

The three features do what the README says, and they keep the library's promises where it
matters: `log()` still takes no lock and makes no blocking call, the rate cap is one CAS and the
disk mark one volatile read on the accepted path, the allocation suites confirm the caller
allocates nothing with the caps on, when shedding and when over the rate, and the stats identity
holds in both modes with the new counters. The file cap's arithmetic is right on all four write
paths (a record that fits the buffer, one that needs the buffer written out first, one larger
than the buffer, and write-through), a file is never left holding part of a record, the open-file
budget is balanced on the new paths, and a full file is skipped by eviction and close. The
reopen rule, "only once smaller than when it filled", handles `copytruncate` and, as a bonus,
rename rotation, since a full file holds no handle.

The defects are small and at the edges:

- **Rejections after close are miscounted when the caps are on.** The disk and rate checks in
  `AsyncLogger.log` come before the admission word is consulted, so after `close()` a shed or
  rate-capped message counts as `droppedDiskSpace` or `droppedRateLimit`, not `rejectedClosed`,
  and the rate bucket is charged for a message that will never be written. This is the exact
  shape of review 19's Q1, reintroduced on two new paths (C1, Low, reproduced).
- **A dropping caller wakes the worker on every call while a reading is overdue.** The cost to
  the caller is nothing measurable (30 ns a call either way), but each `unpark` is a spurious
  wake-up for a worker that is parked inside a handler's wait, for as long as that handler
  blocks. One wake per interval is enough (C2, Low).
- **The end-of-episode warning can quote a stale or zero reading** when the probe has failed
  (C3, Low).

The rest is Info: a conservative charge on the rate bucket for messages the queue then rejects,
long episodes that report their count only at close, one system call a second per logger rather
than per volume, and two documentation gaps. Nothing needs a design change.

### Findings

Severities as in the earlier reviews: **Medium** is a correctness or availability defect under a
realistic precondition; **Low** needs a narrow precondition or is a contract drift; **Info** is
worth knowing. "Verified" means reproduced against the compiled classes.

| # | Finding | Severity | Verified |
|---|---|---|---|
| C1 | After close, shed and rate-capped messages count as `droppedDiskSpace` / `droppedRateLimit`, not `rejectedClosed`, and the rate bucket is charged | Low | Yes |
| C2 | While a free-space reading is overdue, every shed `log()` call unparks worker 0, a spurious wake-up per drop for a worker blocked in a handler | Low | Cost measured; effect by reading |
| C3 | After a probe failure the episode ends with "free disk space is back to" a stale reading, or "0 bytes" if no reading ever succeeded | Low | By reading |
| C4 | A message the rate cap accepts and the queue then rejects stays charged to the bucket | Info | By reading |
| C5 | An episode that never pauses reports its count only at close; a long run of drops is one warning at the start and silence for hours | Info | By reading (seen in the capped benchmark) |
| C6 | A full file that keeps receiving messages costs the worker one `stat` a second, which allocates; not in the README's list of worker allocations | Info / Doc | By reading |
| C7 | One free-space probe per logger, not per volume: 100 loggers on one volume are 100 system calls a second; a probe on a hung network volume stalls the building thread or a shared pool thread | Info | By reading |
| C8 | A file larger than the cap (from a run with a bigger cap) that is truncated to a size still at or above the cap re-enters FULL with a fresh warning each time, until the 100-report limit | Info | By reading |
| C9 | The tracked size drifts if another process appends to a file or a write partly succeeds before failing; it resyncs at the next open | Info | By reading |
| D1 | README's worker-allocation exception should also name the full-file re-check (C6); the `copytruncate` note should say rename rotation works for full files too | Doc | By reading |

### Status since the review

Everything below was addressed in the working tree after the review, with a test for each
behaviour that could be pinned, and the probe was re-run against the new classes. The full build
passes afterwards: 443 tests (12 new), 7 POSIX-only skipped, 0 failures; both allocation suites
8 of 8, so the caller still allocates nothing on every path the caps touch.

| # | Status | What changed | Probe now |
|---|---|---|---|
| C1 | Fixed | The disk-shedding branch of `AsyncLogger.log` checks `closing()` first and counts a rejection; the rate check moved after admission (see C4), so a closed logger rejects before the rate is consulted. Test: `AsyncLoggerTest.rejectionsAfterCloseWithTheCapsOnCountAsRejectedClosed`. | the call after close gives `droppedDiskSpace=0 rejectedClosed=1 droppedCount=0` and `droppedRateLimit=0 rejectedClosed=1` (it was 1 and 0 in each) |
| C2 | Fixed | `DiskWatch.shouldWake(now)` replaces `due`: a second CAS-guarded stamp lets one caller per interval wake a worker once a reading is overdue; the others just count their drop. Tests: `DiskWatchTest.oneDroppingCallerPerIntervalWakesAWorkerOnceAReadingIsOverdue`, and on real threads `AsyncThreadsTest.sheddingStartsAndEndsOnAnOtherwiseIdleLogger`, in which the only worker is in deep sleep when the volume fills and again when it recovers, and shedding starts and ends with no other traffic. | by test |
| C3 | Fixed | The end message reads "free disk space can no longer be read, so minFreeDiskSpace isn't enforced; dropped N" when the probe has failed, instead of "back to" a stale reading. Test: `DiskWatchTest.anEpisodeCutShortByAProbeFailureSaysSo`. | by test |
| C4 | Fixed | In async mode the rate is charged after admission; a message the queue refuses, or a closed logger rejects, costs nothing, and a message the rate then refuses gives its admission back (`admission.release`). Sync mode still charges before the write, which the `maxCharsPerSecond` javadoc now says. Test: `AsyncLoggerTest.aMessageTheQueueRefusesIsNotChargedToTheRate`. | by test |
| C5 | Fixed | `DropEpisode` emits a progress report every `REPORT_INTERVAL_NANOS` while an episode goes on: "cause; still dropping: N since the last report (T in total)"; the end report still gives the episode's total. Applies to the queue, the disk mark and the rate cap alike; the README's three descriptions say so. Tests: `DropEpisodeTest` (new, two tests); the six pre-existing overflow tests pass unchanged, since none spans an interval while dropping. | by test |
| C6, D1 | Fixed | README "Allocation and threads" names both system calls that allocate: the free-space reading (now per volume) and the full-file size re-check, once a second each. The `copytruncate` bullet says a full file holds no handle, so renaming it away works too, and the size-cap bullet says the exact size assumes one writer (C9). | by reading |
| C7 | Fixed | Readings are shared per volume: `DiskWatch.forDirectory` keeps one `VolumeReading` per `FileStore` in a static map and reuses a reading younger than the interval, so a hundred loggers on one disk take one reading a second between them. A thread that finds the reading being refreshed uses the last one rather than wait, so a stalled volume stalls one thread, not every logger on it; only the very first reading is waited for. The README says the reading can stall like a write. Test: `DiskWatchTest.readingsOfOneVolumeAreSharedBetweenLoggers`. | by test |
| C8 | Fixed | `FileTable.reopenIfDue` keeps a FULL file full, without a new warning, when it has shrunk but not below the cap, remembering the smaller size so a further shrink is seen. Test: `FileTableTest.aFileLeftLargerThanTheCapStaysFullQuietlyUntilItIsBelowIt`. | by test |
| C9 | Documented | The README's size-cap bullet states the one-writer assumption. | by reading |
| (tests) | Added | The gaps listed below are covered: `FileTableTest.aFullFileWhoseSizeCantBeReadIsTreatedAsAnIoError`, `fullFilesAreEvictedAndClosedLikeAnyEntry`, `theSeparatorCountsTowardsTheCap`; `RateLimitTest.staysExactUnderContention` (four producers for half a second against one meter: accepted chars within rate × (elapsed + 1 s) and at least half the budget). Twelve new tests in all. | |

## Correctness and robustness

### C1. Rejections after close are miscounted when the caps are on (Low)

**Where:** `AsyncLogger.log` (`AsyncLogger.java:143-156`): the disk-shedding branch and the rate
branch run before `admission.tryAcquire`, which is where a closed logger is detected. Review 19's
Q1 fixed the same ordering for the too-long-ID and oversize paths with a `closing()` check, but
the two new paths were added without one. `SyncLogger.log` is correct: it tests `closed` before
either check (`SyncLogger.java:83-86`).

**What happens:** reproduced (Probe 4):

```
shedding logger, log() after close:    droppedDiskSpace=1 rejectedClosed=0 droppedCount=1
rate-capped logger, log() after close: droppedRateLimit=1 rejectedClosed=0
```

After `close()`, a shed message is counted as dropped for disk space and shows in
`droppedCount()`, and a rate-capped one as dropped for the rate; a message the rate cap accepts is
charged to the bucket and then rejected as closed. The counters lie about why the message was
refused, `droppedCount()` keeps growing after close, and the end-of-episode or final report can
name drops that were really post-close calls. The precondition is a logger that is still being
called after close with one of the caps biting, which is common enough in shutdown sequences
that review 19 thought the original worth fixing.

**Fix:** on the two drop paths only, so the accepted path stays as it is:

```java
if (disk != null && disk.shedding()) {
    if (closing()) {
        return rejectClosed();
    }
    disk.recordDrop();
    ...
}
if (rate != null) {
    long now = clock.getAsLong();
    if (!rate.tryAcquire(len, now)) {
        if (closing()) {
            return rejectClosed();
        }
        rate.recordDrop(now);
        return false;
    }
}
```

The charge for a message accepted by the rate cap and then rejected as closed doesn't matter
once the logger is closed. Extend `AsyncLoggerTest.rejectionsAfterCloseCountAsRejectedClosed`
with a shedding logger and a rate-capped one.

### C2. A dropping caller wakes the worker on every call while a reading is overdue (Low)

**Where:** `AsyncLogger.log` (`AsyncLogger.java:145-148`): `if (disk.due(now)) rings[0].wakeConsumer()`.
`DiskWatch.due` (`DiskWatch.java:99-101`) stays true until some thread takes the reading, which
only a worker does in async mode (`Worker.checkLimits`).

**What happens:** the mechanism exists so that shedding can end on an otherwise idle logger
whose workers are in deep sleep: a dropping caller unparks worker 0, which takes the reading on
its next step. That is right, and the caller's cost is negligible: measured at 30 ns a call with
the worker blocked and the reading overdue, against 30 ns with the worker free (Probe 4, two
million calls a round, three rounds each). But until the reading is taken, *every* shed call
unparks the thread. If worker 0 is awake but blocked, in another logger's handler or in a slow
write, each `unpark` gives it a permit, and if that handler is parked (on a lock, a latch, a
queue) its wait returns spuriously and re-parks, once per dropped message, at whatever rate the
application drops. The library can't be made to spin by this, but it makes a stalled handler's
wait loop busy for as long as the stall lasts.

**Fix:** let only one caller per interval do the waking. Give `DiskWatch` a second stamp:

```java
private final AtomicLong nextWakeNanos = new AtomicLong();

/** True for one caller per interval once a reading is overdue: that caller wakes a worker to take it. */
boolean shouldWake(long now) {
    if (now - nextCheckNanos.get() < 0) {
        return false;
    }
    long wake = nextWakeNanos.get();
    return now - wake >= 0 && nextWakeNanos.compareAndSet(wake, now + CHECK_INTERVAL_NANOS);
}
```

and use it in place of `due` on the drop path. One `unpark` a second per logger is then the most
the drop path ever does. `DiskWatchTest.dueSaysWhenTheNextReadingMayBeTaken` becomes a test of
the once-per-interval behaviour.

### C3. After a probe failure the episode ends with a stale or zero reading (Low)

**Where:** `DiskWatch.sample` (`DiskWatch.java:115-126`) sets `shedding = false` when the probe
throws, and `check` then runs `episode.check(now, false)`, whose end message is built from
`lastFree` (`DiskWatch.java:79`), the last *successful* reading.

**What happens:** a logger that is shedding when the volume stops answering (a network share
gone, a device unmounted) ends its episode with "free disk space is back to 700.0 KiB; dropped N",
quoting the reading that started the shedding, which is the opposite of what happened. If no
reading ever succeeded, `lastFree` is 0 and the message says "back to 0 bytes". The separate
"can't read the free space" warning is emitted too, so the operator has both, but the pair
contradicts itself.

**Fix:** keep the reason for the end: when the probe has failed, have the recovered supplier say
"free disk space can no longer be read, so minFreeDiskSpace isn't enforced". A volatile `boolean
probeFailed` already exists; the supplier can read it. Add the case to
`DiskWatchTest.aProbeThatFailsWarnsOnceAndShedsNothing` with drops in flight.

## Smaller observations

### C4. The rate bucket is charged for a message the queue then rejects (Info)

`AsyncLogger.log` charges the rate cap (`rate.tryAcquire`) before `admission.tryAcquire`, so a
message dropped for a full queue has still consumed its share of the rate. The cap is meant to
bound what reaches disk, and this makes it stricter under overflow, never looser, so it is safe.
If exactness matters, charge after admission and, on a rate refusal, give the admission back
with `admission.release(Admission.delta(len))`; or accept the conservative behaviour and say so
in the `maxCharsPerSecond` javadoc.

### C5. An episode that never pauses reports only at close (Info)

`DropEpisode` emits a start and an end. The rate cap's episode ends after a second without drops
(`RateLimit.QUIET_NANOS`), and the disk mark's when free space recovers, so a rate cap that is
exceeded continuously, or a volume that stays full, is one warning at the start and then nothing
until `close()`, when the final report gives the count. The capped benchmark shows it: 100 "rate
limit reached" warnings in the first second and 100 "dropped 152 before close" a minute later,
nothing between. The overflow tracker has always behaved this way, so it is consistent, but for
a cap meant to run for hours a periodic "still dropping, N so far" every `REPORT_INTERVAL_NANOS`
would be more useful to an operator than silence. A small addition to `DropEpisode.check`'s
active branch.

### C6. A full file that keeps receiving messages costs the worker a `stat` a second (Info / Doc)

`FileTable.reopenIfDue` (`FileTable.java:331-345`) calls `sinks.size(f.path)` once a second for a
FULL file while records for it arrive, and the default `SinkFactory.size` allocates a
`BasicFileAttributes`. The README's "Allocation and threads" section lists the free-space reading
as the one exception to the worker allocating nothing; this is a second, of the same size and
rate, and only while a file is full. It is in the spirit of the existing "allocation happens ...
when something goes wrong" in `FileTable`'s javadoc, but the README should say so (D1).

### C7. One probe per logger, not per volume; the probe can stall (Info)

Each logger has its own `DiskWatch`, so 100 loggers on one volume take 100 free-space readings a
second between them, on the pool's threads. A sampler shared per `FileStore` (a static map, one
reading a second per store) would make it one, and would matter only to applications with many
loggers and a slow `statvfs`, which includes network file systems. Also, the first reading is
taken on the building thread in the `DiskWatch` constructor, and later ones on a pool thread that
other loggers share; on a hung network volume `getUsableSpace` can block for the mount's timeout,
stalling `build()` or every logger on that thread. The README already warns that a logger whose
disk stalls holds up its thread-mates; the probe is one more way for that to happen, and worth a
clause there.

### C8. A file above the cap re-enters FULL with a fresh warning after each partial truncation (Info)

`FileTable.open` marks a file FULL when its size is at or above the cap (`FileTable.java:362-368`),
with `fullAtBytes` set to that size and a warning. A file left larger than the cap by an earlier
run with a bigger cap, then truncated to a size still at or above the current cap, satisfies
`size < fullAtBytes` in `reopenIfDue`, is reopened, and is at once marked FULL again with another
warning. Each such cycle costs a warning, up to the 100-report limit for full files. Harmless,
and only for a cap lowered between runs with an external truncation that stops short of it.

### C9. The tracked size can drift (Info)

`FileState.bytesOnDisk` is the size at open plus what this logger wrote. Another process appending
to the same file, or a `write` that lands part of its bytes before throwing, makes the real file
larger than the tracked size, so the cap can be overshot by that difference. A failed write marks
the file FAILED, and the next open re-reads the size, so the drift lasts until the next reopen.
Sharing a log file with another writer is already outside what the README promises (the line
ordering guarantee needs one writer), so this needs at most a sentence.

## Documentation

### D1. Two sentences to add

- "Allocation and threads": the full-file size re-check allocates like the free-space reading
  does, once a second per full file that is still receiving messages (C6).
- "On Linux", the `copytruncate` bullet: a file that has reached its cap holds no handle, so a
  rename rotation of that file also works; the logger creates a fresh file at the next message
  once it finds the path gone (`size()` returns 0, below `fullAtBytes`).

## Tests

The new tests are deterministic (fake clock, fake probe, fake sinks) and pin the exact warning
texts and counters, and the three allocation tests cover the new caller paths. Gaps the findings
expose or that the review noticed:

- Nothing logs after `close()` with a cap biting (C1). The existing
  `rejectionsAfterCloseCountAsRejectedClosed` would have caught it with two more loggers.
- Nothing exercises the drop-path wake-up: a real-thread test with a shedding logger whose worker
  is in deep sleep, asserting the reading is taken and shedding ends (the mechanism C2 is about),
  and that the handler is not woken per drop once fixed.
- No test drops while the probe fails (C3), so the end message on that path is unasserted.
- No test of `reopenIfDue`'s path where `size()` throws for a FULL file, nor of a FULL entry
  being evicted by the LRU or passing through `closeAll`.
- No test of the cap with the `"\r\n"` separator, where the separator is two bytes of the budget.
- `RateLimit` is tested single-threaded; a contention test (several producers against one meter,
  total accepted chars within `rate × (duration + 1 s)`) would pin the "exact under contention"
  claim.
- `ManyLoggersBenchmark` exercises all three caps end to end on real threads; it is the only
  real-thread coverage of the disk mark in async mode, and it is not part of `check`.

## Checked and found sound

- **The cap arithmetic**, on each path. A record that fits in the remaining buffer is checked as
  `bytesOnDisk + endPos + separator`, where `endPos` already includes the earlier buffered records,
  and on refusal `f.pos = start` discards the encoded bytes. A record that doesn't fit has the
  earlier records written first (each was checked when buffered, so they fit), then is measured
  whole or by a dry encode; the dry encode reuses the empty buffer and is followed by a fresh
  encode, so nothing stale is written. Write-through measures the same way. The comparison is
  `>`, so a file can reach the cap to the byte, which `aRecordLargerThanTheBufferIsMeasured...`
  asserts.
- **Nothing partial reaches disk.** The oversize record is measured before its first piece is
  written, in both modes.
- **The open-file budget** is released on the new mark-full-at-open path, and the `maxFiles`
  reservation can't be held there (the file exists, so none was taken). Checked by
  `aFileAlreadyAtTheCapIsNotEvenOpened`.
- **FULL entries** are skipped by `evictLeastRecentlyUsedOpen` and `closeAll`, both of which test
  for OPEN, and are evicted by `create` like any entry; `dropFor` counts them under `FILE_FULL`,
  or under `LOST_IO_ERROR` if the size check turned them FAILED.
- **The reopen rule** reads the size only once a second per full file and reopens only on shrink;
  rename rotation leaves no file, `size()` returns 0, and the logger creates a new one.
- **`SinkFactory.size`'s default** mirrors `lastByte`: NOFOLLOW stat, refuses a non-regular file,
  0 when missing. `FakeSinks` overrides it; the real factory and the test factories use it.
- **GCRA.** `base = max(tat, now)` keeps an idle bucket from banking more than one burst;
  acceptance is `base - now <= BURST`; the charge is `min(len × nanosPerChar, 1 day)`, so the TAT
  never runs more than a day plus a burst ahead of `now`, and no subtraction can overflow in the
  292-year range of `nanoTime`. A zero-length message costs nothing, and a rate of
  `Long.MAX_VALUE` makes every charge round to zero, which are both the intended limits.
- **`DropEpisode`'s happens-before for the disk cause**: `shedCause` is written before `shedding`
  (volatile), the producer reads `shedding` before its `getAndAdd` on the drop counter, and the
  worker reads the counter before `cause.get()`, so the start message never sees a null cause.
- **The `OverflowTracker` refactor** onto `DropEpisode` keeps its three message forms and its
  state machine; the six pre-existing episode tests pass unchanged.
- **Sync mode** tests `closed` before the caps, runs both checks and the probe outside the stripe
  monitor, and reports at `close()`; its `droppedCount()` is now the disk and rate drops, which
  the README states.
- **Builder validation**: the cap's range (1 to 2^63/1024 KiB, so `× 1024` can't overflow), the
  mark's `resumeAbove ≥ shedBelow ≥ 1`, the rate's `≥ 1`, and both options accepted in sync mode,
  all under test.

## Recommendations, in order of value for effort

1. **C1.** Two `closing()` checks on the drop paths, two more loggers in the existing test.
2. **C2.** A once-per-interval wake stamp in `DiskWatch`; swap `due` for it on the drop path.
3. **C3.** A probe-failed wording for the end message, and a test with drops in flight.
4. **D1.** Two sentences in the README.
5. **C5.** A periodic "still dropping" report in `DropEpisode`, if long-running caps are expected.
6. **C4, C6 to C9** as convenient; none is urgent.

## How the findings were verified

One program in the review session's scratch folder (not committed), compiled against
`build/classes/java/main` in package `org.example.efficientlogger` so it could use `TestHooks`:

- **Post-close counting (C1):** a logger with `minFreeDiskSpace` and a probe returning 0 free is
  closed, then called once, and its stats read; the same with `maxCharsPerSecond(1)` after a
  100,000-char message has charged the bucket a day.
- **Drop-path cost (C2):** a logger with the same probe, its only worker held before its first
  batch so no reading is ever taken, is called two million times a round for three rounds while
  shedding, timed; then the same with the worker free. Both came out at about 30 ns a call.

The outputs are quoted in the findings. Everything else was verified by reading.
