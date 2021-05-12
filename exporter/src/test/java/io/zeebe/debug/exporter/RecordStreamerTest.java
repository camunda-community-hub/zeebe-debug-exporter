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

import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;
import io.camunda.zeebe.test.exporter.MockController;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.zeebe.debug.exporter.protocol.DebugExporter.Ack;
import io.zeebe.debug.exporter.protocol.DebugExporter.ExportedRecord;
import io.zeebe.debug.exporter.protocol.ExporterServiceGrpc;
import io.zeebe.debug.exporter.protocol.ExporterServiceGrpc.ExporterServiceStub;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.agrona.CloseHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Execution(ExecutionMode.CONCURRENT)
class RecordStreamerTest {
  private static final ExportedRecord TEMPLATE =
      ExportedRecord.newBuilder()
          .setPosition(1)
          .setPartitionId(1)
          .setData(ByteString.EMPTY)
          .buildPartial();

  private final List<ExportedRecord> exportedRecords = new CopyOnWriteArrayList<>();
  private final MockController controller = new MockController();
  private final Logger logger = LoggerFactory.getLogger(getClass());
  private final RecordBuffer buffer = new RecordBuffer(1024 * 1024L, logger);
  private final ExecutorService executor = MoreExecutors.newDirectExecutorService();

  private ControllableExporterService service;
  private Server server;
  private ManagedChannel channel;
  private ControllableServerMonitor monitor;
  private RecordStreamer streamer;

  @BeforeEach
  void beforeEach() throws IOException {
    final String serverName = UUID.randomUUID().toString();
    service = new ControllableExporterService();
    server =
        InProcessServerBuilder.forName(serverName).directExecutor().addService(service).build();
    channel = InProcessChannelBuilder.forName(serverName).usePlaintext().directExecutor().build();

    final ExporterServiceStub serviceStub = ExporterServiceGrpc.newStub(channel);
    monitor = new ControllableServerMonitor();
    streamer = new RecordStreamer(controller, logger, serviceStub, executor, buffer, monitor);

    service.setOnNextCallback(
        (r, s) -> {
          exportedRecords.add(r);
          s.onNext(
              Ack.newBuilder()
                  .setPartitionId(r.getPartitionId())
                  .setPosition(r.getPosition())
                  .build());
        });
    server.start();
  }

  /**
   * NOTE: it's not possible to use shutdownNow on the channel/server since they are both using the
   * direct executor and in process transport - doing so would result in an infinite loop where
   * either one tries to cancel each other's connection
   */
  @AfterEach
  void afterEach() throws InterruptedException {
    CloseHelper.quietCloseAll(streamer, monitor);

    if (channel != null) {
      channel.shutdown();
      channel.awaitTermination(10, TimeUnit.MILLISECONDS);
    }

    if (server != null) {
      server.shutdown();
      server.awaitTermination(10, TimeUnit.MILLISECONDS);
    }
  }

  @Test
  void shouldStreamAllRecords() {
    // given no records was exported yet
    final List<ExportedRecord> offeredRecords =
        List.of(
            ExportedRecord.newBuilder(TEMPLATE).build(),
            ExportedRecord.newBuilder(TEMPLATE).setPosition(2).build());
    offeredRecords.forEach(buffer::offer);

    // when
    streamer.onNewRecordAvailable();

    // then
    assertThat(exportedRecords)
        .as("all records from the buffer should have been exported")
        .containsExactlyElementsOf(offeredRecords);
  }

  @Test
  void shouldStreamNewRecords() {
    // given that we've already exported the first record with position 1
    final List<ExportedRecord> offeredRecords =
        List.of(
            ExportedRecord.newBuilder(TEMPLATE).setPosition(2).build(),
            ExportedRecord.newBuilder(TEMPLATE).setPosition(3).build());
    buffer.offer(ExportedRecord.newBuilder(TEMPLATE).build());
    streamer.onNewRecordAvailable();
    offeredRecords.forEach(buffer::offer);
    exportedRecords.clear();

    // when
    streamer.onNewRecordAvailable();

    // then
    assertThat(exportedRecords)
        .as("only records with position greater than 1 should have been should have been exported")
        .containsExactlyElementsOf(offeredRecords);
  }

