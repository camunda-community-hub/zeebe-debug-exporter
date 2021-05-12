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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.util.NetUtil;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.protocol.record.RecordValue;
import io.camunda.zeebe.test.EmbeddedBrokerRule;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates golden files for IT tests. If you want to enable the tests, you'll need to remove the
 * annotation on both the class level and the specific tests you want to run.
 *
 * <p>NOTE: these must be executed in the same thread due to their reliance on the RecordingExporter
 * which provides no isolation.
 */
@Disabled(value = "enable only if you want to generate new golden files")
@SuppressWarnings("java:S2699")
@Execution(ExecutionMode.SAME_THREAD)
final class GenerateGoldenFileTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final EmbeddedBrokerRule brokerRule = new EmbeddedBrokerRule();

  @BeforeEach
  void beforeEach() {
    brokerRule.startBroker();

    RecordingExporter.setMaximumWaitTime(5_000);
    RecordingExporter.reset();
  }

  @AfterEach
  void afterEach() {
    brokerRule.stopBroker();
  }

  /** Remove or comment {@link Disabled} to generate a new golden file. */
  @Disabled(value = "enable only if you want to generate new golden files")
  @Test
  void generateZeebeDebugExporterIT() throws JsonProcessingException {
    try (final ZeebeClient client =
        ZeebeClient.newClientBuilder()
            .gatewayAddress(NetUtil.toSocketAddressString(brokerRule.getGatewayAddress()))
            .usePlaintext()
            .build()) {
      new SampleWorkload(client).execute();
    }

    RecordingExporter.setMaximumWaitTime(1_000);
    final List<Record<RecordValue>> records =
        RecordingExporter.records().collect(Collectors.toList());
    final String contents =
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(records);

    final Logger logger = LoggerFactory.getLogger(getClass());
    logger.info(
        "Golden file contents fetched; please paste the following into resources/records.golden.json:\n{}",
        contents);
  }
}
