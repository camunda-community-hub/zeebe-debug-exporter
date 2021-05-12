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
package io.zeebe.debug.exporter.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.protocol.record.Record;
import io.zeebe.containers.ZeebeContainer;
import io.zeebe.debug.exporter.common.Transport;
import io.zeebe.debug.exporter.server.DebugServer;
import io.zeebe.debug.exporter.server.DebugServerConfig;
import io.zeebe.debug.exporter.server.RecordCollector;
import io.zeebe.protocol.immutables.record.value.ImmutableRecord;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.BindMode;

@Execution(ExecutionMode.CONCURRENT)
final class ZeebeDebugExporterIT {
  private final List<Record<?>> exportedRecords = new CopyOnWriteArrayList<>();
  private final DebugServerConfig serverConfig = new DebugServerConfig();
  private final DebugServer server = new DebugServer(serverConfig);
  private final ZeebeContainer zeebeContainer = new ZeebeContainer();

  @BeforeEach
  void beforeEach(final @TempDir Path socketDir) throws IOException {
    configureTransport(socketDir);
    configureExporter();

    server.start();
  }

  @AfterEach
  void afterEach() throws Exception {
    zeebeContainer.stop();
    server.close();
  }

  @Test
  void shouldPerformSampleWorkload() throws IOException {
    // given
    zeebeContainer.start();

    // when
    try (final ZeebeClient client =
        ZeebeClient.newClientBuilder()
            .gatewayAddress(zeebeContainer.getExternalGatewayAddress())
            .usePlaintext()
            .build()) {
      new SampleWorkload(client).execute();
    }

    // then
    final List<Record<?>> expectedRecords = readGoldenFile();
    Awaitility.await("until all records have been exported")
        .atMost(Duration.ofSeconds(10))
        .pollInSameThread()
        .untilAsserted(() -> assertThat(exportedRecords).hasSameSizeAs(expectedRecords));
    assertThat(exportedRecords)
        .zipSatisfy(
            expectedRecords,
            (actualRecord, expectedRecord) ->
                assertThat(actualRecord)
                    .usingRecursiveComparison()
                    .ignoringFields("timestamp", "hashCode")
                    .isEqualTo(expectedRecord));
  }

  private List<Record<?>> readGoldenFile() throws IOException {
    final ObjectReader reader =
        new ObjectMapper().readerFor(new TypeReference<List<ImmutableRecord<?>>>() {});

    try (final InputStream inputStream =
        getClass().getClassLoader().getResourceAsStream("records.golden.json")) {
      final List<ImmutableRecord<?>> records = reader.readValue(inputStream);
      return new ArrayList<>(records);
    }
  }

  private void configureExporter() {
    final String jarPath = "/usr/local/zeebe/exporters/zeebe-debug-exporter.jar";

    zeebeContainer
        .withEnv("ZEEBE_LOG_LEVEL", "debug")
        .withEnv(
            "ZEEBE_BROKER_EXPORTERS_DEBUG_CLASSNAME", "io.zeebe.debug.exporter.ZeebeDebugExporter")
        .withEnv("ZEEBE_BROKER_EXPORTERS_DEBUG_JARPATH", jarPath)
        .withClasspathResourceMapping("zeebe-debug-exporter.jar", jarPath, BindMode.READ_ONLY);
  }

  private void configureTransport(final Path socketDir) {
    final Path hostSocketPath = socketDir.resolve("server.sock").toAbsolutePath();
    final String containerSocketPath = "/tmp/server.sock";
    serverConfig
        .setTransport(Transport.UNIX)
        .setTarget(hostSocketPath.toString())
        .setRecordConsumer(new RecordCollector(exportedRecords));
    zeebeContainer
        .withEnv("ZEEBE_BROKER_EXPORTERS_DEBUG_ARGS_TARGET", hostSocketPath.toString())
        .withEnv("ZEEBE_BROKER_EXPORTERS_DEBUG_ARGS_TRANSPORT", Transport.UNIX.name())
        .withFileSystemBind(hostSocketPath.toString(), containerSocketPath, BindMode.READ_WRITE);
  }
}
