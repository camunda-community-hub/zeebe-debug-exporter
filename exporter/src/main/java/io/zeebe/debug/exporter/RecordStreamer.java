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

import io.camunda.zeebe.exporter.api.context.Controller;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.zeebe.debug.exporter.protocol.DebugExporter.Ack;
import io.zeebe.debug.exporter.protocol.DebugExporter.ExportedRecord;
import io.zeebe.debug.exporter.protocol.ExporterServiceGrpc.ExporterServiceStub;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.WillNotClose;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;

/**
 * Streams records out to the server. On error, retries any records in the buffer, in order, before
 * sending new records. On acknowledgement, removes all records with a position less than or equal
 * to the acknowledged position from the buffer.
 *
 * <p>NOTE: this implementation relies on the sequential executor executing all submitted tasks in
 * the order in which they were submitted. It also expects that, if the executor is shut down, tasks
 * are simply silently discarded (i.e. it will not handle rejections). It does not however manage
 * the executor, and will not shut it down on close.
 *
 * <p>NOTE: monitors the circuit breaker, and on half open will send a single record (if any) in its
 * buffer.
 */
@ThreadSafe
@ParametersAreNonnullByDefault
final class RecordStreamer implements StreamObserver<Ack>, AutoCloseable {
  private final Controller controller;
  private final Logger logger;
  private final ExporterServiceStub service;
  private final ExecutorService sequentialExecutor;
  private final RecordBuffer buffer;
  private final ServerMonitor monitor;

  @GuardedBy("sequentialExecutor")
  private StreamObserver<ExportedRecord> exportStream;

  @GuardedBy("sequentialExecutor")
  private long currentPosition = Long.MIN_VALUE;

  RecordStreamer(
      final Controller controller,
      final Logger logger,
      final ExporterServiceStub service,
      final @WillNotClose ExecutorService sequentialExecutor,
      final RecordBuffer buffer,
      final @WillNotClose ServerMonitor monitor) {
    this.controller = Objects.requireNonNull(controller);
    this.logger = Objects.requireNonNull(logger);
    this.service = Objects.requireNonNull(service);
    this.sequentialExecutor = Objects.requireNonNull(sequentialExecutor);
    this.buffer = Objects.requireNonNull(buffer);
    this.monitor = Objects.requireNonNull(monitor);

    this.monitor.withProbe(this::probe).onAvailable(this::onNewRecordAvailable);
  }

  void onNewRecordAvailable() {
    sequentialExecutor.submit(this::sendNextRecord);
  }

  /**
   * Closes any outstanding calls.
   *
   * <p>NOTE: this method is expected to be call once the executor has ALREADY been shut down, so
   * there is no need to sequence the operation.
   */
  @Override
  public void close() {
    assert sequentialExecutor.isShutdown()
        : "executor must be shutdown first to avoid race conditions with the exportStream";

    if (exportStream != null) {
      exportStream.onCompleted();
    }
  }

  @GuardedBy("sequentialExecutor")
  @Override
  public void onNext(final Ack ack) {
    final int partitionId = ack.getPartitionId();
    final long position = ack.getPosition();

    // for now assume partitions are fine, but I suppose this should be validated
    logger.trace(
        "[STREAMER] Received server ACK for partitionId {} and position {}", partitionId, position);
    controller.updateLastExportedRecordPosition(position);
    buffer.compact(position);
  }

  @GuardedBy("sequentialExecutor")
  @Override
  public void onError(final Throwable throwable) {
    resetStream();

    // UNAVAILABLE may be returned on client/server orderly shutdown, or when the server is truly
    // unreachable; at any rate, there's no point retrying here or even logging an error as the
    // monitor will log server (un)availability
    if (throwable instanceof StatusRuntimeException) {
      final StatusRuntimeException statusException = (StatusRuntimeException) throwable;
      if (statusException.getStatus().getCode() == Code.UNAVAILABLE) {
        return;
      }
    }

    // if the breaker isn't closed then we're already in error state, reporting another error
    // is just noise
    if (monitor.isServerAvailable()) {
      logger.warn("[STREAMER] Received server error, closing client and retrying", throwable);
      sequentialExecutor.submit(this::sendNextRecord);
    }
  }

  @GuardedBy("sequentialExecutor")
  @Override
  public void onCompleted() {
    logger.trace("[STREAMER] Remote server gracefully closed the gRPC stream");
    resetStream();

    if (monitor.isServerAvailable()) {
      sequentialExecutor.submit(this::sendNextRecord);
    }
  }

  @GuardedBy("sequentialExecutor")
  private void sendNextRecord() {
    if (!monitor.isServerAvailable()) {
      return;
    }

    if (exportStream == null) {
      exportStream = service.export(this);
    }

    buffer.get(currentPosition + 1).ifPresent(this::sendSequencedRecord);
  }

  @GuardedBy("sequentialExecutor")
  private void sendSequencedRecord(final ExportedRecord record) {
    if (sendRecord(record)) {
      sequentialExecutor.submit(this::sendNextRecord);
    }
  }

  /**
   * NOTE: even when returning false, the error has already been handled.
   *
   * @param record the record to send to the server
   * @return true if successfully sent, false otherwise
   */
  @GuardedBy("sequentialExecutor")
  private boolean sendRecord(final ExportedRecord record) {
    Objects.requireNonNull(record);

    logger.trace("[STREAMER] Exporting record at position {}", record.getPosition());

    try {
      exportStream.onNext(record);
      currentPosition = record.getPosition();
      return true;
    } catch (final RuntimeException e) {
      exportStream.onError(e);
      exportStream = null;
      return false;
    }
  }

  @GuardedBy("sequentialExecutor")
  private void resetStream() {
    exportStream = service.export(this);
    currentPosition = Long.MIN_VALUE;
  }

  private void probe() {
    sequentialExecutor.submit(this::probeSequenced);
  }

  @GuardedBy("sequentialExecutor")
  private void probeSequenced() {
    logger.trace("[STREAMER] Exporting a single record as availability probe");

    resetStream();
    buffer.get(currentPosition + 1).ifPresent(this::sendRecord);
  }
}
