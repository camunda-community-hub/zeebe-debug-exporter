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

import io.camunda.zeebe.client.ZeebeClient;
import java.time.Duration;
import java.util.Map;

final class SampleWorkload {
  private final ZeebeClient client;

  SampleWorkload(final ZeebeClient client) {
    this.client = client;
  }

  void execute() {
    final Map<String, String> variables = Map.of("foo", "bar");
    client
        .newPublishMessageCommand()
        .messageName("message")
        .correlationKey("key")
        .messageId("id")
        .timeToLive(Duration.ZERO)
        .variables(variables)
        .send()
        .join();
  }
}
