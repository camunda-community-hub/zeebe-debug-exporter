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

import io.zeebe.debug.exporter.protocol.DebugExporter.ExportedRecord;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;

/**
 * Buffers records, ordered by position, in a thread safe way, with a maximum total size.
 *
 * <p>Note: the maximum size may be breached iff the buffer is currently empty, and the record
 * offered is greater than the maximum size. This is to ensure we can send out records even if they
 * would be too big to fit in the buffer.
 */
@ThreadSafe
@ParametersAreNonnullByDefault
final class RecordBuffer {
  private final long maxSizeBytes;
  private final Logger logger;

  private final Lock bufferLock;

  @GuardedBy("bufferLock")
  private final NavigableMap<Long, ExportedRecord> buffer = new TreeMap<>();

  @GuardedBy("bufferLock")
  private long currentSizeBytes;

  RecordBuffer(final long maxSizeBytes, final Logger logger) {
    this(maxSizeBytes, logger, new ReentrantLock());
  }

  RecordBuffer(final long maxSizeBytes, final Logger logger, final Lock bufferLock) {
    this.maxSizeBytes = maxSizeBytes;
    this.bufferLock = bufferLock;
    this.logger = logger;
  }

  /**
   * Adds the given record to this buffer iff {@link #currentSizeBytes} + {@link
   * ExportedRecord#getSerializedSize()} <= {@link #maxSizeBytes}. Returns false if the record
   * cannot be added.
   *
   * @param record the record to add to the buffer
   * @return true if added, false otherwise
   */
  boolean offer(final ExportedRecord record) {
    return Objects.requireNonNull(callWithLock(() -> offerWithLock(record), false));
  }

  /**
   * Removes all records from the buffer with a position less than or equal to the given {@code
   * position}, such that any remaining records have a position strictly greater than the given
   * position.
   *
   * @param position the position to compact up to (inclusive)
   */
  void compact(final long position) {
    runWithLock(() -> compactWithLock(position));
  }

  /**
   * Trims the buffer down to the last entry, which will remain in the buffer. Useful to clear some
   * memory for a lossy exporter when the server is unavailable, but keep a single record in the
   * pipeline to use as a health check/probe.
   */
  void trim() {
    runWithLock(this::trimWithLock);
  }

  /**
   * A non-threadsafe method which returns the number of entries in the buffer. This is mostly used
   * in the "hot" path when the exporter is configured to be lossy and the circuit is opened. It
   * avoids having to acquire the lock every time we call {@link #trim()}. Although not thread safe,
   * in this case it's perfectly fine to be a little outdated, as long as eventually we stop having
   * to call {@link #trim()}.
   *
   * @return the number of records in the buffer
   */
  int countUnsafe() {
    return buffer.size();
  }

  @Nonnull
  Optional<ExportedRecord> get(long position) {
    return Objects.requireNonNull(
        callWithLock(
            () -> Optional.ofNullable(buffer.ceilingEntry(position)).map(Entry::getValue),
            Optional.empty()));
  }

  @GuardedBy("bufferLock")
  private boolean offerWithLock(final ExportedRecord record) {
    Objects.requireNonNull(record);

    // if the batch is empty, we can ignore the max batch size - this allows for large records
    // which would be bigger than the max batch size
    final long newBufferSize = currentSizeBytes + record.getSerializedSize();
    if (buffer.isEmpty() || maxSizeBytes <= 0 || newBufferSize <= maxSizeBytes) {
      logger.trace(
          "[BUFFER] Buffered record #{} with size {}",
          record.getPosition(),
          record.getSerializedSize());
      buffer.put(record.getPosition(), record);
      currentSizeBytes += record.getSerializedSize();
      return true;
    }

    logger.trace(
        "[BUFFER] Expected to buffer record #{} with size {}, but the current size is {} and the maximum size is {}",
        record.getPosition(),
        record.getSerializedSize(),
        currentSizeBytes,
        maxSizeBytes);
    return false;
  }

  @GuardedBy("bufferLock")
  private void compactWithLock(final long key) {
    final NavigableMap<Long, ExportedRecord> compactedRecords = buffer.headMap(key, true);
    final int compactedSize =
        compactedRecords.values().stream().mapToInt(ExportedRecord::getSerializedSize).sum();

    compactedRecords.clear();
    currentSizeBytes -= compactedSize;
  }

  @GuardedBy("bufferLock")
  private void trimWithLock() {
    final Entry<Long, ExportedRecord> lastEntry = buffer.lastEntry();
    if (lastEntry != null) {
      buffer.headMap(lastEntry.getKey(), false).clear();
      currentSizeBytes = lastEntry.getValue().getSerializedSize();
    }
  }

  private void runWithLock(final VoidSupplier runnable) {
    callWithLock(runnable, null);
  }

  @Nullable
  private <T> T callWithLock(final Supplier<T> callable, final @Nullable T interruptedValue) {
    try {
      bufferLock.lockInterruptibly();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.trace(
          "[BUFFER] Interrupted while attempting to lock bufferLock, most likely exporter is shutting down...",
          e);
      return interruptedValue;
    }

    try {
      return callable.get();
    } finally {
      bufferLock.unlock();
    }
  }

  /** Utility interface to seamlessly convert Runnable to Supplier<Void> */
  @FunctionalInterface
  private interface VoidSupplier extends Supplier<Void>, Runnable {
    default Void get() {
      run();
      return null;
    }
  }
}
