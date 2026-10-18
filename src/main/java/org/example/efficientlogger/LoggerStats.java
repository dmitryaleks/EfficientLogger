package org.example.efficientlogger;

/**
 * A snapshot of a logger's counters, in messages unless noted. Once a logger is closed and
 * drained, {@code accepted == written + droppedInvalidId + lostIoError}.
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
 * @param rejectedClosed {@code log} calls made after close
 * @param queuedMessages waiting in the queue
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
        long rejectedClosed,
        long queuedMessages,
        long queuedChars,
        int openFiles) {}
