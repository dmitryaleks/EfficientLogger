package org.example.efficientlogger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * Drains one ring into one {@link FileTable}. Each ID always goes to the same worker, so its lines
 * stay in order, and the worker's files need no locks.
 *
 * <p>A worker doesn't own a thread: a {@link WorkerPool} thread serves several workers, from any
 * number of loggers, by calling {@link #step} on each in turn and sleeping only when none of them
 * has anything to do. A step writes a batch, hands buffers to the OS once the oldest record has
 * waited a flush interval, and says what the worker wants next: another step at once, a wait for
 * a slot that a producer is still publishing, a wait for the rest of a closing logger, or sleep.
 * Before the thread sleeps each worker writes everything out and announces how it sleeps on its
 * own ring: TIMED (up to a flush interval; producers wake it only once the queue is a quarter
 * full), or DEEP (until any message arrives) after about a second with nothing to do, or always in
 * eager mode. See {@link MpscRing} for the wake-up protocol; it is per ring, so a thread serving
 * several rings announces on each and re-checks each.
 *
 * <p>After close, it keeps going until every admitted message, logger-wide, has been consumed,
 * then writes out and closes its files ({@link #finish}). If the queue makes no progress for
 * {@link #STUCK_CLOSE_NANOS} (a {@code log} call cut off, or stalled, between being admitted and
 * publishing), it gives up instead, so close can finish; the last worker to finish reports what
 * was left (see {@code AsyncLogger}).
 *
 * <p>Tests can drive it by hand with {@link #runOnce} and {@link #finish} instead of a pool.
 */
final class Worker implements MpscRing.Handler {

    /** What {@link #step} wants next, most urgent first; a pool thread does the most urgent of its workers'. */
    static final int CONTINUE = 0;      // took messages: step again at once
    static final int AWAIT_PUBLISH = 1; // the head slot is claimed but not yet published
    static final int CLOSING = 2;       // closed and its ring is empty, but the logger's queue isn't
    static final int IDLE = 3;          // nothing to do
    static final int DONE = 4;          // closed and drained, or gave up: call finish()

    /** Timed sleeps in a row with nothing to do before sleeping until woken. */
    static final int DEEP_SLEEP_AFTER = 10;
    static final int SPINS = 64;
    static final long CLOSING_POLL_NANOS = TimeUnit.MICROSECONDS.toNanos(100);
    private static final long ERROR_PAUSE_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    /** Passes spent waiting for one slot to be published before the thread stops spinning for it. */
    static final int STALL_SPINS = 100;
    /** How often the thread then polls a slot that stays unpublished (a producer cut off mid-publish). */
    static final long STUCK_SLOT_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    /** After close, how long the queue size may stop falling before the worker gives up and finishes. */
    static final long STUCK_CLOSE_NANOS = TimeUnit.SECONDS.toNanos(2);

    final MpscRing ring;
    final FileTable files;
    private final Admission admission;
    private final OverflowTracker tracker;
    private final Warnings warnings;
    private final LongSupplier clock;
    private final int batchSize;
    private final long flushIntervalNanos;
    private final boolean eager;
    private final Runnable beforeBatch; // a test hook; null in production

    private long batchNanos; // time of the current batch, for append()
    private long releasable; // admission to release once the current batch's slots are free
    private int emptyTimedWakeups;
    private int stalledPasses; // consecutive steps waiting for an unpublished head slot
    private long parkedMode = MpscRing.AWAKE; // what announceSleep() last set on the ring
    private boolean closing;   // the queue has been observed closed
    private long closeCount;   // its size the last time that changed
    private long closeStableNanos; // when it last changed

    private final AtomicLong flushRequested = new AtomicLong();
    private volatile long flushDone;
    private final Object flushLock = new Object();
    private volatile boolean terminated;
    private volatile Thread thread; // the pool thread serving this worker; null when tests run it by hand
    private Runnable onTerminated; // set by the logger before the worker runs

    Worker(MpscRing ring, FileTable files, Admission admission, OverflowTracker tracker, Warnings warnings,
            LongSupplier clock, int batchSize, long flushIntervalNanos, boolean eager, Runnable beforeBatch) {
        this.ring = ring;
        this.files = files;
        this.admission = admission;
        this.tracker = tracker;
        this.warnings = warnings;
        this.clock = clock;
        this.batchSize = batchSize;
        this.flushIntervalNanos = flushIntervalNanos;
        this.eager = eager;
        this.beforeBatch = beforeBatch;
    }

    // ---- what the pool thread calls ----

    /**
     * One pass of the worker's loop: {@link #runOnce}, then a look at the logger's state.
     *
     * @return what the worker wants next: {@link #CONTINUE}, {@link #AWAIT_PUBLISH}, {@link #CLOSING},
     *     {@link #IDLE} or {@link #DONE}
     */
    int step() {
        int n = runOnce();
        if (n > 0) {
            stalledPasses = 0;
        }
        if (n == batchSize) {
            return CONTINUE;
        }
        long word = admission.get();
        if (Admission.isClosed(word)) {
            long count = Admission.messages(word);
            if (count == 0) {
                return DONE; // everything admitted, logger-wide, has been consumed
            }
            // The queue won't reach zero if a log() call was cut off between being admitted and
            // publishing its message (e.g. a StackOverflowError). Once the size has stopped falling
            // for a while, give up so close can't wait forever. The last worker to finish reports
            // what was left, so a worker whose own ring is simply empty while a sibling is stalled
            // doesn't raise a false alarm.
            long now = clock.getAsLong();
            if (!closing || count != closeCount) {
                closing = true;
                closeCount = count;
                closeStableNanos = now;
            } else if (now - closeStableNanos >= STUCK_CLOSE_NANOS) {
                return DONE;
            }
            if (n != 0) {
                return CONTINUE;
            }
            return ring.head() != ring.tail() ? awaitPublish() : CLOSING;
        }
        if (n != 0) {
            return CONTINUE;
        }
        return ring.head() != ring.tail() ? awaitPublish() : IDLE;
    }

    /**
     * The head slot is claimed but not published. A producer that has claimed it is normally
     * microseconds from publishing, so the thread spins at first ({@link #stalledBriefly}). But if a
     * producer was cut off between claiming the slot and publishing it (e.g. a StackOverflowError
     * in log()), the slot never arrives, so after many passes the thread backs off to a timed poll
     * rather than burn a core. The poll leaves PARKED as AWAKE, so it is only a poll: no producer
     * unparks the thread and no wake-up can be lost. The slot is never reused, so a merely slow
     * producer that later publishes is still safe.
     */
    private int awaitPublish() {
        stalledPasses++;
        return AWAIT_PUBLISH;
    }

    /** After {@link #AWAIT_PUBLISH}: true while the slot is worth spinning for, false once the thread should poll. */
    boolean stalledBriefly() {
        return stalledPasses <= STALL_SPINS;
    }

    /** Before the thread sleeps: writes everything out. Never sleep holding unwritten records. */
    void beforeSleep() {
        long now = clock.getAsLong();
        files.flushDirty(now);
        warnings.emitAll(files.takeWarnings());
        tracker.check(now);
    }

    /**
     * Announces on the ring how the thread is about to sleep, so producers know whether to wake
     * it. Then {@link #hasWork} must re-check what a producer may have done meanwhile (Dekker).
     *
     * @return {@link MpscRing#TIMED} or {@link MpscRing#DEEP}
     */
    long announceSleep() {
        long mode = eager || emptyTimedWakeups >= DEEP_SLEEP_AFTER ? MpscRing.DEEP : MpscRing.TIMED;
        ring.setParkedState(mode);
        parkedMode = mode;
        return mode;
    }

    /** After {@link #announceSleep}: true if a message, a close or a flush request arrived meanwhile. */
    boolean hasWork() {
        long tail = ring.tail();
        long word = admission.get();
        return (tail != ring.head() && (parkedMode == MpscRing.DEEP || ring.aboveWakeThreshold(word)))
                || Admission.isClosed(word)
                || flushRequested.get() != flushDone;
    }

    /**
     * After the sleep (or the decision not to): the ring is awake again.
     *
     * @param sleptNanos how long the thread slept; a full timed sleep that found nothing counts
     *     towards deep sleep
     */
    void afterSleep(long sleptNanos) {
        if (parkedMode == MpscRing.TIMED && sleptNanos >= flushIntervalNanos >> 1 && ring.tail() == ring.head()) {
            emptyTimedWakeups++;
        }
        parkedMode = MpscRing.AWAKE;
        ring.setParkedState(MpscRing.AWAKE);
    }

    long flushIntervalNanos() {
        return flushIntervalNanos;
    }

    /** Recovers from an unexpected error in a step without ever letting the thread die. */
    void recover(Throwable t) {
        try {
            reportError("unexpected error in the worker thread", t);
        } catch (Throwable ignored) {
            // Even reporting failed, e.g. out of memory. The worker must still not die.
        }
        LockSupport.parkNanos(this, ERROR_PAUSE_NANOS);
    }

    /** The pool thread serving this worker, set before it starts; null when tests run the worker by hand. */
    Thread thread() {
        return thread;
    }

    void attach(Thread thread) {
        this.thread = thread;
        ring.attachConsumer(thread);
    }

    /** What to run once {@link #finish} has completed, on the thread that ran it. */
    void onTerminated(Runnable callback) {
        onTerminated = callback;
    }

    Runnable onTerminated() {
        return onTerminated;
    }

    // ---- the steps themselves ----

    /**
     * One step: write up to a batch of messages, serve a flush request, hand buffers to the OS if
     * the oldest has waited a flush interval, and check for overflow.
     *
     * @return the number of messages taken from the ring
     */
    int runOnce() {
        if (beforeBatch != null) {
            beforeBatch.run();
        }
        long now = clock.getAsLong();
        int n = drainBatch(now);
        if (n > 0) {
            emptyTimedWakeups = 0;
        }
        if (flushRequested.get() != flushDone) {
            serveFlush();
        }
        if (files.dirtyCount() != 0 && now - files.firstDirtyNanos() >= flushIntervalNanos) {
            files.flushDirty(now);
        }
        tracker.check(now);
        warnings.reportTooLongIds();
        warnings.reportOversizeMessages();
        warnings.emitAll(files.takeWarnings());
        return n;
    }

    /** Writes out and closes every file, reports drops not yet reported, and releases flush waiters. */
    void finish() {
        try {
            files.closeAll(clock.getAsLong());
            tracker.finalReport();
            warnings.reportTooLongIds();
            warnings.reportOversizeMessages();
            warnings.emitAll(files.takeWarnings());
            warnings.reportSuppressedIoErrors(); // each worker does; the last to finish catches the rest
        } finally {
            synchronized (flushLock) {
                if (ring.head() == ring.tail()) {
                    flushDone = flushRequested.get(); // nothing is left unwritten, so every request is served
                }
                terminated = true;
                flushLock.notifyAll();
            }
        }
    }

    /** True once the logger is closed and every admitted message has been consumed. */
    boolean closedAndDrained() {
        long word = admission.get();
        return Admission.isClosed(word) && Admission.messages(word) == 0;
    }

    boolean terminated() {
        return terminated;
    }

    /** Asks the worker to write out everything queued so far. Returns a ticket for {@link #awaitFlush}. */
    long requestFlush() {
        long ticket = flushRequested.incrementAndGet();
        ring.wakeConsumer();
        return ticket;
    }

    /**
     * Waits until the flush with this ticket is done. False if the deadline ({@code nanoTime})
     * passed first, or if the worker has stopped with messages still in its ring: ones it gave up
     * on at close, or one published after it stopped.
     */
    boolean awaitFlush(long ticket, long deadlineNanos) throws InterruptedException {
        synchronized (flushLock) {
            while (flushDone - ticket < 0 && !terminated) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(flushLock, remaining);
            }
            if (flushDone - ticket >= 0) {
                return true;
            }
            // The worker has stopped: everything it accepted was written only if its ring is empty.
            return ring.head() == ring.tail();
        }
    }

    // ---- MpscRing.Handler ----

    @Override
    public void accept(String id, String json) {
        releasable += Admission.delta(json.length());
        try {
            files.append(id, json, batchNanos);
        } catch (Throwable t) {
            // FileTable handles I/O errors itself; this is a bug or an OutOfMemoryError. Drop just this message.
            files.countLost(1);
            reportError("unexpected error writing a message for id " + FileNamePattern.quote(id) + "; it was dropped", t);
        }
    }

    // ---- internals ----

    private int drainBatch(long now) {
        batchNanos = now;
        try {
            return ring.drain(this, batchSize);
        } finally {
            long released = releasable;
            if (released != 0) {
                releasable = 0;
                admission.release(released); // after drain() freed the slots
            }
        }
    }

    /**
     * Writes out every message logged before the request, then signals the requester.
     *
     * <p>If the ring can't be drained that far because its head slot was claimed but not yet
     * published (a producer between claiming and publishing: normally for microseconds, but for
     * good if its {@code log()} call was cut off), this returns with the request still pending
     * rather than spin here. The thread waits for the slot with back-off, or gives up at close,
     * and comes back to the request. Spinning here instead once wedged the worker for the life of
     * the process after such a cut-off, with {@code close()} unable to finish.
     */
    private void serveFlush() {
        long request = flushRequested.get();
        long target = ring.tail(); // read after the request, so it covers every log() that came before flush()
        while (ring.head() - target < 0) {
            if (drainBatch(clock.getAsLong()) == 0) {
                return; // the head slot isn't published yet
            }
        }
        files.flushDirty(clock.getAsLong());
        warnings.emitAll(files.takeWarnings());
        synchronized (flushLock) {
            flushDone = request;
            flushLock.notifyAll();
        }
    }

    private void reportError(String message, Throwable t) {
        long suppressed = warnings.admitIoErrorReport(clock.getAsLong());
        if (suppressed >= 0) {
            warnings.emit(suppressed > 0 ? message + " (" + suppressed + " more errors were not reported)" : message, t);
        }
    }
}
