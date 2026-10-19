# EfficientLogger security audit

| | |
|---|---|
| Date | 2026-10-18 |
| Code audited | commit `6bb8f6d` (master) |
| Scope | the library (`src/main`, 23 files, about 2,700 lines), the build (`build.gradle.kts`, `settings.gradle.kts`, Gradle wrapper), and the benchmark and test code where it could matter |
| Method | two independent full reads of the code, one by an adversarial reviewer, then reproduction of each finding where possible |
| Platforms tested | Windows 11 (JDK 17.0.12, NTFS) and Ubuntu 24.04 under WSL 2 (Temurin 17.0.20.1, ext4) |

## Summary

No finding lets a caller of `log()` escape the log directory or run code inside the logging
process. The path checks hold. The lock-free queue was stress-tested under heavy contention and
never lost, duplicated, reordered or misrouted a message.

The weaknesses sit at the edges, where untrusted data meets the file system, the JVM's failure
modes, and the people and tools that read the logs:

- **Messages are written as given.** A newline inside a message makes a second, forged log
  line, and terminal escape codes pass through (F1).
- **The logger trusts its directory.** Anyone who can write there can redirect a file through a
  symlink or hang the logger with a FIFO. Anyone who can create the directory first owns it (F2).
- **IDs are checked only to be file names, not for size or count.** Attacker-chosen IDs can
  create unlimited files, slow writing 16 to 1,000 times, and pin memory far beyond the queue's
  limit (F3, F4).
- **Some valid IDs are dangerous names.**
  - On Windows, an ID can be another file's 8.3 short name and write into it (F5).
  - With a pattern such as `{id}.json`, an ID can start with `-` and act as a command option in
    shell scripts, or name an unrelated file in the directory (F6).
- **One unlucky `StackOverflowError` inside `log()` can stop the async logger for good,** with
  a worker spinning at 100% CPU (F7).
- **Warnings can be flooded without any queue pressure.** With the default stderr handler and an
  undrained stderr, the flood stops the worker (F8).

Combined, these make a working attack. With the pattern `{id}.json`, two log calls carrying
attacker-chosen IDs and a message containing a newline made an administrator's
`tar cf backup.tar *` in the log directory run a shell command (F1 + F6).

