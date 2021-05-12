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
import io.grpc.stub.StreamObserver;
import io.zeebe.debug.exporter.protocol.DebugExporter.Ack;
import io.zeebe.debug.exporter.protocol.DebugExporter.ExportedRecord;
import io.zeebe.debug.exporter.protocol.ExporterServiceGrpc.ExporterServiceImplBase;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;

@ParametersAreNonnullByDefault
final class ExporterService extends ExporterServiceImplBase {
  private final RecordConsumer consumer;
  private final ObjectReader jsonReader;
  private final Logger logger;

  ExporterService(
      final RecordConsumer consumer, final ObjectReader jsonReader, final Logger logger) {
    this.consumer = Objects.requireNonNull(consumer);
    this.jsonReader = Objects.requireNonNull(jsonReader);
    this.logger = Objects.requireNonNull(logger);
  }

  @Nonnull
  @Override
  public StreamObserver<ExportedRecord> export(final StreamObserver<Ack> responseObserver) {
    return new RecordIngestor(consumer, responseObserver, jsonReader, logger);
  }
}
