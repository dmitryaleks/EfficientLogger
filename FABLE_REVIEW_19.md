# EfficientLogger review 19: the threading model

| | |
|---|---|
| Date | 2026-10-19 |
| Code reviewed | commit `a7218b0` (master), clean working tree |
| Question asked | Can the caller thread deadlock? What other threading edge cases are there? |
| Scope | the library (`src/main`, 26 files), with the pool, worker, ring, admission, logger, shutdown hook and sync logger read line by line; the tests (`src/test`, 20 files) for coverage; `README.md`, `PLAN.md` and `FABLE_REVIEW.md` for the contracts the code claims to keep |
| Method | every blocking call and every lock reachable from `log`, `flush`, `close`, `build` and the shutdown hook was listed and its lock order checked for cycles; the pool thread's loop was re-derived pass by pass with two or more workers on one thread, which is the configuration the earlier review's tests don't exercise; each suspected defect was reproduced with a small program compiled against `build/classes/java/main` |
| Build | `gradlew build` passes on Windows 11, JDK 17.0.12: 409 tests, 7 skipped (POSIX-only), 0 failures; `allocationTest` and `allocationTestNoEA` 5 of 5 each. The defects below are not covered by any test, which is why the suite is green. |

## Summary

**No deadlock is possible on the caller thread.** `log()` takes no locks and makes no blocking
call. `flush()` and `close()` wait only on a timed latch or a timed monitor wait, so they return
by their timeout whatever the workers do, and both refuse to wait when the caller is one of the
logger's own pool threads. No two locks in the library are ever held at once in an order that
another thread takes the other way round, so there is no lock cycle, with or without the user's
warning handler and thread factory in the picture. Sync mode blocks the caller on a stripe
monitor for the length of one write, and its `close()` waits for writes in progress without a
timeout; both are documented. The full argument is in "Can the caller thread deadlock?" below.

What the review did find is three defects in the shared `WorkerPool`, which is the newest and
least-tested code. All three need two or more workers on one pool thread, which is the normal
state of the default shared pool once an application has more than four loggers, and all three
were reproduced:

- **A logger registered onto a pool thread that is already asleep is not served until something
  unrelated wakes that thread.** `register` adds the worker to the thread's list but never unparks
  the thread. A thread in deep sleep (all its loggers quiet for about a second) stays asleep; the
  new logger's queue fills and then drops every message, and the "queue full" warning is never
  emitted because only a worker can emit it. The messages are written when another logger on that
  thread happens to log, or when the new logger is flushed or closed (T1, Medium).
- **With a second worker on the thread, an unpublished ring slot pins a core.** The back-off to a
  1 ms poll that fixed R7 asks every worker on the thread whether it has "stalled briefly", and an
  idle worker that never stalled says yes. The thread spins at 100% CPU for as long as the slot
  stays unpublished, which after a cut-off `log()` call is until that logger closes (T2, Medium).
