# EfficientLogger — Implementation Plan

> **Source:** `SCOPE.md`. **Status:** plan approved 2026-10-18; M0–M7 done. Both modes are usable: `EfficientLogger.builder(...).build()` gives the async logger (the default), and `.synchronous().build()` the sync one. Next: M8.

## 1. Context
Today the user writes multiple JSON log files through log4j, appending across restarts.
SCOPE.md asks for a purpose-built replacement. It must:
- be async, block the caller as little as possible, and allocate frugally
- use a bounded queue that drops on overflow with a single warning
- run a worker pool (one thread by default), with an optional sync mode

The project is an empty IntelliJ Kotlin template (Gradle 8.14, toolchain 17, a hello-world `Main.kt`). Nothing can be reused; this is a greenfield library.

**Decisions confirmed with the user**
- **Java 17**, with zero runtime dependencies. Remove the Kotlin plugin and `Main.kt`.
- The queue limit is **both a max message count and a max total queued chars** (sum of `String.length()`).
- The drop warning fires **once per overflow episode**. When the queue recovers, one line reports the dropped count, and the warning re-arms.
- About **500 concurrent files** per logger. Per-file buffers stay small and exist only while the file is dirty. Open handles are capped with LRU eviction.

**Assumptions** (flag in review)
- Output is NDJSON: each JSON string is written as-is, followed by `\n` (configurable to `\r\n`).
- JSON is not validated. Callers must pass single-line JSON.
- Out of scope for v1: rotation, compression, an fsync policy, and log4j integration.

**Design goal on the hot path:** `log()` allocates 0 bytes and never blocks.
It is one CAS on an admission word, one atomic add on the shard tail, two reference stores and one volatile read. An `unpark` happens only rarely.

---

## 2. Public API (package `org.example.efficientlogger`)

```java
public interface EfficientLogger extends AutoCloseable {
  static EfficientLoggerBuilder builder(Path directory, String fileNamePattern); // exactly one "{id}", e.g. "events-{id}.json"
  boolean log(String id, String json);  // false = dropped (limits / too large / closed; sync: invalid id or I/O failure). Only throws NPE on null args.
  boolean flush(Duration timeout);      // async: everything accepted before the call has reached the OS (no fsync). sync: true at once.
  boolean flush();                      // flush(closeTimeout)
  long droppedCount();
  LoggerStats stats();                  // snapshot record (allocates; not for the hot path)
  boolean close(Duration timeout);      // idempotent; true = drained, flushed, files closed
  @Override void close();               // close(closeTimeout)
}
public interface WarningHandler { void warn(String message, Throwable causeOrNull); static WarningHandler stderr(); }
public record LoggerStats(long accepted, long written, long droppedQueueFull, long droppedTooLarge,
    long droppedInvalidId, long lostIoError, long rejectedClosed, long queuedMessages, long queuedChars, int openFiles) {}
```
- **IDs are `String`s.** They are immutable and cache their hash. `CharSequence` is not accepted because it may be mutable and can't be queued by reference.
- **Sync mode is the explicit `synchronous()` flag.** `workerThreads(0)` is rejected, so a miscomputed 0 can't silently switch modes.

### Builder options (`build()` validates everything and creates the directory)
| Option | Default | Notes |
|---|---|---|
| `workerThreads(int)` | 1 | 1..16 |
| `synchronous()` | off | Combining it with an async-only option throws `IllegalArgumentException` |
| `maxQueuedMessages(int)` | 65 536 | 1..2^23, exact |
| `maxQueuedChars(long)` | 16 Mi | 1..2^38−1. A single message over the limit is dropped as too large. |
| `fileBufferBytes(int)` | 8192 | 64..16 MiB, per **dirty** file (async) or per stripe (sync). 8 KiB stays on FileOutputStream's native stack-buffer path. |
| `maxOpenFiles(int)` | 1024 | Soft global cap, LRU eviction |
| `flushIntervalMillis(long)` | 100 | 0..60 000. Max time buffered or queued data waits before reaching the OS. 0 means flush after every batch, and it forces eager wakeup (`parkNanos(0)` would spin). |
| `eagerWakeup(boolean)` | false | Wake the worker on every message. With interval 0 this matches log4j's `immediateFlush`. |
| `closeTimeout(Duration)` | 10 s | |
| `registerShutdownHook(boolean)` | true | Async only. Drains at JVM exit. |
| `threadFactory(ThreadFactory)` | daemon threads named `efficient-logger-<n>-worker-<i>` | |
| `warningHandler(WarningHandler)` | stderr | |
| `lineSeparator(String)` | `"\n"` | `"\n"` or `"\r\n"` |
| `syncStripes(int)` | 16 | Power of two 1..256, sync only |

