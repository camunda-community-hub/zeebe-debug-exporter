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
package io.zeebe.debug.exporter.agent;

import io.grpc.Status;
import io.zeebe.debug.exporter.common.Transport;
import io.zeebe.debug.exporter.protocol.DebugExporter.ExportedRecord;
import io.zeebe.debug.exporter.server.DebugServer;
import io.zeebe.debug.exporter.server.DebugServerConfig;
import io.zeebe.debug.exporter.server.RecordConsumer;
import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.util.Either;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import org.agrona.concurrent.ShutdownSignalBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@SuppressWarnings({"FieldMayBeFinal", "FieldCanBeLocal"})
@Command(
    name = "agent",
    mixinStandardHelpOptions = true,
    version = "agent 0.26.1",
    description = "Standalone Zeebe Debug Exporter Server agent")
@ParametersAreNonnullByDefault
public final class Agent implements Callable<Integer>, RecordConsumer {
  private final Logger logger;

  @Option(
      names = {"-a", "--address"},
      paramLabel = "ADDRESS",
      description =
          "The server's target address; format changes based on the transport: INET => host:port,"
              + " UNIX => /path/to/socket",
      defaultValue = "localhost:0")
  private String address = "localhost:0";

  @Option(
      names = {"-t", "--transport"},
      paramLabel = "TRANSPORT",
      description = "The server transport, one of INET, UNIX, or IPC",
      defaultValue = "INET",
      converter = TransportConverter.class)
  private Transport transport = Transport.INET;

  public Agent() {
    logger = LoggerFactory.getLogger(getClass());
  }

  @Override
  @Nonnull
  public Integer call() throws Exception {
    final DebugServerConfig config =
        new DebugServerConfig()
            .setTransport(transport)
            .setTarget(address)
            .setRecordConsumer(this)
            .setLogger(logger);
    final ShutdownSignalBarrier shutdownBarrier = new ShutdownSignalBarrier();

    try (final DebugServer server = new DebugServer(config)) {
      server.start();
      shutdownBarrier.await();
      return 0;
    }
  }

  @Nonnull
  @Override
  public Either<Status, OptionalLong> acknowledge(final Record<?> record) {
    throw new UnsupportedOperationException(
        "The agent does not need to consume deserialized records as it will just print them back as JSON");
  }

  @Nonnull
  @Override
  public Either<Status, OptionalLong> acknowledge(
      final ExportedRecord record, final RecordDeserializer recordDeserializer) {
    if (logger.isInfoEnabled()) {
      logger.info(
          "Received record #{} (P#{}):\n{}",
          record.getPosition(),
          record.getPartitionId(),
          record.getData().toStringUtf8());
    }

    return Either.right(OptionalLong.of(record.getPosition()));
  }

  public static void main(final String[] args) {
    final int exitCode = new CommandLine(new Agent()).execute(args);
    System.exit(exitCode);
  }
}
