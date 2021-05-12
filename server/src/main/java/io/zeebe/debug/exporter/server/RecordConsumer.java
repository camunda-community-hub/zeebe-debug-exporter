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

import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.util.Either;
import io.grpc.Status;
import io.zeebe.debug.exporter.protocol.DebugExporter.ExportedRecord;
import java.util.OptionalLong;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import org.apiguardian.api.API;

/**
 * Record consumers receive a deserialized records and must return their greatest known acknowledged
 * position. Consumers are guaranteed to receive records in order w.r.t to their partition - that
 * is, all records for a given partition X will be received in sorted by position, ascending. Note
 * however that a consumer may receive records from multiple partitions, and records between
 * partitions are not ordered.
 *
 * <p>NOTE: as Zeebe exporters use at-least-once semantics, it's possible to receive duplicates.
 * Note however that each sequence is still sorted w.r.t to the partition. This means that while a
 * single sequence will always be increasing and sorted, a new sequence may restart at a previously
 * seen position. However, that sequence will then be increasing and sorted. It's not possible to
 * receive a sequence such as: {@code 1, 9, 2, -1, 3, 0, 10}, but it is possible to receive {@code
 * 1, 2, 3, 4, 5, 2, 3, 4, 5, 6}. Consumers are expected to perform deduplication.
 *
 * <p>NOTE: when acknowledging with a given position, if a higher position was already recorded by
 * the Zeebe broker for that partition, the new position returned here will simply be ignored.
 */
@API(status = API.Status.EXPERIMENTAL)
@FunctionalInterface
@ParametersAreNonnullByDefault
public interface RecordConsumer {
  @Nonnull
  Either<Status, OptionalLong> acknowledge(final Record<?> record);

  @Nonnull
  default Either<Status, OptionalLong> acknowledge(
      final ExportedRecord record, final RecordDeserializer recordDeserializer) {
    return recordDeserializer.deserialize(record).flatMap(this::acknowledge);
  }

  @ParametersAreNonnullByDefault
  interface RecordDeserializer {

    /**
     * Deserializes the given exported record or returns an exception if it could not.
     *
     * @param record the record to deserializer
     * @return a deserialized record, or a status error
     */
    @Nonnull
    Either<Status, Record<?>> deserialize(final ExportedRecord record);
  }
}