---

## 3. Architecture

```
log(id,json) ─► Admission (one global packed CAS word: CLOSED|count|chars) ─► shard = mix(id.hash) → MpscRing[shard]
                                                                                     │ (lazy unpark)
Worker[shard] ─ drainBatch ─► FileTable (HashMap + intrusive LRU/dirty lists) ─► Utf8.encode → byte[] buf ─► FileOutputStream(append)
```

### 3.1 Admission: one exact, global limit word (`Admission`)
- **Layout.** One `long` in a padded `long[]` cell (value at index 16, at least 128 B of padding on each side), accessed through `LONGS = MethodHandles.arrayElementVarHandle(long[].class).withInvokeExactBehavior()`.
  - Bit 63 = `CLOSED`, so `v < 0` means closed.
  - Bits 39..62 = message count. Bits 0..38 = char count.
  - `ONE_MSG = 1L<<39`. The limits (≤2^23 messages, ≤2^38−1 chars) guarantee that no field carries into its neighbour.
- **Acquire (producer).** Reject `len > maxChars` before touching the word.
  - Loop: `v = getVolatile`. If `v < 0`, the logger is closed. `nv = v + (ONE_MSG|len)`.
  - If `count(nv) > maxCount || chars(nv) > maxChars`, it's over a limit and nothing is written, so there is never overshoot.
  - Otherwise `compareAndExchange`.
  - Inline this in `log()` so `nv` stays a local; it's used for the wake check.
- **Release (worker).** One `getAndAdd(-sum)` per batch, done in `finally` **after** the slots are nulled.
- **Close.** `getAndBitwiseOr(CLOSED)` is the linearization point. After it, the count can only fall. `count == 0` means every admitted message, in-flight ones included, has been consumed.
- **Why the ring claim can't fail.** Each shard ring has capacity `nextPow2(maxQueuedMessages)`. The global count bounds the unconsumed messages in any shard, so the producer's tail XADD always gets a free slot.
  - This removes the "budget taken, ring full" rollback race and the skew problems of per-shard budgets.
  - Memory is 2 refs × cap × N rings: about 0.5 MiB for the defaults with one worker.

### 3.2 `MpscRing` and the producer path
- **Fields.**
  - `Object[] slots` of length 2×cap, holding id at 2i and msg at 2i+1. A null msg means "not yet published".
  - `long[] cells` with TAIL, PARKED and CONSUMED, each at least 128 B apart.
  - `head` is a plain field owned by the worker. Indexes are `long` (an `int` would wrap after about 35 minutes at 1 M msg/s), and the slot index is `(int) pos & mask`.
- **`log()`**, in this exact order. Nothing that can throw may sit between the CAS and the publish. A claimed but unpublished slot would stall its shard forever.
```java
int len = json.length(); int h = id.hashCode();             // NPEs fire before any state change
if (len > maxChars) { DROPS++; TOO_LARGE++; return false; }
MpscRing r = rings[shard(h)];   // N==1 → 0; else m = h*0x9E3779B9; (int)(((m & 0xFFFFFFFFL) * N) >>> 32)
long nv = /* inlined admission CAS */;  closed → rejectedClosed++, false;  over limit → DROPS++, false
long pos = (long) LONGS.getAndAdd(r.cells, TAIL, 1L);       // can't fail (3.1)
int i = ((int) pos & r.mask) << 1;
r.slots[i] = id;                                           // plain store, ordered by the release below
SLOTS.setRelease(r.slots, i + 1, (Object) json);           // publish
long p = (long) LONGS.getVolatile(r.cells, PARKED);        // Dekker read; the CAS and XADD above are sync actions
if (p != AWAKE && (p == DEEP || aboveWake(nv)) && LONGS.compareAndSet(r.cells, PARKED, p, AWAKE))
  LockSupport.unpark(r.worker);                            // only the CAS winner unparks (no storm)
return true;
```
- **Wake threshold.** `aboveWake(v)` means count ≥ `max(1, maxCount/4)` or chars ≥ `max(1, maxChars/4)`. Eager mode sets both thresholds to 1.
- **As built in M5:**
  - **Admission result.** `Admission.tryAcquire(len)` returns the new word (always > 0), `REJECTED_CLOSED` (0) or `REJECTED_FULL` (−1).
  - **Ring cells.** The consumer's `head` lives in its own padded cell of `MpscRing.cells`, so its writes don't share a cache line with the fields producers read. There is no CONSUMED cell.
  - **Draining.** `MpscRing.drain(handler, max)` nulls each slot before handing the message to the handler. The caller releases admission after the batch.
  - **Shared hashing.** `Hashing.index(hash, n)` (multiply by 0x9E3779B9, then `((m & 0xFFFFFFFFL) * n) >>> 32`) picks both async shards and sync stripes, for any `n`.
