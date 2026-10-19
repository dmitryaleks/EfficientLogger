package org.example.efficientlogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * The threads that write for asynchronous loggers. A pool has at most {@code maxThreads} daemon
 * threads, and any number of loggers share them: each logger's workers (one per
 * {@code workerThreads}) are spread over the pool's threads, and a thread serves every worker
 * assigned to it in turn, sleeping only when none of them has anything to do. So an application
 * with many loggers has at most {@code maxThreads} logging threads, not one per logger.
 *
 * <p>Threads are started when a logger first needs them and exit once the last logger they serve
 * has closed. By default every logger uses {@link #shared()}; a logger can be given its own pool
 * with {@link EfficientLoggerBuilder#workerPool}, for example to keep a critical logger's thread to
 * itself, or to name the threads.
 *
 * <p>Thread-safe.
 */
public final class WorkerPool {

    /** The system property that sizes {@link #shared()}: {@value}. */
    public static final String SHARED_THREADS_PROPERTY = "efficientlogger.sharedWorkerThreads";
    /** Threads in the shared pool unless the property says otherwise: the smaller of 4 and the processor count. */
    static final int DEFAULT_SHARED_THREADS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    static final int MAX_THREADS = 256;

    private static final AtomicInteger POOL_NUMBERS = new AtomicInteger();
    private static final Worker[] NO_WORKERS = new Worker[0];
    private static volatile WorkerPool shared; // created on first use

    private final int maxThreads;
    private final ThreadFactory factory;
    private final int number;
    private final Object lock = new Object();
    private final Runner[] runners; // guarded by lock; null where no thread is running

    private WorkerPool(int maxThreads, ThreadFactory factory) {
        this.maxThreads = maxThreads;
        this.number = POOL_NUMBERS.incrementAndGet();
        this.factory = factory != null ? factory : defaultThreadFactory();
        this.runners = new Runner[maxThreads];
    }

    /**
     * The pool every logger uses unless told otherwise. It has {@value #SHARED_THREADS_PROPERTY}
     * threads if that system property is set, else the smaller of 4 and the number of processors.
     *
     * @throws IllegalArgumentException if the property is set to something other than an integer
     *     from 1 to 256
     */
    public static WorkerPool shared() {
        WorkerPool pool = shared;
        if (pool == null) {
            synchronized (WorkerPool.class) {
                pool = shared;
                if (pool == null) {
                    pool = create(sharedThreads());
                    shared = pool;
                }
            }
        }
        return pool;
    }

    /**
     * A pool of at most {@code maxThreads} daemon threads, named
     * {@code efficient-logger-<pool>-worker-<i>}.
     *
     * @param maxThreads 1 to 256
     */
    public static WorkerPool create(int maxThreads) {
        return create(maxThreads, null);
    }

    /**
     * A pool of at most {@code maxThreads} threads made by {@code factory}, which should make daemon
     * threads: a non-daemon worker would keep the JVM from exiting while any logger is open.
     *
     * <p>The factory is called while a logger is being built, under the pool's lock, so it must
     * return promptly and must not block, and must not take a lock that a {@link WarningHandler}
     * might hold while it builds a logger. While it runs, no other logger can register with this
     * pool and none of the pool's threads can exit.
     *
     * @param maxThreads 1 to 256
     * @throws NullPointerException if {@code factory} is null
     */
    public static WorkerPool create(int maxThreads, ThreadFactory factory) {
        if (maxThreads < 1 || maxThreads > MAX_THREADS) {
            throw new IllegalArgumentException("maxThreads must be between 1 and " + MAX_THREADS + ", was " + maxThreads);
        }
        return new WorkerPool(maxThreads, factory);
    }

    /** The most threads this pool will run. */
    public int maxThreads() {
        return maxThreads;
    }

    /** The threads running now. Zero when no logger using this pool is open. */
    public int threads() {
        synchronized (lock) {
            int n = 0;
            for (Runner r : runners) {
                if (r != null) {
                    n++;
                }
            }
            return n;
        }
    }

    @Override
    public String toString() {
        return "WorkerPool[" + number + ", maxThreads=" + maxThreads + ", threads=" + threads() + "]";
    }

    private static int sharedThreads() {
        String value = System.getProperty(SHARED_THREADS_PROPERTY);
        if (value == null) {
            return DEFAULT_SHARED_THREADS;
        }
        int threads;
        try {
            threads = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(SHARED_THREADS_PROPERTY + " must be an integer from 1 to " + MAX_THREADS
                    + ", was \"" + value + "\"");
        }
        if (threads < 1 || threads > MAX_THREADS) {
            throw new IllegalArgumentException(SHARED_THREADS_PROPERTY + " must be between 1 and " + MAX_THREADS
                    + ", was " + threads);
        }
        return threads;
    }

    private ThreadFactory defaultThreadFactory() {
        return runnable -> {
            Thread t = new Thread(runnable, "efficient-logger-" + number + "-worker-" + ((Runner) runnable).slot);
            t.setDaemon(true);
            return t;
        };
    }

    // ---- what loggers call ----

    /**
     * Gives a logger's workers to the pool's threads, each to the one serving the fewest workers,
     * starts the threads that aren't running yet, and wakes the ones that are, so a thread asleep
     * with nothing to do picks the new worker up at once rather than when one of its other loggers
     * happens to wake it. Every thread is created before any is started, so a worker can't run
     * before its logger has finished registering. If a thread can't be made or started, nothing
     * stays registered and the error propagates.
     *
     * @throws IllegalStateException if the thread factory returns null
     */
    void register(Worker[] workers) {
        synchronized (lock) {
            List<Runner> fresh = new ArrayList<>();
            List<Runner> assigned = new ArrayList<>(workers.length);
            int started = 0;
            try {
                for (Worker w : workers) {
                    Runner r = leastLoaded();
                    if (r.thread == null) {
                        Thread t = factory.newThread(r);
                        if (t == null) {
                            throw new IllegalStateException("threadFactory returned null");
                        }
                        r.thread = t;
                        runners[r.slot] = r;
                        fresh.add(r);
                    }
                    w.attach(r.thread); // before the thread can see the worker, so flush/close from a handler on it recognise it
                    r.add(w);
                    assigned.add(r);
                }
                for (; started < fresh.size(); started++) {
                    fresh.get(started).thread.start();
                }
                for (Runner r : assigned) {
                    // A running thread reads its list once per pass and only then sleeps. If it read
                    // the list before the add, the permit ends its sleep and the next pass sees the
                    // worker. Without this, a thread in deep sleep (and a new ring, whose parked
                    // state no producer finds set) would sleep on while the new logger's queue
                    // filled and dropped.
                    LockSupport.unpark(r.thread);
                }
            } catch (RuntimeException | Error e) {
                // Don't leave the logger half-registered. A thread already started finds no work
                // and exits; one not yet started is forgotten.
                for (int i = 0; i < assigned.size(); i++) {
                    assigned.get(i).remove(workers[i]);
                }
                for (int i = started; i < fresh.size(); i++) {
                    runners[fresh.get(i).slot] = null;
                }
                throw e;
            }
        }
    }

    /** Called by the thread serving {@code worker} once it has finished. */
    private void unregister(Runner runner, Worker worker) {
        synchronized (lock) {
            runner.remove(worker);
        }
    }

    /** The runner with the fewest workers; a slot without one counts as empty and gets a new runner. */
    private Runner leastLoaded() {
        Runner best = null;
        int bestSlot = -1;
        int bestLoad = Integer.MAX_VALUE;
        for (int i = 0; i < runners.length; i++) {
            Runner r = runners[i];
            int load = r == null ? 0 : r.workers.length;
            // Prefer a running thread with nothing to do over starting one (it hasn't exited: that
            // takes this lock, and re-checks).
            if (load < bestLoad || (load == bestLoad && best == null && r != null)) {
                best = r;
                bestSlot = i;
                bestLoad = load;
            }
        }
        return best != null ? best : new Runner(bestSlot);
    }

    /** True if the runner may exit: it has no workers and its slot is freed. */
    private boolean retire(Runner runner) {
        synchronized (lock) {
            if (runner.workers.length != 0) {
                return false;
            }
            runners[runner.slot] = null;
            return true;
        }
    }

    // ---- the thread ----

    /**
     * One pool thread's loop over the workers assigned to it. The list is copy-on-write under the
     * pool's lock, and the loop reads it once per pass, so stepping a worker never takes a lock and
     * allocates nothing.
     */
    private final class Runner implements Runnable {
        final int slot;
        Thread thread; // set, under the pool's lock, before start
        volatile Worker[] workers = NO_WORKERS;

        Runner(int slot) {
            this.slot = slot;
        }

        void add(Worker w) {
            Worker[] old = workers;
            Worker[] copy = new Worker[old.length + 1];
            System.arraycopy(old, 0, copy, 0, old.length);
            copy[old.length] = w;
            workers = copy;
        }

        void remove(Worker w) {
            Worker[] old = workers;
            for (int i = 0; i < old.length; i++) {
                if (old[i] == w) {
                    Worker[] copy = new Worker[old.length - 1];
                    System.arraycopy(old, 0, copy, 0, i);
                    System.arraycopy(old, i + 1, copy, i, old.length - i - 1);
                    workers = copy;
                    return;
                }
            }
        }

        /**
         * The thread never dies while it has workers: each call into a worker is guarded, and so is
         * the pass as a whole. A thread that died would stay in the pool's table, so its loggers
         * would never be served again and new loggers would be sent to it.
         */
        @Override
        public void run() {
            for (;;) {
                try {
                    if (!pass()) {
                        return;
                    }
                } catch (Throwable t) {
                    // Nothing below should let anything through; this is the last resort.
                    Worker[] ws = workers;
                    if (ws.length > 0) {
                        ws[0].recover(t);
                    } else {
                        LockSupport.parkNanos(this, Worker.ERROR_PAUSE_NANOS);
                    }
                }
            }
        }

        /** One pass over the workers, then the most urgent wait any of them asked for. False once the thread may exit. */
        private boolean pass() {
            Thread.interrupted(); // a leftover interrupt would make every park() return at once
            Worker[] ws = workers;
            if (ws.length == 0) {
                return !retire(this); // else a worker arrived meanwhile
            }
            int want = Worker.IDLE;
            for (Worker w : ws) {
                int s;
                try {
                    s = w.step();
                } catch (Throwable t) {
                    w.recover(t);
                    s = Worker.CONTINUE;
                }
                if (s == Worker.DONE) {
                    finish(w);
                    s = Worker.CONTINUE; // the list has changed: read it again before sleeping
                }
                if (s < want) {
                    want = s;
                }
            }
            switch (want) {
                case Worker.CONTINUE:
                    break;
                case Worker.AWAIT_PUBLISH:
                    awaitPublish(ws);
                    break;
                case Worker.CLOSING:
                    waitWhileClosing(ws);
                    break;
                default:
                    sleep(ws);
                    break;
            }
            return true;
        }

        /**
         * Closes the worker's files, tells its logger, then forgets it. The logger is told first,
         * so a close() waiting on it is released without waiting for the pool's lock, which a
         * registration in progress may hold while it runs the thread factory.
         */
        private void finish(Worker w) {
            try {
                w.finish();
            } catch (Throwable t) {
                w.recover(t); // finish() guards itself; this is a last resort
            } finally {
                try {
                    Runnable callback = w.onTerminated();
                    if (callback != null) {
                        callback.run();
                    }
                } catch (Throwable t) {
                    w.recover(t);
                } finally {
                    unregister(this, w);
                }
            }
        }

        /**
         * Some worker's head slot is claimed but not published. Spin while any of them has only just
         * started waiting; once they have all waited {@link Worker#STALL_SPINS} passes, poll.
         */
        private void awaitPublish(Worker[] ws) {
            for (Worker w : ws) {
                if (w.stalledBriefly()) {
                    spinThenYield();
                    return;
                }
            }
            LockSupport.parkNanos(this, Worker.STUCK_SLOT_POLL_NANOS);
        }

        /** A logger is closing with nothing here to take: producers are still publishing, or other threads still draining. */
        private void waitWhileClosing(Worker[] ws) {
            for (int i = 0; i < Worker.SPINS; i++) {
                if (anyRingHasMessages(ws)) {
                    return;
                }
                Thread.onSpinWait();
            }
            Thread.yield();
            LockSupport.parkNanos(this, Worker.CLOSING_POLL_NANOS);
        }

        private static boolean anyRingHasMessages(Worker[] ws) {
            for (Worker w : ws) {
                if (w.ring.head() != w.ring.tail()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Nothing to do anywhere: sleep until a producer, a close or a flush wakes the thread, or
         * until the shortest flush interval of the workers sleeping TIMED has passed. Each worker
         * announces its own mode on its ring and then re-checks it (see {@link MpscRing}).
         */
        private void sleep(Worker[] ws) {
            for (Worker w : ws) {
                try {
                    w.beforeSleep();
                } catch (Throwable t) {
                    // E.g. an OutOfMemoryError from a write (FileOutputStream allocates a native
                    // buffer for writes over 8 KiB) or from building a warning. The records stay
                    // buffered and the next step writes them out, or fails the file properly.
                    w.recover(t);
                }
            }
            boolean deep = true;
            long nanos = Long.MAX_VALUE;
            for (Worker w : ws) {
                if (w.announceSleep() == MpscRing.TIMED) {
                    deep = false;
                    nanos = Math.min(nanos, w.flushIntervalNanos());
                }
            }
            boolean work = false;
            for (Worker w : ws) {
                work |= w.hasWork();
            }
            long slept = 0;
            if (!work) {
                long start = System.nanoTime();
                if (deep) {
                    LockSupport.park(this);
                } else {
                    LockSupport.parkNanos(this, nanos);
                }
                slept = System.nanoTime() - start;
            }
            for (Worker w : ws) {
                w.afterSleep(slept);
            }
        }

        private static void spinThenYield() {
            for (int i = 0; i < Worker.SPINS; i++) {
                Thread.onSpinWait();
            }
            Thread.yield();
        }
    }
}
