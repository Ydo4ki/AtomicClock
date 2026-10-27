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

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * A lightweight, tick-based clock thread that executes periodic updates and schedules
 * tasks for future ticks.
 *
 * <p>
 * Each tick invokes the {@link Update}
 * callback and completes tasks whose target tick has been reached.
 * </p>
 *
 * <p>
 * Tasks are stored in a timing wheel that allows tasks to be scheduled by tick without creating
 * a separate thread or timer for each task.
 * </p>
 *
 * <p>
 * The clock supports configurable catch-up behavior when execution falls
 * behind its intended schedule.
 * </p>
 *
 * <p>
 * Instances must be started using {@link #start()}. Call {@link #stopMe()}
 * to request that the clock stop running.
 * </p>
 *
 * <p>
 * Please note that while the current tick accessor is thread-safe, and the timing wheel
 * uses concurrent queues, the update callback and the tick-rate
 * sampling state are accessed by the clock thread and should not be assumed
 * to be independently thread-safe.
 * </p>
 *
 * @see Update
 * @see TickFuture
 *
 * @since 7/3/2025 8:59 PM
 * @author Sulphuris
 */
@SuppressWarnings("jol")
public final class AtomicClock extends Thread {
	private final long ticksPerSecond;
	private final long nanosPerTick;
	private final int wheelSize;
	private final ConcurrentLinkedQueue<Task>[] wheel;
	private final AtomicLong currentTick = new AtomicLong();
	private final Update update;
	private final CatchUpMode catchUp;

	/**
	 * Defines how the clock adjusts its schedule when the execution of {@link update} takes more
	 * than intended.
	 */
	public enum CatchUpMode {

		/**
		 * Attempts to catch up by advancing the scheduled execution time by
		 * one tick interval after each tick.
		 *
		 * <p>
		 * If the clock falls behind, subsequent ticks may execute in quick
		 * succession until the intended schedule is recovered.
		 * </p>
		 */
		CATCH_UP,

		/**
		 * Attempts to catch up after short delays, but abandons the accumulated
		 * schedule when the clock falls behind by more than ten tick intervals.
		 *
		 * <p>
		 * In that case, the next tick is scheduled one tick interval after
		 * the time at which the delay was detected.
		 * </p>
		 *
		 * This is the recommended option to use in games.
		 */
		CATCH_UP_ONLY_SHORT_DELAYS,

		/**
		 * Does not attempt to catch up after a delayed tick.
		 *
		 * <p>After each tick, the next tick is scheduled one tick interval
		 * after the current time, preventing accumulated delays from causing
		 * consecutive catch-up ticks.
		 */
		NO_CATCH_UP
	}

	private static final int TPS_SAMPLE_SIZE = 100;
	private final long[] tickTimes = new long[TPS_SAMPLE_SIZE];
	private int tickIndex = 0;
	private boolean filled = false;

	private volatile boolean running = false;

	/**
	 * Creates a clock configured to run at the specified tick rate.
	 *
	 * <p>
	 * The clock is configured as a thread named {@code AtomicClock}.
	 * Please do not forget to call {@link #start()} to actually start the clock.
	 * </p>
	 *
	 * @param ticksPerSecond the target number of ticks per second; must be positive
	 * @param update the callback invoked on every tick; must not be {@code null}
	 * @param catchUp the strategy used to handle delayed ticks; must not be {@code null}
	 * @param name clock's name (nullable).
	 * @param daemon whether the clock thread should be daemon.
	 */
	public AtomicClock(int ticksPerSecond, Update update, CatchUpMode catchUp, String name, boolean daemon) {
		if (ticksPerSecond <= 0)
			throw new IllegalArgumentException(ticksPerSecond + " <= 0 (ticksPerSecond must be positive)");
		if (update == null)
			throw new IllegalArgumentException("Why create AtomicClock if there is nothing to update?");
		if (catchUp == null)
			throw new IllegalArgumentException("CatchUpMode should not be null");

		this.ticksPerSecond = ticksPerSecond;
		this.nanosPerTick = 1000000000L / ticksPerSecond;
		this.update = update;
		this.catchUp = catchUp;
		this.wheelSize = 1024;
		//noinspection unchecked
		this.wheel = new ConcurrentLinkedQueue[wheelSize];
		for (int i = 0; i < wheelSize; i++) {
			wheel[i] = new ConcurrentLinkedQueue<Task>();
		}
		if (name == null) {
			setName("AtomicClock");
		} else {
			setName("AtomicClock-" + name);
		}
		setDaemon(daemon);
	}

	/**
	 * Requests that this clock stop running.
	 *
	 * <p>
	 * This method calmly tells the thread to stop before the next tick. It does not
	 * interrupt the thread, wait for termination, or immediately cancel
	 * scheduled tasks. The clock will finish its current tick before
	 * exiting.
	 * </p>
	 *
	 * <p>
	 * Calling this method before {@link #start()} does not do anything, the clock will still be able to run normally.
	 * </p>
	 */
	public void stopMe() {
		running = false;
	}

	/**
	 * Returns the current tick number.
	 *
	 * <p>
	 * The counter is incremented immediately before the corresponding
	 * tick is processed. The initial value is {@code 0}.
	 * </p>
	 *
	 * @return the current tick number
	 */
	public long currentTick() {
		return currentTick.get();
	}

	/**
	 * Runs the clock loop.
	 *
	 * <p>
	 * The first tick is scheduled one tick interval after the loop starts.
	 * Subsequent ticks follow the configured {@link CatchUpMode}.
	 * </p>
	 *
	 * <p>
	 * The thread parks while waiting for the next tick deadline. If the
	 * thread is interrupted while waiting, the loop exits without switching the {@link running} flag.
	 * </p>
	 *
	 * <p>
	 * Each processed tick increments the tick counter and invokes the
	 * update callback before processing tasks scheduled for that tick.
	 * </p>
	 *
	 * This method is invoked internally by the thread after {@link #start()} is called.
	 * Calling it directly might result in disciplinary measures, fines, and a 5-year ban on entry to Canada.
	 */
	@Override
	public void run() {
		running = true;

		long next = System.nanoTime() + nanosPerTick;

		while (running) {
			long now = System.nanoTime();
			long waitNanos = next - now;

			if (waitNanos > 0) {
				LockSupport.parkNanos(waitNanos);
				if (Thread.interrupted()) break;
				continue;
			}

			tick(currentTick.incrementAndGet());
			if (catchUp == CatchUpMode.NO_CATCH_UP) {
				next = System.nanoTime() + nanosPerTick;
			} else {
				next += nanosPerTick;
				if (catchUp == CatchUpMode.CATCH_UP_ONLY_SHORT_DELAYS) {
					if (now - next > nanosPerTick * 10) {
						// too late
						next = now + nanosPerTick;
					}
				}
			}
		}
	}

	private void tick(long tick) {
		int slot = (int) (tick & (wheelSize - 1));
		update.update(tick);

		tickTimes[tickIndex] = System.nanoTime();
		tickIndex = (tickIndex + 1) % TPS_SAMPLE_SIZE;
		if (tickIndex == 0) filled = true;

		Task task;
		while ((task = wheel[slot].poll()) != null) {
			if (task.targetTick <= tick) {
				task.cf.complete(tick);
			} else {
				int newSlot = (int) (task.targetTick & (wheelSize - 1));
				wheel[newSlot].add(task);
			}
		}
	}

	/**
	 * Returns the estimated tick rate based on recent tick timestamps.
	 *
	 * <p>
	 * The estimate uses a rolling sample of up to {@link AtomicClock#TPS_SAMPLE_SIZE} tick timestamps
	 * and calculates the average number of ticks per second over the sampled interval.
	 * </p>
	 *
	 * <p>
	 * Before sufficient samples are available, this method returns
	 * {@code 0.0}. The result represents the observed tick rate, not a guarantee that
	 * the clock will maintain its configured rate.
	 * </p>
	 *
	 * @return the measured ticks per second
	 */
	public double getTicksPerSecond() {
		if (!filled && tickIndex < 2) return 0.0; // not enough data

		int oldest = filled ? tickIndex : 0;
		int newest = (tickIndex + TPS_SAMPLE_SIZE - 1) % TPS_SAMPLE_SIZE;

		long deltaNanos = tickTimes[newest] - tickTimes[oldest];
		if (deltaNanos <= 0) return ticksPerSecond;

		int samples = filled ? TPS_SAMPLE_SIZE - 1 : tickIndex - 1;
		return (samples * 1000000000.0) / deltaNanos;
	}

	/**
	 * Schedules a task to complete after the specified number of milliseconds.
	 *
	 * <p>
	 * The delay is converted using integer arithmetic:
	 * {@code (ms * ticksPerSecond) / 1000}. As a result, fractional ticks
	 * are truncated, and short delays may convert to zero ticks.
	 * </p>
	 *
	 * <p>
	 * This method does not guarantee wall-clock precision. Actual
	 * completion depends on the clock's tick execution and catch-up behavior.
	 * </p>
	 *
	 * @param ms the requested delay in milliseconds
	 * @return a future completed with the tick number at which the scheduled task is processed, or an already-completed future if the
	 * calculated target tick is not in the future
	 */
	public TickFuture inMillis(long ms) {
		return in((ms * ticksPerSecond) / 1000);
	}

	/**
	 * Schedules a task to complete after the specified number of ticks.
	 *
	 * <p>
	 * The target tick is calculated by adding {@code waitTicks} to the current tick.
	 * If the target tick is not in the future, an already-completed {@link TickFuture} is returned.<br>
	 * Otherwise, the task is placed in the corresponding slot of the timing wheel.
	 * The returned future completes when the clock processes the target tick or a later tick,
	 * and its completion value is the tick number at which the task is processed.
	 * </p>
	 *
	 * <p>
	 * A zero or negative delay therefore completes immediately with the
	 * calculated target tick instead of waiting for another clock tick.
	 * </p>
	 *
	 * @param waitTicks the number of ticks to wait; positive values schedule future completion,
	 * while zero or negative values produce an already-completed future
	 * @return a future representing the scheduled task
	 */
    public TickFuture in(long waitTicks) {
		long target = currentTick.get() + waitTicks;
		if (target <= currentTick.get()) {
			return TickFuture.completed(target);
		}
		TickFuture cf = new TickFuture();
		int slot = (int) (target & (wheelSize - 1));
		wheel[slot].add(new Task(target, cf));
		return cf;
	}

	private static class Task {
		final long targetTick;
		final TickFuture cf;

		Task(long targetTick, TickFuture cf) {
			this.targetTick = targetTick;
			this.cf = cf;
		}
	}
}
