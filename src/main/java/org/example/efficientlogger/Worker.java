package org.example.efficientlogger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * Drains one ring into one {@link FileTable}. Each ID always goes to the same worker, so its lines
 * stay in order, and the worker's files need no locks.
 *
 * <p>The loop: write a batch, hand buffers to the OS once the oldest record has waited a flush
 * interval, then go round again or sleep. Before sleeping it always writes everything out. It
 * sleeps TIMED (up to a flush interval; producers wake it only once the queue is a quarter full),
 * or DEEP (until any message arrives) after about a second with nothing to do, or always in eager
 * mode. See {@link MpscRing} for the wake-up protocol.
 *
 * <p>After close, it keeps going until every admitted message, logger-wide, has been consumed,
 * then writes out and closes its files.
 *
 * <p>Tests can drive it by hand with {@link #runOnce} and {@link #finish} instead of a thread.
 */
final class Worker implements Runnable, MpscRing.Handler {

    /** Timed sleeps in a row with nothing to do before sleeping until woken. */
    static final int DEEP_SLEEP_AFTER = 10;
    private static final int SPINS = 64;
    private static final long CLOSING_POLL_NANOS = TimeUnit.MICROSECONDS.toNanos(100);
    private static final long ERROR_PAUSE_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    /** Passes spent waiting for one slot to be published before the worker stops spinning for it. */
    private static final int STALL_SPINS = 100;
    /** How often the worker then polls a slot that stays unpublished (a producer cut off mid-publish). */
    private static final long STUCK_SLOT_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
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
    private int stalledPasses; // consecutive passes waiting for an unpublished head slot

    private final AtomicLong flushRequested = new AtomicLong();
    private volatile long flushDone;
    private final Object flushLock = new Object();
    private volatile boolean terminated;

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

    @Override
    public void run() {
        boolean closing = false;   // the queue has been observed closed
        long closeCount = 0;       // its size the last time that changed
        long closeStableNanos = 0; // when it last changed
        try {
            for (;;) {
                try {
                    Thread.interrupted(); // a leftover interrupt would make every park() return at once
                    int n = runOnce();
                    if (n > 0) {
                        stalledPasses = 0;
                    }
                    if (n == batchSize) {
                        continue;
                    }
                    long word = admission.get();
                    if (Admission.isClosed(word)) {
                        long count = Admission.messages(word);
                        if (count == 0) {
                            break; // everything admitted, logger-wide, has been consumed
                        }
                        // The queue won't reach zero if a log() call was cut off between being
                        // admitted and publishing its message (e.g. a StackOverflowError). Once the
                        // size has stopped falling for a while, give up so close can't wait forever.
                        long now = clock.getAsLong();
                        if (!closing || count != closeCount) {
                            closing = true;
                            closeCount = count;
                            closeStableNanos = now;
                        } else if (now - closeStableNanos >= STUCK_CLOSE_NANOS) {
                            warnings.reportIncompleteClose();
                            break;
                        }
                        if (n == 0) {
                            waitWhileClosing();
                        }
                        continue;
                    }
                    if (n == 0) {
                        if (ring.head() != ring.tail()) {
                            awaitPublish(); // a producer is between claiming a slot and publishing it
                        } else {
                            idle();
                        }
                    }
                } catch (Throwable t) {
                    recover(t);
                }
            }
        } finally {
            finish();
        }
    }

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
            terminated = true;
            synchronized (flushLock) {
                flushDone = flushRequested.get();
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

    /** Waits until the flush with this ticket is done. False if the deadline ({@code nanoTime}) passed first. */
    boolean awaitFlush(long ticket, long deadlineNanos) throws InterruptedException {
        synchronized (flushLock) {
            while (flushDone - ticket < 0 && !terminated) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(flushLock, remaining);
            }
            return true;
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

    /** Writes out every message logged before the request, then signals the requester. */
    private void serveFlush() {
        long request = flushRequested.get();
        long target = ring.tail(); // read after the request, so it covers every log() that came before flush()
        while (ring.head() - target < 0) {
            if (drainBatch(clock.getAsLong()) == 0) {
                spinThenYield(); // a producer is publishing a message we must include
            }
        }
        files.flushDirty(clock.getAsLong());
        warnings.emitAll(files.takeWarnings());
        synchronized (flushLock) {
            flushDone = request;
            flushLock.notifyAll();
        }
    }

    /** Sleeps until there is work. Never sleeps holding unwritten records. */
    private void idle() {
        files.flushDirty(clock.getAsLong());
        warnings.emitAll(files.takeWarnings());
        tracker.check(clock.getAsLong());

        long mode = eager || emptyTimedWakeups >= DEEP_SLEEP_AFTER ? MpscRing.DEEP : MpscRing.TIMED;
        ring.setParkedState(mode); // then re-check what a producer may have done meanwhile (Dekker)
        long tail = ring.tail();
        long word = admission.get();
        boolean work = (tail != ring.head() && (mode == MpscRing.DEEP || ring.aboveWakeThreshold(word)))
                || Admission.isClosed(word)
                || flushRequested.get() != flushDone;
        if (!work) {
            if (mode == MpscRing.DEEP) {
                LockSupport.park(this);
            } else {
                LockSupport.parkNanos(this, flushIntervalNanos);
                if (ring.tail() == ring.head()) {
                    emptyTimedWakeups++;
                }
            }
        }
        ring.setParkedState(MpscRing.AWAKE);
    }

    /** Closing, with nothing to take: producers are still publishing, or other workers still draining. */
    private void waitWhileClosing() {
        for (int i = 0; i < SPINS && ring.head() == ring.tail(); i++) {
            Thread.onSpinWait();
        }
        if (ring.head() == ring.tail()) {
            Thread.yield();
            LockSupport.parkNanos(this, CLOSING_POLL_NANOS);
        }
    }

    private static void spinThenYield() {
        for (int i = 0; i < SPINS; i++) {
            Thread.onSpinWait();
        }
        Thread.yield();
    }

    /**
     * Waits for the slot at the head of the ring to be published. A producer that has claimed it is
     * normally microseconds from publishing, so spin at first. But if a producer was cut off between
     * claiming the slot and publishing it (e.g. a StackOverflowError in log()), the slot never
     * arrives, so after many passes back off to a timed poll rather than burn a core. The poll
     * leaves PARKED as AWAKE, so it is only a poll: no producer unparks the worker and no wake-up can
     * be lost. The slot is never reused, so a merely slow producer that later publishes is still safe.
     */
    private void awaitPublish() {
        if (++stalledPasses <= STALL_SPINS) {
            spinThenYield();
        } else {
            LockSupport.parkNanos(this, STUCK_SLOT_POLL_NANOS);
        }
    }

    /** Recovers from an unexpected error without ever letting the worker loop exit. */
    private void recover(Throwable t) {
        try {
            reportError("unexpected error in the worker thread", t);
        } catch (Throwable ignored) {
            // Even reporting failed, e.g. out of memory. The worker must still not die.
        }
        LockSupport.parkNanos(this, ERROR_PAUSE_NANOS);
    }

    private void reportError(String message, Throwable t) {
        long suppressed = warnings.admitIoErrorReport(clock.getAsLong());
        if (suppressed >= 0) {
            warnings.emit(suppressed > 0 ? message + " (" + suppressed + " more errors were not reported)" : message, t);
        }
    }
}