- **Stats** come from counters that already exist, so the hot path pays no extra atomics:
  - `accepted` = Σ shard tails
  - `written` = Σ worker counters (opaque writes)
  - `rejectedClosed` and `TOO_LARGE` are cold-path atomics

### 3.3 `Worker` loop, batching, and the idle/wake protocol
Each worker owns one shard and that shard's files. The affinity keeps per-file order with no locks on file state.
`batchSize = clamp(maxQueuedMessages/4, 1, 256)`. Call `System.nanoTime()` once per batch.
```java
for (;;) {
  Thread.interrupted();                                    // a stale interrupt flag would make park() return at once and spin
  long now = clock.nanoTime();
  int n = drainBatch(now);                                 // getAcquire(msg); null → stop. Null both slots. Per-message try/catch(Throwable): budget always released, worker never dies.
                                                           // finally: head=h; setRelease(CONSUMED,h); admission.release(sum)
  if (flushReq != flushDone) serveFlush();
  if (files.dirtyCount() != 0 && now - files.firstDirtyNanos() >= flushIntervalNanos) files.flushDirty();
  tracker.check();
  if (n == batchSize) continue;
  long v = admission.getVolatile();
  if (v < 0) { if ((v & ~CLOSED) == 0) break; if (n == 0) waitInFlight(); continue; }   // close: drain until global count == 0
  if (n == 0 && head != tail()) { spinThenYield(); continue; }   // producer is between XADD and publish
  if (n == 0) idle();
}
finally { files.flushDirty(); files.closeAll(); tracker.finalReport(); releaseFlushWaiters(); }
```
**`idle()`** has three states:
- `AWAKE`: producers only do a volatile read.
- `TIMED`: the normal idle state, `parkNanos(flushInterval)`. Producers wake the worker only above the threshold, so a small trickle waits at most one flush interval.
- `DEEP`: `park()`. Entered after about 10 consecutive empty timed wakeups (about 1 s idle), or always in eager mode. Any message wakes it, which costs one unpark per idle period.

```java
files.flushDirty(); tracker.check();                       // never sleep while holding dirty buffers
LONGS.setVolatile(cells, PARKED, mode);                    // Dekker write
long t = getVolatile(TAIL), v = admission.getVolatile();   // Dekker re-check on TAIL and admission, NOT on the slot (a setRelease followed by a load isn't StoreLoad-ordered)
boolean work = (t != head && (mode == DEEP || aboveWake(v))) || v < 0 || flushReq != flushDone;
if (!work) { if (mode == DEEP) LockSupport.park(this); else LockSupport.parkNanos(this, flushIntervalNanos); }
LONGS.setVolatile(cells, PARKED, AWAKE);
```

**Why no wakeup is lost.** All accesses to PARKED, TAIL and the admission word are synchronization actions.
- If the producer reads PARKED after the worker's write, it sees the sleep state and unparks. An unpark that lands before `park()` leaves a permit, so `park()` returns at once.
- Otherwise the worker's re-check comes after the producer's XADD and CAS, so the worker sees the new message.
- `close()` and `flush()` unpark unconditionally.

`waitInFlight()` (close path only) spins about 64 times with `onSpinWait`, then calls `Thread.yield()`, then `parkNanos(100µs)`, and loops.
On Windows `parkNanos` is about 16 ms granular. Don't use microsecond parks for correctness waits.

