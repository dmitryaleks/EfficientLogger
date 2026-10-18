# EfficientLogger

Appends JSON strings, one per line, to one file per ID. By default a background thread does the
writing, so `log()` returns at once: it never blocks on I/O, and it allocates nothing. Java 17, no
runtime dependencies.

```java
try (EfficientLogger logger = EfficientLogger.builder(Path.of("logs"), "events-{id}.json").build()) {
    logger.log("42", "{\"event\":\"login\"}"); // appends a line to logs/events-42.json
}
```

Files are opened in append mode, so a restarted process carries on where the last one stopped.

## Two modes

- **Asynchronous (the default).** `log()` puts the message in a bounded in-memory queue and
  returns. One worker thread (or more, with `workerThreads(n)`) writes the queue out. When the
  queue is full, `log()` drops the message and returns false instead of waiting. You get one
  warning when dropping starts and one when the queue recovers, with the count.
- **Synchronous**, with `.synchronous()`. `log()` writes the message to the file before it
  returns, on the calling thread, with no queue and no extra threads.

## Options

All set on the builder; each setter checks its argument at once.

| Option | Default | Range and notes |
|---|---|---|
| `workerThreads(int)` | 1 | 1 to 16. Each ID always goes to the same worker. Async only. |
| `synchronous()` | off | Write on the calling thread. |
| `maxQueuedMessages(int)` | 65,536 | 1 to 2^23. Messages beyond the limit are dropped. Async only. |
| `maxQueuedChars(long)` | 16 Mi | 1 to 2^38−1, total `String.length()` of queued messages. A single longer message is always dropped. Async only. |
| `fileBufferBytes(int)` | 8192 | 64 B to 16 MiB. Async: one per file with unwritten data. Sync: one per stripe. |
| `maxOpenFiles(int)` | 1024 | At least 1. When reached, the least recently used file is closed, and reopened when next needed. |
| `flushIntervalMillis(long)` | 100 | 0 to 60,000. The longest a message waits before it is handed to the OS. 0 writes after every batch. Async only. |
| `eagerWakeup(boolean)` | false | Wake the worker for every message instead of when the queue is a quarter full. Lower latency to disk, more CPU. Async only. |
| `closeTimeout(Duration)` | 10 s | How long `close()` waits for the queue to drain. |
| `registerShutdownHook(boolean)` | true | Close the logger at JVM exit, so queued messages are written. Async only. |
| `threadFactory(ThreadFactory)` | daemon threads | Default names: `efficient-logger-<n>-worker-<i>`. Async only. |
| `warningHandler(WarningHandler)` | stderr | Receives the logger's own warnings. |
| `lineSeparator(String)` | `"\n"` | `"\n"` or `"\r\n"`. |
| `syncStripes(int)` | 16 | Power of two, 1 to 256: independent locks in sync mode. Sync only. |

Combining an async-only option with `synchronous()`, or `syncStripes` without it, throws
`IllegalArgumentException` at `build()`.

## What to expect

- **Order.** In each file, lines appear in the order of their `log()` calls. There is no order
  across files.
- **Whole lines.** Every OS write carries whole lines (a message larger than the buffer is the
  one exception), so a crash can't leave half a line followed by the next one. If a file ends
  mid-line when it is opened, for example after a crash, a line separator is written first.
- **JSON is not checked.** Pass single-line JSON; a newline inside a message would split it.
- **`flush()`** waits until everything logged before it has been handed to the OS. It is not
  an fsync. In sync mode it returns at once.
- **`close()`** stops accepting messages, writes everything already accepted, and closes the
  files. If it times out, it warns, and the rest is still written in the background. Called from
  a warning handler, it starts closing and returns false at once. After `close()` returns true,
  the lines on disk are exactly the `log()` calls that returned true, less any that `stats()`
  counts as lost to an invalid ID or an I/O error (in async mode those are found after `log()`
  has returned).
- **IDs must make portable file names.** They can't be empty, contain `/ \ : * ? " < > |`,
  control characters or unpaired surrogates, end in a dot or space, be `.` or `..`, or be a Windows device name such as
  `CON` or `NUL`, and the whole name must fit in 255 characters and bytes. Messages for other IDs
  are dropped and counted, with a warning per ID for the first 100. On a case-insensitive file
  system, `Foo` and `foo` share a file; their lines stay whole, but their relative order isn't
  guaranteed.
- **I/O errors** don't throw. The messages for that file are dropped for one second, then it is
  retried. A missing directory is created again. Error warnings are limited to one every
  10 seconds, and the number held back is reported at close.
- **Warnings** go to the `WarningHandler`, never to the caller as exceptions. A handler may call
  `log()`, `flush()` or `close()`.
- **`stats()`** returns counters: accepted, written, dropped (queue full, too large, invalid ID),
  lost to I/O errors, rejected after close, queued, and open files. Once a logger is closed,
  `accepted == written + droppedInvalidId + lostIoError`.

## Allocation and threads

`log()` allocates nothing on the Java heap, whether it queues a message, drops one, writes one
(sync mode) or is called after close, and neither does the async worker. `AllocationTest` checks
this by counting each thread's allocation over millions of calls, once normally and once with
escape analysis off, so the JIT can't hide an allocation.

What the caller does is up to the caller: building an ID with `"user-" + n` for each call
allocates, so keep IDs around when you can.

`log()` takes no locks in async mode: one CAS on the queue limits, one atomic add to claim a
slot, two reference stores, and a volatile read. It wakes the worker only when the queue is a
quarter full (or for every message, with `eagerWakeup`). A slow trickle of messages is therefore
written in batches, each message waiting at most one flush interval.

## Performance

`LoggerBenchmark` (JMH) compares five ways of appending JSON lines to one file per ID:

- EfficientLogger, async with 1 worker (the default) and with 4 workers
- EfficientLogger, sync
- a `BufferedWriter` per file, locked while a line is written
- log4j2 async loggers (LMAX Disruptor, garbage-free), with a logger and an appender per ID

Each message is about 85 characters, spread over 50 IDs. Every logger writes to the OS null
device, so the write calls are real but no disk is involved: this measures the loggers, not the
disk. The test machine ran Windows 11 on an 8-core, 16-thread AMD Ryzen AI 7 PRO 350, with
JDK 17.0.12 and JMH 1.37. Each figure comes from one fork, with 2 × 1 s of warm-up and
3 × 1 s of measurement, so read the differences in orders of magnitude, not in percent.

**Throughput, threads calling `log()` flat out.** The figures are messages written per second.
The async EfficientLogger drops messages instead of making callers wait, so for it the calls
per second are shown too.

| Logger | 1 thread | 4 threads | 8 threads | Heap per message |
|---|---|---|---|---|
| EfficientLogger async, 1 worker | 12.2 M (134.6 M calls) | 10.3 M (72.3 M calls) | 9.2 M (60.0 M calls) | 0 |
| EfficientLogger async, 4 workers | 46.1 M | 27.2 M (28.0 M calls) | 35.5 M (35.6 M calls) | 0 |
| EfficientLogger sync | 3.2 M | 3.0 M | 3.5 M | 0 |
| `BufferedWriter` per file | 27.1 M | 35.1 M | 35.2 M | 0.6 B |
| log4j2 async loggers | 0.39 M | 0.52 M | 0.51 M | 0 |

**Latency at a steady rate.** Each thread does about 0.7 µs of other work between calls, so
it logs about 1.3 million messages a second. The figures are microseconds per call, including
that work; the first row is the work alone.

| Logger | Threads | p50 | p99 | p99.9 | p99.99 |
|---|---|---|---|---|---|
| (the work alone) | 1 | 0.7 | 0.8 | 2.9 | 18.7 |
| EfficientLogger async | 1 | 0.7 | 0.8 | 3.6 | 20.8 |
| EfficientLogger sync | 1 | 1.0 | 1.2 | 3.9 | 21.7 |
| `BufferedWriter` per file | 1 | 0.7 | 1.3 | 5.0 | 39.4 |
| log4j2 async loggers | 1 | 0.8 | 2.5 | 6.4 | 36.5 |
| (the work alone) | 4 | 0.7 | 0.8 | 3.2 | 18.6 |
| EfficientLogger async | 4 | 0.8 | 1.1 | 4.9 | 22.1 |
| EfficientLogger async, 4 workers | 4 | 0.8 | 0.9 | 3.8 | 18.9 |
| EfficientLogger sync | 4 | 1.1 | 9.7 | 45.4 | 91.8 |
| `BufferedWriter` per file | 4 | 0.8 | 2.3 | 8.0 | 93.8 |
| log4j2 async loggers | 4 | 0.9 | 3.1 | 982 | 15,379 |

Windows' clock ticks every 0.1 µs, so the latencies are rounded to that.

What this shows:

- **The async logger costs its callers almost nothing.** At a steady rate, it is within 0.3 µs
  of doing no logging at all at p99, and it never waits for I/O. Dropping a message costs even
  less than queueing one.
- **One worker writes about 10 million messages a second** here, about 0.9 GB/s of JSON.
  Beyond that it drops, and warns, rather than slowing the callers down. With 4 workers it
  writes 27 to 46 million a second.
- **The `BufferedWriter` approach has high throughput,** because each caller formats its own
  lines and they wait in an 8 KB buffer in memory. But nothing reaches the OS until that buffer
  fills, so a quiet file can hold lines indefinitely, and they are lost on a crash. A caller
  that fills a buffer waits for the write, which shows in its p99.99. On a real disk those
  waits are much longer.
- **Sync mode makes one OS write call per message,** so each line is in the OS by the time
  `log()` returns. That limits it to about 3 million messages a second.
- **log4j2 does poorly here, most likely because of Windows.** When its ring buffer is full,
  its callers back off with `LockSupport.parkNanos(1)`, which Windows turns into a sleep of at
  least a millisecond (often about 15 ms). That fits its low flat-out throughput and its
  millisecond stalls at 4 × 1.3 million messages a second. On Linux that backoff is far
  shorter, so expect better log4j2 numbers there; this benchmark has only been run on Windows.

To run it with real files instead, add `-p output=disk`. The numbers then depend mostly on
the disk.

## Building and testing

```
.\gradlew.bat build      # compile, then run the tests and the allocation tests (Windows)
./gradlew build          # the same elsewhere
```

`build` runs the unit, integration and stress tests, then the allocation tests in two separate
JVMs (`allocationTest` and `allocationTestNoEA`). The library is
`build/libs/EfficientLogger-1.0-SNAPSHOT.jar`, with no dependencies. Benchmarks run separately:

```
.\gradlew.bat jmh                                         # all benchmarks; results in build/results/jmh
.\gradlew.bat jmhJar                                      # or build the jar and pass JMH options:
java -jar build/libs/EfficientLogger-1.0-SNAPSHOT-jmh.jar LoggerBenchmark -prof gc -p output=disk
```

The design, the reasoning behind it and the milestones are in [PLAN.md](PLAN.md).

## Not included

Rotation, compression, an fsync policy, and log4j integration. Other limits:

- On Unix, deleting a file or directory the logger has open makes later writes to that file
  disappear until it is reopened, as with most loggers. Windows doesn't allow deleting open
  files.
- Running out of file descriptors is recognized by the English error message, on Unix and
  Windows. With a localized message, the file is retried a second later like any other error.
