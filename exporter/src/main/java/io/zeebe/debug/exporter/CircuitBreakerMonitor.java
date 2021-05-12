/*
 * Copyright © 2021 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.debug.exporter;

import io.camunda.zeebe.util.Either;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.WillNotClose;
import javax.annotation.concurrent.GuardedBy;
import net.jodah.failsafe.CircuitBreaker;
import net.jodah.failsafe.CircuitBreaker.State;
import org.slf4j.Logger;

/**
 * Monitors the connection to the remote server, using a {@link CircuitBreaker} to manage the state
 * of the connection. The circuit breaker is configured to open after 3 failures within 5 seconds.
 * When opened, it will transition back to closed as soon as a successful connection can be
 * established
 *
 * <p>When the breaker transitions to closed, it will call its callback {@link
 * #onAvailableCallback}.
 *
 * <p>When the breaker transitions to opened, it will schedule a check to transition to half-open
 * for {@link CircuitBreaker#getDelay()}.
 *
 * <p>NOTE: an external component should listen for the half open and ocrre
 *
 * <p>NOTE: the monitor does not take ownership of any of the arguments passed to it; any closeable
 * resources given to it must be closed by the caller.
 */
@ParametersAreNonnullByDefault
final class CircuitBreakerMonitor implements AutoCloseable, ServerMonitor {
  private static final Set<ConnectivityState> ACCEPTABLE_STATES =
      EnumSet.of(ConnectivityState.READY, ConnectivityState.IDLE);

  private final ScheduledExecutorService sequentialExecutor;
  private final CircuitBreaker<Status> breaker;
  private final Logger logger;
  private final ManagedChannel channel;

  // it's fine to make it atomic instead of sequencing or some such as we don't access it in any
  // kind of hot path
  private final AtomicReference<State> currentState = new AtomicReference<>(State.CLOSED);

  @GuardedBy("sequentialExecutor")
  private ScheduledFuture<?> halfOpenCheck;

  private Runnable probeCallback;
  private Runnable onAvailableCallback;

  CircuitBreakerMonitor(
      final @WillNotClose ScheduledExecutorService sequentialExecutor,
      final @WillNotClose CircuitBreaker<Status> breaker,
      final Logger logger,
      final @WillNotClose ManagedChannel channel) {
    this.sequentialExecutor = Objects.requireNonNull(sequentialExecutor);
    this.breaker = Objects.requireNonNull(breaker);
    this.logger = Objects.requireNonNull(logger);
    this.channel = Objects.requireNonNull(channel);

    this.breaker
        .onClose(this::onBreakerClose)
        .onOpen(this::onBreakerOpen)
        .onHalfOpen(this::onBreakerHalfOpen);
    probeCallback = this.breaker::recordSuccess;
  }

  /**
   * The sequentialExecutor is expected to have been shutdown at this point, so there's no need to
   * use it to guard against race conditions anymore.
   */
  @Override
  public void close() {
    cancelHalfOpenCheck();
  }

  public boolean isServerAvailable() {
    return currentState.get() == State.CLOSED;
  }

  @Nonnull
  public synchronized CircuitBreakerMonitor onAvailable(final @Nullable Runnable callback) {
    onAvailableCallback = callback;
    return this;
  }

  @Nonnull
  public synchronized CircuitBreakerMonitor withProbe(final Runnable callback) {
    probeCallback = Objects.requireNonNull(callback);
    return this;
  }

  private void onBreakerClose() {
    sequentialExecutor.submit(this::cancelHalfOpenCheck);

    logger.info("[MONITOR] Remote server is available, will resume exporting");
    currentState.set(State.CLOSED);

    if (onAvailableCallback != null) {
      onAvailableCallback.run();
    }
  }

  private void onBreakerOpen() {
    // the breaker may alternate between opened and half opened, there's no need to notify as a
    // warning every time - we only care when going from closed to opened
    if (currentState.getAndSet(State.OPEN) == State.CLOSED) {
      logger.warn(
          "[MONITOR] Remote server is unavailable, will stop exporting, probing remote server for availability every {}",
          breaker.getDelay());
    }

    sequentialExecutor.submit(() -> scheduleHalfOpenTransition(breaker.getRemainingDelay()));
  }

  private void onBreakerHalfOpen() {
    logger.trace("[MONITOR] Checking connectivity before calling probe task");
    getConnectionState()
        .ifRightOrLeft(
            ready -> probeCallback.run(),
            failure -> {
              breaker.preExecute();
              breaker.recordFailure(failure);
            });
  }

  @GuardedBy("sequentialExecutor")
  private void transitionToHalfOpen() {
    if (breaker.isClosed()) {
      return;
    }

    if (!breaker.allowsExecution()) {
      final Duration remainingDelay = breaker.getRemainingDelay();
      logger.trace(
          "[MONITOR] Remote server is still unavailable, will probe again in {}", remainingDelay);
      scheduleHalfOpenTransition(remainingDelay);
    }
  }

  @GuardedBy("sequentialExecutor")
  private void scheduleHalfOpenTransition(final Duration delay) {
    cancelHalfOpenCheck();

    logger.trace("[MONITOR] Scheduling half open check in {}", delay);
    halfOpenCheck =
        sequentialExecutor.schedule(
            this::transitionToHalfOpen, delay.toNanos(), TimeUnit.NANOSECONDS);
  }

  @GuardedBy("sequentialExecutor")
  private void cancelHalfOpenCheck() {
    if (halfOpenCheck != null) {
      logger.trace("[MONITOR] Cancelling half open check");
      halfOpenCheck.cancel(false);
      halfOpenCheck = null;
    }
  }

  @Nonnull
  private Either<Throwable, ConnectivityState> getConnectionState() {
    try {
      final ConnectivityState state = channel.getState(true);
      logger.trace("[MONITOR] Remote server connection is in state {}", state);

      if (ACCEPTABLE_STATES.contains(state)) {
        return Either.right(state);
      }

      return Either.left(
          new IllegalStateException(
              "Connection is not in state READY/IDLE, but is in state " + state));
    } catch (final Exception e) {
      return Either.left(e);
    }
  }
}
