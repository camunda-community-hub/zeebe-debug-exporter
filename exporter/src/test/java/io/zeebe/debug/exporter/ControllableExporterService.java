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

import io.grpc.stub.StreamObserver;
import io.zeebe.debug.exporter.protocol.DebugExporter.Ack;
import io.zeebe.debug.exporter.protocol.DebugExporter.ExportedRecord;
import io.zeebe.debug.exporter.protocol.ExporterServiceGrpc.ExporterServiceImplBase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

final class ControllableExporterService extends ExporterServiceImplBase
    implements StreamObserver<ExportedRecord> {
  private AtomicReference<StreamObserver<Ack>> currentStreamRef = new AtomicReference<>();
  private AtomicBoolean completed = new AtomicBoolean();
  private List<Throwable> errors = new CopyOnWriteArrayList<>();
  private BiConsumer<ExportedRecord, StreamObserver<Ack>> onNextCallback = (r, s) -> {};

  @Override
  public StreamObserver<ExportedRecord> export(final StreamObserver<Ack> responseObserver) {
    currentStreamRef.set(responseObserver);
    return this;
  }

  StreamObserver<Ack> getCurrentStream() {
    return currentStreamRef.get();
  }

  boolean isCompleted() {
    return completed.get();
  }

  List<Throwable> getErrors() {
    return new ArrayList<>(errors);
  }

  void setOnNextCallback(final BiConsumer<ExportedRecord, StreamObserver<Ack>> onNextCallback) {
    this.onNextCallback = onNextCallback;
  }

  @Override
  public void onNext(final ExportedRecord value) {
    onNextCallback.accept(value, getCurrentStream());
  }

  @Override
  public void onError(final Throwable t) {
    errors.add(t);
  }

  @Override
  public void onCompleted() {
    completed.set(true);
  }
}
