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

package com.github.mizosoft.concurrent.serialexecutor.benchmarks;

import com.github.mizosoft.concurrent.serialexecutor.SerialExecutor;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.jctools.queues.MpscLinkedQueue;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 5)
@Measurement(iterations = 4, time = 5)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.Throughput)
@Fork(2)
public class SerialExecutorSameThreadBenchmark {
  public enum Impl {
    SERIAL_EXECUTOR {
      @Override
      Executor create(Executor delegate) {
        return new SerialExecutor(delegate);
      }
    },

    SERIAL_EXECUTOR_MPSC_LINKED_QUEUE {
      @Override
      Executor create(Executor delegate) {
        return new SerialExecutor(delegate, new MpscLinkedQueue<>());
      }
    },

    GUAVA {
      @Override
      Executor create(Executor delegate) {
        return MoreExecutors.newSequentialExecutor(delegate);
      }
    };

    abstract Executor create(Executor delegate);
  }

  @Param public Impl impl;

  private Executor executor;

  @Setup(Level.Trial)
  public void setUp() {
    executor = impl.create(Runnable::run);
  }

  volatile int i;

  @Benchmark
  @Threads(1)
  public void execute_singleThread() {
    executor.execute(() -> i++);
  }

  public static void main(String[] args) throws RunnerException {
    var builder =
        new OptionsBuilder()
            .include(SerialExecutorSameThreadBenchmark.class.getSimpleName())
            //            .addProfiler("gc")
            .shouldFailOnError(true);
    new Runner(builder.build()).run();
  }
}
