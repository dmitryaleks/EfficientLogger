package org.example.efficientlogger;

/**
 * The limits every logger in a process shares: the cap on each file's size, the count of files
 * created, the free-space mark and the output rate cap. There is one instance per JVM
 * ({@link #shared()}), and each {@code build()} applies to it the settings its builder set, so
 * the latest build wins, for every logger, including those built earlier: a file cap takes effect
 * at each file's next record, a lower file count caps new IDs until it is raised, a new mark or
 * rate is used at the next reading or charge. A build that leaves a setting out leaves it as it
 * is; the file cap is mandatory, so every build sets that one.
 *
 * <p>What stays per logger: its open-file budget, its queue, and the counting and reporting of
 * what the shared limits made it drop (each logger's {@code stats()} and warning handler see its
 * own drops). Tests get an instance of their own through {@link TestHooks}, as they get a private
 * {@link WorkerPool}.
 */
final class ProcessLimits {

    private static volatile ProcessLimits shared; // created on first use

    private volatile long maxFileBytes = Long.MAX_VALUE;
    final FileCreationBudget files = new FileCreationBudget();
    final DiskMark disk = new DiskMark();
    final RateMeter rate = new RateMeter();

    /** The instance every logger built with {@link EfficientLoggerBuilder#build()} uses. */
    static ProcessLimits shared() {
        ProcessLimits limits = shared;
        if (limits == null) {
            synchronized (ProcessLimits.class) {
                limits = shared;
                if (limits == null) {
                    limits = new ProcessLimits();
                    shared = limits;
                }
            }
        }
        return limits;
    }

    /** Applies what a builder set: the latest build in the process wins. */
    void apply(Config config) {
        maxFileBytes = config.maxFileBytes;
        if (config.maxFilesSet) {
            files.setMax(config.maxFiles);
        }
        if (config.minFreeDiskBytes > 0) {
            disk.set(config.minFreeDiskBytes, config.resumeFreeDiskBytes);
        }
        if (config.maxCharsPerSecond > 0) {
            rate.set(config.maxCharsPerSecond);
        }
    }

    /** A file never grows past this; read at each record, so a change applies at once. */
    long maxFileBytes() {
        return maxFileBytes;
    }

    /** For tests that build a FileTable by hand. */
    void setMaxFileBytes(long bytes) {
        maxFileBytes = bytes;
    }
}
