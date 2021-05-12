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

import com.google.protobuf.ByteString;
import com.google.protobuf.UnsafeByteOperations;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.zeebe.debug.exporter.common.SocketAddressUtil;
import io.zeebe.debug.exporter.common.Transport;
import io.zeebe.debug.exporter.protocol.DebugExporter.ExportedRecord;
import io.zeebe.debug.exporter.protocol.ExporterServiceGrpc;
import io.zeebe.debug.exporter.protocol.ExporterServiceGrpc.ExporterServiceStub;
import io.camunda.zeebe.exporter.api.Exporter;
import io.camunda.zeebe.exporter.api.context.Context;
import io.camunda.zeebe.exporter.api.context.Controller;
import io.camunda.zeebe.protocol.record.Record;
import java.net.SocketAddress;
import java.nio.BufferOverflowException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor.DiscardPolicy;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.concurrent.ThreadSafe;
import net.jodah.failsafe.CircuitBreaker;
import org.slf4j.Logger;

/**
 * A gRPC exporter to be used for debugging. The exporter will hold a long living bidirectional
 * stream to some server, and for each record, will stream it out. The server may then reply at
 * times with acknowledgements (i.e. {@link io.zeebe.debug.exporter.protocol.DebugExporter.Ack},
 * which contain the highest acknowledged position.
 *
 * <p>The exporter is built to have a low footprint/impact on the system. It will create a single
 * thread executor for sequencing streaming operations and callback handling, and another thread for
 * the actual communication. Note that it might be interesting to see if we can get away with using
 * the same thread for both.
 *
 * <p>If the {@link CircuitBreakerMonitor} detects the remote server is unavailable, it will stop
 * exporting until the server is detected as available again. To detect this, we keep at least one
 * record in the buffer (or all if loss is not allowed), and will use the record with lowest
 * position in the buffer to probe if the server is successfully accepting records. As soon as a
 * successful {@link io.zeebe.debug.exporter.protocol.DebugExporter.Ack} is received, the server
 * will be marked as available and exporting will resume.
 *
 * <p>One use case of the {@link CircuitBreakerMonitor} is to enable this exporter at all times,
 * even when there is no server, and to only start a server when you want to start exporting. You
 * can configure the exporter to be lossy (see {@link ZeebeDebugExporterConfig#isLossy()}), such
 * that when the server is unavailable, records will be skipped and exporting will not stop.
 */
@SuppressWarnings("unused")
@ThreadSafe
@ParametersAreNonnullByDefault
public final class ZeebeDebugExporter implements Exporter {
  private String id;
  private ZeebeDebugExporterConfig config;
  private Logger logger;
  private Transport transport;
  private SocketAddress address;

  private Controller controller;
  private EventLoopGroup eventLoopGroup;
  private ScheduledExecutorService executor;
  private ManagedChannel channel;
  private RecordBuffer recordBuffer;
  private RecordStreamer recordStreamer;
  private ServerMonitor monitor;

  private ExportedRecord nonBufferedRecord;

  @Override
  public void configure(final Context context) {
    id = context.getConfiguration().getId();
    config = context.getConfiguration().instantiate(ZeebeDebugExporterConfig.class);
    logger = context.getLogger();
    config.setMaxBufferedSizeBytes(Math.max(0, config.getMaxBufferedSizeBytes()));

    transport = Transport.of(config.getTransport());
    address = SocketAddressUtil.convert(config.getTarget(), transport);

    validate();
  }

  @Override
  public void open(final Controller controller) {
    this.controller = controller;

    executor = createExecutor();
    channel = buildChannel();

    final CircuitBreaker<Status> breaker =
        new CircuitBreaker<Status>()
            .withDelay(Duration.ofMillis(config.getProbeIntervalMs()))
            .withFailureThreshold(config.getFailureThreshold())
            .withSuccessThreshold(1);
    monitor = new CircuitBreakerMonitor(executor, breaker, logger, channel);

    final ExporterServiceStub service =
        ExporterServiceGrpc.newStub(channel)
            .withCompression("gzip")
            .withInterceptors(new CircuitBreakerInterceptor(breaker));
    recordBuffer = new RecordBuffer(config.getMaxBufferedSizeBytes(), logger);
    recordStreamer =
        new RecordStreamer(controller, logger, service, executor, recordBuffer, monitor);
  }