- **A pool thread can die, and the pool keeps assigning loggers to it.** Only `step()` and
  `finish()` are guarded. An `Error` from the write-out before sleeping (an `OutOfMemoryError`
  from `FileOutputStream.write`'s native buffer, or from building a warning string) escapes
  `run()`. The runner stays in the pool's table, every logger on it stops being written, `close()`
  waits its full timeout and reports that nothing remains, and new loggers are routed to the dead
  thread because it has the fewest workers (T3, Medium).

Each is a few lines to fix. The rest are smaller: a `flush()` drains its whole ring in one step
and monopolizes a shared thread (T4); user code runs under the pool's lock (T5); and the
documentation doesn't say that a healthy logger's `close()`, and its drain at JVM exit, now
depend on every other logger that shares its thread (D1).

### Findings

Severities as in `FABLE_REVIEW.md`: **Medium** is a correctness or availability defect under a
realistic precondition; **Low** needs a narrow precondition or is a contract drift; **Info** is
worth knowing. "Verified" means reproduced against the compiled classes.

| # | Finding | Severity | Verified |
|---|---|---|---|
| T1 | A logger registered onto a sleeping pool thread isn't served until something else wakes the thread; its messages are dropped without a warning | Medium | Yes |
| T2 | With two or more workers on a thread, an unpublished slot makes the thread spin at full CPU instead of polling | Medium | Yes |
| T3 | An `Error` outside `step()` kills the pool thread; its loggers are never served again and new loggers are assigned to it | Medium | Yes |
| T4 | `serveFlush` drains the whole ring in one step, so one `flush()` holds a shared thread for every other logger on it | Low | By reading |
| T5 | `ThreadFactory.newThread` and `Thread.start` run under the pool lock, and `close()` can't complete until that lock is free | Low | By reading |
| T6 | A worker is in its runner's list before its thread is attached | Info | By reading |
| D1 | The shared-thread coupling isn't documented for `close()` and the shutdown hook, and the timeout warning can say "0 messages" with records unwritten | Doc | Yes (second part) |
| Q1 | After close, a too-long ID or an oversize message is counted as accepted, not rejected, and its one-time warning is never emitted | Info | By reading |
| Q2 | If registering the shutdown hook throws, `build()` fails but the workers stay registered | Info | By reading |

### Status since the review

Everything below was addressed in the working tree after the review, each with a test where one
could pin it, and the three probes were re-run against the new classes. The full build passes
afterwards: 415 tests (six new), 7 POSIX-only skipped, 0 failures; both allocation suites 5 of 5,
so `log()` and the worker still allocate nothing.

| # | Status | What changed | Probe now |
|---|---|---|---|
| T1 | Fixed | `WorkerPool.register` unparks every runner that received a worker, after the fresh threads are started. The thread reads its list at the top of each pass and parks only after, so a thread that read the list before the add gets a permit that ends its sleep, and the next pass announces on the new ring. Test: `WorkerPoolTest.aLoggerRegisteredOntoASleepingThreadIsServedAtOnce` waits for the thread to be in `WAITING` (deep sleep) before building the second logger, then asserts its five messages are written with nothing else waking the thread. | B's 8 messages written within the 3 s window, with the queue-full warning emitted, instead of 0 written and silence |
| T2 | Fixed | `Worker.stalledBriefly` is `stalledPasses > 0 && stalledPasses <= STALL_SPINS`: a worker that isn't waiting for a slot no longer looks like one that has just started waiting, so `Runner.awaitPublish` reaches the 1 ms poll with an idle sibling on the thread. Test: `AsyncThreadsTest.anUnpublishedSlotOnASharedThreadStillBacksOff` runs the F7 scenario with an idle second logger on a one-thread pool and asserts the thread parks and uses little CPU, and that the idle logger is still served. | 0 ms of CPU in 1000 ms with the idle sibling, `TIMED_WAITING` (it was 1015 ms, `RUNNABLE`) |
| T3 | Fixed | `Runner.run` is now `for (;;) try { pass() } catch (Throwable)`: the pass over the workers and the wait after it are guarded as a whole, `beforeSleep` is guarded per worker with `recover`, and the `onTerminated` callback is guarded too. An `Error` from a write on the way to sleep is reported like any unexpected error; the records stay buffered and the next step writes them out, or fails the file properly if the error persists as an `IOException`. Test: `AsyncThreadsTest.anErrorWhileWritingOutBeforeSleepingDoesNotKillTheThread`, with a sink whose first write throws `OutOfMemoryError`: the thread survives, the record is written on the next step, one warning is emitted, and a second logger on the pool is served by the same thread. | thread alive; `close(2 s)` true at once; `accepted=1, written=1`; the second logger's message written; it was a dead thread, `close` false after the full timeout, and a second logger routed to it |
| T4 | Fixed | `Worker.serveFlush` no longer drains the ring itself. It records the request's ticket and target tail once, and on each step (after the step's own batch) completes the request if the head has reached the target, otherwise leaves it pending. A flush over a backlog therefore costs one batch per step, interleaved with the thread's other workers. Test: `AsyncLoggerTest.aFlushIsServedOneBatchPerStep` (manual mode, batch size 2, six messages: pending after the first and second steps, done after the third, with nothing written out before). | by test |
| T5 | Partly fixed | `Runner.finish` runs the logger's callback (which releases `close()`) before `unregister`, so `close()` no longer waits for the pool lock and so never for a slow `ThreadFactory`. The factory still runs under the pool lock: moving it outside needs a reservation protocol in which a concurrent registration that lands on a reserved slot can be orphaned if the first registration's thread then fails to start, and the only safe answer to that is a wait on user code, which is what the change would be removing. Instead `WorkerPool.create(int, ThreadFactory)`'s javadoc and the README now say the factory is called under the pool's lock while a logger is built, must return promptly, and must not take a lock a warning handler might hold while building a logger. | by reading |
| T6 | Fixed | `register` calls `w.attach(r.thread)` before `r.add(w)`, so a worker is never visible to its thread without its thread set. | by reading |
| D1 | Fixed | README: the shared-thread paragraph now says that while one logger's handler or disk stalls, `close()` on the others returns false at its timeout and the shutdown hook can lose every logger on that thread the messages still queued, and that a logger whose messages must survive exit is the one that most needs its own pool. The close-timeout warning now counts records taken into a file buffer but not yet written out (`FileTable.buffered()`, a fourth opaque counter kept with `written` and `lostIoError`) as well as the queued ones. Test: `AsyncThreadsTest.theCloseTimeoutWarningCountsBufferedRecordsToo` stalls the worker inside the first record's write, logs two more, and asserts "the remaining 3 messages" (it said 2). | by test |
| Q1 | Fixed | `AsyncLogger.log` checks the closed bit on the two early reject paths (too-long ID, oversize message) and counts them in `rejectedClosed` instead of as accepted and dropped, so `accepted` stops growing after close and no warning is handed to a worker that no longer exists. The hot path is unchanged. Test: `AsyncLoggerTest.rejectionsAfterCloseCountAsRejectedClosed`. | by test |
| Q2 | Fixed | `AsyncLogger.start` wraps `ShutdownHook.register` as it does `pool.register`: on failure it begins closing, so the running workers finish and leave the pool, then rethrows. | by reading |

