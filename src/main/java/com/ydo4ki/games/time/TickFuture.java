/*
 * MIT License
 *
 * Copyright (c) 2025-2026 Ydo4ki LLC
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

package com.ydo4ki.games.time;

/**
 * Represents the result of a tick-based scheduling operation.
 *
 * <p>
 * A {@code TickFuture} holds a {@code long} value associated with a scheduled tick and allows a single {@link Runnable}
 * callback to be assigned for execution when the future completes.
 * </p>
 *
 * <p>
 * A future can be completed explicitly using {@link #complete(long)} or created in an already-completed state using {@link #completed(long)}.
 * Completion is idempotent: subsequent calls to {@code complete(long)} have no effect after the first successful completion.
 * </p>
 *
 * @see AtomicClock#in(long)
 * @see AtomicClock#inMillis(long)
 */
public final class TickFuture {
    private volatile boolean done = false;
    private long value;
    private Runnable callback;

    /**
     * Creates a future that is already completed with the specified value.
     * @param target the target tick number it was completed with
     * @return an already-completed future containing {@code target}
     */
    public static TickFuture completed(long target) {
        TickFuture f = new TickFuture();
        f.complete(target);
        return f;
    }

    /**
     * Registers a callback to be executed when this future completes.
     *
     * <p>
     * Only one callback can be registered. If the future has already completed, this method throws an {@link IllegalArgumentException}.
     * Registering another callback before completion replaces the previously registered callback.
     * </p>
     *
     * @param cb the callback to execute upon completion
     * @throws IllegalArgumentException if this future has already completed
     */
    public synchronized void thenRun(Runnable cb) {
        if (done) {
            // cb.run();
            throw new IllegalArgumentException("The future is already there");
        } else {
            this.callback = cb;
        }
    }

    void complete(long value) {
        Runnable callback;

        synchronized (this) {
            if (done) return;

            this.done = true;
            this.value = value;
            callback = this.callback;

            notifyAll();
        }

        if (callback != null) callback.run();
    }

    /**
     * Returns whether this future has completed.
     *
     * @return {@code true} if this future has completed; {@code false} otherwise
     */
    public boolean isDone() {
        return done;
    }

    /**
     * Returns the value associated with this future. If the future is not completed yet, waits until it is completed.
     *
     * @return the target tick value the future was completed with
     */
    public synchronized long get() {
        boolean interrupted = false;

        try {
            while (!done) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }

            return value;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
