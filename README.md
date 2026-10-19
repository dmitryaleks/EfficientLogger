# EfficientLogger

Appends JSON strings, one per line, to one file per ID. By default a background thread does the
writing, so `log()` returns at once: it never blocks on I/O, and it allocates nothing. Java 17, no
runtime dependencies.

```java
try (EfficientLogger logger = EfficientLogger.builder(Path.of("logs"), "events-{id}.json", 100 * 1024).build()) {
    logger.log("42", "{\"event\":\"login\"}"); // appends a line to logs/events-42.json
}
```

Files are opened in append mode, so a restarted process carries on where the last one stopped.
The third argument is the most a file may grow to, in kilobytes (of 1024 bytes); here 100 MiB.
It is mandatory: a logger that writes one file per ID must have a ceiling on each of them (see
"Files have a size cap" under "What to expect").

## Two modes

- **Asynchronous (the default).** `log()` puts the message in a bounded in-memory queue and
  returns. A worker (or more, with `workerThreads(n)`) writes the queue out, on a thread from a
  pool that every logger in the JVM shares, so ten loggers don't mean ten threads (see
  "Allocation and threads"). When the queue is full, `log()` drops the message and returns false
  instead of waiting. You get one
  warning when dropping starts, one every 10 seconds while it goes on with the count so far, and
  one when the queue recovers, with the total; so a queue that keeps overflowing can't flood the
  handler, and isn't silent about it either. The disk-space mark and the rate cap below report
  the same way.
  A message by itself larger than `maxQueuedChars` is dropped as too large (counted separately in
  `stats()`), with its own one-time warning, not a "queue full" one.
- **Synchronous**, with `.synchronous()`. `log()` writes the message to the file before it
  returns, on the calling thread, with no queue and no extra threads. The file cap, the
  disk-space mark and the rate cap apply here too; with no worker, the caller that finds a
  free-space reading due takes it, and the rate is charged before the write, so a message that
  then fails to write stays charged.

## Options

`builder(directory, fileNamePattern, maxFileSizeKb)` takes the three things every logger needs;
everything else is set on the builder, and each setter checks its argument at once.

| Option | Default | Range and notes |
|---|---|---|
| `maxFileSizeKb` (builder argument) | mandatory | 1 to 2^63/1024 kilobytes of 1024 bytes. A message that would take its file past this is dropped and counted (`droppedFileFull`), with a warning per file (the first 100). The file is checked once a second and written to again once something has truncated it. |
| `minFreeDiskSpace(long shedBelow, long resumeAbove)` | off | Bytes. Drop every message while the log volume has less than `shedBelow` free, until it has at least `resumeAbove`; the gap is hysteresis. Read once a second, on a worker (sync mode: by a caller). Counted in `droppedDiskSpace` and `droppedCount()`, with a warning when shedding starts and one when it ends. |
| `maxCharsPerSecond(long)` | off | At least 1. Caps the logger's own output: a leaky bucket over message chars with a burst of one second's worth. Messages over it are dropped at once, counted in `droppedRateLimit` and `droppedCount()`, with a warning when dropping starts and one when it has stopped for a second. |
| `workerThreads(int)` | 1 | 1 to 16 workers, each with its own queue and files. Each ID always goes to the same worker. The workers are spread over the pool's threads, so this is also the most threads that can write for this logger at once. Async only. |
| `synchronous()` | off | Write on the calling thread. |
| `maxQueuedMessages(int)` | 65,536 | 1 to 2^23. Messages beyond the limit are dropped. Async only. |
| `maxQueuedChars(long)` | 16 Mi | 1 to 2^38−1, total `String.length()` of queued messages. A single longer message is always dropped. Async only. |
| `fileBufferBytes(int)` | 8192 | 64 B to 16 MiB. Async: one per file with unwritten data. Sync: one per stripe. |
| `maxOpenFiles(int)` | 1024 | At least 1. When reached, the least recently used file is closed, and reopened when next needed. |
| `maxFiles(int)` | unlimited | Most new files the logger will create. Beyond it, messages for IDs without a file yet are dropped and counted. Reopening an existing file doesn't count. Set it when IDs are untrusted. |
| `flushIntervalMillis(long)` | 100 | 0 to 60,000. The longest a message waits before it is handed to the OS. 0 writes after every batch. Async only. |
| `eagerWakeup(boolean)` | false | Wake the worker for every message instead of when the queue is a quarter full. Lower latency to disk, more CPU. Async only. |
| `closeTimeout(Duration)` | 10 s | How long `close()` waits for the queue to drain. |
| `registerShutdownHook(boolean)` | true | Close the logger at JVM exit, so queued messages are written. Async only. |
| `workerPool(WorkerPool)` | `WorkerPool.shared()` | The threads that write for this logger. `WorkerPool.create(n)` or `create(n, threadFactory)` gives a logger (or a group of them) threads of their own. Async only. |
| `warningHandler(WarningHandler)` | stderr | Receives the logger's own warnings. Must be thread-safe and must not block. |
| `lineSeparator(String)` | `"\n"` | `"\n"` or `"\r\n"`. |
| `filePermissions(Set<PosixFilePermission>)` | umask | POSIX permissions for new files, e.g. `rw-------`; the directory gets those plus execute. The umask can still remove bits, never add any. Existing files keep theirs. No effect off POSIX (e.g. Windows). |
| `normalizeIds()` | off | Canonicalize each ID (Unicode NFC, then lower-case) so IDs differing only by case or form share one file and worker. For case-insensitive file systems; costs an allocation per `log`. |
| `syncStripes(int)` | 16 | Power of two, 1 to 256: independent locks in sync mode. Sync only. |