All of these can be fixed without giving up the design goals. `log()` would still allocate
nothing and never block on I/O; the fixes are listed in [Recommendations](#recommendations).
Until then, applications can avoid most of the risk:
- Pass messages built by a JSON library.
- Don't use raw untrusted input as IDs.
- Keep the log directory private to the service's user.
- Use a pattern with a fixed prefix and a 4-letter extension, like the README's
  `events-{id}.json`, which rules out F5 and F6.

### Findings

Severities assume the finding's precondition holds; a deployment where it can't happen isn't
exposed. "Verified" means reproduced on the platforms above.

| # | Finding | Severity | Precondition | Verified |
|---|---|---|---|---|
| F1 | Line breaks and control characters in messages are written raw | Medium | Messages contain untrusted text not escaped by a JSON library | Yes |
| F2 | The log directory is trusted: symlinks, FIFOs and devices are opened, and its owner and mode are never checked | Medium (High if the process is more privileged than whoever can write the directory) | Someone else can write, or create, the log directory | Yes |
| F3 | Untrusted IDs can create unlimited files and force an open and close per message | Medium | IDs come from untrusted input | Yes |
| F4 | ID length isn't bounded or counted against the queue limit | Medium | IDs come from untrusted input | Yes |
| F5 | Windows: an ID can be another file's 8.3 short name and write into it | Medium | Windows volume with 8.3 names; pattern with no extension or one of 3 or fewer chars | Yes |
| F6 | With a pattern starting with `{id}`, IDs can act as command options or name unrelated files | Medium | Pattern starts with `{id}`; IDs come from untrusted input | Yes |
| F7 | A `StackOverflowError` inside `log()` can wedge the async logger permanently | Medium | `log()` called with almost no stack left, before the code is fully JIT-compiled | Yes |
| F8 | Warning floods: too-large messages start bogus "queue full" episodes, nothing rate-limits them, and a blocked stderr stalls the worker | Medium | Attacker-controlled message size or load; undrained stderr for the stall | Yes |
| F9 | Case-insensitive and Unicode-normalizing file systems merge IDs | Low | IDs differ only by case or normalization; Windows or macOS | Yes (Windows) |
| F10 | Files and directories get the default permissions (world-readable with umask 022) | Low | Other local users; logs hold personal data | Yes |
| F11 | Invisible and look-alike characters are allowed in IDs and printed raw in warnings | Low | IDs come from untrusted input | Yes |
| F12 | A record larger than the buffer is split, and another ID's line can land in the middle | Low | Aliased IDs (F5, F9), or another writer on the same file | Yes |
| F13 | If a worker thread dies, `flush()` and `close()` report success while messages pile up | Low | An `Error` escapes the worker's own error handling, e.g. out of memory | By reading |
| F14 | Build supply chain: Gradle 8.14 advisories, no wrapper checksum, no dependency verification | Low | Building in an untrusted network or CI | Yes (versions, checksums) |
| F15 | Benchmarks use log4j-core 2.24.3, which has two advisories | Info | None reachable | Yes (versions) |
| F16 | A timed-out close in the shutdown hook says messages "will be written", then the JVM exits | Info | Close times out at JVM exit | Yes |
| F17 | Package-private test hooks can be reached by code in the same package | Info | Hostile code already on the classpath | By reading |
| F18 | Crafted IDs can put all traffic on one worker or one sync lock | Info | IDs come from untrusted input | By reading |

### Status since the audit

Recommendations 1 and 2 have been implemented in the commit after this audit's. Each probe
below was re-run against the new jar.

| # | Status | What changed | Probe now |
|---|---|---|---|
| F1 | Fixed | A raw CR or LF in a message is written as a space. Other C0 controls except tab, C1 controls, U+2028 and U+2029 become `\uXXXX` escapes. | The forged line stays on the first line, and escape codes are written as text |
| F2 | Mostly fixed | Before writing, the logger refuses any path that isn't a regular file (a symlink, FIFO, device or directory), using a NOFOLLOW stat so it never opens or blocks on one; it is dropped and retried like any I/O error. At `build()`, a POSIX directory writable by its group or by other users without the sticky bit draws a warning (group-writable since the code review, D3). | A planted symlink and a directory are refused, the symlink's target is untouched, and a world-writable directory is flagged |
| F8 | Fixed | A too-large message is counted and warned about on its own (once), not fed into the queue-full episode logic. Overflow episode warnings are rate-limited to one every 10 s, folding the dropped counts into the next report. The `WarningHandler` contract now says it must not block, and rate-limiting means it is called at most occasionally. | 1,000,000 too-large messages produce 1 warning (was ~9,900); 20,000 overflow cycles produce 3 (was ~9,400) |
| F10 | Fixed | New `filePermissions(Set<PosixFilePermission>)` option creates new files with the given POSIX permissions and the directory with those plus execute, less whatever the umask removes (so never less private than asked; the chmod that made them exact was dropped by the code review, Q2, because it followed symlinks); existing files keep theirs. Off POSIX it is a no-op with a one-time warning. | A logger built with `rw-------` creates a `rw-------` file and a `rwx------` directory even under umask 022 |
| F12 | Mostly fixed | Gradle upgraded to 8.14.4 (past the repository-failover advisories), with `distributionSha256Sum` pinning the distribution and the wrapper jar refreshed to 8.14.4's genuine one. `gradle/verification-metadata.xml` pins the SHA-256 of every plugin and dependency, the Foojay toolchain resolver included. Toolchain auto-download is on again (2026-10-19), so a fresh checkout builds with no JDK 17 installed: the JDK comes from its vendor over HTTPS through the Foojay Disco API and Gradle checks its version, but its archive isn't checksum-pinned, since dependency verification doesn't cover toolchains; `gradle.properties` says how to turn the download off. | A build from an empty Gradle home re-downloads the distribution and every dependency and verifies them all; a tampered artifact fails it |
| F3 | Mostly fixed | New `maxFiles(int)` option caps how many new files the logger creates; beyond it, messages for IDs without a file are dropped and counted, with one warning. Reopening an existing file doesn't count, so a bounded ID set never trips it. The docs now tell callers to use IDs from a bounded set. | With `maxFiles(2)`, a third distinct ID's file is never created; re-logging an existing ID is unaffected |
| F9 | Fixed | New `normalizeIds()` option canonicalizes each ID (Unicode NFC then lower-case) before routing and naming, so IDs a case- or normalization-insensitive file system treats as one name share a file and a worker instead of racing across workers. The docs explain the disclosure risk and the option. Off by default (unchanged behaviour, no hot-path allocation). | With `normalizeIds()`, `Foo`/`FOO`/`foo` and the NFC/NFD forms of `café` each land in one file in order |
| F18 | Fixed | The ID-to-worker/stripe mapping is a hash of the ID's characters keyed with a per-logger random value, chosen at build, so IDs from untrusted input can't be crafted to all land on one worker or one sync lock without knowing the key. (The first fix XORed a seed into `String.hashCode()`; the later code review found that IDs with equal hash codes, which are easy to make in bulk, still shared a worker for every seed, and replaced it: `FABLE_REVIEW.md`, R5.) A given ID still always maps to the same worker for that logger. About a nanosecond per ID character on the hot path, no allocation. | 64 IDs with one `String.hashCode()` spread over the workers under every key tried; the same ID moves between workers as the key changes |
| F16 | Fixed | A `close()` that times out inside the shutdown hook now warns the messages "won't be written: the JVM is exiting" rather than "will be written in the background", which is false there. A direct close keeps the background wording. | Running the hook's body against a stalled worker emits the exit wording |
| F7 | Fixed | The worker now backs off to a timed poll on a slot a cut-off `log()` call never published, instead of spinning a core, and `close()` gives up after the queue stops draining for 2 s instead of waiting forever, with a warning. The worker loop can no longer be exited by an error, and a worker that does stop closes the logger. | A simulated cut-off producer no longer pegs a core, and `close()` finishes |
| F13 | Fixed (with F7) | The worker's error handling can no longer throw its way out of the loop, and if a worker thread ever stops while the logger is open, admission is closed and a warning is emitted. | Covered by the F7 tests and the existing error-path tests |
| F4 | Fixed | `log()` rejects an ID that can't fit in a file name with one length comparison, before queueing or locking. It is counted in `droppedInvalidId` and warned about once. Invalid IDs are no longer copied into a file name first, and those kept as entries are at most 255 chars. | The 50-million-char ID is rejected at once |
| F5 | Fixed | `~` is no longer allowed in a file name. | `VERYLO~1` is rejected; the other ID's file is untouched |
| F6 | Partly fixed | A file name can't start with `-`. An ID can still name an unrelated file with the same prefix and suffix, so keep the directory for this logger's files. | The `tar` chain's ID is rejected, and `tar` runs nothing |
| F11 | Fixed | File names can't contain control, invisible formatting (category Cf) or line and paragraph separator characters, so valid paths print safely in warnings. `quote()` escapes the same characters. | |

The other findings are still open. The message and ID checks (F1, F4, F5, F6, F11) cost one async
worker about 7% of its peak throughput (see the README); the worker-recovery changes (F7, F13) are
off the hot path and cost it nothing. Sync mode was unchanged throughout.

One residual remains for F7: a slot a cut-off `log()` call never published still blocks its own
ring while the logger runs, so that ring's later messages pile up and are dropped (counted and,
once, warned), until the logger is closed. The worker no longer spins or hangs, and other workers
are unaffected; reclaiming the blocked slot safely would need a larger redesign, because a merely
slow producer (for example paused at a GC safepoint) could still publish into it.

Two residuals remain for F2. A regular file could in principle be swapped for a symlink in the
instant between the stat and the open; a stat after opening narrows this, but the log directory
should still be writable only by the service's own user (which the build-time warning encourages).
And the directory warning is advisory and POSIX-only. Restricting the permissions of the files the
logger creates is tracked separately as F10.