  @Override
  public void close() {
    // it's critical to shut down the executor before the record streamer, otherwise we can run into
    // race conditions between shutting down the record streamer, channel, and the executor, which
    // will complicate error handling in the streamer.
    if (executor != null) {
      executor.shutdownNow();
    }

    if (monitor != null) {
      monitor.close();
    }

    if (recordStreamer != null) {
      recordStreamer.close();
    }

    if (recordBuffer != null) {
      recordBuffer.trim();
    }

    if (channel != null) {
      channel.shutdownNow();
    }

    if (eventLoopGroup != null) {
      eventLoopGroup.shutdownGracefully(10, 1000, TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public void export(final Record<?> record) {
    // allowing loss when the server is not available enables the use case of "attaching" a server
    // at runtime to inspect the log, while keeping the overhead light when the server is not
    // present if loss is not allowed, then eventually the buffer will fill up and exporting will
    // stop until it can be emptied
    if (!monitor.isServerAvailable() && config.isLossy()) {
      // keep only one entry in the buffer - this limits memory usage in the case of a missing
      // server, while allowing us to use a real record to probe if the service is back/working
      if (recordBuffer.countUnsafe() > 1) {
        logger.debug("Trimming buffered records for lossy exporter due to server unavailability");
        recordBuffer.trim();
      }

      logger.trace(
          "[EXPORTER] Skipping record {}.{} (#{}) for lossy exporter due to server unavailability, and acknowledging it",
          record.getValueType(),
          record.getIntent(),
          record.getPosition());
      controller.updateLastExportedRecordPosition(record.getPosition());
      return;
    }

    // memoize the next non-buffered record such that, on retry, we don't have to serialize it again
    if (nonBufferedRecord == null) {
      nonBufferedRecord = serializeRecord(record);
      logger.trace(
          "[EXPORTER] Serialized record {}.{} (#{}) with size {}",
          record.getValueType(),
          record.getIntent(),
          record.getPosition(),
          nonBufferedRecord.getSerializedSize());
    }

    if (!recordBuffer.offer(nonBufferedRecord)) {
      throw new BufferOverflowException();
    }

    nonBufferedRecord = null;
    recordStreamer.onNewRecordAvailable();
  }

  @Nonnull
  private ExportedRecord serializeRecord(final Record<?> record) {
    final byte[] serializedRecord = record.toJson().getBytes(StandardCharsets.UTF_8);
    final ByteString bytes = UnsafeByteOperations.unsafeWrap(serializedRecord);
    return ExportedRecord.newBuilder()
        .setData(bytes)
        .setPartitionId(record.getPartitionId())
        .setPosition(record.getPosition())
        .build();
  }

  /**
   * Validates that our executor, event loops and channel can be built. This is rather heavy, and is
   * a good candidate for optimization to lower startup time.
   */
  private void validate() {
    ManagedChannel managedChannel = null;
    try {
      executor = createExecutor();
      managedChannel = buildChannel();
    } finally {
      if (managedChannel != null) {
        managedChannel.shutdownNow();
      }

      if (eventLoopGroup != null) {
        eventLoopGroup.shutdownGracefully(0, 10, TimeUnit.MILLISECONDS);
      }

      if (executor != null) {
        executor.shutdownNow();
      }
    }
  }

  /**
   * Creates a single thread executor, with an unbounded task queue, which will silently discard
   * tasks if the executor is already shut down.
   */
  @Nonnull
  private ScheduledExecutorService createExecutor() {
    final RejectedExecutionHandler silentlyDiscardPolicy = new DiscardPolicy();
    final ThreadFactory threadFactory = new DefaultThreadFactory("exporter-sequencer-" + id);

    return new ScheduledThreadPoolExecutor(1, threadFactory, silentlyDiscardPolicy);
  }

  /**
   * Builds a channel for the configured target, using Epoll if available, allowing for long lived
   * calls (using keep alive packets).
   *
   * <p>NOTE: the built-in retry mechanism for gRPC is disabled as the exporter has its own retry
   * logic.
   *
   * @return a new, well configured managed channel
   */
  @Nonnull
  private ManagedChannel buildChannel() {
    final ManagedChannelBuilder<?> builder =
        transport == Transport.IPC ? buildInProcessChannel() : buildNettyChannel();

    return builder
        .keepAliveWithoutCalls(true)
        .keepAliveTimeout(30, TimeUnit.SECONDS)
        .keepAliveTime(5, TimeUnit.SECONDS)
        .disableRetry()
        .usePlaintext()
        .executor(executor)
        .offloadExecutor(executor)
        .build();
  }

  @Nonnull
  private ManagedChannelBuilder<?> buildInProcessChannel() {
    return InProcessChannelBuilder.forName(config.getTarget())
        .propagateCauseWithStatus(true)
        .scheduledExecutorService(executor);
  }

  @Nonnull
  private ManagedChannelBuilder<?> buildNettyChannel() {
    final NettyChannelBuilder builder = NettyChannelBuilder.forAddress(address);
    final DefaultThreadFactory threadFactory = new DefaultThreadFactory("exporter-transport-" + id);

    switch (transport) {
      case IPC:
        throw new UnsupportedOperationException(
            "Expected to build Netty channel for [INET, UNIX] transports, but was 'IPC'");
      case UNIX:
        buildUnixDomainNettyChannel(builder, threadFactory);
        break;
      case INET:
      default:
        buildInetNettyChannel(builder, threadFactory);
        break;
    }

    return builder
        .eventLoopGroup(eventLoopGroup)
        .withOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000);
  }

  private void buildInetNettyChannel(
      final NettyChannelBuilder builder, final DefaultThreadFactory threadFactory) {
    if (Epoll.isAvailable()) {
      eventLoopGroup = new EpollEventLoopGroup(1, threadFactory);
      builder.channelFactory(EpollSocketChannel::new).channelType(EpollSocketChannel.class);
    } else {
      eventLoopGroup = new NioEventLoopGroup(1, threadFactory);
      builder.channelFactory(NioSocketChannel::new).channelType(NioSocketChannel.class);
    }
  }

  private void buildUnixDomainNettyChannel(
      final NettyChannelBuilder builder, final DefaultThreadFactory threadFactory) {
    if (!Epoll.isAvailable()) {
      throw new IllegalStateException(
          "Expected to configure a channel over Unix Domain Socket, but Epoll is not available; "
              + Epoll.unavailabilityCause());
    }

    eventLoopGroup = new EpollEventLoopGroup(1, threadFactory);
    builder
        .channelFactory(EpollDomainSocketChannel::new)
        .channelType(EpollDomainSocketChannel.class);
  }
}
