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
package io.zeebe.debug.exporter.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.debug.exporter.common.SocketAddressUtil;
import io.zeebe.debug.exporter.common.Transport;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArraySet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.slf4j.Logger;

/** Configuration for a {@link DebugServer}. */
@API(status = Status.EXPERIMENTAL)
@ParametersAreNonnullByDefault
public final class DebugServerConfig {
  private String target = "localhost:0";
  private Transport transport = Transport.INET;
  private RecordConsumer recordConsumer = new RecordCollector(new CopyOnWriteArraySet<>());
  private int threadCount = 1;

  private ObjectMapper jsonMapper;
  private Logger logger;

  /**
   * Returns the server's current target. The target should be interpreted based on the {@link
   * #getTransport()}. If the transport is {@link Transport#INET}, then the target would be the
   * 'host:port' string. If the transport is {@link Transport#UNIX}, then the target should be a
   * path to the socket file. If the transport is {@link Transport#IPC}, then the target should be
   * the server's name.
   *
   * <p>NOTE: you can use {@link #getAddress()} to get a {@link SocketAddress} which is built from
   * the {@link #getTransport()} and target.
   *
   * @return the server's target
   */
  @Nonnull
  public String getTarget() {
    return target;
  }

  /**
   * Sets the target of the server. The target should be interpreted based on the {@link
   * #getTransport()}. If the transport is {@link Transport#INET}, then the target would be the
   * 'host:port' string. If the transport is {@link Transport#UNIX}, then the target should be a
   * path to the socket file. If the transport is {@link Transport#IPC}, then the target should be
   * the server's name.
   *
   * @param target the target of the server
   * @return this config for chaining
   */
  @Nonnull
  public DebugServerConfig setTarget(final String target) {
    this.target = Objects.requireNonNull(target);
    return this;
  }

  /**
   * The transport of the server. See {@link Transport} for more.
   *
   * @return the server's transport
   */
  @Nonnull
  public Transport getTransport() {
    return transport;
  }

  /**
   * Sets a new transport for the server.
   *
   * @param transport the new server's transport
   * @return this config for chaining
   */
  @Nonnull
  public DebugServerConfig setTransport(final Transport transport) {
    this.transport = Objects.requireNonNull(transport);
    return this;
  }

  /**
   * Returns the configured {@link SocketAddress} of the server based on the configured target and
   * transport.
   *
   * <p>Defaults to {@link InetSocketAddress} over localhost and port 0, which will grab a random
   * port when the server starts.
   *
   * @see SocketAddressUtil#convert(String, Transport)
   * @return the configured socket address of the server
   */
  @Nonnull
  public SocketAddress getAddress() {
    return SocketAddressUtil.convert(target, transport);
  }

  /**
   * Returns the record consumer. Defaults to a thread safe instance of {@link RecordCollector} with
   * a {@link java.util.concurrent.CopyOnWriteArraySet} collection.
   *
   * @return the current record consumer
   */
  @Nonnull
  public RecordConsumer getRecordConsumer() {
    return recordConsumer;
  }

  /**
   * Sets a new record consumer.
   *
   * @see #getRecordConsumer()
   * @param recordConsumer the new record consumer
   * @throws NullPointerException if the record consumer is null
   * @return this config for chaining
   */
  @Nonnull
  public DebugServerConfig setRecordConsumer(final RecordConsumer recordConsumer) {
    this.recordConsumer = Objects.requireNonNull(recordConsumer);
    return this;
  }

  /** @return the number of threads to handle incoming requests */
  public int getThreadCount() {
    return threadCount;
  }

  /**
   * Sets the number of threads to handle incoming requests. Defaults to 1.
   *
   * @param threadCount the new thread count
   * @return this config for chaining
   */
  @Nonnull
  public DebugServerConfig setThreadCount(final int threadCount) {
    this.threadCount = threadCount;
    return this;
  }

  /**
   * Returns a optional {@link ObjectMapper}. This has no default value as {@link ObjectMapper} is a
   * rather heavy object. Callers are expected to find an appropriate default value if absent.
   *
   * @return the current json mapper
   */
  @Nonnull
  public Optional<ObjectMapper> getJsonMapper() {
    return Optional.ofNullable(jsonMapper);
  }

  /**
   * Sets a new JSON {@link ObjectMapper}. Must be configured to read and write JSON. Configurable
   * such that a user could use optimizations such as BlackBird. As this is an optional
   * configuration option, can be set ot null to revert to the default value.
   *
   * @param jsonMapper the new {@link ObjectMapper} to use
   * @return this config for chaining
   */
  @Nonnull
  public DebugServerConfig setJsonMapper(final @Nullable ObjectMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
    return this;
  }

  /** @return an optional pre-configured logger */
  @Nonnull
  public Optional<Logger> getLogger() {
    return Optional.ofNullable(logger);
  }

  /**
   * Sets the logger to be used by the server. As this is an optional configuration option, can be
   * set to null.
   *
   * @param logger the logger to use
   * @return this config for chaining
   */
  @Nonnull
  public DebugServerConfig setLogger(final @Nullable Logger logger) {
    this.logger = logger;
    return this;
  }
}
