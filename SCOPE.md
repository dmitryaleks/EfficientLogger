# Scope

I'm currently using log4j to write multiple JSON log files, appending after process restart.

## Compile time

Gradle with Java 17

## Runtime

Java 17

## Architecture

An EfficientLogger that:

  - is configured with a target directory and filename pattern;
  - takes in an ID to fill the file name pattern;
  - takes in JSON strings to append to a file (or create a file if it is not there);
  - is asynchronous;
  - exercises minimal blocking for the main thread;
  - is frugal with Java object allocation;
  - has a configuration for the maximum in-memory buffer/queue size (drops messages beyond that with a single warning);
  - can have a thread pool (one thread by default) to facilitate the async mode;
  - can be configured to act in a sync mode (no extra threads).