## Can the caller thread deadlock?

The question is taken literally: can a thread that calls the public API wait forever? Every
blocking point reachable from the API is listed, then the locks.

### What `log()` does (`AsyncLogger.java:105-140`)

Two null checks, optionally `FileNamePattern.normalize` (which allocates but takes no lock), a
keyed hash, one CAS loop on the admission word (`Admission.java:58-76`), one `getAndAdd` on the
ring's tail, two array stores, one volatile read of the parked state, and at most one CAS plus
`LockSupport.unpark` (`MpscRing.java:91-103`). `unpark` holds the target thread's JVM-internal
parker mutex for the duration of a condition signal; the parked thread releases that mutex while
it waits, so this is microseconds, never a wait on application code. The drop paths are one
atomic add each. There is no monitor, no I/O and no wait anywhere in `log()`: **it cannot block,
and so cannot deadlock.** The CAS loop is lock-free rather than wait-free, so a producer can be
made to retry under contention, but every retry is caused by another producer's success.

### What `flush()` does (`AsyncLogger.java:151-172`, `Worker.java:296-322`)

Refuses at once if the caller is a pool thread serving one of this logger's unfinished workers
(`onOwnThread`, `AsyncLogger.java:352-363`). Otherwise one atomic increment and one `unpark` per
worker, then `awaitFlush`: a `synchronized (flushLock)` with a timed `wait` until the ticket is
served, the deadline passes, or the worker has terminated. The monitor is held by the worker only
for the two assignments in `serveFlush` and `finish`, and by other flushers only inside their own
timed wait, which releases it. **Bounded by the timeout.**

### What `close()` does (`AsyncLogger.java:213-276`)

`beginClose` is one atomic OR and one `unpark` per ring, then the same own-thread check, then
`finished.await(timeout)` on a `CountDownLatch`. After a timeout it calls the warning handler on
the caller's thread. **Bounded by the timeout**, plus whatever the handler does.

The own-thread guard is correct in every state it was checked in: during a worker's `finish()`
(the latch hasn't been counted down yet, so the guard still fires); during the last worker's
`reportLeftBehind` callback (same); once every worker has finished (the guard stands down, and the
wait returns at once); and for a handler on a shared thread calling `flush` or `close` on *another*
logger served by that same thread, which `WorkerPoolTest.aWarningHandlerOnASharedThreadCannotWaitForAnotherLoggerOnIt`
covers.

### Timeouts that are effectively infinite

`saturatedNanos` (`AsyncLogger.java:379-388`) turns a `Duration` whose nanoseconds overflow into
`Long.MAX_VALUE`. Both waits then compute `deadline = now + MAX`, which overflows, but every
comparison is a difference (`deadline - now`), so in two's complement the remaining time is
`MAX - elapsed` and the waits behave as "forever". This is correct, and it means that with such a
timeout the two residual risks below become real hangs rather than a false return.

### The locks, and why there is no cycle

| Lock | Held by | While holding it, calls |
|---|---|---|
| `WorkerPool.lock` (`WorkerPool.java:39`) | `register` (the building thread), `unregister`, `retire`, `threads()` (pool threads) | `ThreadFactory.newThread`, `Thread.start` (user code and the JVM; see T5). Never another library lock |
| `WorkerPool.class` | `shared()` | nothing |
| `ShutdownHook.LOCK` (`ShutdownHook.java:15`) | `register` (building thread, after the pool lock is released), `unregister` (pool thread, after the pool lock is released), `closeAll` (the hook, briefly) | `Runtime.addShutdownHook` / `removeShutdownHook`, which take the JDK's `ApplicationShutdownHooks` class lock. The JDK releases that lock before it starts the hooks, and `add`/`remove` throw `IllegalStateException` once shutdown has begun, so the hook thread never waits for a pool thread's `unregister` and vice versa |
| `Worker.flushLock` (`Worker.java:74`) | `awaitFlush` (callers), `serveFlush`, `finish` (pool thread) | nothing; the callers' hold is a timed wait |
| `Warnings` monitor (`Warnings.java:115-140`) | any thread reporting an I/O error | nothing |
| `OverflowTracker` monitor (`OverflowTracker.java:66-89`) | pool threads in `check`/`finalReport` | two volatile reads; the handler is called after the block |
| `SyncLogger` stripe monitors (`SyncLogger.java:90-93`, `143-149`) | `log` and `close` in sync mode | the write itself; warnings are emitted after unlocking |
| `System.err` (`WarningHandler.java:31`) | the default handler | `println` and `printStackTrace`, which re-enter the same monitor |

No lock is taken while another library lock is held, so a cycle inside the library is
impossible. The user's handler can run under no library lock at all: workers emit after
`OverflowTracker` and `Warnings` release their monitors, sync stripes emit after unlocking, and
`close()`'s timeout warning runs on the caller with nothing held. The only user code that runs
under a library lock is the `ThreadFactory` (T5).

### Residual ways to wait a long time

None is a deadlock inside the library, and all but the last are bounded by the caller's timeout:

1. **The pool thread is unavailable.** It is blocked in another logger's warning handler or in
   another logger's slow write (the README's "holds up the other loggers on its thread" case), or
   it has died (T3). `flush()` and `close()` then return false at the timeout. With a saturated
   timeout they never return.
