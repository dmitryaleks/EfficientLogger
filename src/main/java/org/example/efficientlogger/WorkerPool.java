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
     * and starts the threads that aren't running yet. Every thread is created before any is started,
     * so a worker can't run before its logger has finished registering. If a thread can't be made
     * or started, nothing stays registered and the error propagates.
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
                    r.add(w);
                    assigned.add(r);
                    w.attach(r.thread);
                }
                for (; started < fresh.size(); started++) {
                    fresh.get(started).thread.start();
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

        @Override
        public void run() {
            for (;;) {
                Thread.interrupted(); // a leftover interrupt would make every park() return at once
                Worker[] ws = workers;
                if (ws.length == 0) {
                    if (retire(this)) {
                        return;
                    }
                    continue; // a worker arrived meanwhile
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
            }
        }

        /** Closes the worker's files and tells its logger, then forgets it. */
        private void finish(Worker w) {
            try {
                w.finish();
            } catch (Throwable t) {
                w.recover(t); // finish() guards itself; this is a last resort
            } finally {
                unregister(this, w);
                Runnable callback = w.onTerminated();
                if (callback != null) {
                    callback.run();
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
                w.beforeSleep();
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