Combining an async-only option with `synchronous()`, or `syncStripes` without it, throws
`IllegalArgumentException` at `build()`.

### The guards at a glance

Five limits bound what a logger can cost the machine. Each is independent, each counts what it
drops under its own name in `stats()`, and each reports through the warning handler: one warning
when dropping starts, one every 10 seconds while it goes on, and one with the total when it ends.

| Guard | Bounds | When it is hit | Counted in | Recovers |
|---|---|---|---|---|
| `maxFileSizeKb` (mandatory) | one file's size | the message is dropped and the file closed | `droppedFileFull` | when something truncates or renames the file away |
| `maxFiles` | files created in this run | messages for IDs without a file yet are dropped | `droppedInvalidId` | never within the run |
| `minFreeDiskSpace` | free space on the log volume | every message is dropped | `droppedDiskSpace` | when free space reaches the resume mark |
| `maxCharsPerSecond` | this logger's own output | messages over the cap are dropped | `droppedRateLimit` | as soon as the rate falls |
| `maxQueuedMessages`, `maxQueuedChars` | memory held by the queue | messages are dropped until the queue is half empty | `droppedQueueFull`, `droppedTooLarge` | as the worker drains |

A logger fed from untrusted input might take all of them:

```java
EfficientLogger logger = EfficientLogger.builder(Path.of("logs"), "events-{id}.json", 100 * 1024) // 100 MiB per file
        .maxFiles(10_000)                                   // at most 10,000 files, however many IDs arrive
        .minFreeDiskSpace(2L << 30, 4L << 30)               // stop below 2 GiB free, resume above 4 GiB
        .maxCharsPerSecond(5_000_000)                       // about 5 MB/s of JSON from this logger
        .build();
```

Two things to know when choosing them:

- **They are per logger, not per process.** A hundred loggers at 1,000 chars/s are 100,000 chars/s
  of output between them, and each one sheds on its own reading of the volume (the reading itself is
  shared). For one budget across an application, route its messages through one logger, or divide
  the budget between loggers.
- **A message is counted once, under the first test it fails.** `log()` tests in this order: the
  ID too long for a file name; the message too large for the queue; the volume low; the queue full,
  or the logger closed; the rate cap. So `droppedRateLimit` stays at zero while the queue is
  overflowing, a shed message is never charged to the rate, and after `close()` everything is
  `rejectedClosed` whatever else is wrong with it. A file at its cap is found later, by the worker,
  and counted in `droppedFileFull`.

## What to expect

