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
import com.fasterxml.jackson.databind.ObjectReader;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerDomainSocketChannel;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.zeebe.debug.exporter.common.SocketAddressUtil;
import io.zeebe.debug.exporter.common.Transport;
import io.zeebe.debug.exporter.protocol.ExporterServiceGrpc.ExporterServiceImplBase;
import io.zeebe.protocol.immutables.ImmutableRecordTypeReference;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor.DiscardPolicy;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of the debug server. See {@link DebugServerConfig} for configuration
 * options.
 */
@API(status = Status.EXPERIMENTAL)
@ParametersAreNonnullByDefault
public final class DebugServer implements AutoCloseable {
  private final DebugServerConfig config;

  private EventLoopGroup bossLoopGroup;
  private EventLoopGroup workerLoopGroup;
  private ExecutorService executor;
  private Server server;
  private Logger logger;

  private volatile boolean started;

  @SuppressWarnings("unused")
  public DebugServer() {
    this(new DebugServerConfig());
  }

  public DebugServer(final DebugServerConfig config) {
    this.config = Objects.requireNonNull(config);
  }

  /**
   * Attempts to return the first bound InetSocketAddress of the server. If the server is not
   * started yet, will simply return the configured address (which may not have been bound and/or
   * resolved yet).
   *
   * @return the server's first eligible bind address
   */
  public SocketAddress getBindAddress() {
    if (!started) {
      return config.getAddress();
    }

    return server.getListenSockets().stream().findFirst().orElseThrow();
  }

  /**
   * Starts the server. Any changes to the configuration done afterwards will be ignored.
   *
   * @throws IOException if the server could not start (e.g. address already in use)
   */
  public void start() throws IOException {
    logger = config.getLogger().orElseGet(() -> LoggerFactory.getLogger(getClass()));
    final ThreadFactory threadFactory = new DefaultThreadFactory(DebugServer.class);

    executor =
        new ScheduledThreadPoolExecutor(
            config.getThreadCount(), threadFactory, new DiscardPolicy());
    server =
        buildServer(threadFactory)
            .addService(createExporterService())
            .intercept(new GzipServerInterceptor())
            .executor(executor)
            .build();

    server.start();
    started = true;

    if (logger.isInfoEnabled()) {
      logger.info(
          "[SERVER] Started debug server listening on {}",
          SocketAddressUtil.toString(getBindAddress()));
    }
  }

  /**
   * Closes the server and all managed resources.
   *
   * @throws Exception if the server fails to close
   */
  @Override
  public void close() throws Exception {
    if (executor != null) {
      executor.shutdownNow();
    }

    if (bossLoopGroup != null) {
      bossLoopGroup.shutdownGracefully(0, 10, TimeUnit.MILLISECONDS);
    }

    if (workerLoopGroup != null) {
      workerLoopGroup.shutdownGracefully(0, 10, TimeUnit.MILLISECONDS);
    }

    if (started) {
      if (logger.isInfoEnabled()) {
        logger.info(
            "[SERVER] Shutting down debug server listening on {}",
            SocketAddressUtil.toString(getBindAddress()));
      }

      server.shutdownNow();
      server.awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  @Nonnull
  protected ExporterServiceImplBase createExporterService() {
    final ObjectMapper jsonMapper = config.getJsonMapper().orElseGet(ObjectMapper::new);
    final ObjectReader jsonReader = jsonMapper.readerFor(new ImmutableRecordTypeReference<>());
    return new ExporterService(config.getRecordConsumer(), jsonReader, logger);
  }

  @Nonnull
  @SuppressWarnings("java:S1452")
  private ServerBuilder<?> buildServer(final ThreadFactory threadFactory) {
    return config.getTransport() == Transport.IPC
        ? buildInProcessServer()
        : buildNettyServer(threadFactory);
  }

  @Nonnull
  private InProcessServerBuilder buildInProcessServer() {
    return InProcessServerBuilder.forName(config.getTarget());
  }

  @Nonnull
  private NettyServerBuilder buildNettyServer(final ThreadFactory threadFactory) {
    final NettyServerBuilder builder = NettyServerBuilder.forAddress(config.getAddress());

    switch (config.getTransport()) {
      case IPC:
        throw new UnsupportedOperationException(
            "Expected to build Netty channel for [INET, UNIX] transports, but was 'IPC'");
      case UNIX:
        if (!Epoll.isAvailable()) {
          throw new IllegalStateException(
              "Expected to configure a channel over Unix Domain Socket, but Epoll is not available; "
                  + Epoll.unavailabilityCause());
        }

        workerLoopGroup = new EpollEventLoopGroup(1, threadFactory);
        bossLoopGroup = new EpollEventLoopGroup(1, threadFactory);
        builder
            .channelFactory(EpollServerDomainSocketChannel::new)
            .channelType(EpollServerDomainSocketChannel.class);
        break;
      case INET:
      default:
        if (Epoll.isAvailable()) {
          workerLoopGroup = new EpollEventLoopGroup(1, threadFactory);
          bossLoopGroup = new EpollEventLoopGroup(1, threadFactory);
          builder
              .channelFactory(EpollServerSocketChannel::new)
              .channelType(EpollServerSocketChannel.class);
        } else {
          workerLoopGroup = new NioEventLoopGroup(1, threadFactory);
          bossLoopGroup = new NioEventLoopGroup(1, threadFactory);
          builder
              .channelFactory(NioServerSocketChannel::new)
              .channelType(NioServerSocketChannel.class);
        }

        builder
            .withChildOption(ChannelOption.TCP_NODELAY, true)
            .withChildOption(ChannelOption.SO_KEEPALIVE, true);
        break;
    }

    return builder
        .workerEventLoopGroup(workerLoopGroup)
        .bossEventLoopGroup(bossLoopGroup)
        .keepAliveTime(5, TimeUnit.SECONDS)
        .keepAliveTimeout(30, TimeUnit.SECONDS)
        .permitKeepAliveTime(5, TimeUnit.SECONDS)
        .permitKeepAliveWithoutCalls(true)
        .maxConcurrentCallsPerConnection(1);
  }
}
