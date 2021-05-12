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

import io.grpc.Status;
import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.util.Either;
import java.util.Collection;
import java.util.Objects;
import java.util.OptionalLong;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

/**
 * Default implementation of {@link RecordConsumer}. It will add all records to the given
 * collection, and immediately acknowledge them, properly reporting any errors back to the client.
 *
 * <p>NOTE: the class is meant to be extensible by design.
 */
@ParametersAreNonnullByDefault
public class RecordCollector implements RecordConsumer {
  protected final Collection<Record<?>> collection;

  public RecordCollector(final Collection<Record<?>> collection) {
    this.collection = Objects.requireNonNull(collection);
  }

  @Nonnull
  @Override
  public Either<Status, OptionalLong> acknowledge(final Record<?> record) {
    try {
      collection.add(record);
      return Either.right(OptionalLong.of(record.getPosition()));
    } catch (final UnsupportedOperationException e) {
      return Either.left(Status.UNIMPLEMENTED.withCause(e));
    } catch (final ClassCastException | NullPointerException | IllegalArgumentException e) {
      return Either.left(Status.INVALID_ARGUMENT.withCause(e));
    } catch (final IllegalStateException e) {
      return Either.left(Status.FAILED_PRECONDITION.withCause(e));
    } catch (final Exception e) {
      return Either.left(Status.INTERNAL.withCause(e));
    }
  }
}