### 3.4 Overflow episodes (`OverflowTracker`, one per logger)
- **Producers** only do `getAndAdd(DROPS, 1)`. They never print or reset anything; a flag that producers set and the worker resets would race.
- **Workers** call `check()` after every batch and before sleeping. Its fast path is `!active && drops == reported`: two volatile reads.
- **Episode logic**, done under the tracker's monitor (cold path):
  - `!active && drops != reported` → set `active`, then warn once: "queue limit reached (maxQueuedMessages=…, maxQueuedChars=…); dropping messages".
  - `active` and below low water (count ≤ max/2 **and** chars ≤ maxChars/2). The hysteresis is needed because the queue may never be fully empty under steady load. → read admission first, then drops.
  - Then warn "queue recovered; dropped N messages (total T)", set `reported = drops`, and re-arm.
  - Start and recovery are in an if/else, so one `check()` can never do both. Tests rely on this.
- **Where warnings are emitted:** outside the monitor, with any Throwable from the handler swallowed. A handler that calls `log()` can't block or loop.
- **`finalReport()` on close** reports any unreported drops.
- Too-large drops count toward episodes. Log-after-close is counted only (`rejectedClosed`), with no warning.

### 3.5 `close(Duration)`, `flush(Duration)`, shutdown hook
- **`close`**
  1. Run `STATE` CAS OPEN→CLOSING. A loser waits for TERMINATED, which makes `close` idempotent.
  2. Set CLOSED in the admission word.
  3. Unpark all workers.
  4. Join with the remaining time, skipping the current thread (`close()` may be called from the warning handler on a worker). On InterruptedException, restore the flag and return false.
  5. Remove the shutdown hook unless we are in it, so it can't leak the logger. Catch `IllegalStateException`.
  6. On timeout, warn "N still queued" and return false. The daemon workers keep draining.
- **Workers alone** drain, flush and close their files. `close()` never touches FileTables; doing so would be a data race.
- **Guarantee:** lines on disk == the number of `log()` calls that returned true (unless close timed out).
- **`flush`**
  - Per worker: a volatile `flushReq`/`flushDone` ticket pair plus a monitor. This is a cold path, so allocation is fine.
  - The caller increments `flushReq` and unparks the worker.
  - The worker snapshots TAIL **after** reading `req`, drains until `head ≥ target`, runs `flushDirty()`, sets `flushDone = req` and calls `notifyAll`.
  - Returns false when called from a worker thread (deadlock guard), or after close if not terminated.
- **`build()`**
  - Start the threads only after construction finishes.
  - If a thread start fails, set CLOSED, join the threads already started, and rethrow.
- **Shutdown hook:** calls `close(closeTimeout)`.
- **As built in M7:**
  - **No STATE CAS.** Setting CLOSED is idempotent. Every caller waits on one `CountDownLatch` that each worker thread counts down when it finishes, so concurrent `close` calls all return true once the queue is drained. Waiting on the latch rather than joining threads means a custom `threadFactory` can't make `close` hang.
  - **From a worker thread** (e.g. a warning handler), `close` sets CLOSED, wakes the workers and returns false at once. Waiting there would stall until the timeout, because the drain needs that very thread.
  - **The last worker to finish removes the shutdown hook.** A `close` that times out keeps it, so a JVM exit still drains what is left.
  - **Messages.** On timeout: "close didn't finish within X ms; the remaining N messages will be written in the background". When interrupted, `close` and `flush` return false, keep the interrupt flag and warn nothing.
  - **Test seams:** `TestHooks.beforeBatch` (runs before every batch; tests block in it to hold a worker) and the accessors `AsyncLogger.thread(i)`, `closing()`, `admission()` and `shutdownHook()`.
  - **Bugs the threaded tests found:**
    1. A record ending exactly at the end of a file buffer left the next append starting past the encoder's limit. With assertions on, that tripped an assert. Without them, an empty message threw `ArrayIndexOutOfBoundsException`.
    2. `maxOpenFiles + EXTRA_ENTRIES` overflowed `int` for very large `maxOpenFiles`. It is now `Config.maxFileEntries`, capped at `Integer.MAX_VALUE`.
  - **Checking the checks.** With the worker's pre-sleep re-check removed, both lost-wakeup tests fail within 50 messages. With workers stopping at an empty ring instead of waiting for in-flight messages, `closeWaitsForAMessageAdmittedButNotYetQueued` fails deterministically; the close-race stress test alone didn't catch that.

