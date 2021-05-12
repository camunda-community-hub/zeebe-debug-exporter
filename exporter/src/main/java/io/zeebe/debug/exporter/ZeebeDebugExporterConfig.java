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

import io.zeebe.debug.exporter.common.Transport;
import java.util.Objects;
import javax.annotation.ParametersAreNonnullByDefault;

@SuppressWarnings({"UnusedReturnValue", "unused"})
@ParametersAreNonnullByDefault
public final class ZeebeDebugExporterConfig {
  private String target = "localhost:8080";
  private String transport = Transport.INET.name();
  private long maxBufferedSizeBytes = 16 * 1024 * 1024L;
  private boolean lossy;
  private long probeIntervalMs = 1000L;
  private int failureThreshold = 3;

  /**
   * The target server formatted as host and port, i.e. hostname:port. Hostname may be a DNS host or
   * an IP address. In the case of IPv6 addresses, the hostname will be bracketed, e.g. [::]:80.
   *
   * @return the target server host and port string
   */
  public String getTarget() {
    return target;
  }

  /**
   * Sets a new target server host and port server string which must be formatted as hostname:port.
   * Hostname may be a DNS host (e.g. "google.com:80") or an IP address (e.g. "127.0.0.1:80"). In
   * the case of IPv6 addresses, the hostname will be bracketed, e.g. [::]:80.
   *
   * <p>NOTE: defaults to "localhost:8080"
   *
   * @param target the new target server host and port string
   * @return this config for chaining
   */
  public ZeebeDebugExporterConfig setTarget(final String target) {
    this.target = Objects.requireNonNull(target);
    return this;
  }

  /** @return the maximum size, in bytes, of records that can be buffered for retries */
  public long getMaxBufferedSizeBytes() {
    return maxBufferedSizeBytes;
  }

  /**
   * Sets the new maximum size, in bytes, of records which can be buffered for retries. When a new
   * record would not fix in the buffer, a {@link java.nio.BufferOverflowException} will be thrown
   * by the exporter, causing the export operation to be retried (with backoff), hopefully giving
   * enough time for the server to acknowledge some records. When records are acknowledged by the
   * server, they are removed from the buffer.
   *
   * <p>NOTE: you can set 0 to make the buffer unbounded.
   *
   * <p>NOTE: if the buffer is empty an a record would be bigger than the max size given here, it
   * will still accept it. However, if there is already one record in it, it will not.
   *
   * <p>NOTE: defaults to 16MB
   *
   * @param maxBufferedSizeBytes the new max buffered size in bytes
   * @return this config for chaining
   */
  public ZeebeDebugExporterConfig setMaxBufferedSizeBytes(final long maxBufferedSizeBytes) {
    this.maxBufferedSizeBytes = maxBufferedSizeBytes;
    return this;
  }

  /**
   * If true, when the remote server is unavailable, records will be skipped. See {@link
   * CircuitBreakerMonitor} for more info about what unavailability means and how it's detected.
   *
   * @return true if records can be skipped, false otherwise
   */
  public boolean isLossy() {
    return lossy;
  }

  /**
   * Sets whether or not records can be skipped.
   *
   * @param lossy the new setting for lossy
   * @return this config for chaining
   */
  public ZeebeDebugExporterConfig setLossy(final boolean lossy) {
    this.lossy = lossy;
    return this;
  }

  /**
   * The probe interval is used when the remote server is unavailable. When unavailable, the
   * exporter will probe every {@code probeIntervalMs} milliseconds to check if the server is
   * available again. When it is, exporting to it will resume. Until then, nothing will be exported.
   * If {@link #isLossy()} is true, then records will be skipped when exporting is disabled. If
   * false, records will be buffered until the buffer is full, and no records will be skipped.
   *
   * @return the probe interval in milliseconds
   */
  public long getProbeIntervalMs() {
    return probeIntervalMs;
  }

  /**
   * Sets a new probe interval in milliseconds.
   *
   * @param probeIntervalMs the new probe interval
   * @return this config for chaining
   */
  public ZeebeDebugExporterConfig setProbeIntervalMs(final long probeIntervalMs) {
    this.probeIntervalMs = probeIntervalMs;
    return this;
  }

  /**
   * The expected transport to use. See {@link Transport} for support values.
   *
   * @return the transport to use
   */
  public String getTransport() {
    return transport;
  }

  /**
   * Sets the transport. See {@link Transport} for more.
   *
   * @param transport the new transport to use
   * @throws NullPointerException if transport is null
   * @return this config for chaining
   */
  public ZeebeDebugExporterConfig setTransport(final String transport) {
    this.transport = Objects.requireNonNull(transport);
    return this;
  }

  /**
   * Returns how many consecutive failures must occur for the server to be detected as unavailable.
   * Defaults to 3.
   *
   * <p>NOTE: not all failures will be considered - client side failures will be ignored, for
   * example. Only non transient failures from the server are considered. See {@link
   * CircuitBreakerMonitor} for more.
   *
   * @see CircuitBreakerMonitor
   * @return the number of consecutive failures required to mark the server as unavailable
   */
  public int getFailureThreshold() {
    return failureThreshold;
  }

  /**
   * Sets the new failure threshold.
   *
   * @see #getFailureThreshold()
   * @param failureThreshold the new failure threshold
   * @return this config for chaining
   */
  public ZeebeDebugExporterConfig setFailureThreshold(final int failureThreshold) {
    this.failureThreshold = failureThreshold;
    return this;
  }
}