2. **Lock inversion through the handler.** The caller holds lock L and calls `close()`; a worker
   needs the handler, and the handler takes L. The worker blocks, `close()` times out, the worker
   continues once the caller releases L. Inherent to any callback-based logger and covered by the
   handler's "must not block" contract.
3. **Two handlers closing each other's loggers across two pool threads.** A's handler on thread 1
   calls `B.close()`, B's handler on thread 2 calls `A.close()`: each waits for the other's thread,
   which is in a handler. Both time out. Contrived, and bounded.
4. **Sync mode.** `log()` blocks on its stripe monitor, uninterruptibly, for as long as another
   thread's write on that stripe takes; `close()` takes every stripe monitor in turn with no
   timeout ("the timeout doesn't apply", `EfficientLogger.java:81-82`). A hung disk hangs those
   callers. Documented, and the price of sync mode.

## Correctness and robustness

### T1. A logger registered onto a sleeping pool thread isn't served until something else wakes the thread (Medium)

**Where:** `WorkerPool.register` (`WorkerPool.java:154-190`); the sleep protocol in
`WorkerPool.Runner.sleep` (`WorkerPool.java:365-394`) and `MpscRing.offer` (`MpscRing.java:97-102`).

**What happens:** `register` picks the least loaded runner, appends the worker to its copy-on-write
list and attaches the thread. It starts threads it created, but it never unparks a thread that
already exists. That thread reads its worker list once per pass (`WorkerPool.java:270`); if it
was already past that read, or asleep, it doesn't know about the new worker.

The wake-up protocol is per ring: a producer unparks the thread only if it finds the ring's
PARKED state set to TIMED or DEEP, and only the thread sets that, in `announceSleep`, for the
workers in the list it read. A ring the thread has never seen stays AWAKE, so every `log()` to the
new logger decides there is nothing to wake. The thread sleeps until one of: a message to a
logger it *does* know about, whose ring says DEEP (or TIMED and above the quarter-full threshold);
the expiry of a timed sleep, which is the shortest flush interval among the workers it knows
(up to 60 s); or `flush()`, `close()` or an invalid-ID rejection on the new logger, which unpark
unconditionally. In deep sleep, which a thread reaches after about ten empty flush intervals
(one second at the defaults) or at once in eager mode, there is no timed expiry at all.

`leastLoaded` prefers a running thread with nothing to do (`WorkerPool.java:207-209`), so a new
logger is routed to exactly the idle thread this affects.

Reproduced (Probe 1): pool of one thread, logger A logs one message and goes quiet, the thread
reaches deep sleep (`Thread.State.WAITING`) after 1.1 s; logger B is built on the pool with
`maxQueuedMessages(8)` and logs 20 messages:

```
A's thread went into deep sleep after 1128 ms
B registered on the same thread: true, pool.threads()=1
after 3 s: B accepted=8 written=0 droppedQueueFull=12 queued=8 file exists=false thread=WAITING warnings=[]
500 ms after a message to A: B written=8 warnings=[queue full (maxQueuedMessages=8, ...); dropping messages
   until it is half empty, queue recovered; dropped 12 messages (12 in total)]
```

Nothing of B's reaches disk for three seconds; its queue fills and drops; no warning is emitted,
because `OverflowTracker.check` runs only on a worker. One unrelated message to A wakes the thread,
which then serves B. With the default pool (four threads) this is the fate of the fifth logger an
application builds while the others are quiet, and of any logger built on a private pool that
is already idle: a per-tenant or per-job logger created on demand, logging a burst, and dropping
all of it with no trace until something else on that thread happens to log.

`WorkerPoolTest.manyLoggersShareThePoolsThreads` and the other pool tests build their loggers
back to back, so the threads are still in timed sleep (100 ms) and pick the new workers up on the
next expiry; none waits for a thread to go idle first.

