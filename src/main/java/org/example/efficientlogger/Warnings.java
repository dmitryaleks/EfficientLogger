package org.example.efficientlogger;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** The logger's warning output: safe calls to the user's handler, and logger-wide report limits. */
final class Warnings {

    static final long IO_ERROR_REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    static final int MAX_INVALID_ID_REPORTS = 100;

    /** A warning built while holding a lock, to be emitted after releasing it. */
    record Pending(String message, Throwable cause) {}

    private final WarningHandler handler;
    private final AtomicInteger invalidIdReports = new AtomicInteger();
    private final AtomicBoolean thrashReported = new AtomicBoolean();

    // Guarded by this.
    private boolean ioErrorReported;
    private long nextIoErrorReportNanos;
    private long suppressedIoErrors;

    Warnings(WarningHandler handler) {
        this.handler = handler;
    }

    /** Passes a warning to the handler. Anything the handler throws is ignored. */
    void emit(String message, Throwable cause) {
        try {
            handler.warn(message, cause);
        } catch (Throwable ignored) {
            // A broken handler must not break logging.
        }
    }

    /** Emits each pending warning in order. {@code pending} may be null. */
    void emitAll(List<Pending> pending) {
        if (pending == null) {
            return;
        }
        for (int i = 0; i < pending.size(); i++) {
            Pending p = pending.get(i);
            emit(p.message(), p.cause());
        }
    }

    /**
     * Rate-limits I/O error reports to one per 10 seconds. Returns -1 if an error at {@code nowNanos}
     * shouldn't be reported, otherwise how many errors were held back since the last report.
     */
    synchronized long admitIoErrorReport(long nowNanos) {
        if (ioErrorReported && nowNanos - nextIoErrorReportNanos < 0) {
            suppressedIoErrors++;
            return -1;
        }
        ioErrorReported = true;
        nextIoErrorReportNanos = nowNanos + IO_ERROR_REPORT_INTERVAL_NANOS;
        long suppressed = suppressedIoErrors;
        suppressedIoErrors = 0;
        return suppressed;
    }

    /**
     * At close: reports the I/O errors the rate limit held back since the last report, which would
     * otherwise go unmentioned. Reports each held-back error once, however many owners call it.
     */
    void reportSuppressedIoErrors() {
        long n;
        synchronized (this) {
            n = suppressedIoErrors;
            suppressedIoErrors = 0;
        }
        if (n > 0) {
            emit(n == 1 ? "1 more I/O error was not reported" : n + " more I/O errors were not reported", null);
        }
    }

    /** Numbers invalid-id reports 1 to 100; returns 0 once that many have been made. */
    int admitInvalidIdReport() {
        if (invalidIdReports.get() >= MAX_INVALID_ID_REPORTS) {
            return 0;
        }
        int n = invalidIdReports.incrementAndGet();
        return n <= MAX_INVALID_ID_REPORTS ? n : 0;
    }

    /** True the first time only. */
    boolean admitThrashReport() {
        return !thrashReported.get() && thrashReported.compareAndSet(false, true);
    }
}