- **Order.** In each file, lines appear in the order of their `log()` calls. There is no order
  across files.
- **Whole lines.** Every OS write carries whole lines (a message larger than the buffer is the
  one exception), so a crash can't leave half a line followed by the next one. If a file ends
  mid-line when it is opened, for example after a crash, a line separator is written first.
- **One message, one line.** A raw CR or LF in a message is written as a space. Other control
  characters (except tab), U+2028 and U+2029 are written as `\u` escapes. So a message can't
  forge extra lines or send control codes to a terminal. Valid JSON means the same afterwards: it
  can have raw line breaks only between tokens, and inside strings an escape means the same as
  the character. The JSON isn't otherwise checked.
- **`flush()`** waits until everything logged before it has been handed to the OS. It is not
  an fsync. In sync mode it returns at once.
- **`close()`** stops accepting messages, writes everything already accepted, and closes the
  files. If it times out, it warns, and the rest is still written in the background. Called from
  a warning handler, it starts closing and returns false at once. After `close()` returns true,
  the lines on disk are exactly the `log()` calls that returned true, less any that `stats()`
  counts as lost to an invalid ID or an I/O error (in async mode those are found after `log()`
  has returned). One thing can stop the queue from draining: a `log()` call cut off by an error
  such as `StackOverflowError` between being admitted and queueing its message, or stalled there.
  Once the queue has made no progress for 2 seconds, the workers give up on what is left, warn,
  and close the files; `close()` then returns false, and the messages left behind are counted in
  `stats()` as `queuedMessages` and, for those that had reached a queue, `abandoned`.
- **Files have a size cap.** A file never grows past `maxFileSizeKb`. A message that would take
  it past the cap is dropped and counted in `stats().droppedFileFull()`, with one warning per file
  (for the first 100 files); what was already buffered for the file is written out, and the file is
  closed. Once a second, while messages for it keep arriving, the logger checks whether the file
  has shrunk, and if something has truncated it (logrotate's `copytruncate`, or you) it writes to
  it again. The size is known exactly, as long as this logger is the file's only writer: the file's
  size when opened plus what the logger has written (another writer's appends are seen when the
  file is next opened). A message larger than the buffer is measured before any of it is written,
  so a file is never left with part of a message.
- **Low disk space sheds messages, if you ask.** With `minFreeDiskSpace(shedBelow, resumeAbove)`,
  the logger reads the log volume's free space once a second (one system call, on a worker thread;
  in sync mode on whichever caller finds the reading due; loggers on the same volume share one
  reading) and drops every message while it is below `shedBelow`, until it is back above
  `resumeAbove`. The gap between the two keeps a volume hovering around the mark from switching
  the logger on and off. Shed messages are counted in `stats().droppedDiskSpace()`, with one
  warning when shedding starts (with the reading), one every 10 seconds while it goes on, and one
  with the total when it ends. If the volume can't be read, nothing is shed and that is warned
  about once. Like a write, the reading can stall on a hung network volume, and it holds up the
  thread that takes it. A worker that has gone to sleep is woken by one dropping caller a second
  while a reading is overdue, so shedding ends even on an otherwise idle logger.
- **An output rate cap, if you ask.** `maxCharsPerSecond(n)` bounds what the logger accepts, as a
  leaky bucket over the chars of accepted messages with a burst of one second's worth: a burst of
  up to that goes through, and over any longer period the accepted messages average no more than
  the cap. It is one CAS per `log()` call, exact under contention, and it needs no system calls,
  so unlike the disk-space mark it can't be fooled by other programs writing to the volume. A
  message larger than the burst is still accepted when the logger is under the cap, and then
  holds the following messages back until its share of time has passed (at most a day). In async
  mode a message is charged only once the queue has taken it, so one the queue drops costs
  nothing. Drops are counted in `stats().droppedRateLimit()`, with a warning when they start, one
  every 10 seconds while they go on, and one with the total once they have stopped for a second.