**Fix:** in `register`, after the workers are added and the fresh threads started, unpark every
runner that received a worker:

```java
for (Runner r : assigned) {
    LockSupport.unpark(r.thread); // a thread that read its list before add() re-reads it on the next pass
}
```

`add` is a volatile write that precedes the unpark; the thread reads `workers` at the top of
every pass and parks only afterwards, so either it saw the new worker, or the permit makes its
next park return at once and it sees the worker on the following pass. An unpark on a thread
that is between passes costs one extra pass. Add a test that waits for the thread to be in
`WAITING` before building the second logger, logs to it, and asserts the message is written within
its flush interval, with no drops.

### T2. With two or more workers on a thread, an unpublished slot makes the thread spin at full CPU (Medium)

**Where:** `WorkerPool.Runner.awaitPublish` (`WorkerPool.java:329-337`) and
`Worker.stalledBriefly` (`Worker.java:154-156`), with `stalledPasses` (`Worker.java:66`).

**What happens:** when any worker's step returns `AWAIT_PUBLISH` (its head slot is claimed but not
published), the thread calls `awaitPublish(ws)`, which spins and yields if *any* worker on the
thread reports `stalledBriefly()`, and otherwise parks for 1 ms. `stalledBriefly()` is
`stalledPasses <= STALL_SPINS`, and `stalledPasses` is 0 for a worker that has never stalled. So an
idle worker on the same thread always says "stalled briefly", the 1 ms poll is never reached, and
the thread spins until the slot is published or the stuck logger finishes closing. The spinning
also steps every worker on each pass, which is harmless for them but is the full cost of a core.

Reproduced (Probe 2): logger A with a slot claimed but not published and one message behind it,
measured over one second after the spin budget is exhausted, alone and with an idle logger B on
the same thread:

```
A stuck alone on its thread  : thread TIMED_WAITING used 0 ms of CPU in 1000 ms
A stuck + B idle on one thread: thread RUNNABLE used 1015 ms of CPU in 1000 ms
```

This is the R1/R7 scenario (a `log()` cut off by a `StackOverflowError` between the tail claim and
the publish) coming back for the shared pool. `AsyncThreadsTest.aSlotLeftUnpublishedDoesNotWedgeOrBurnTheWorker`
and `aFlushBehindAnUnpublishedSlotTimesOutAndTheWorkerStillBacksOff` run one worker on a private
pool, so they pass. A producer merely stalled for a few milliseconds costs a few milliseconds of
spin, which is what the design intends; the defect is that the back-off never engages when the
stall is long.

**Fix:** consult only the workers that are actually waiting for a slot. The smallest change is in
`Worker`, since a worker's `stalledPasses` is positive exactly when its last step returned
`AWAIT_PUBLISH` (it is reset whenever the worker consumes anything):

```java
boolean stalledBriefly() {
    return stalledPasses > 0 && stalledPasses <= STALL_SPINS;
}
```

Alternatively have `Runner.run` remember which workers returned `AWAIT_PUBLISH` and pass only
those. Extend the two F7 tests, or add one in `WorkerPoolTest`, with a second idle logger on the
same thread and the existing CPU-time assertion.

### T3. An `Error` outside `step()` kills the pool thread, which keeps its loggers and attracts new ones (Medium)

**Where:** `WorkerPool.Runner.run` (`WorkerPool.java:267-308`): the `try/catch (Throwable)` covers
only `w.step()` (lines 280-285), and `finish(w)` guards only `w.finish()` (311-323). `sleep`
(365-394) calls `Worker.beforeSleep` (`Worker.java:159-164`), which calls `FileTable.flushDirty`,
`Warnings.emitAll` and `OverflowTracker.check` unguarded. `FileTable.writeRaw`
(`FileTable.java:402-412`) catches `IOException | RuntimeException` only. `awaitPublish`,
`waitWhileClosing`, `retire` and the `onTerminated` callback in `finish` are unguarded too.

**What happens:** anything that is an `Error` on those paths propagates out of `run()` and the
thread exits. Realistic sources: `FileOutputStream.write` throws `OutOfMemoryError` when its
native `malloc` for a write larger than 8 KiB fails (so any `fileBufferBytes` above the default,
under native memory pressure); building a warning string in `OverflowTracker.check` or
`FileTable.reportIoError` under Java heap exhaustion; a `StackOverflowError` from a deep handler.
Memory pressure is exactly when the logs matter.

After the thread dies:

- its `Runner` stays in `runners[]`, so `pool.threads()` still counts it and `leastLoaded` keeps
  choosing it (it has the fewest workers), so **new loggers are assigned to a dead thread**;
- every logger on it is never stepped again: `log()` keeps accepting until the queue is full,
  then drops, with no warning;
