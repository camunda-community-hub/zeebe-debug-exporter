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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.jodah.failsafe.CircuitBreaker;
import org.awaitility.Awaitility;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Execution(ExecutionMode.CONCURRENT)
class CircuitBreakerMonitorTest {
  private ScheduledExecutorService sequentialExecutor;
  private CircuitBreaker<Status> breaker;
  private ManagedChannel channel;
  private CircuitBreakerMonitor monitor;

  @BeforeEach
  void beforeEach() {
    sequentialExecutor = new ScheduledThreadPoolExecutor(1);
    breaker = new CircuitBreaker<Status>().withFailureThreshold(1).withSuccessThreshold(1);
    channel = spy(ManagedChannel.class);
    final Logger logger = LoggerFactory.getLogger(getClass());

    doReturn(ConnectivityState.READY).when(channel).getState(anyBoolean());
    monitor = new CircuitBreakerMonitor(sequentialExecutor, breaker, logger, channel);
  }

  @AfterEach
  void afterEach() {
    breaker.close();
    channel.shutdownNow();
    sequentialExecutor.shutdownNow();
  }

  @Test
  void shouldCallOnAvailableOnClose() {
    // given
    final AtomicBoolean availableCalled = new AtomicBoolean(false);
    breaker.open();
    monitor.onAvailable(() -> availableCalled.set(true));

    // when
    breaker.close();

    // then
    assertThat(availableCalled).as("callback was called").isTrue();
  }

  @Test
  void shouldCallProbeOnHalfOpen() {
    // given
    final AtomicBoolean probeCalled = new AtomicBoolean(false);
    monitor.withProbe(() -> probeCalled.set(true));

    // when
    breaker.open();
    breaker.halfOpen();

    // then
    assertThat(probeCalled).as("callback was called").isTrue();
  }

  @Test
  void shouldTransitionToHalfOpenAfterConfiguredTime() {
    // given
    final AtomicBoolean probeCalled = new AtomicBoolean(false);
    breaker.withDelay(Duration.ofMillis(200));
    monitor.withProbe(() -> probeCalled.set(true));

    // when
    breaker.open();

    // then
    Awaitility.await("until monitor has scheduled at least one probe")
        .untilAtomic(probeCalled, Matchers.equalTo(true));
  }

  @Test
  void shouldScheduleProbeRepeatedlyUntilSuccessful() {
    // given
    final AtomicInteger calledCount = new AtomicInteger(0);
    final AtomicBoolean availableCalled = new AtomicBoolean(false);
    breaker.withDelay(Duration.ofMillis(50));
    monitor
        .withProbe(
            () -> {
              breaker.preExecute();

              if (calledCount.incrementAndGet() >= 3) {
                breaker.recordSuccess();
              } else {
                breaker.recordFailure();
              }
            })
        .onAvailable(() -> availableCalled.set(true));

    // when
    breaker.open();

    // then
    Awaitility.await("until server is available again")
        .untilAtomic(availableCalled, Matchers.equalTo(true));
  }
}