  @Test
  void shouldRetryFromStartOnError() {
    // given that the first record will be rejected
    final List<ExportedRecord> offeredRecords =
        List.of(
            ExportedRecord.newBuilder(TEMPLATE).build(),
            ExportedRecord.newBuilder(TEMPLATE).setPosition(2).build());
    final List<ExportedRecord> expectedRecords = new ArrayList<>(offeredRecords);
    expectedRecords.addAll(offeredRecords);
    offeredRecords.forEach(buffer::offer);

    // when
    service.setOnNextCallback((r, s) -> exportedRecords.add(r));
    streamer.onNewRecordAvailable();
    streamer.onError(new RuntimeException("some error"));

    // then
    assertThat(exportedRecords)
        .as("all records should have been exported twice due to retry")
        .containsExactlyElementsOf(expectedRecords);
  }

  @Test
  void shouldSendSingleRecordOnProbe() {
    // given
    final List<ExportedRecord> offeredRecords =
        List.of(
            ExportedRecord.newBuilder(TEMPLATE).build(),
            ExportedRecord.newBuilder(TEMPLATE).setPosition(2).build());
    offeredRecords.forEach(buffer::offer);
    monitor.setServerAvailable(false);

    // when
    monitor.probe();

    // then
    assertThat(exportedRecords)
        .as("only the first record should have been sent as a probe")
        .containsExactly(offeredRecords.get(0));
  }

  @Test
  void shouldResendSameRecordOnMultipleProbe() {
    // given
    final List<ExportedRecord> offeredRecords =
        List.of(
            ExportedRecord.newBuilder(TEMPLATE).build(),
            ExportedRecord.newBuilder(TEMPLATE).setPosition(2).build());
    offeredRecords.forEach(buffer::offer);
    service.setOnNextCallback((r, s) -> exportedRecords.add(r));
    monitor.setServerAvailable(false);

    // when
    monitor.probe();
    monitor.probe();

    // then
    assertThat(exportedRecords)
        .as("only the first record should be sent as a probe")
        .containsExactly(offeredRecords.get(0), offeredRecords.get(0));
  }

  @Test
  void shouldStreamRemainingBufferWhenServerIsAvailableAgain() {
    // given that only the probe record has been sent
    final List<ExportedRecord> offeredRecords =
        List.of(
            ExportedRecord.newBuilder(TEMPLATE).build(),
            ExportedRecord.newBuilder(TEMPLATE).setPosition(2).build(),
            ExportedRecord.newBuilder(TEMPLATE).setPosition(3).build());
    offeredRecords.forEach(buffer::offer);
    monitor.setServerAvailable(false);
    monitor.probe();

    // when
    monitor.setServerAvailable(true);

    // then
    assertThat(exportedRecords)
        .as("all records should have been exported once the server became available")
        .containsExactlyElementsOf(offeredRecords);
  }

  @Test
  void shouldCompactBufferOnAck() {
    final List<ExportedRecord> offeredRecords =
        List.of(
            ExportedRecord.newBuilder(TEMPLATE).build(),
            ExportedRecord.newBuilder(TEMPLATE).setPosition(2).build(),
            ExportedRecord.newBuilder(TEMPLATE).setPosition(3).build());
    offeredRecords.forEach(buffer::offer);

    // when
    streamer.onNext(
        Ack.newBuilder().setPartitionId(TEMPLATE.getPartitionId()).setPosition(2L).build());

    // then
    assertThat(buffer.get(Long.MIN_VALUE))
        .as("only the last record should remain in the buffer")
        .hasValue(offeredRecords.get(2));
  }

  @Test
  void shouldRetryWithRemainingRecordsOnCompleted() {
    // given only the first record has been sent
    final List<ExportedRecord> offeredRecords =
        List.of(
            ExportedRecord.newBuilder(TEMPLATE).build(),
            ExportedRecord.newBuilder(TEMPLATE).setPosition(2).build(),
            ExportedRecord.newBuilder(TEMPLATE).setPosition(3).build());
    buffer.offer(offeredRecords.get(0));
    streamer.onNewRecordAvailable();

    // when
    buffer.offer(offeredRecords.get(1));
    buffer.offer(offeredRecords.get(2));
    streamer.onCompleted();

    // then
    assertThat(exportedRecords)
        .as("all remaining records should have been exported after a retry on completed")
        .containsExactlyElementsOf(offeredRecords);
  }
}
