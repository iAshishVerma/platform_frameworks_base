/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.perftests.utils;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Debug;
import android.support.test.InstrumentationRegistry;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Provides a benchmark framework.
 *
 * Example usage:
 * // Executes the code while keepRunning returning true.
 *
 * public void sampleMethod() {
 *     BenchmarkState state = new BenchmarkState();
 *
 *     int[] src = new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 };
 *     while (state.keepRunning()) {
 *         int[] dest = new int[src.length];
 *         System.arraycopy(src, 0, dest, 0, src.length);
 *     }
 *     System.out.println(state.summaryLine());
 * }
 */
public final class BenchmarkState {

    private static final String TAG = "BenchmarkState";
    private static final boolean ENABLE_PROFILING = false;

    private static final int NOT_STARTED = 0;  // The benchmark has not started yet.
    private static final int WARMUP = 1; // The benchmark is warming up.
    private static final int RUNNING = 2;  // The benchmark is running.
    private static final int FINISHED = 3;  // The benchmark has stopped.

    private int mState = NOT_STARTED;  // Current benchmark state.

    private static final long WARMUP_DURATION_NS = ms2ns(250); // warm-up for at least 250ms
    private static final int WARMUP_MIN_ITERATIONS = 16; // minimum iterations to warm-up for

    // TODO: Tune these values.
    private static final long TARGET_TEST_DURATION_NS = ms2ns(500); // target testing for 500 ms
    private static final int MAX_TEST_ITERATIONS = 1000000;
    private static final int MIN_TEST_ITERATIONS = 10;
    private static final int REPEAT_COUNT = 5;

    private long mStartTimeNs = 0;  // Previously captured System.nanoTime().
    private boolean mPaused;
    private long mPausedTimeNs = 0; // The System.nanoTime() when the pauseTiming() is called.
    private long mPausedDurationNs = 0;  // The duration of paused state in nano sec.

    private int mIteration = 0;
    private int mMaxIterations = 0;

    private int mRepeatCount = 0;

    // Statistics. These values will be filled when the benchmark has finished.
    // The computation needs double precision, but long int is fine for final reporting.
    private long mMedian = 0;
    private double mMean = 0.0;
    private double mStandardDeviation = 0.0;
    private long mMin = 0;

    // Individual duration in nano seconds.
    private ArrayList<Long> mResults = new ArrayList<>();

    private static final long ms2ns(long ms) {
        return TimeUnit.MILLISECONDS.toNanos(ms);
    }

    /**
     * Calculates statistics.
     */
    private void calculateSatistics() {
        final int size = mResults.size();
        if (size <= 1) {
            throw new IllegalStateException("At least two results are necessary.");
        }

        Collections.sort(mResults);
        mMedian = size % 2 == 0 ? (mResults.get(size / 2) + mResults.get(size / 2 + 1)) / 2 :
                mResults.get(size / 2);

        mMin = mResults.get(0);
        for (int i = 0; i < size; ++i) {
            long result = mResults.get(i);
            mMean += result;
            if (result < mMin) {
                mMin = result;
            }
        }
        mMean /= (double) size;

        for (int i = 0; i < size; ++i) {
            final double tmp = mResults.get(i) - mMean;
            mStandardDeviation += tmp * tmp;
        }
        mStandardDeviation = Math.sqrt(mStandardDeviation / (double) (size - 1));
    }

    // Stops the benchmark timer.
    // This method can be called only when the timer is running.
    public void pauseTiming() {
        if (mPaused) {
            throw new IllegalStateException(
                    "Unable to pause the benchmark. The benchmark has already paused.");
        }
        mPausedTimeNs = System.nanoTime();
        mPaused = true;
    }

    // Starts the benchmark timer.
    // This method can be called only when the timer is stopped.
    public void resumeTiming() {
        if (!mPaused) {
            throw new IllegalStateException(
                    "Unable to resume the benchmark. The benchmark is already running.");
        }
        mPausedDurationNs += System.nanoTime() - mPausedTimeNs;
        mPausedTimeNs = 0;
        mPaused = false;
    }

    private void beginWarmup() {
        mStartTimeNs = System.nanoTime();
        mIteration = 0;
        mState = WARMUP;
    }