- `close()` waits its whole timeout and returns false, with the warning "the remaining N messages
  will be written in the background", which is false;
- the records already in the dead worker's buffers are lost and counted nowhere: `accepted` is 1,
  `written`, `lostIoError` and `abandoned` are 0, so the stats identity fails.

Reproduced (Probe 3) with a sink whose first `write` throws `OutOfMemoryError`, standing in for
the native allocation failure:

```
uncaught on efficient-logger-1-worker-0: java.lang.OutOfMemoryError: simulated: native buffer for write()
A's thread alive=false state=TERMINATED, pool.threads()=1, warnings=[]
a.close(2 s) -> false after 2020 ms; warnings=[close didn't finish within 2000 ms; the remaining 0 messages will be written in the background]
a.stats()=LoggerStats[accepted=1, written=0, ..., lostIoError=0, abandoned=0, queuedMessages=0, ...]
B registered on the dead thread: true (alive=false), pool.threads()=1
B after 1 s: written=0 queued=1
b.close(2 s) -> false after 2002 ms
```

The earlier review's R9 note that "a worker can't actually die while the logger is open (every
`Throwable` in the loop is caught)" was true of the per-logger thread and is no longer true of
the pool thread.

**Fix:** two parts. First, make the thread immortal again: move the `try/catch (Throwable)` to
cover the whole body of one pass (the step loop, `finish`, and the wait or sleep), and on a
catch call `recover` on the workers involved (or a runner-level equivalent: report once, pause
10 ms). `beforeSleep` is the one place that writes outside `step()`, so alternatively guard it the
way `accept` is guarded, counting what was lost with `files.countLost`. Second, as a backstop, if
`run()` ever does exit abnormally, free the slot and re-register the workers in a `finally`, so at
worst a new thread takes over; and make `close()`'s warning honest when the thread is dead (check
`thread.isAlive()` in `awaitClose`). Test with a sink that throws an `Error` from `write`, as the
probe does: the thread must survive, the message be counted as lost, and a second logger on the
pool must still be written.

### T4. `serveFlush` drains the whole ring in one step, so one `flush()` holds a shared thread (Low)

**Where:** `Worker.serveFlush` (`Worker.java:363-377`): `while (ring.head() - target < 0)
drainBatch(...)`.

**What happens:** every other place the thread writes is one batch (at most 256 messages) before
it moves to the next worker, which is what lets the README say that sharing "adds no latency
beyond the other loggers' batches" (`README.md:138`). `serveFlush` is the exception: it drains
everything logged before the request, up to the whole ring (65,536 messages by default, 8 million
at most), writing as it goes, before the thread steps any other worker. A `flush()` on a logger with
a deep backlog, or on a fast one that is kept full, holds every other logger on that thread for
the time it takes to write that backlog: their messages wait past their flush intervals, their
own `flush()` calls wait, and in eager mode their latency promise is broken. A checkpointing
application that calls `flush()` on each logger in turn makes this routine.

**Fix:** have `serveFlush` drain at most one batch per call and return with the request still
pending if the target isn't reached; `runOnce` is called again on the next pass (the step returns
`CONTINUE` when it took messages), so the request completes after as many passes as it needs,
interleaved with the other workers. `flushDone` is set only when the target is reached, as now.

### T5. User code runs under the pool lock, and `close()` can't complete until that lock is free (Low)

**Where:** `WorkerPool.register` (`WorkerPool.java:155-189`) calls `factory.newThread(r)` and
`thread.start()` inside `synchronized (lock)`. `Runner.finish` (`WorkerPool.java:311-323`) calls
`unregister`, which takes the lock, *before* it runs the `onTerminated` callback that counts down
the latch `close()` waits on.

**What happens:** a `ThreadFactory` that is slow or blocked (one that waits on a semaphore, logs
through another system, or takes a lock the application also uses in a warning handler) holds the
pool lock for that long. While it does, every worker on that pool that finishes closing waits in
`unregister` and so every `close()` on that pool waits too, regardless of logger. There is also a
genuine deadlock, with user code on both sides: thread U is in `register` holding the pool lock and
inside `newThread`, which wants lock X; a warning handler on pool thread P holds X and builds a
logger, so `register` on P wants the pool lock. Each needs what the other holds. It needs a
factory and a handler that share a lock, which is unusual, but the pool lock is the only library
lock under which user code runs at all.

**Fix:** create the threads outside the lock: under the lock, choose runners and reserve fresh
slots (a placeholder runner with no thread); release it; call the factory; take the lock again to
publish the threads and add the workers, or roll back if a thread couldn't be made. Also run the
`onTerminated` callback before `unregister`, or do the removal without the pool lock (the list is
copy-on-write and only its own thread removes from it, so a per-runner lock would do). Say in
`WorkerPool.create`'s javadoc that the factory is called while loggers are being built and must
not block.

