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

import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.util.ByteBufferBackedInputStream;
import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.util.Either;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.zeebe.debug.exporter.protocol.DebugExporter.Ack;
import io.zeebe.debug.exporter.protocol.DebugExporter.ExportedRecord;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.WillClose;
import org.slf4j.Logger;

@ParametersAreNonnullByDefault
final class RecordIngestor implements StreamObserver<ExportedRecord> {

  /**
   * records may be ingested asynchronously even if an error was already returned to the client;
   * this means, even after failing for record X, we might received record X+1, which we don't want
   * to pass to the consumer unnecessarily as it will be retried by the client; instead, we flip
   * this switch and ignore further calls as a new RecordIngestor will be created when the client
   * retries
   */
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private final RecordConsumer consumer;
  private final StreamObserver<Ack> ackChannel;
  private final ObjectReader jsonReader;
  private final Logger logger;

  public RecordIngestor(
      final RecordConsumer consumer,
      final @WillClose StreamObserver<Ack> ackChannel,
      final ObjectReader jsonReader,
      final Logger logger) {
    this.consumer = Objects.requireNonNull(consumer);
    this.ackChannel = Objects.requireNonNull(ackChannel);
    this.jsonReader = Objects.requireNonNull(jsonReader);
    this.logger = Objects.requireNonNull(logger);
  }

  @Override
  public void onNext(final ExportedRecord value) {
    if (closed.get()) {
      return;
    }

    logger.trace("[INGESTOR] Received exported record {}", value);

    final int partitionId = value.getPartitionId();
    consumer
        .acknowledge(value, this::transform)
        .ifRightOrLeft(
            p -> p.ifPresent(position -> acknowledge(partitionId, position)),
            status -> ackChannel.onError(status.asException()));
  }

  @Override
  public void onError(final Throwable t) {
    if (!closed.compareAndExchange(false, true)) {
      return;
    }

    Code errorCode = Code.UNKNOWN;
    if (t instanceof StatusRuntimeException) {
      errorCode = ((StatusRuntimeException) t).getStatus().getCode();
    } else if (t instanceof StatusException) {
      errorCode = ((StatusException) t).getStatus().getCode();
    }

    switch (errorCode) {
        // some errors are safe to ignore, such as CANCELLED or UNAVAILABLE, i.e. the connection was
        // already closed
      case ABORTED:
      case UNAVAILABLE:
      case CANCELLED:
        return;
      default:
        logger.warn("[INGESTOR] Client returned an error and closed the connection", t);
        break;
    }
  }

  @Override
  public void onCompleted() {
    if (closed.compareAndExchange(false, true)) {
      logger.debug("[INGESTOR] Client closed the connection gracefully");
    }
  }

  private void acknowledge(final int partitionId, final long acknowledgedPosition) {
    final Ack ack =
        Ack.newBuilder().setPartitionId(partitionId).setPosition(acknowledgedPosition).build();

    try {
      ackChannel.onNext(ack);
    } catch (final Exception e) {
      ackChannel.onError(e);
    }
  }

  @Nonnull
  private Either<Status, Record<?>> transform(final ExportedRecord record) {
    final ByteBuffer data = Objects.requireNonNull(record).getData().asReadOnlyByteBuffer();

    try (final ByteBufferBackedInputStream input = new ByteBufferBackedInputStream(data)) {
      return Either.right(jsonReader.readValue(input));
    } catch (final IOException e) {
      final String message =
          String.format(
              "could not deserialize record #%d on partition %d",
              record.getPosition(), record.getPartitionId());
      return Either.left(Status.INVALID_ARGUMENT.augmentDescription(message).withCause(e));
    }
  }
}