### 3.6 File I/O (shared by both modes): `FileTable`, `FileState`, `Sink`, `FileNamePattern`
- **Sink.** `new FileOutputStream(path.toFile(), true)` behind a `Sink`/`SinkFactory` seam, so tests can inject faults.
  - Why not FileChannel: an interrupt closes a FileChannel, so in sync mode one interrupted caller would kill a shared file. Heap-buffer writes also go through a JDK temporary direct buffer.
  - `FileOutputStream.write(byte[],off,len)` allocates nothing on the Java heap.
- **Map and LRU.**
  - One `HashMap<String, FileState>` per owner (worker or sync stripe), pre-sized.
  - A 1-entry `lastId`/`lastFile` cache, invalidated on eviction.
  - An intrusive doubly-linked LRU list instead of LinkedHashMap, so no iterators. Every loop is an indexed loop.
- **Open-file budget.**
  - `maxOpenFiles` is a global soft budget: an `AtomicInteger`, touched only on open and close. Each owner evicts from its own LRU.
  - Warn once if files thrash (more active IDs than `maxOpenFiles`).
  - On "Too many open files", evict half the LRU and retry once. On NoSuchFile, run `createDirectories` and retry once.
- **Buffers.**
  - Heap `byte[fileBufferBytes]` buffers, taken from a per-owner `byte[][]` stack on a file's first write and returned on flush.
  - Peak memory is (dirty files) × 8 KiB, so about 4 MiB if all 500 files are dirty.
  - An intrusive dirty array (`dirtyIdx`, swap-remove) tracks dirty files. There's one `firstDirtyNanos` per owner.
- **Append writes whole records only.** A record split across two `write()` calls would leave a corrupt line after a crash.
  1. Optimistically encode the JSON into the buffer, keeping room for the separator.
  2. If it doesn't fit, roll back to the record start, write out the buffer, and re-encode at offset 0.
  3. Only a record larger than the buffer is split into chunks.
  - Then add the separator, `pendingRecords++`, mark the file dirty, and touch the LRU.
- **Newline repair on open.** If an existing file is non-empty and its last byte (read with a short-lived `RandomAccessFile "r"`) isn't `\n`, write the separator first. This handles a crash mid-line or a full disk.
- **I/O error.**
  - Drop the file's buffer and add `pendingRecords` to `lostIoError`. Close the file and mark it FAILED with `retryAt = now + 1 s`. That ID's messages are dropped until then.
  - Report through the WarningHandler with the cause, rate-limited to one per 10 s with a suppressed count.
  - The thread never dies.