### T6. A worker is in its runner's list before its thread is attached (Info)

`WorkerPool.java:171-173`: `r.add(w)` precedes `w.attach(r.thread)`. A running thread can step
the new worker before `attach` has set `Worker.thread` and the ring's consumer. The step itself
doesn't need either, but a warning emitted during that first step (an invalid ID, say) runs a
handler that could call `close()` on the new logger, and `onOwnThread` would then read a null
thread, miss the match, and wait on itself for the timeout. The window is nanoseconds and the
scenario needs a handler that closes its own logger on its first warning. Swap the two lines; it
is free, and with T1's unpark the ordering matters slightly more.

## Documentation drift

### D1. The shared-thread coupling isn't stated for `close()` and the shutdown hook, and the timeout warning can say "0 messages" with records unwritten

`README.md:140-144` says a logger whose handler blocks or whose disk stalls "holds up the other
loggers on its thread for that long" and recommends a private pool. Two consequences deserve a
sentence each, because they change what an operator should do:

- **`close()` on a healthy logger returns false if an unrelated logger on its thread is stalled**,
  and at JVM exit the hook waits at most `closeTimeout` for all loggers at once, so one stalled
  logger loses the queued messages of every logger sharing its thread. The recommendation "give
  such a logger a pool of its own" is right, but the reader should know that the alternative is
  not just latency for the others but data loss at exit, and that a logger whose messages must
  survive exit is the one that most needs a private pool.