- **IDs must make portable, safe file names.** The whole name must fit in 255 characters and
  bytes; `log()` rejects a longer ID at once, before queueing it. The name can't:
  - be empty, `.` or `..`, or a Windows device name such as `CON` or `NUL`
  - contain `/ \ : * ? " < > | ~`, control characters, invisible formatting characters (such
    as bidi overrides and zero-width spaces or joiners), line separators or unpaired surrogates
  - start with `-`, which a shell command run on `*` would take as an option
  - end in a dot or space

  `~` is excluded because Windows 8.3 short names contain one, so an ID such as `VERYLO~1` could
  otherwise write into another ID's file. Messages for invalid IDs are dropped and counted, with
  a warning per ID for the first 100.
- **Case- and Unicode-insensitive file systems merge IDs.** On Windows (NTFS) and macOS (APFS),
  IDs that differ only by case, and on APFS also by Unicode form, name the same file. Because the
  logger routes by the ID as written, two such IDs can go to different workers and interleave in
  that one file — a cross-tenant disclosure if the IDs are tenants. Lines then have no defined
  order, and a message larger than the buffer can be split around the other ID's lines. Turn on
  `normalizeIds()` (or canonicalize IDs yourself) on these systems, so such IDs map to one file
  and one worker; be aware it also merges IDs that differ only by case.
- **Use IDs from a bounded set.** Each distinct ID is a separate file, so IDs taken straight from
  untrusted input (a user ID, a header) let a caller fill the disk or the directory with files, and
  once more IDs are active than `maxOpenFiles` the logger opens and closes a file per message, which
  is far slower. Map untrusted values to a bounded set of IDs, and set `maxFiles` as a backstop.
  The worker (or, in sync mode, the lock) for an ID is picked by a hash of its characters keyed
  with a per-logger random value, so untrusted IDs can't be chosen to pile onto one worker or
  lock.
- **I/O errors** don't throw. The messages for that file are dropped for one second, then it is
  retried. A missing directory is created again. Error warnings are limited to one every
  10 seconds, and the number held back is reported at close.
- **Warnings** go to the `WarningHandler`, never to the caller as exceptions. A handler may call
  `log()`, `flush()` or `close()`. It must be thread-safe: with several workers, or several threads
  logging in sync mode, it is called from more than one thread at once.
- **`stats()`** returns counters: accepted, written, dropped (queue full, too large, invalid ID,
  file at its cap, low disk space, over the rate cap), lost to I/O errors, abandoned at close,
  rejected after close, queued, and open files. Once a logger is closed,
  `accepted == written + droppedInvalidId + droppedFileFull + lostIoError + abandoned`, and
  `abandoned` is 0 unless `close()` gave up as described above. `droppedCount()` is what `log()`
  dropped at once for want of room: queue full, too large, low disk space and the rate cap.

## Allocation and threads

`log()` allocates nothing on the Java heap, whether it queues a message, drops one, writes one
(sync mode) or is called after close, with or without the rate cap and the disk-space mark, and
neither does the async worker. `AllocationTest` checks this by counting each thread's allocation
over millions of calls, once normally and once with escape analysis off, so the JIT can't hide an
allocation. The exceptions are two system calls the JDK answers with a small object: reading free
space for `minFreeDiskSpace`, once a second per volume on the thread that takes the reading (a
worker, or in sync mode a caller), and re-reading the size of a file that has reached
`maxFileSizeKb`, once a second per such file while messages for it keep arriving. Each is a few
dozen bytes.

What the caller does is up to the caller: building an ID with `"user-" + n` for each call
allocates, so keep IDs around when you can.

`log()` takes no locks in async mode: a keyed hash of the ID to pick its worker (about a
nanosecond per character), one CAS on the queue limits, one atomic add to claim a slot, two
reference stores, and a volatile read. It wakes the worker only when the queue is a
quarter full (or for every message, with `eagerWakeup`). A slow trickle of messages is therefore
written in batches, each message waiting at most one flush interval.

