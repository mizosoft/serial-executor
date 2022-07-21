/*
 * Copyright (c) 2022 Moataz Abdelnasser
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.mizosoft.concurrent.serialexecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class SerialExecutorTest {
  private MockExecutor mockExecutor;
  private SerialExecutor executor;

  @BeforeEach
  void setUp() {
    mockExecutor = new MockExecutor();
    executor = new SerialExecutor(mockExecutor);
  }

  @Test
  void sequentialExecution() {
    var calls = new AtomicInteger();
    Runnable incrementTask =
        () -> {
          calls.incrementAndGet();
          // There shouldn't be any awaiting drain tasks
          assertThat(mockExecutor.hasNext()).isFalse();
        };

    executor.execute(incrementTask);
    assertThat(mockExecutor.taskCount()).isOne();

    executor.execute(incrementTask);
    // SerialExecutor's drain task is only submitted once
    assertThat(mockExecutor.taskCount()).isOne();
    // Nothing is run so far
    assertThat(calls).hasValue(0);

    // Run drain task -> all submitted incrementTasks run
    mockExecutor.runNext();
    assertThat(calls).hasValue(2);
    assertThat(mockExecutor.hasNext()).isFalse();

    for (int i = 0; i < 10; i++) {
      executor.execute(incrementTask);
    }
    assertThat(mockExecutor.taskCount()).isOne();

    mockExecutor.runNext();
    assertThat(calls).hasValue(12);
  }

  @Test
  void executionOrder() {
    var order = new ArrayList<Integer>();
    for (int i = 0; i < 10; i++) {
      int val = i; // Can't use i in lambda
      executor.execute(() -> order.add(val));
    }
    mockExecutor.runNext();
    assertThat(order).containsExactly(IntStream.range(0, 10).boxed().toArray(Integer[]::new));
  }

  @Test
  void rejectedExecution() {
    var calls = new AtomicInteger();

    mockExecutor.reject(true);
    assertThatThrownBy(() -> executor.execute(calls::incrementAndGet))
        .isInstanceOf(RejectedExecutionException.class);
    assertThat(mockExecutor.taskCount()).isZero();

    mockExecutor.reject(false);
    executor.execute(calls::incrementAndGet);
    assertThat(mockExecutor.taskCount()).isOne();

    mockExecutor.reject(true);
    // Nothing submitted to the delegate so nothing is rejected
    executor.execute(calls::incrementAndGet);
    assertThat(mockExecutor.taskCount()).isOne();

    mockExecutor.runNext();
    // The first rejected task is not retained, so there are only 2 increments
    assertThat(calls).hasValue(2);
  }

  @Test
  void shutdown() {
    var calls = new AtomicInteger();

    executor.execute(calls::incrementAndGet);
    assertThat(mockExecutor.taskCount()).isOne();
    executor.shutdown();
    assertThatThrownBy(() -> executor.execute(calls::incrementAndGet))
        .isInstanceOf(RejectedExecutionException.class);

    // Shutdown doesn't prevent already scheduled drain from running
    mockExecutor.runNext();
    assertThat(calls).hasValue(1);
  }

  @Test
  void throwFromTask() {
    var calls = new AtomicInteger();
    Runnable saneTask = calls::incrementAndGet;
    Runnable crazyTask =
        () -> {
          calls.incrementAndGet();
          throw new TestingException();
        };

    executor.execute(saneTask);
    executor.execute(crazyTask);
    executor.execute(saneTask);
    // Drain task is submitted
    assertThat(mockExecutor.taskCount()).isOne();

    // saneTask runs then exception propagates from crazyTask
    // to delegate executor's thread (current thread)
    assertThatThrownBy(mockExecutor::runNext).isInstanceOf(TestingException.class);
    assertThat(calls).hasValue(2);
    // Drain task is retried with common FJ pool
    assertThat(mockExecutor.awaitNext(10, TimeUnit.SECONDS))
        .withFailMessage("drain task wasn't retried in 10 secs")
        .isTrue();
    assertThat(mockExecutor.taskCount()).isOne();

    mockExecutor.runNext();
    assertThat(calls).hasValue(3);
  }

  /** See javadoc of {@link SerialExecutor#sync}. */
  @Test
  void submissionABA() {
    Executor sameThreadExecutor =
        r -> {
          assertFalse(executor.isRunningBitSet());
          r.run();
          assertFalse(executor.isRunningBitSet());
        };
    executor = new SerialExecutor(sameThreadExecutor);

    var calls = new AtomicInteger();
    Runnable incrementTask =
        () -> {
          calls.incrementAndGet();
          // Make sure the drain task has set the RUNNING bit
          assertTrue(executor.isRunningBitSet());
        };

    executor.execute(incrementTask);
    assertThat(calls).hasValue(1);
    assertThat(executor.drainCount()).isOne();
    assertThat(executor.isSubmittedBitSet()).isFalse();

    executor.execute(incrementTask);
    assertThat(calls).hasValue(2);
    assertThat(executor.drainCount()).isEqualTo(2);
    assertThat(executor.isSubmittedBitSet()).isFalse();
  }

  @RepeatedTest(10)
  void executionFromMultipleThreads() throws InterruptedException {
    var service = Executors.newCachedThreadPool();
    executor = new SerialExecutor(service);

    var concurrentAccessDetector =
        new Object() {
          private final AtomicBoolean acquired = new AtomicBoolean();

          void acquire() {
            assertTrue(acquired.compareAndSet(false, true), "concurrent access detected");
          }

          void release() {
            assertTrue(acquired.compareAndSet(true, false), "concurrent access detected");
          }
        };

    int threadCount = 10;
    var arrival = new CyclicBarrier(threadCount);
    var completion = new CountDownLatch(threadCount);
    var calls = new AtomicInteger();
    for (int i = 0; i < threadCount; i++) {
      service.execute(
          () -> {
            awaitUninterruptibly(arrival);
            executor.execute(
                () -> {
                  concurrentAccessDetector.acquire();
                  try {
                    calls.incrementAndGet();
                  } finally {
                    concurrentAccessDetector.release();
                    completion.countDown();
                  }
                });
          });
    }

    assertTrue(completion.await(10, TimeUnit.SECONDS));
    assertEquals(threadCount, calls.get());
  }

  @Test
  void incrementingDrainCountMaintainsStateBits() {
    executor.execute(() -> {});
    assertEquals(1, mockExecutor.taskCount());

    // Set SHUTDOWN bit
    executor.shutdown();
    assertTrue(executor.isShutdownBitSet());

    // Execute drain to increment drain count
    mockExecutor.runNext();
    assertEquals(1, executor.drainCount());

    // Shutdown bit isn't touched
    assertTrue(executor.isShutdownBitSet());
  }

  private static void awaitUninterruptibly(CyclicBarrier barrier) {
    while (true) {
      try {
        barrier.await();
        return;
      } catch (InterruptedException ignored) {
        // continue;
      } catch (BrokenBarrierException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