- **The timeout warning counts queued messages, not buffered records.** `awaitClose`
  (`AsyncLogger.java:269-270`) reports `Admission.messages(...)`, which is the number not yet taken
  from a ring. Records already taken and sitting in a `FileTable` buffer, which is what a thread
  stalled in a write is holding, aren't counted, so a close that times out behind one slow write
  says "the remaining 0 messages will be written in the background" (seen in Probe 3). Either add
  the dirty records (each worker's `files` can expose a pending count) or reword to "the queue
  and any buffered records".

`WorkerPool`'s javadoc should also say what T5 implies for a custom `ThreadFactory`.

## Smaller observations

### Q1. After close, a too-long ID or an oversize message is counted as accepted and its warning never emitted (Info)

`AsyncLogger.log` (`AsyncLogger.java:109-128`) rejects over-long IDs and oversize messages before
it consults the admission word, so after `close()` these are counted in `tooLongIds` and
`oversizeMessages` rather than `rejectedClosed`, and `stats().accepted()` grows after close. The
first such rejection after the workers have finished calls `rings[0].wakeConsumer()` to have a
worker emit the one-time warning, but no worker will ever step again, so the warning is lost for
good, and `oversizeReported` stays false. Check the closed bit first (one volatile read, which
`tryAcquire` does anyway), or let `stats()` fold these into `rejectedClosed` once closed.

### Q2. If registering the shutdown hook throws, `build()` fails but the workers stay registered (Info)

`AsyncLogger.start` (`AsyncLogger.java:89-103`) rolls back only around `pool.register`. If
`ShutdownHook.register` throws (an `OutOfMemoryError` from `new Thread`, or a `SecurityException`
under a security manager), `build()` propagates it with the workers already registered and the
thread running: a logger nobody can reference holds its rings (about a megabyte at the defaults),
keeps its pool thread alive, and is closed only at JVM exit if the hook had been registered
earlier by another logger. Wrap the hook registration in the same rollback, closing the admission
word so the workers finish and unregister themselves.

## Checked and found sound

Re-derived from the code, not taken from the documents; listed so the next reader needn't redo
them.

- **The per-ring wake-up protocol survives several rings on one thread.** `sleep` announces on
  every ring, then re-checks every ring's tail, the admission word and the flush ticket, then
  parks once. A producer on any ring that publishes before its ring's re-check is seen; one that
  publishes after sees TIMED or DEEP and unparks. Permits don't accumulate, but one is enough,
  and `afterSleep` resets every ring to AWAKE, so no stale state is left on a ring. The T1 case is
  the one ring the thread didn't announce on.
- **The runner lists are safe.** Copy-on-write under the pool lock, read once per pass by their
  own thread, never read by another thread except for `threads()`. A worker is removed only by
  its own thread after `finish`, so a worker is never stepped after it has finished.
- **Retire and register can't lose a thread or a worker.** Both take the pool lock; `retire`
  re-checks the list under it, so either the registration sees the running thread and keeps it,
  or the retirement frees the slot first and the registration starts a new thread.
- **The registration rollback is complete**: assigned workers are removed, unstarted fresh
  runners are forgotten, started ones find no work and retire, and `start()` closes the admission
  word so a logger that failed to build never accepts a message.
- **Lock-free fairness between loggers on a thread**: a worker that fills its batch returns
  `CONTINUE`, but the loop still steps every other worker before coming back, so a busy logger
  can't starve a quiet one (T4 is the one exception). Dirty buffers of a quiet logger are written
  by its own `runOnce` interval check even if the thread never sleeps.
- **The 2 s give-up is still sound with shared threads.** A step drains before it compares the
  admitted count, so a worker that has anything consumable always changes the count and never
  gives up on it; a worker whose thread was blocked for over 2 s by another logger resumes,
  drains, and only then judges. A sibling with an empty ring that gives up early closes only its
  own files and reports nothing; the last worker reports the logger-wide count (R2/R8).
- **A late publish into a finished worker's ring** finds PARKED at AWAKE and unparks nothing, so
  it can't spuriously wake a thread now serving other loggers; it is counted as `abandoned`.
- **Handler re-entrancy on a pool thread** is safe in every place a handler is called: `log()` on
  any logger on that thread finds the ring AWAKE and is drained on the next pass; `flush()` and
  `close()` on a logger of that thread return false at once; `build()` registers normally and may
  land on the current thread, which picks the worker up next pass (once T1 is fixed, or at once).
- **The shutdown hook's lock order** is `ShutdownHook.LOCK` then the JDK's hooks lock, never the
  reverse; a logger that finishes while hooks are running gets `IllegalStateException` from
  `removeShutdownHook` and ignores it; a logger built while hooks are running is simply not
  registered.
- **Interrupts**: cleared at the top of each pass so a leftover permit or interrupt costs one extra
  pass; preserved for API callers; `FileOutputStream` ignores them.
- **`flush()` tickets**: a request that arrives between `serveFlush` reading its ticket and
  setting `flushDone` is served on the next step, since `runOnce` compares the two every time;
  `requestFlush` unparks unconditionally, so no Dekker argument is needed for it.
- **Saturated timeouts** work as "forever" through the modular arithmetic in both `awaitFlush`
  and the latch (see above).

## Tests

The pool tests cover sharing, spreading, ordering under contention, a thread sleeping only when
all its loggers are idle, a closed logger's thread serving the rest, handler re-entrancy on a
shared thread, and the single shutdown hook. The gaps the findings expose:

- No test registers a logger onto a thread that is already in deep sleep, or in a long timed sleep
  (T1). The existing tests register back to back.
- No F7 test puts a second worker on the stuck worker's thread (T2). Both F7 CPU-time tests run a
  lone worker on a private pool.
- No test throws an `Error` from a sink, or from anything on the sleep path (T3). The fault
  injection in `FakeSinks` and `IoErrorTest` uses `IOException` and `RuntimeException`, which
  `FileTable` catches.
- No test calls `flush()` on a logger with a large backlog while measuring another logger on the
  same thread (T4).
- No test uses a `ThreadFactory` that blocks (T5), and none checks `onOwnThread` during a worker's
  very first step (T6).

## Recommendations, in order of value for effort

1. **T1.** Unpark every runner that received a worker at the end of `register`, and swap `add`
   and `attach` (T6). Three lines plus a test that waits for deep sleep.
2. **T2.** Make `stalledBriefly` require `stalledPasses > 0`. One line plus a second logger in the
   F7 CPU tests.
3. **T3.** Guard the whole pass in `Runner.run`, count records lost in `beforeSleep`, and free the
   slot if the thread ever does exit. A dozen lines plus a sink that throws an `Error`.
4. **T4.** One batch per `serveFlush` call.
5. **T5, D1, Q1, Q2** as convenient; D1's two sentences in the README are cheap and worth adding
   with T3's fix to the close warning.

## How the findings were verified

Three programs in the review session's scratch folder (not committed) were compiled against
`build/classes/java/main` in package `org.example.efficientlogger`, so they could use the
package-private seams the tests use (`TestHooks`, `AsyncLogger.thread(i)`, `admission()`,
`worker(i).ring`, `MpscRing.claimWithoutPublishingForTest`, `WorkerPool.threads()`):

- **Late registration (T1):** a one-thread pool; logger A logs once and is flushed; wait until
  the thread's state is `WAITING` (deep sleep); build logger B with a queue of 8 on the same pool;
  log 20 messages; after 3 s read B's stats and the thread state; then log one message to A and
  read B's stats again.
- **Stuck slot on a shared thread (T2):** admit one message and claim its slot without
  publishing, log one message behind it, wait 500 ms for the spin budget, then measure the pool
  thread's CPU time over one second; once with the logger alone on its thread, once with an idle
  second logger on it.
- **Thread death (T3):** a sink whose first `write` throws `OutOfMemoryError`; log one message;
  after a second read the thread state and the pool's thread count; `close(2 s)` and its warning
  and stats; then build a second logger on the pool, log to it, and close it.

Their outputs are quoted in the findings. Everything else was verified by reading.
