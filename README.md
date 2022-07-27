# SerialExecutor

An [`Exectuor`](https://docs.oracle.com/en/java/javase/18/docs/api/java.base/java/util/concurrent/Executor.html)
that guarantees task-wise mutual exclusion on top of an arbitrary `Executor` implementation. This
is similar to [Guava's](https://github.com/google/guava) [SequentialExecutor](https://github.com/google/guava/blob/master/guava/src/com/google/common/util/concurrent/SequentialExecutor.java),
but completely relies on atomics for synchronization instead of built-in locks.

## Usage 

Just create a `SerialExecutor` (you can copy the [code](/serial-executor/src/main/java/com/github/mizosoft/concurrent/serialexecutor/SerialExecutor.java) into your project),
passing it the delegate executor of your choice (i.e. `ThreadPoolExecutor`). All the `Runnables` you 
pass are guaranteed to run sequentially in order. Instances are very lightweight, so you
can create many instances to serialize tasks mutating each of many objects, although not preventing
tasks targeting different objects from running concurrently.

Here's an example of a class that maintains a set of files, each updated regularly. Update requests might
arrive from multiple concurrent producers. To avoid file corruption, you don't want the same file to be
updated concurrently, but it's OK to allow concurrent updates to different files. The updates are guaranteed
to run sequentially w.r.t to each file.

```java
class FileUpdater {
  private static final Logger logger = System.getLogger(FileUpdater.class.getName());

  private final Executor delegate = Executors.newCachedThreadPool();
  private final Map<Path, SerialExecutor> executors = new ConcurrentHashMap<>();

  void update(Path path, UpdateOperation update) {
    var executor = executors.computeIfAbsent(path, __ -> new SerialExecutor(delegate));
    executor.execute(() -> {
      try {
        update.accept(path);
      } catch (IOException e) {
        logger.log(Level.ERROR, "file update failed", e);
      }
    });
  }

  interface UpdateOperation {
    void accept(Path file) throws IOException;
  }
}
```

# Tests

In addition to `SerialExecutor`'s own tests, Guava's `SequantialExecutorTest` is also brought in. However, there are 
two incompatibilities: 

 * Unlike `SequentialExecutor`, `SerialExecutor` propagates exception thrown by executing tasks (Guava just logs them). 
   The queue worker is restarted by executing a bogus task (via `ForkJoinPool.commonPool()`, as delegate is not guaranteed 
   to be concurrent (i.e. same-thread executor)).
 * `SerialExecutor` doesn't track the currently running task, so it's not produced in `toString`. 

# Benchmarks

TODO put benchmarks & results.