A handler that blocks forever (F8) can still stall the worker on the rare call that reaches it;
the logger can't fix a handler that never returns, so the contract requires one that does, and
rate-limiting keeps the calls rare.

For F12, the benchmark plugin is still applied to the main build rather than an isolated
subproject; moving it is optional and not done here, since the plugin and its dependencies are now
pinned by checksum. Dependency verification must be regenerated when a dependency changes
(`./gradlew --write-verification-metadata sha256 build jmhJar` from a clean Gradle home).

For F3, `maxFiles` bounds the new files created within a run but doesn't track files across
restarts (an existing file reopened after a restart isn't a new file), and it doesn't reduce the
open-and-close churn once active IDs exceed `maxOpenFiles` — the real remedy for both is using IDs
from a bounded set, which the docs now call for. The still-per-call `lastByte` probe on reopen
(the audit's third suggestion) is unchanged, since skipping it safely would need a larger
rework of how files are evicted.

## Threat model

- **`id` and `json` may be attacker-influenced.** A server might use a user or tenant ID as `id`
  and log request data in `json`.
- **The application developer is trusted.** That covers the directory, the pattern, the options,
  the `WarningHandler` and the `WorkerPool` with its `ThreadFactory`.
- **Log files have readers.** Tenants may receive their own file, and administrators, shell
  scripts, log shippers and terminals all read the directory.
- **The log directory may be shared** with other local users or processes in some deployments.
- **The attacker's goals considered:**
  - escaping the directory or writing to other files
  - mixing one ID's lines into another's file
  - forging log lines
  - denial of service (memory, CPU, file handles, blocked threads, warning floods)
  - reading logs they shouldn't
  - tampering with the build

## Findings

### F1. Line breaks and control characters in messages are written raw — Medium

**Where:** `Utf8.encode` (`Utf8.java:35-38`) copies every character as it is. `log()` documents
that `json` "should be a single line; it is not validated" (`EfficientLogger.java:36-37`).

**What happens:** a message containing `\n` becomes two lines in the file. `\r` and other
control characters, including terminal escape sequences, reach anyone who views the file with
`cat`, `less -R` or `tail -f`. Verified:

```
log("a", "{\"user\":\"bob\",\"note\":\"hi\"}\n{\"user\":\"admin\",\"event\":\"password_reset\"}")
log("a", "{\"note\":\"<ESC>]0;owned<BEL><ESC>[2J\"}")
→ a.json has 3 lines; the second is a forged admin event, the third carries raw escape codes
```

Even valid JSON can split a line for some readers. U+0085, U+2028 and U+2029 may appear
unescaped inside JSON strings, and Jackson, for one, writes them that way by default. Python's
`str.splitlines()` and some log shippers treat them as line breaks.

**Impact:**
- Forged entries mislead audits and alerting, and an NDJSON consumer parses them as real events.
- Escape codes can retitle or clear an administrator's terminal, or hide lines from view.
- In F6's chain, the forged line became a shell command.

**Fix:**
- Valid JSON can contain a raw CR or LF only as whitespace between tokens, because inside
  strings they must be escaped (RFC 8259 §7). Replacing each raw CR or LF with a space therefore
  never changes the meaning of valid JSON, and it makes forging a line impossible.
- Other raw characters below U+0020 never appear in valid JSON at all, so the encoder could
  write them as `\u00XX`.
- U+0085, U+2028 and U+2029 can be written as `\u0085`, `\u2028` and `\u2029`. Inside a string
  that means the same thing, and outside one they aren't valid JSON anyway.
- All of this fits in the encoder's existing loop without allocating. Make it the default; if
  an opt-out is wanted, call it something like `rawMessages()`.

### F2. The log directory is trusted: symlinks, FIFOs and devices are opened, and its owner and mode are never checked — Medium (High in some deployments)

**Where:**
- `FileOutputStreamSink.java:22` opens with `new FileOutputStream(path, true)`, which follows
  symlinks and opens any file type.
- `FileOutputStreamSink.java:27` reads the last byte with `RandomAccessFile(path, "r")`, which
  also follows links and blocks on a FIFO.
- `EfficientLoggerBuilder.java:186` and `FileOutputStreamSink.java:40-46` accept an existing
  directory as it is, whoever owns it. `FileTable.java:319` silently recreates the directory if
  it disappears.

**What happens (verified on Linux):**
- **Symlink:** a link `victim.json → /some/file` made the logger append to `/some/file`, after
  first adding a newline because the target didn't end with one. Any file the process can write
  can be appended to this way.
- **FIFO, async mode:** a FIFO named `fifo.json` hung the worker inside `open()`. Messages for
  every other ID on that worker stopped being written, `flush(2 s)` and `close(2 s)` returned
  false, and only a reader opening the FIFO released it.
- **FIFO, sync mode:** the caller hung inside `log()` while holding its stripe's lock. A second
  thread logging a different ID on the same stripe was still `BLOCKED` 2 seconds later.
- **Directory squatting (by reading):** if the directory is, say, `/tmp/app-logs`, another local
  user can create it first with mode 777. The logger then writes into a directory the attacker
  owns, where they can plant symlinks and FIFOs and read every log. If the directory is deleted
  while the logger runs, it is recreated with default permissions, which gives a second chance
  to win that race.

**Impact:**
- Anyone who can create entries in the log directory can redirect writes or hang logging.
- If the logging process runs with more privilege than they have (root, or a service account),
  this becomes an arbitrary-file append. Combined with F1, the attacker also chooses whole lines,
  which can corrupt configuration files or plant cron entries.
- Linux's `fs.protected_symlinks=1` (the default on Red Hat and Ubuntu) blocks this only in
  sticky, world-writable directories such as `/tmp`. It does nothing for a log directory that is
  merely group-writable.

**Fix:**
- At `build()` on POSIX systems, check the directory and its parents. Warn, or fail with an
  option, if any is owned by another user (other than root) or writable by group or others
  without the sticky bit. Create a new directory with restrictive permissions (see F10).
- Before each open, `lstat` the path
  (`Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS)`). Accept only a regular
  file or a missing one; refuse symlinks, FIFOs, devices and directories, as with any I/O error.
  This leaves a small race window, which the next point closes.
- For a strict guarantee, open with `NOFOLLOW_LINKS` through `Files.newOutputStream`. That
  returns a channel, which an interrupt closes; this is acceptable for async workers, which
  already treat a failed write as an I/O error.
- Make recreating a missing directory opt-in, or recreate it with the same checks.
- Document that the log directory must be writable only by the service's own user.

### F3. Untrusted IDs can create unlimited files and force an open and close per message — Medium

**Where:** `FileTable.create` (`FileTable.java:247-263`) creates a file for every new ID.
`FileTable.open` (`FileTable.java:274-300`) closes the least recently used file once
`maxOpenFiles` is reached, and reading the last byte (`FileOutputStreamSink.java:26-37`) costs
one more open.

**What happens:** each distinct valid ID creates a file, with no limit on their number or size.
Once more distinct IDs are in use than `maxOpenFiles` (default 1,024), every message costs a
close, an open to read the last byte, and an open to append. Measured with 100,000 messages,
cycling round-robin over the IDs:

| Measurement | Few IDs | 5,000 IDs | Slowdown |
|---|---|---|---|
| Linux, sync, time per `log()` call | 0.6 µs (50 IDs) | 10.1 µs | 16× |
| Linux, async, messages written per second | 2.4 M (50 IDs) | 95 K | 25× |
| Windows, async, messages written per second (reviewer's run) | 24.6 M (500 IDs) | 25 K | about 1,000× |

On Windows, Defender scans every open, which makes each one far more expensive. In async mode
the callers aren't slowed, but the worker falls behind and drops; in the Windows run, 93.4
million of 93.5 million calls were dropped.

**Impact:**
- An attacker who controls IDs can fill the disk or run out of inodes with files.
- A directory with millions of entries slows every file operation in it.
- They can cut logging throughput by one to three orders of magnitude; in sync mode, every
  request that logs is slowed.

**Fix:**
- Document that IDs must come from a bounded set. Map or hash untrusted values to IDs rather
  than using them directly.
- Optionally add `maxFiles(n)`, a cap on distinct files per logger. New IDs beyond it would be
  dropped, counted and warned about once, like invalid IDs.
- Check the last byte only the first time a path is opened in a process; after that the logger
  knows how its own last write ended. That removes one open per reopen.

### F4. ID length isn't bounded or counted against the queue limit — Medium

**Where:**
- `AsyncLogger.log` (`AsyncLogger.java:75-82`) checks only `json.length()` against
  `maxQueuedChars`; the ID's size isn't counted.
- `FileNamePattern.invalidReason` (`FileNamePattern.java:73`) builds `prefix + id + suffix`
  before checking the 255-character limit (`FileNamePattern.java:94`).
- `FileTable.create` (`FileTable.java:253-255`) keeps an entry, holding the ID string, for each
  invalid ID: up to `maxOpenFiles + 1024` entries per worker or stripe.

**What happens (verified):** with `maxQueuedChars(100)`, `log(<50-million-char id>, "{}")`
returned true. The worker then copied the 50 million chars to build the file name before
rejecting it.

**Impact:**
- `maxQueuedChars` is meant to bound the queue's memory, but every queued message also pins its
  ID string. Under a burst, 65,536 queued messages with 1 MB IDs pin about 64 GB, so this ends
  in an out-of-memory crash.
- Each over-long ID costs the worker a full copy (or the caller, in sync mode, while holding the
  stripe lock).
- The invalid-ID entries then keep up to 2,048 such strings per worker or stripe.

**Fix:** reject `id.length() > 255` in `log()` before queueing. No file name can be longer, so
this rejects no valid ID; it is an O(1) check that allocates nothing. Count those IDs as
`droppedInvalidId`. Either count the ID's length against `maxQueuedChars` as well, or rely on
that 255 cap. Store a truncated copy, or nothing, for invalid IDs.

### F5. Windows: an ID can be another file's 8.3 short name and write into it — Medium

**Where:** `FileNamePattern.invalidNameReason` (`FileNamePattern.java:89-127`) allows `~`.

**What happens (verified on this machine, where C: has 8.3 names):**

```
pattern "{id}.log": log("verylongtenantname", …), then log("VERYLO~1", …)
→ verylongtenantname.log: [{"tenant":"verylongtenantname"}, {"tenant":"attacker"}]
```

Windows resolves `VERYLO~1.log` to the existing file's short name. The same happens with the
pattern `{id}`. With `.json`, a 4-character extension, the short name is `VERYLO~1.JSO`, which no
ID can produce, so `{id}.json` and `events-{id}.json` were not affected.

**Impact:** one tenant can write lines into another tenant's file. The victim's file then holds
attacker-chosen lines, and with F1 they can be made to look like the victim's own events.

**Fix:** reject `~` in IDs. Every generated short name contains one, and a long name without `~`
can never collide with a generated short name. The rule is cheap and portable, like the existing
Windows rules.

### F6. With a pattern starting with `{id}`, IDs can act as command options or name unrelated files — Medium

**Where:** `FileNamePattern.invalidNameReason` (`FileNamePattern.java:89-127`) allows a leading
`-` or `.`, spaces and `=`. Any name that passes is fair game inside the directory.

**What happens (verified on Linux, with the pattern `{id}.json`):**

```
log("--use-compress-program=sh x.sh", "{}")
log("x.sh", "{\"a\":1}\ntouch PWNED")          // F1: a second line that is a shell command
$ tar cf ../backup.tar *                        // an administrator backs up the log directory
→ tar runs "sh x.sh.json"; its second line creates PWNED
```

The shell expands `*` to `--use-compress-program=sh x.sh.json`, and tar reads that as an option.
`rsync`, `chown`, `chmod` and others have similar options.

The same pattern also lets an ID name any other file in the directory with that suffix. If the
directory holds `config.json`, the ID `config` appends to it, after first adding a newline if it
doesn't end with one. An ID can also create hidden files such as `.bashrc.json`.

**Impact:**
- Code execution as whoever runs such a command in the log directory. This needs an
  administrator or script that uses `*` there.
- Corruption of non-log files that share the directory.

**Fix:**
- Reject IDs that start with `-`, and those that start with `.` if hidden files aren't wanted.
- Document that the directory should hold only this logger's files.
- A pattern with a fixed prefix, like `events-{id}.json`, avoids all of this.

### F7. A `StackOverflowError` inside `log()` can wedge the async logger permanently — Medium

**Where:** `AsyncLogger.log` (`AsyncLogger.java:82-91`) and `MpscRing.offer`
(`MpscRing.java:91-103`). The comment at `AsyncLogger.java:91` says the publish "can't fail or
throw". The close logic (`Admission.java:19-22`, `Worker.java:75-84`) and the producer wait
(`Worker.java:85-88`) rely on that.

**What happens:** `log()` changes shared state in two steps, with method calls between them, and
a `StackOverflowError` can strike at any call:

- **Between admission and claiming a slot:** the message counts as queued forever.
  `close()` can never return true, and every worker polls every 100 µs until the process exits.
- **Between claiming a slot and publishing it:** the worker waits for that slot forever.
  - It spins at 100% CPU (`Worker.java:85-88`).
  - Every later message for that worker piles up behind it until the logger-wide queue limit
    is reached.
  - Because the limit is shared, messages for every ID are then dropped.

Verified on Windows with the reviewer's `SoeWedge` program, run with `-XX:TieredStopAtLevel=1`.
It recursed on a thread with a 256 KB stack and called `log()` from the deepest frame, sweeping
depths around the overflow point. Results:
- 303 overflows hit inside `log()`, leaving 9,402 messages stuck.
- 100 later messages from a thread with plenty of stack were never written.
- The worker used 984 ms of CPU per second.
- At exit the shutdown hook waited the full 10-second `closeTimeout`, and 9,502 messages were
  lost.

A second reviewer program, interpreted only (`-Xint`), leaked the admission instead. With the
JVM's default settings, 30 sweeps didn't hit the window, because the fully optimized code checks
the stack once on entry to `log()`. Code runs interpreted or under the C1 compiler during
warm-up and after deoptimization, so the window is real.

**Impact:** a single overflow at the wrong moment silently ends logging for the life of the
process and burns a core. An attacker could aim for it where the application recurses on input
they control, such as a parser on deeply nested data, and calls `log()` deep in that recursion.
Many JDK structures share this weakness, so an application that survives a
`StackOverflowError` can't fully trust its own state either. The consequence here is unusually
severe and silent, though.

**Fix:**
- Make the worker recover from a slot that stays unpublished. After about a second, skip it:
  release one message's worth of admission, count it as lost, and warn. Park while waiting,
  instead of spinning.
- At close, finish once every ring is empty and no slot is pending, even if the admission count
  hasn't reached zero, and report the leaked count.
- Optionally inline `offer` into `log()`, so that no call sits between the admission and the
  claim.

### F8. Warning floods: too-large messages start bogus "queue full" episodes, nothing rate-limits them, and a blocked stderr stalls the worker — Medium

**Where:**
- `AsyncLogger.log` (`AsyncLogger.java:76-80`) counts a too-large message as a drop.
- `OverflowTracker.check` (`OverflowTracker.java:51-76`) starts an episode on any drop and ends
  it as soon as the queue is below half; it has no rate limit. I/O errors, by contrast, are
  limited to one report every 10 seconds (`Warnings.java:54-64`).
- `WarningHandler.stderr()` (`WarningHandler.java:21-31`) writes on the worker thread.

**What happens:**
- **No queue pressure needed (reviewer's run).** Each message longer than `maxQueuedChars`
  starts an episode ("queue full (…); dropping messages until it is half empty"), even though
  the queue is empty. The next check ends it ("queue recovered; dropped 1 message"). With
  default limits, one 16 MiB string logged repeatedly next to normal traffic produced 9,928
  warnings in 3 seconds; `stats()` showed `droppedQueueFull=0` and `droppedTooLarge=1,976,598`.
  With a small `maxQueuedChars`, the rate reached about 3 million warnings per second.
- **Under queue pressure (verified on Linux).** With `maxQueuedMessages(4)`, cycles of 20 quick
  messages each followed by `flush()` printed 9,398 warning lines over 5,000 cycles.
- **Stalling the worker (verified on Linux).** With stderr going to a pipe that nobody read, the
  same test blocked the worker inside the handler at cycle 436, once about 64 KB of warnings
  had filled the pipe. From then on `flush()` timed out, and every message for that worker's IDs
  would be dropped.

**Impact:**
- An attacker who can make the application log oversized messages, such as large request
  bodies, can flood stderr (filling disks wherever it is collected) or stall logging entirely.
- The "queue full" text sends whoever investigates in the wrong direction.

**Fix:**
- Count too-large messages separately from queue-full episodes, with their own rate-limited
  warning.
- Rate-limit episode reports the way I/O errors are, folding the counts into the next report.
- Document that a `WarningHandler` must never block, or have the default handler drop lines
  rather than wait.

### F9. Case-insensitive and Unicode-normalizing file systems merge IDs — Low

**Where:** by design, IDs are compared as Java strings. File systems compare names their own way.

**What happens:**
- On Windows (NTFS) and macOS (APFS), `Foo` and `foo` name the same file, which the README
  already says. The reviewer found that NTFS merges simple case pairs (`a`/`A`, `ǆ`/`Ǆ`). It does
  not merge `I`/`ı`, `K`/the Kelvin sign, `s`/`ſ`, `σ`/`ς`, `ß`/`SS`, or composed and decomposed
  forms.
- APFS also treats different Unicode normalizations of the same text as one name: `é` as one
  code point and `e` + U+0301 collide (not tested here).
- The two IDs may hash to different workers, so two threads append to one file, and their
  order isn't defined. Lines can also be torn (F12).

**Impact:** where IDs are tenants that differ only by case or normalization, one tenant's lines
land in another's file. This is a disclosure if tenants receive their files.

**Fix:**
- Document the security implication next to the existing case note.
- Applications should normalize IDs (NFC, then lower case) before logging. A library option
  could do it, at the cost of an allocation per new ID.

### F10. Files and directories get the default permissions — Low

**Where:** `FileOutputStreamSink.java:22` and `:44`.

**What happens (verified, umask 022):** the directory is created `rwxr-xr-x` and files
`rw-r--r--`, so every local user can read the logs. On Windows they inherit the directory's ACL.

**Impact:** logs often contain personal data or tokens, and other accounts on the host can read
them.

**Fix:** add an option such as `filePermissions(Set<PosixFilePermission>)`. When it is set,
create new files (`Files.createFile` with attributes) and directories with those permissions,
for example `rw-------` and `rwx------`. Document that otherwise the process umask decides.

### F11. Invisible and look-alike characters are allowed in IDs and printed raw in warnings — Low

**Where:**
- `FileNamePattern.invalidNameReason` (`FileNamePattern.java:103-118`) rejects C0 controls,
  DEL, the Windows reserved characters and unpaired surrogates, but nothing else.
- `FileNamePattern.quote` (`FileNamePattern.java:155-171`) escapes only C0 controls and
  surrogates.
- I/O-error warnings include the file path unescaped (`FileTable.java:185, 298, 355, 387,
  410`), and so do the exception messages and stack traces the default handler prints.

**What happens (verified by the reviewer on Windows):** IDs can contain:
- C1 controls (U+0080–U+009F)
- bidi overrides (U+202A–U+202E, U+2066–U+2069)
- zero-width characters (U+200B–U+200F, U+FEFF)
- line and paragraph separators (U+0085, U+2028, U+2029)

Such IDs create files whose names display differently from what they are. With a right-to-left
override, the name `invoice\u202Enosj.exe` displays as `invoiceexe.json`. Look-alikes such as
Cyrillic `а` (U+0430) for Latin `a` produce names that look like another tenant's. Both the
invalid-ID warning and the I/O-error warning emitted raw U+009B, U+202E and U+0085; some
terminals treat U+009B as the start of an escape sequence.

**Fix:**
- Reject these ranges in IDs. They're never needed in a file name, and the check is one
  comparison per character, done once per file open.
- Pass every string derived from user input through one sanitizer before it reaches a warning,
  including paths and exception messages. It should escape C0 and C1 controls and the bidi,
  zero-width and separator characters.
- An optional strict mode could allow only `[A-Za-z0-9._-]`, not starting with `-` or `.`.

### F12. A record larger than the buffer is split, and another ID's line can land in the middle — Low

**Where:** `FileTable.append` (`FileTable.java:102-122`) writes an oversized record in pieces and
keeps the last piece buffered. `FileTable.flushDirty` (`FileTable.java:126-130`) flushes files in
reverse order of when they became dirty. `FileTable.writeThrough` (`FileTable.java:161-167`)
also writes in pieces.

**What happens (verified on Windows):**

```
pattern "e-{id}.json": log("Foo", <10,006-char record>), then log("foo", "{\"victim\":1}")
→ e-foo.json: an 8,191-char fragment, {"victim":1}, then the 1,817-char rest of Foo's record
```

`Foo` and `foo` share the file on NTFS (F9). The victim's line ends up inside the attacker's
record, which corrupts both for any parser. The same happens when another process or logger
appends to the file between the pieces. The README promises that aliased IDs' "lines stay whole"
(`README.md:67-69`), which is untrue in this case.

**Fix:**
- Fixing F9 by mapping aliased IDs to one table entry removes the in-process case.
- To keep oversized records whole, write them in one `write` call from a temporary buffer sized
  to the record. That allocates, but only for oversized records.
- Otherwise, correct the README.

### F13. If a worker thread dies, `flush()` and `close()` report success while messages pile up — Low

**Where:**
- `Worker.run` (`Worker.java:92-95`): the catch block calls `reportError` (`Worker.java:265-270`),
  which builds strings and can itself throw, for example `OutOfMemoryError`.
- Anything the catch block throws ends the loop, and `finish()` runs in `finally`
  (`Worker.java:97-99`).
- `awaitFlush` treats a terminated worker as done (`Worker.java:164`).
- `close()` waits only for the worker threads to finish (`AsyncLogger.java:166`, `216-227`).

**What happens (by reading):** once a worker has died while the logger is still open:
- `log()` keeps admitting messages for its IDs until the queue limit is reached, then drops
  everything.
- `flush()` returns true, although those messages were never written.
- With one worker, which is the default:
  - No overflow warning is printed, because only workers print them.
  - `close()` returns true at once, while `stats()` shows messages queued forever.
- With several workers, `close()` times out instead, because the surviving workers wait for the
  dead one's messages.

This breaks the README's promise that after `close()` returns true, every accepted message was
written.

**Fix:**
- Guard the catch block itself, so nothing can escape the worker loop.
- If a worker ever exits while the logger is open, close admission and warn.
- Return true from `flush()` and `close()` only if no messages remain queued.

### F14. Build supply chain — Low

**Where:** `gradle/wrapper/gradle-wrapper.properties:3`, `settings.gradle.kts:2`,
`build.gradle.kts`.

**What's there:**
- **Gradle 8.14 advisories.** CVE-2026-22816 and CVE-2026-22865 cover Gradle silently moving on
  to the next repository after a failure, which lets a later repository serve artifacts. They
  are fixed in Gradle 9.3.0; some trackers list 8.14.4 as the fixed 8.x release, so check
  Gradle's advisory before relying on it. This build declares only Maven Central for
  dependencies and the Plugin Portal for plugins, so there is no fallback to abuse, but the next
  repository someone adds would make it reachable.
- **No wrapper checksum.** `gradle-wrapper.properties` has no `distributionSha256Sum`, so the
  wrapper runs whatever ZIP it downloads.
- **An older wrapper jar.** `gradle-wrapper.jar` is genuine: its SHA-256 (`91a23940…`) matches
  Gradle's published checksum for the 7.5 and 7.5.1 wrapper jar, and no other release. It comes
  from IntelliJ's project template, not from 8.14.
- **No dependency verification.** There is no `gradle/verification-metadata.xml`, so plugins and
  dependencies are trusted on HTTPS alone.
- **The benchmark plugin applies to the main build.** The third-party `me.champeau.jmh` plugin
  is applied there, not in an isolated subproject.
- **JDK download.** The foojay toolchain resolver downloads a JDK if JDK 17 isn't installed.

None of this affects the library jar, which has no dependencies, unless the build itself is
compromised.

**Fix:**
- Run `./gradlew wrapper --gradle-version <fixed version> --gradle-distribution-sha256-sum <sum>`,
  with the sum from <https://gradle.org/release-checksums/>.
- Commit the regenerated wrapper jar.
- Run `./gradlew --write-verification-metadata sha256 build` and commit the file it writes.
- In CI, set `org.gradle.java.installations.auto-download=false` and preinstall the JDK.
- Optionally move the benchmarks into a subproject.

### F15. Benchmarks use log4j-core 2.24.3 — Info

`build.gradle.kts:25` pulls log4j-core 2.24.3 into the benchmarks only. It is affected by
CVE-2025-68161 (no TLS hostname check in the Socket appender, fixed in 2.25.3) and
CVE-2026-34478 (log injection through `Rfc5424Layout`, fixed in 2.25.4). The benchmark uses
neither feature, and the library jar doesn't include log4j. Bump it to 2.25.4 or later anyway,
so scanners stay quiet. JUnit 5.13.4 is past the fix for CVE-2025-53103 (fixed in 5.13.2). The
Disruptor and JMH weren't checked in depth.

### F16. A timed-out close in the shutdown hook promises writes that won't happen — Info (fixed)

**Fixed:** when `close()` is called from the shutdown hook and times out, the warning now says
"the remaining N messages won't be written: the JVM is exiting" instead of "will be written in the
background", since the JVM halts once the hooks return. A direct `close()` that times out still
says "will be written in the background", because its daemon workers do keep draining. Verified by
running the hook's body against a stalled worker.

Original finding:


`AsyncLogger.close` (`AsyncLogger.java:171-175`) warns that "the remaining N messages will be
written in the background". When it is called from the shutdown hook
(`AsyncLogger.java:270-278`), the JVM exits right after, so the daemon workers are stopped and
those messages are lost. Verified on Linux, with the worker held up by a FIFO (F2) and
`closeTimeout(1 s)`: the hook warned "the remaining 6 messages will be written in the
background", the JVM exited a second later, and the 5 queued messages for another ID never
reached its file. That matters for anyone who relies on the warning in an incident. Say that
they will be lost when the JVM is exiting; `Runtime` doesn't expose this, but the hook can pass
a flag.

### F17. Package-private test hooks can be reached by code in the same package — Info

`EfficientLoggerBuilder.build(TestHooks)` (`EfficientLoggerBuilder.java:183`) lets same-package
code swap the file sink, the clock and other seams. On the class path, any jar can declare
classes in `org.example.efficientlogger`. This only matters if hostile code is already running,
which could do worse through reflection. Sealing the package in the jar manifest
(`Sealed: true`), or shipping a `module-info`, closes it cheaply.

### F18. Crafted IDs can put all traffic on one worker or one sync lock — Info

`Hashing.index` (`Hashing.java:14`), used by `AsyncLogger.java:81` and `SyncLogger.java:57`,
spreads IDs by `String.hashCode()`, which isn't seeded. Anyone who controls IDs can choose ones
that land on the same worker or the same sync stripe. Extra workers then stop helping, that
worker drops more, and in sync mode all those callers contend for one lock. Within a worker's
file map, colliding `String` keys are kept in a tree, so lookups stay fast. Seeding the mix per
logger, for example from `System.nanoTime()` at build time, makes this impractical.

## Other observations

These aren't rated as findings, but are worth knowing:

- **The invalid-ID warning budget is per logger and lifetime.** After 100 invalid-ID warnings,
  for example from an attacker, later invalid IDs show up only in `stats()`.
- **The I/O-error rate limit is shared by all files,** so one failing file can hide another's
  first error for up to 10 seconds. The count of hidden errors is reported later.
- **Each worker's ring is sized for the whole logger's queue limit.** 16 workers with
  `maxQueuedMessages(2^23)` allocate about 1 GiB at `build()`. That is trusted configuration,
  but worth documenting.
- **`maxOpenFiles` is a soft cap** and can be exceeded by about one file per worker or stripe;
  it is documented as soft.
- **Shutdown edge cases:**
  - A `ThreadFactory` that makes non-daemon threads keeps the JVM from ever starting shutdown,
    so the hook never runs.
  - `System.exit` called from a worker thread or warning handler makes the hook wait the full
    `closeTimeout` for a worker that is itself inside `exit`.
- **Unpaired surrogates in messages are written as `?` without a warning,** matching
  `String.getBytes(UTF_8)`.

## Checked and found sound

- **Directory escape.** Every ID is checked against the full file name:
  - `/ \ : * ? " < > |`, C0 controls and DEL are rejected, as are `.`, `..`, a trailing dot or
    space, Windows device names (including `COM¹`–`³`), names over 255 chars or 255 UTF-8 bytes,
    and unpaired surrogates.
  - The resolved path's parent must equal the directory (`FileNamePattern.java:78-84`).
  - `InvalidPathException` counts as an invalid ID, never as an exception from `log()`.
  - The pattern goes through the same rules, so it can't add a separator.
- **Windows names.** The `:` rule blocks alternate data streams (`a.json:evil`).
  - Rejected as expected: device-name variants such as `con`, `CoM1`, `lpt9`, `COM0`,
    `con.x.y`, `con  ` and `AUX.json`.
  - Created as ordinary files on Windows 11, which is harmless: `CONIN$`, `CONOUT$`, `CLOCK$`,
    `$MFT`, `com10`, fullwidth `ＣＯＮ`, and `NUL` followed by a zero-width space. Adding the
    first three to the device list would cover older Windows versions too.
- **Non-UTF-8 locales.** IDs that the locale's charset can't encode are rejected, not
  mangled (tested on Linux with the POSIX locale).
- **Message routing between IDs.** The queue stores `(id, json)` as two references in adjacent
  slots:
  - The consumer reads the message with acquire semantics before the ID, and clears both slots
    before releasing the queue budget.
  - The next producer for a slot is admitted only after that release, so it can't overwrite a
    slot still being read.
  - The ring holds at least as many slots as the queue limit, so a claim never wraps onto an
    unconsumed slot.
  - The reviewer's stress run used 8 producers, 100 IDs, 64-byte buffers (forcing split writes)
    and `maxOpenFiles(8)` (forcing constant reopening). It found no misrouted, reordered,
    duplicated, missing or malformed line, in async mode with 4 workers (174,087 lines) and in
    sync mode (1,200,000 lines).
- **Queue limit arithmetic.** The counts packed in `Admission` can't overflow into each other
  (at most 2^23 messages, at most 2^38−1 chars, and a single message never longer than the char
  limit), and a release subtracts exactly what was added.
- **Other arithmetic:**
  - Duration conversions saturate instead of overflowing.
  - Flush tickets and deadlines use wrap-safe comparisons.
  - `Config` sizes are capped.
  - `Hashing.index` stays in range.
- **Encoding.** All buffer arithmetic is bounds-checked, size math uses `long`, and a surrogate
  pair is never split. Output matches `String.getBytes(UTF_8)`, including `?` for unpaired
  surrogates.
- **Failures don't leak.** On every error path the file is closed, its open-file slot returned
  and its buffer recycled. A failing file is retried after 1 second and can't stall the others.
- **The `log()` contract.** Apart from `Error`s such as F7's, `log()` throws only
  `NullPointerException` in both modes.
- **Warning plumbing.** Warnings are emitted outside locks, anything a handler throws is
  ignored, and a handler may call `log()`, `flush()` or `close()`.
- **Close and flush.** Concurrent and repeated `close()` is safe, and `close()` from a worker
  thread returns at once. A `ThreadFactory` that returns null or throws at start-up is handled.
- **What crosses into the logger.** There is no deserialization, reflection, native code,
  network access or class loading, and there are no runtime dependencies.
- **Interrupts.** An interrupt can't close a shared file, because `FileOutputStream` ignores
  interrupts.
- **Benchmarks.** The benchmarks' temporary directories come from `Files.createTempDirectory`
  (mode 0700), and cleanup doesn't follow symlinks.
- **Repository.**
  - A pattern search of the full git history found no keys, tokens or passwords, and the
    tracked `.idea` files hold nothing sensitive.
  - Commits carry the author's personal email address; use GitHub's noreply address for future
    commits if that isn't wanted.

## Recommendations

In order of value for effort:

1. **Neutralize line breaks in messages (F1).**
   - Write each raw CR or LF as a space during encoding.
   - Write other C0 controls, U+0085, U+2028 and U+2029 as `\uXXXX`.
2. **Tighten ID rules (F4, F5, F6, F11).**
   - Reject IDs longer than 255 chars in `log()`.
   - Reject IDs that start with `-`, contain `~`, or contain C1 controls, bidi or zero-width
     characters, or line separators.
   - Sanitize every user-derived string in warnings.
3. **Make the async path survive a lost publish (F7, F13).**
   - Skip a slot that stays unpublished, and park instead of spinning.
   - Finish closing when the rings are empty.
   - Never let the worker loop exit early, and report true from `flush()` and `close()` only
     when the queue is empty.
4. **Open files safely (F2).**
   - `lstat` before opening, and refuse anything but a regular or missing file.
   - Check the directory's ownership and mode at `build()`, and don't silently recreate it.
   - Document that the directory must be private to the service.
5. **Rate-limit warnings (F8).** Count too-large messages separately from queue-full episodes,
   and rate-limit both.
6. **Bound what IDs can cost (F3, F4).**
   - Add `maxFiles`.
   - Check each file's last byte once per process.
   - Document that IDs must come from a bounded set.
7. **Add `filePermissions` (F10), and fix or document torn records (F12).**
8. **Build hygiene (F14, F15).**
   - A Gradle release with the repository fixes, a wrapper checksum and a regenerated wrapper
     jar.
   - Dependency verification.
   - log4j-core 2.25.4 or later in the benchmarks.

Items 1 and 2 are small changes to `Utf8` and `FileNamePattern`, and they remove the demonstrated
attack chain. Item 3 is the largest change, confined to `Worker` and `AsyncLogger`. None of the
items adds allocation or locking to `log()`.

## How the findings were verified

The probes ran against the built jar through the public API only:
- **On Linux:** newline injection, symlink, FIFO in both modes, permissions, the 50-million-char
  ID, the tar chain, ID churn, the shutdown hook timing out, and a warning flood into an
  undrained stderr.
- **On Windows:**
  - 8.3 short names with `.json`, `.log` and no extension, and device names.
  - From the reviewer: the `StackOverflowError` wedge (reproduced again independently), torn
    records with aliased IDs (also reproduced again), the oversize-message warning flood, ID
    churn, case folding, name rules, warning sanitization, and the routing stress run.

They live outside the repository, in the audit session's scratch folder, and aren't part of
this commit.

## References

- CVE-2025-68161, log4j Socket appender: <https://www.kodemsecurity.com/cve-archive/cve-2025-68161>
- CVE-2026-34478, log4j `Rfc5424Layout`: <https://security-tracker.debian.org/tracker/CVE-2026-34478>
- CVE-2026-22816, Gradle repository failover: <https://www.wiz.io/vulnerability-database/cve/cve-2026-22816>
- CVE-2026-22865, Gradle repository failover: <https://www.wiz.io/vulnerability-database/cve/cve-2026-22865>
- CVE-2025-53103, JUnit Open Test Reporting: <https://www.tenable.com/cve/CVE-2025-53103>
- Gradle release checksums: <https://gradle.org/release-checksums/>