- **ID / file-name validation.**
  - Runs on every open (so once per ID per open) against the full generated name. Rules:
    - non-empty, ≤255 chars and ≤255 UTF-8 bytes
    - not `.` or `..`, and none of `/\:*?"<>|`
    - no U+0000–U+001F or U+007F
    - no unpaired surrogates (they can't be encoded as a file name on Linux/macOS)
    - no trailing `.` or space
    - not a Windows reserved base name (CON, PRN, AUX, NUL, COM0–9, LPT0–9)
    - `dir.resolve(name).getParent().equals(dir)`
  - An invalid ID gets an INVALID placeholder entry. Its messages are counted in `droppedInvalidId`, with one warning per ID (first ~100 IDs).
  - Case-insensitive filesystems can merge `Foo` and `foo` into one file, possibly from two shards. Lines stay whole but their relative order isn't guaranteed. Document it.
- **Pattern:** exactly one `{id}`. The static prefix and suffix must pass the same rules. The directory must be on the default FileSystem.
- **As built in M3:**
  - **Warnings are queued.** `FileTable` never calls the handler. The owner emits `takeWarnings()` itself: a worker after each batch, a sync stripe after unlocking.
  - **Closed files are forgotten.** A file closed to stay under the cap is removed from the table and reopened in append mode on next use.
  - **Invalid IDs and failed files stay in the table** so their checks and backoff aren't repeated. The table is capped at `maxOpenFiles + 1024` entries, with LRU eviction.
  - **The thrash warning** fires once per logger, when a file used within the last second has to be closed to stay under the cap.
  - **Counters** (`written`, `lostIoError`, `droppedInvalidId`) are written with opaque stores, so `stats()` can read them from any thread.

### 3.7 `Utf8` (no allocation)
`static long encode(String s, int from, byte[] dst, int pos, int limit)` returns `(nextCharIndex << 32) | newPos`. There is also `utf8Length(s)`.
- An ASCII fast loop over `charAt`, then the general path: 1, 2 or 3 bytes, a valid surrogate pair as 4 bytes (never split), and a lone or reversed surrogate as `'?'`.
- It does not consume the char after a bad high surrogate, which matches `String.getBytes(UTF_8)` byte for byte.
- It stops when the next code point doesn't fit. All size math is done in `long` (`3*len` overflows `int`).

### 3.8 Sync mode (`SyncLogger`)
- **Stripes.** S stripes. Stripe = mixed hash of the ID, with S = 1 special-cased (a shift by 32 is a no-op in Java).
  - Each stripe has a monitor object, a `FileTable` sharing the global open-file budget, and one scratch `byte[fileBufferBytes]`.
  - The same ID always maps to the same stripe, so per-file order is lock order. Different stripes don't contend.
- **Lock.** `synchronized(stripe)`. Unlike ReentrantLock/AQS, a contended monitor doesn't allocate a wait node on the Java heap.
- **Write-through.** One `write()` per record (chunked if oversized), so when `log()` returns true the bytes are in the OS.
- **Errors.** An I/O error or invalid ID returns false, using the same FAILED backoff. Warnings are built under the lock and emitted after unlocking.
- **Interrupts.** They don't affect FileOutputStream, and the caller's interrupt flag is preserved.
- **close():** set volatile `closed`, then for each stripe `synchronized { closed = true; closeAll(); }`. A `log()` waiting on the lock sees `closed` and returns false.
- **No threads**, so there's no shutdown hook. `flush()` returns true.

### 3.9 Classes (package-private unless listed in §2)
| Class | Role |
|---|---|
| `EfficientLogger`, `EfficientLoggerBuilder`, `WarningHandler`, `LoggerStats` | Public API |
| `Config` | Immutable validated settings + derived values (cap, mask, thresholds) |
| `AsyncLogger` / `SyncLogger` | The two implementations |
| `Admission`, `MpscRing`, `Worker`, `OverflowTracker` | Async core |
| `FileTable`, `FileState`, `FileNamePattern`, `OpenFileBudget` | File management |
| `Sink`, `SinkFactory`, `FileOutputStreamSink` | I/O seam |
| `Utf8`, `Warnings` | Encoding; safe handler calls + rate limiting |
| `TestHooks` | Package-private: `startThreads`, `beforeBatch` gate, `nanoClock`, `sinks`, `batchSize`, `ringStartIndex` |

---

## 4. Hot-path rules (check during code review)
- **VarHandles:** `static final` and `.withInvokeExactBehavior()`. Pass `1L` and `(Object) json`; assign to `Object`, then cast. Inexact call sites can box.
- **Banned on the hot paths:**
  - `LongAdder`, `ThreadLocal`, capturing lambdas
  - varargs and `String.format`
  - enhanced-for over collections, `LinkedHashMap`/`entrySet` iteration
  - `String.getBytes`, `CharBuffer.wrap`
  - `ByteBuffer.allocateDirect`, `FileChannel`
  - `ReentrantLock`, `ArrayBlockingQueue` and other JDK queues
- **Padding:** contended counters live in padded `long[]` cells. Don't rely on superclass-chain padding; JDK 15+ field layout breaks it.
- **No warning I/O on producer threads:** warning strings are built on the worker or after unlocking.
- **Timing:** `System.nanoTime` only, never wall-clock time.
- **Callers' own allocation is out of our control:** document that per-call `"user-" + n` IDs allocate and re-hash.

---

## 5. Build changes
- **`build.gradle.kts`**
  - Plugins: replace `kotlin("jvm")` with `` `java-library` ``. Use `java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }`, `options.release = 17` and `-Xlint:all`.
  - Dependencies: `testImplementation(platform("org.junit:junit-bom:5.13.4"))`, `testImplementation("org.junit.jupiter:junit-jupiter")` and `testRuntimeOnly("org.junit.platform:junit-platform-launcher")`, which Gradle 8 needs.
  - `tasks.test { useJUnitPlatform { excludeTags("alloc") }; maxHeapSize = "1g" }`.
  - Two `Test` tasks for `@Tag("alloc")`: `allocationTest` (`-Xmx512m`) and `allocationTestNoEA` (adds `-XX:-DoEscapeAnalysis`, which proves the code is allocation-free by construction). `check` depends on both.
  - Jar manifest gets `Automatic-Module-Name: org.example.efficientlogger`. No `module-info` in v1, so same-package tests can reach the package-private hooks.
- **`settings.gradle.kts`:** keep the foojay resolver and the rootProject name.
- **Delete** `src/main/kotlin/Main.kt` and the `src/main/kotlin` directory.
- **Layout:** sources in `src/main/java/org/example/efficientlogger/`, tests in `src/test/java/org/example/efficientlogger/` (same package).

---

## 6. Milestones (each one is green on its own before moving on)
| # | Deliverable | Done when |
|---|---|---|
| M0 | Build switched to Java; Kotlin plugin and `Main.kt` removed | `.\gradlew.bat build` passes with a trivial test |
| M1 | `Utf8` | Encoder and fuzz tests pass |
| M2 | `FileNamePattern`, `Config` / builder validation | Table-driven tests pass |
| M3 | `Sink`, `FileState`, `FileTable` (single-threaded) | Fake sink and fake clock: open, newline repair, append, flush, LRU, budget, errors, buffer pool |
| M4 | `SyncLogger` + public facade | Sync integration tests pass. **First usable release.** |
| M5 | `Admission`, `MpscRing` | Ring/admission concurrency and stress tests (no files) |
| M6 | `Worker`, `AsyncLogger`, `OverflowTracker`, all code including the thread loop, idle protocol, `flush`, `close` and shutdown hook. Tests drive the workers by hand (`startThreads=false`). | Deterministic drain, deadline, episode and close-drain tests |
| M7 | Real threads: tests for the idle protocol, `flush`, `close`, shutdown hook and interrupt resilience. Then public `build()` accepts async mode. | Lost-wakeup and close-linearizability stress tests |
| M8 | I/O error paths end to end | Rate limiting, EMFILE, deleted directory |
| M9 | Allocation tests | `allocationTest` and `allocationTestNoEA` pass |
| M10 (optional) | JMH (`me.champeau.jmh`): 1/4/8 producers, `-prof gc`, compared with a synchronized BufferedWriter per file, optionally log4j2 async + RoutingAppender; README | Benchmark report |

---

## 7. Test plan (JUnit 5, `@TempDir`, no sleeps for correctness)

**Determinism tools**
- Manual-step mode (`startThreads=false`). Tests call `worker(i).runOnce()` and `idleOnce()` on the test thread.
- A `beforeBatch` gate: a Semaphore plus an `entered` latch blocks the worker at a known point.
- A fake `nanoClock`, and a fault-injecting `SinkFactory` that logs every write call.

**Tests**
- **Utf8**
  - Matches `getBytes(UTF_8)` on boundary code points (U+007F/0080/07FF/0800/D7FF/E000/FFFF/1F600), lone/reversed/trailing surrogates, and the empty string.
  - 10 K random strings.
  - Chunked encoding at every limit from 4 to 64 concatenates to the same bytes.
  - `utf8Length` agrees.
- **Pattern / builder**
  - IDs rejected: `../x`, `a/b`, `a\b`, `a:b`, `""`, NUL, `\t`, `x.`, `x `, `CON`, names over 255. `a..b` is accepted.
  - Patterns with zero or two `{id}`. Sync combined with async-only options. Out-of-range values.
- **Ring / admission**
  - 8 producers × 1 M (producer, seq) messages on cap 8/16 with `ringStartIndex = 2^31−3`, `@RepeatedTest(20)`. No loss, no duplicates, monotonic per producer, a claimed slot is always null.
  - Exact count limit (max 5, cap 8 → exactly 5 accepted).
  - Char limit (max 100, 30-char messages → the 4th is dropped; a 101-char message is too large).
  - Shard spread of `id-0..499` over N = 2..8 stays within ±30% of the mean.
- **Overflow episodes (manual mode)**
  - max 8, log 10 → 8 true, 2 false.
  - First `runOnce` → [start]. Second → [start, "dropped 2"].
  - A second episode adds exactly 2 lines. Drops above low water add nothing more.
  - Close during an episode emits the final report.
- **Overflow (threads)**
  - Gate the worker, fill the queue, overflow by 5, release the gate, then `flush()`.
  - The file holds exactly 1..8 in order; warnings are exactly [start, "dropped 5"].
- **Integration**
  - Append across two logger lifetimes.
  - Newline repair (`abc` → `abc\n{…}\n`; nothing added for an empty file or one already ending in a newline).
  - Many files and nested directories. `\r\n` separator.
  - Oversized record (64 B buffer, 10 K chars with multibyte and surrogate pairs) is byte-exact.
  - **Every recorded write ends with the separator**, except oversized records.
  - LRU with `maxOpenFiles = 2` and 3 IDs round-robin: content correct, thrash warning once.
  - Invalid ID: nothing written outside the directory.
- **Ordering:** producers 1/8 × IDs 1/50 × workers 1/3. Each (producer, ID) sequence is contiguous.
- **Close**
  - Drains: gate, log 1000, `close()` on another thread, assert `log()` now returns false, release the gate → returns true with 1000 lines.
  - Timeout: `close(200ms)` returns false and warns. Data still arrives after the gate is released.
  - **Linearizability:** 8 producers count their `true` returns while another thread closes. Lines on disk == Σ true.
  - Idempotent close, two concurrent closes, and `close()` from inside the warning handler (no deadlock).
- **Wakeup**
  - Lost-wakeup: eager, spin 0 (always DEEP), 100 K × (log one → await `written == i+1`, 5 s deadline) with 0–2 µs jitter. Repeat with 4 producers.
  - Lazy threshold: interval 1 h, max 64 (threshold 16). The 16th message gets written, × 1 K.
- **Interrupts**
  - Interrupt the worker repeatedly: data is still written, and the thread settles in WAITING/TIMED_WAITING instead of spinning.
  - Sync: an interrupted caller's `log()` returns true, the line is written, and the interrupt flag is preserved.
- **I/O errors (fault sink + fake clock)**
  - A failed write warns with its cause and counts `lostIoError`. The ID is dropped during backoff, then reopens and writes after the clock advances.
  - EMFILE triggers eviction and retry. A deleted directory is recreated.
- **Sync concurrency:** 8 threads × 10 K messages over 50 IDs. No interleaving within a line, exact counts, per-(thread, ID) order.
- **Shutdown hook**
  - A child JVM (`ProcessHandle.current().info().command()` plus the test classpath, running a test-scope `ShutdownMain`) logs 10 K messages and exits without `close()` → 10 K lines.
  - Repeat with `System.exit(0)`.
- **Stats:** after close, `accepted == written + lostIoError + droppedInvalidId`. In async mode, invalid IDs are only detected by the worker, after the message was accepted.
- **Allocation (`@Tag("alloc")`, separate JVMs)**
  - Use `com.sun.management.ThreadMXBean`, guarded by `assumeTrue(isThreadAllocatedMemorySupported())`.
  - Pre-build 50 IDs and 64 messages. Warm up with 5 × 200 K `log()` calls plus `flush()`.
  - Measure 1 M calls in a static loop that returns a count, with the calibration overhead subtracted.
  - Assert ≤ 256 B on the producer and ≤ 4 KiB on the worker. Any per-call allocation would show up as ≥ 16 MB.
  - Variants: the drop path (worker gated, every call returns false) and the sync path.
- **Windows:** always close loggers (try-with-resources) so `@TempDir` cleanup works. No timing assertions finer than 100 ms.

---

## 8. Verification (end to end)
1. Run `.\gradlew.bat build`. It compiles with `-Xlint:all` and runs the unit, integration and stress tests, plus both allocation suites through `check`.
2. Run the stress suites repeatedly to shake out flakes, for example `.\gradlew.bat test --tests "*Ring*" --tests "*Close*" --tests "*Wakeup*" --rerun-tasks` five times.
3. The shutdown-hook child-JVM test is the real-process end-to-end check. It covers append mode, files left without `close()`, and draining at JVM exit.
4. Optional (M10): `.\gradlew.bat jmh` with `-prof gc`. Confirm `gc.alloc.rate.norm ≈ 0 B/op` for `log()`, and compare throughput and latency against the baselines.