    private void beginBenchmark(long warmupDuration, int iterations) {
        if (ENABLE_PROFILING) {
            File f = new File(InstrumentationRegistry.getContext().getDataDir(), "benchprof");
            Log.d(TAG, "Tracing to: " + f.getAbsolutePath());
            Debug.startMethodTracingSampling(f.getAbsolutePath(), 16 * 1024 * 1024, 100);
        }
        mMaxIterations = (int) (TARGET_TEST_DURATION_NS / (warmupDuration / iterations));
        mMaxIterations = Math.min(MAX_TEST_ITERATIONS,
                Math.max(mMaxIterations, MIN_TEST_ITERATIONS));
        mPausedDurationNs = 0;
        mIteration = 0;
        mRepeatCount = 0;
        mState = RUNNING;
        mStartTimeNs = System.nanoTime();
    }

    private boolean startNextTestRun() {
        final long currentTime = System.nanoTime();
        mResults.add((currentTime - mStartTimeNs - mPausedDurationNs) / mMaxIterations);
        mRepeatCount++;
        if (mRepeatCount >= REPEAT_COUNT) {
            if (ENABLE_PROFILING) {
                Debug.stopMethodTracing();
            }
            calculateSatistics();
            mState = FINISHED;
            return false;
        }
        mPausedDurationNs = 0;
        mIteration = 0;
        mStartTimeNs = System.nanoTime();
        return true;
    }

    /**
     * Judges whether the benchmark needs more samples.
     *
     * For the usage, see class comment.
     */
    public boolean keepRunning() {
        switch (mState) {
            case NOT_STARTED:
                beginWarmup();
                return true;
            case WARMUP:
                mIteration++;
                // Only check nanoTime on every iteration in WARMUP since we
                // don't yet have a target iteration count.
                final long duration = System.nanoTime() - mStartTimeNs;
                if (mIteration >= WARMUP_MIN_ITERATIONS && duration >= WARMUP_DURATION_NS) {
                    beginBenchmark(duration, mIteration);
                }
                return true;
            case RUNNING:
                mIteration++;
                if (mIteration >= mMaxIterations) {
                    return startNextTestRun();
                }
                if (mPaused) {
                    throw new IllegalStateException(
                            "Benchmark step finished with paused state. " +
                            "Resume the benchmark before finishing each step.");
                }
                return true;
            case FINISHED:
                throw new IllegalStateException("The benchmark has finished.");
            default:
                throw new IllegalStateException("The benchmark is in unknown state.");
        }
    }

    private long mean() {
        if (mState != FINISHED) {
            throw new IllegalStateException("The benchmark hasn't finished");
        }
        return (long) mMean;
    }

    private long median() {
        if (mState != FINISHED) {
            throw new IllegalStateException("The benchmark hasn't finished");
        }
        return mMedian;
    }

    private long min() {
        if (mState != FINISHED) {
            throw new IllegalStateException("The benchmark hasn't finished");
        }
        return mMin;
    }

    private long standardDeviation() {
        if (mState != FINISHED) {
            throw new IllegalStateException("The benchmark hasn't finished");
        }
        return (long) mStandardDeviation;
    }

    private String summaryLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("Summary: ");
        sb.append("median=").append(median()).append("ns, ");
        sb.append("mean=").append(mean()).append("ns, ");
        sb.append("min=").append(min()).append("ns, ");
        sb.append("sigma=").append(standardDeviation()).append(", ");
        sb.append("iteration=").append(mResults.size()).append(", ");
        // print out the first few iterations' number for double checking.
        int sampleNumber = Math.min(mResults.size(), 16);
        for (int i = 0; i < sampleNumber; i++) {
            sb.append("No ").append(i).append(" result is ").append(mResults.get(i)).append(", ");
        }
        return sb.toString();
    }

    public void sendFullStatusReport(Instrumentation instrumentation, String key) {
        Log.i(TAG, key + summaryLine());
        Bundle status = new Bundle();
        status.putLong(key + "_median", median());
        status.putLong(key + "_mean", mean());
        status.putLong(key + "_min", min());
        status.putLong(key + "_standardDeviation", standardDeviation());
        instrumentation.sendStatus(Activity.RESULT_OK, status);
    }
}
