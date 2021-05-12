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

import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.protocol.record.RecordValue;
import io.camunda.zeebe.test.exporter.ExporterIntegrationRule;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import io.zeebe.debug.exporter.common.Transport;
import io.zeebe.debug.exporter.server.DebugServer;
import io.zeebe.debug.exporter.server.DebugServerConfig;
import io.zeebe.debug.exporter.server.RecordCollector;
import io.zeebe.debug.exporter.server.RecordConsumer;
import io.zeebe.protocol.immutables.ImmutableRecordCopier;
import io.zeebe.protocol.immutables.record.value.ImmutableRecord;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

// since we rely on the RecordingExporter, we need to ensure tests run in the same thread
@Execution(ExecutionMode.SAME_THREAD)
final class ZeebeDebugExporterTest {
  private final ExporterIntegrationRule testHarness = new ExporterIntegrationRule();
  private final ZeebeDebugExporterConfig exporterConfig = new ZeebeDebugExporterConfig();
  private final List<Record<?>> exportedRecords = new CopyOnWriteArrayList<>();
  private final RecordConsumer recordConsumer = new RecordCollector(exportedRecords);
  private final DebugServerConfig serverConfig =
      new DebugServerConfig().setRecordConsumer(recordConsumer);
  private final DebugServer server = new DebugServer(serverConfig);

  @BeforeEach
  void beforeEach() {
    final String serverName = UUID.randomUUID().toString();
    serverConfig.setTarget(serverName).setTransport(Transport.IPC);
    exporterConfig.setTarget(serverName).setTransport(Transport.IPC.name());

    RecordingExporter.setMaximumWaitTime(5_000L);
    RecordingExporter.reset();
  }

  @AfterEach
  void afterEach() throws Exception {
    testHarness.stop();
    server.close();
  }

  @Test
  void shouldPerformSampleWorkload() throws IOException {
    // given
    // server must start first so we can figure out the actual bind address
    server.start();
    testHarness.configure("debug", ZeebeDebugExporter.class, exporterConfig);
    testHarness.start();

    // when
    testHarness.performSampleWorkload();

    // then
    RecordingExporter.setMaximumWaitTime(1000L);
    final List<ImmutableRecord<RecordValue>> expectedRecords =
        RecordingExporter.records()
            .map(ImmutableRecordCopier::deepCopyOfRecord)
            .collect(Collectors.toList());
    Awaitility.await("until all records have been exported")
        .atMost(Duration.ofSeconds(10))
        .until(exportedRecords::size, size -> size >= expectedRecords.size());
    assertThat(exportedRecords).containsExactlyElementsOf(expectedRecords);
  }

  @Test
  void shouldExportRecordsEvenIfFailuresOccur() throws IOException {
    // given
    final AtomicBoolean shouldFail = new AtomicBoolean(true);
    serverConfig.setRecordConsumer(
        record -> {
          if (shouldFail.compareAndSet(true, false)) {
            throw new RuntimeException();
          }

          return recordConsumer.acknowledge(record);
        });
    server.start();
    testHarness.configure("debug", ZeebeDebugExporter.class, exporterConfig.setFailureThreshold(1));
    testHarness.start();

    // when
    testHarness.performSampleWorkload();
    RecordingExporter.setMaximumWaitTime(1000L);
    final List<ImmutableRecord<RecordValue>> expectedRecords =
        RecordingExporter.records()
            .map(ImmutableRecordCopier::deepCopyOfRecord)
            .collect(Collectors.toList());

    // then
    Awaitility.await("until all records have been exported")
        .atMost(Duration.ofSeconds(10))
        .until(exportedRecords::size, size -> size >= expectedRecords.size());
    assertThat(exportedRecords).containsExactlyElementsOf(expectedRecords);
  }

  @Test
  void shouldExportRecordsEvenIfServerStartsAfter() throws IOException {
    // given
    testHarness.configure("debug", ZeebeDebugExporter.class, exporterConfig);
    testHarness.start();
    testHarness.performSampleWorkload();
    RecordingExporter.setMaximumWaitTime(1000L);
    final List<ImmutableRecord<RecordValue>> expectedRecords =
        RecordingExporter.records()
            .map(ImmutableRecordCopier::deepCopyOfRecord)
            .collect(Collectors.toList());

    // when
    server.start();

    // then
    Awaitility.await("until all records have been exported")
        .atMost(Duration.ofSeconds(10))
        .until(exportedRecords::size, size -> size >= expectedRecords.size());
    assertThat(exportedRecords).containsExactlyElementsOf(expectedRecords);
  }
}