**The threads are shared.** Workers don't own threads: they run on a `WorkerPool`, and by default
every logger in the JVM uses the same one, `WorkerPool.shared()`, which has at most 4 threads (or
fewer on a machine with fewer processors; the system property
`efficientlogger.sharedWorkerThreads` sets it, from 1 to 256). Each logger's workers are spread
over the pool's threads, and a thread serves every worker given to it in turn, sleeping only when
none of them has anything to do; a worker's queue is drained whenever its thread passes by, so
sharing adds no latency beyond the other loggers' batches. Threads start when a logger first needs
them and exit when the last logger they serve closes. So an application with 50 loggers has at
most 4 logging threads, and one logger with `workerThreads(4)` gets 4 threads on a shared pool with
room for them, but only as many as the pool has. A logger whose handler blocks, or whose disk
stalls, holds up the other loggers on its thread for that long, and ties their fates to it: while
it stalls, `close()` on the others returns false at its timeout, and at JVM exit the shutdown hook
waits at most the longest `closeTimeout` for all loggers at once, so one stalled logger can cost
every logger on its thread the messages still queued. Give such a logger a pool of its own with
`workerPool(WorkerPool.create(n))`; a logger whose messages must survive exit is the one that
most needs its own. `create` also takes a `ThreadFactory`, for example to name the threads; it is
called while a logger is being built, under the pool's lock, so it must return promptly and not
block. The default threads are daemons named `efficient-logger-<pool>-worker-<i>`. One shutdown
hook, registered while any logger wants one, closes every open logger at JVM exit, all draining
at once, so it waits at most the longest `closeTimeout`, not the sum.

## Performance

`LoggerBenchmark` (JMH) compares five ways of appending JSON lines to one file per ID:

- EfficientLogger, async with 1 worker (the default) and with 4 workers (on the shared pool,
  whose 4 threads they fill on this machine)
- EfficientLogger, sync
- a `BufferedWriter` per file, locked while a line is written
- log4j2 async loggers (LMAX Disruptor, garbage-free), with a logger and an appender per ID

Each message is about 85 characters, spread over 50 IDs. Every logger writes to the OS null
device (`NUL` or `/dev/null`), so the write calls are real but no disk is involved: this
measures the loggers, not the disk. The test machine is an 8-core, 16-thread AMD Ryzen AI 7
PRO 350, running Windows 11 with JDK 17.0.12, and Ubuntu 24.04 under WSL 2 with
Temurin 17.0.20.1. JMH is 1.37. Each figure comes from one fork, with 2 × 1 s of warm-up and
3 × 1 s of measurement, so read the differences in orders of magnitude, not in percent.

