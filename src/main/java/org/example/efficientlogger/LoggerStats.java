package org.example.efficientlogger;

/**
 * A snapshot of a logger's counters, in messages unless noted. Once a logger is closed,
 * {@code accepted == written + droppedInvalidId + lostIoError + abandoned}, and {@code abandoned}
 * is 0 unless {@code close} gave up (see {@link EfficientLogger#close(java.time.Duration)}).
 *
 * @param accepted taken for writing: queued in async mode, attempted in sync mode, and in both
 *     modes those rejected at once because the id was too long for a file name
 * @param written handed to the OS
 * @param droppedQueueFull dropped because the queue was at maxQueuedMessages or maxQueuedChars
 * @param droppedTooLarge dropped because the message alone was longer than maxQueuedChars
 * @param droppedInvalidId dropped because the id can't be used in a file name, including ids
 *     too long for one, which {@code log} rejects at once
 * @param lostIoError lost to I/O errors, including messages dropped while a failed file waits
 *     to be retried (and, should one ever happen, to unexpected internal errors)
 * @param abandoned queued but never written, because {@code close} gave up on a queue that had
 *     stopped draining: a {@code log} call ahead of them was cut off, or stalled, between being
 *     admitted and queueing its message (that call's own place in the queue is counted too).
 *     Always 0 in synchronous mode, and 0 until the worker has stopped
 * @param rejectedClosed {@code log} calls made after close
 * @param queuedMessages waiting in the queue. After {@code close} returns, anything still here
 *     was given up on: the {@code abandoned} messages, plus any whose {@code log} call was cut
 *     off before it reached a queue
 * @param queuedChars total length, in chars, of the messages waiting in the queue
 * @param openFiles files open right now
 */
public record LoggerStats(
        long accepted,
        long written,
        long droppedQueueFull,
        long droppedTooLarge,
        long droppedInvalidId,
        long lostIoError,
        long abandoned,
        long rejectedClosed,
        long queuedMessages,
        long queuedChars,
        int openFiles) {}
