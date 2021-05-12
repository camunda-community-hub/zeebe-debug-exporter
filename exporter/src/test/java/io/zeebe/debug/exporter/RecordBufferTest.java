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

import io.zeebe.debug.exporter.protocol.DebugExporter.ExportedRecord;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Execution(ExecutionMode.CONCURRENT)
class RecordBufferTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(RecordBufferTest.class);

  @Test
  void shouldOfferRecord() {
    // given
    final ExportedRecord record =
        ExportedRecord.newBuilder().setPartitionId(1).setPosition(1).build();
    final RecordBuffer buffer = new RecordBuffer(4096, LOGGER);

    // when
    final boolean offered = buffer.offer(record);

    // then
    final Optional<ExportedRecord> exportedRecord = buffer.get(record.getPosition());
    assertThat(offered).as("record was accepted as there is enough space in the buffer").isTrue();
    assertThat(exportedRecord).as("accepted record is in the buffer").hasValue(record);
  }

  @Test
  void shouldRejectRecord() {
    // given
    final ExportedRecord record =
        ExportedRecord.newBuilder().setPartitionId(1).setPosition(1).build();
    final ExportedRecord rejectedRecord = ExportedRecord.newBuilder(record).setPosition(2).build();
    final RecordBuffer buffer = new RecordBuffer(record.getSerializedSize(), LOGGER);

    // when
    buffer.offer(record);
    final boolean offered = buffer.offer(rejectedRecord);

    // then
    final Optional<ExportedRecord> exportedRecord = buffer.get(rejectedRecord.getPosition());
    assertThat(offered).as("record was rejected due to not enough space in the buffer").isFalse();
    assertThat(exportedRecord).as("rejected record is not in the buffer").isEmpty();
  }

  @Test
  void shouldCompactBuffer() {
    // given
    final List<ExportedRecord> records = new ArrayList<>();
    final RecordBuffer buffer = new RecordBuffer(1024 * 1024L, LOGGER);
    IntStream.range(0, 5)
        .mapToObj(i -> ExportedRecord.newBuilder().setPartitionId(1).setPosition(i).build())
        .peek(records::add)
        .forEach(buffer::offer);

    // when
    buffer.compact(2L);

    // then
    final List<ExportedRecord> exportedRecords = getBufferedRecords(buffer);
    assertThat(exportedRecords)
        .as("has only two records (#3 and #4) left in the buffer after compacting up to #2")
        .hasSize(2)
        .containsExactly(records.get(3), records.get(4));
  }

  @Test
  void shouldTrimBuffer() {
    // given
    final LinkedList<ExportedRecord> records = new LinkedList<>();
    final RecordBuffer buffer = new RecordBuffer(1024 * 1024L, LOGGER);
    IntStream.range(0, 5)
        .mapToObj(i -> ExportedRecord.newBuilder().setPartitionId(1).setPosition(i).build())
        .peek(records::add)
        .forEach(buffer::offer);

    // when
    buffer.trim();

    // then
    final List<ExportedRecord> exportedRecords = getBufferedRecords(buffer);
    assertThat(exportedRecords)
        .as("has exactly only one record (#4, the last one) left in the buffer after trimming")
        .hasSize(1)
        .containsExactly(records.get(4));
  }

  private List<ExportedRecord> getBufferedRecords(final RecordBuffer buffer) {
    final List<ExportedRecord> records = new ArrayList<>();
    Optional<ExportedRecord> record = Optional.empty();

    do {
      final long position = record.map(ExportedRecord::getPosition).orElse(-1L) + 1;
      record = buffer.get(position);
      record.ifPresent(records::add);
    } while (record.isPresent());

    return records;
  }
}