All figures were measured on 2026-10-19 against the current code, after the security audit, the
code review and the move to a shared worker pool, so they include the line-break and ID checks
described under "What to expect", the keyed routing hash, and the pool thread's loop over its
workers (which cost nothing measurable: the figures before and after the pool are within each
other's noise). Measured back to back when they were added, the checks cost one
worker about 7% of its peak throughput, about 10 ns per 85-character message, which is within
the run-to-run noise of the tables below; sync mode was unchanged. The keyed routing hash costs
the caller about 0.7 ns per ID character: with these 7-character IDs, one thread calling flat
out went from about 115 to 135 million calls a second to 75 to 80 million (mostly drops), while
the paced latency and the written rate, which the worker bounds, were unchanged.

**Throughput, threads calling `log()` flat out.** The figures are messages written per second.
The async EfficientLogger drops messages instead of making callers wait, so for it the calls
per second are shown too.

| Logger | OS | 1 thread | 4 threads | 8 threads | Heap per message |
|---|---|---|---|---|---|
| EfficientLogger async, 1 worker | Windows | 11.6 M (75.0 M calls) | 10.3 M (53.0 M calls) | 8.6 M (51.3 M calls) | 0 |
| | Linux | 17.6 M (75.2 M calls) | 14.5 M (54.3 M calls) | 11.4 M (49.1 M calls) | 0 |
| EfficientLogger async, 4 workers | Windows | 38.7 M | 31.8 M | 23.0 M (39.6 M calls) | 0 |
| | Linux | 37.2 M | 30.4 M | 31.5 M | 0 |
| EfficientLogger sync | Windows | 3.1 M | 2.6 M | 2.9 M | 0 |
| | Linux | 4.8 M | 10.1 M | 3.7 M | 0 |
| `BufferedWriter` per file | Windows | 27.5 M | 34.4 M | 33.7 M | 0.6 B |
| | Linux | 26.8 M | 54.8 M | 46.9 M | 0.6 B |
| log4j2 async loggers | Windows | 0.40 M | 0.54 M | 0.51 M | 0 |
| | Linux | 4.5 M | 4.8 M | 4.7 M | 0 |

**Latency at a steady rate.** Each thread does about 0.7 µs of other work between calls, so
it logs about 1.3 million messages a second. The figures are microseconds per call, including
that work; the first row is the work alone.

On Windows:

| Logger | Threads | p50 | p99 | p99.9 | p99.99 |
|---|---|---|---|---|---|
| (the work alone) | 1 | 0.7 | 0.8 | 3.4 | 30.8 |
| EfficientLogger async | 1 | 0.7 | 0.8 | 2.9 | 20.0 |
| EfficientLogger sync | 1 | 1.0 | 1.1 | 3.5 | 9.3 |
| `BufferedWriter` per file | 1 | 0.7 | 1.2 | 3.5 | 23.5 |
| log4j2 async loggers | 1 | 0.8 | 2.6 | 10.3 | 44.0 |
| (the work alone) | 4 | 0.7 | 0.8 | 3.0 | 24.1 |
| EfficientLogger async | 4 | 0.8 | 1.1 | 3.4 | 20.5 |
| EfficientLogger async, 4 workers | 4 | 0.8 | 1.2 | 3.8 | 19.6 |
| EfficientLogger sync | 4 | 1.1 | 24.8 | 62.9 | 145.9 |
| `BufferedWriter` per file | 4 | 0.8 | 2.3 | 12.4 | 49.2 |
| log4j2 async loggers | 4 | 0.9 | 3.1 | 1,036 | 15,286 |

On Linux (WSL 2):

| Logger | Threads | p50 | p99 | p99.9 | p99.99 |
|---|---|---|---|---|---|
| (the work alone) | 1 | 0.7 | 0.8 | 11.8 | 35.4 |
| EfficientLogger async | 1 | 0.8 | 0.8 | 16.5 | 50.1 |
| EfficientLogger sync | 1 | 0.9 | 1.0 | 13.4 | 38.6 |
| `BufferedWriter` per file | 1 | 0.8 | 2.3 | 13.6 | 58.5 |
| log4j2 async loggers | 1 | 0.9 | 3.6 | 44.0 | 75.7 |
| (the work alone) | 4 | 0.7 | 1.0 | 14.0 | 39.8 |
| EfficientLogger async | 4 | 0.8 | 1.3 | 15.2 | 35.9 |
| EfficientLogger async, 4 workers | 4 | 0.8 | 1.3 | 21.2 | 40.6 |
| EfficientLogger sync | 4 | 1.0 | 2.0 | 59.8 | 155.7 |
| `BufferedWriter` per file | 4 | 0.8 | 1.6 | 14.7 | 62.3 |
| log4j2 async loggers | 4 | 1.0 | 3.1 | 33.8 | 223.3 |

The latencies are rounded to 0.1 µs, the tick of Windows' clock. Under WSL 2, everything past
p99 is dominated by the virtual machine: the work alone already reaches 12 to 14 µs at p99.9,
so only differences well above that mean anything there.

What this shows:

- **The async logger costs its callers almost nothing.** At a steady rate, it is within 0.3 µs
  of doing no logging at all at p99, on both systems, and it never waits for I/O. Dropping a
  message costs even less than queueing one.
- **One worker writes 7 to 12 million messages a second** on Windows and 11 to 18 million on
  Linux, roughly 0.6 to 1.5 GB/s of JSON. Beyond that it drops, and warns, rather than slowing
  the callers down. With 4 workers, on 4 pool threads, it writes 23 to 39 million a second.
- **The `BufferedWriter` approach has high throughput,** because each caller formats its own
  lines and they wait in an 8 KB buffer in memory. But nothing reaches the OS until that buffer
  fills, so a quiet file can hold lines indefinitely, and they are lost on a crash. A caller
  that fills a buffer waits for the write, which shows in its p99.99. On a real disk those
  waits are much longer.
- **Sync mode makes one OS write call per message,** so each line is in the OS by the time
  `log()` returns. That limits it to about 3 million messages a second on Windows, and 4 to
  10 million on Linux, where system calls are cheaper.
- **log4j2 does poorly on Windows, most likely because of Windows itself.** When its ring
  buffer is full, its callers back off with `LockSupport.parkNanos(1)`, which Windows turns
  into a sleep of at least a millisecond (often about 15 ms). That fits its low flat-out
  throughput there and its millisecond stalls at 4 × 1.3 million messages a second. On Linux,
  where that backoff is far shorter, it writes ten times as much, about 4.5 million messages a
  second, with no such stalls. That is still a quarter to a third of one EfficientLogger
  worker, and it adds about 2 to 3 µs per call at p99.

To run it with real files instead, add `-p output=disk`. The numbers then depend mostly on
the disk.

**Many quiet loggers.** `ManyLoggersBenchmark` (`gradlew manyLoggers`) is the other shape of
load: 100 loggers, one worker each, every one writing 5 traces a second to its own file on the
shared pool, for one minute, through the public API, with real files. It then closes every logger
and prints each one's `stats()`. On the Windows machine above, on 2026-10-19: all 30,000 traces
accepted, written and on disk in 100 files, nothing dropped or lost, no warnings; the 100 loggers
ran on the pool's 4 threads; `close()` of all 100 took 11 ms; `log()` took 3.1 µs at p50, 59 µs
at p99 and 2.5 ms at worst. The report lists the slowest calls with their JVM uptime, so they can
be matched against `-Xlog:safepoint,gc,class+load` (pass it with `-Pmany.jvmArgs`); doing so
shows the worst call is always the first `log()` in the JVM, which loads the routing-hash class
and links the queue's `VarHandle` call sites in the interpreter (1.7 to 2.5 ms, once per JVM),
the next ones (1 to 1.6 ms, a couple per minute) coincide with nothing in the JVM's log and are
the OS taking the core from the producer, and a cluster of 0.35 ms calls on every producer at
once is the JVM's periodic cleanup safepoint. None involves the queue. Its options are
`-Pmany.loggers`, `-Pmany.rate`, `-Pmany.seconds`, `-Pmany.producers`, `-Pmany.dir` and
`-Pmany.jvmArgs`, plus the caps: `-Pmany.fileKb` (each file's `maxFileSizeKb`, 1 GiB by
default), `-Pmany.minFreeMb` (turns on `minFreeDiskSpace` with that floor and twice it to
resume) and `-Pmany.charsPerSecond` (turns on `maxCharsPerSecond`, per logger).

With all three caps on but none binding (1 MiB files, a 1 GiB free-space floor, 1,000 chars/s
per logger), the same minute wrote all 30,000 traces with nothing dropped; `log()` took 4.0 µs
at p50 and 70 µs at p99, within the run-to-run noise of the uncapped figures, so the rate cap's
CAS and the disk mark's volatile read cost the caller nothing measurable. With the rate cap set
to bite, 200 chars/s per logger against traces of about 81 chars, every one of the 100 loggers
accepted exactly 148 of its 300 traces and dropped 152, all counted in `droppedRateLimit`, with
one "rate limit reached" warning per logger at the start and one with the count at close
(the drops never paused for the second that would have ended the episode earlier); 14,800 lines
on disk, every trace accounted for, and the `log()` latency unchanged.

## On Linux

- **Rotate with `copytruncate`.** The logger keeps each file open, so if logrotate renames a
  file, the logger goes on writing to the renamed one, and the new file stays empty until the
  logger reopens it, normally at the next restart. With `copytruncate`, writing carries on in
  the original file without a gap, since it is open for appending. Lines written between
  logrotate's copy and its truncate are lost, as with any program that logs to a file. A file
  that had reached `maxFileSizeKb` is noticed to have shrunk within a second and written to
  again, so set the cap above the size at which logrotate rotates, and the cap only ever bites
  if rotation stops. A full file holds no open handle, so renaming it away also works: the logger
  finds the path empty at its next check and starts a fresh file.
- **Keep the log directory private to the service's user.** The logger refuses to write to
  anything that isn't a regular file, so a symlink, FIFO or device left at a file's path by
  another user can't redirect the writes or block a thread; those messages are dropped and
  retried like any I/O error. At build it also warns if the directory, or a parent, is writable
  by its group or by other users without the sticky bit. A regular file could still be swapped for a symlink in
  the instant between the check and the open, so a private directory is the real safeguard.
- **`filePermissions(...)` restricts new files.** Without it, new files and the directory follow
  the process umask, so they are usually world-readable. Pass POSIX permissions such as
  `rw-------` to keep logs private to the owner; the directory then gets `rwx------` (less
  anything the umask removes). Existing files keep their own permissions.
- **Deleting a file or directory the logger has open** makes later lines to that file
  disappear until it is reopened, while `stats()` still counts them as written. (Windows
  doesn't allow deleting open files.)
- **File names follow the Windows rules on Linux too,** so an ID such as `host:8080` is
  dropped, as described under "What to expect". A log directory can then be copied to any OS.
- **IDs outside ASCII need a UTF-8 locale.** Java encodes file names in the locale's charset.
  With no `LANG` set, as in an empty environment or some containers, it can't create
  `café.json`, so that ID's messages are dropped as invalid. A systemd service gets `LANG` from
  `/etc/locale.conf`. Message contents are written as UTF-8 whatever the locale.
- **Open-file limits rarely matter.** Java raises the soft limit to the hard limit at startup.
  If even that is too low, the logger closes its least recently used files, warns, and retries,
  so nothing is lost.
- **`systemctl stop`** sends SIGTERM, which runs the shutdown hook, so everything queued is
  written. `kill -9` and the out-of-memory killer lose what is still queued.
- The library uses only `java.base`, so `java-17-openjdk-headless` is enough to run it.

## Building and testing

```
.\gradlew.bat build      # compile, then run the tests and the allocation tests (Windows)
./gradlew build          # the same elsewhere
```

`build` runs the unit, integration and stress tests, then the allocation tests in two separate
JVMs (`allocationTest` and `allocationTestNoEA`). The tests also check the benchmark's null-device
sink against the library's file checks, so `test` compiles the benchmark sources too. It passes on
Windows 11 and on Ubuntu 24.04 (WSL 2). It needs a JDK 17 installed locally: the build won't download one (see below), and the
Gradle wrapper and every dependency are pinned by checksum (`gradle/wrapper/gradle-wrapper.properties`
and `gradle/verification-metadata.xml`), so a tampered download fails the build. The library is
`build/libs/EfficientLogger-1.0-SNAPSHOT.jar`, with no dependencies. Benchmarks run separately:

```
.\gradlew.bat jmh                                         # all benchmarks; results in build/results/jmh
.\gradlew.bat jmhJar                                      # or build the jar and pass JMH options:
java -jar build/libs/EfficientLogger-1.0-SNAPSHOT-jmh.jar LoggerBenchmark -prof gc -p output=disk
.\gradlew.bat manyLoggers                                 # the one-minute soak: 100 loggers at 5 traces/s each
.\gradlew.bat manyLoggers -Pmany.loggers=500 -Pmany.rate=1 -Pmany.seconds=30   # its options (also producers, dir)
```

The design, the reasoning behind it and the milestones are in [PLAN.md](PLAN.md).

## Not included

Rotation (on Linux, see logrotate's `copytruncate` above), compression, an fsync policy, and
log4j integration. Other limits:

- Running out of file descriptors is recognized by the English error message, on Unix and
  Windows. With a localized message, the file is retried a second later like any other error.
- The free-space floor is in bytes, not a percentage of the volume; compute one from
  `Files.getFileStore(dir).getTotalSpace()` if that is what you want.
- The rate cap counts chars, not bytes. ASCII JSON is one byte per char, so the two agree; text
  outside ASCII takes up to three bytes per char on disk, and a message's escapes six, so the
  bytes written can exceed the cap by that much.
- The caps are per logger. There is no process-wide budget for output or files.
