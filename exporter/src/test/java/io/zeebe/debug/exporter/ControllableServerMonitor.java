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

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@ParametersAreNonnullByDefault
final class ControllableServerMonitor implements ServerMonitor {
  private final AtomicReference<Runnable> probeCallback = new AtomicReference<>();
  private final AtomicReference<Runnable> availableCallback = new AtomicReference<>();
  private final AtomicBoolean serverAvailable = new AtomicBoolean(true);

  @Override
  public boolean isServerAvailable() {
    return serverAvailable.get();
  }

  @Nonnull
  @Override
  public ServerMonitor onAvailable(@Nullable final Runnable callback) {
    availableCallback.set(callback);
    return this;
  }

  @Nonnull
  @Override
  public ServerMonitor withProbe(final Runnable callback) {
    probeCallback.set(callback);
    return this;
  }

  void setServerAvailable(final boolean serverAvailable) {
    final boolean previousValue = this.serverAvailable.getAndSet(serverAvailable);

    if (!previousValue && serverAvailable) {
      notifyAvailable();
    }
  }

  void notifyAvailable() {
    Optional.ofNullable(availableCallback.get()).ifPresent(Runnable::run);
  }

  void probe() {
    Optional.ofNullable(probeCallback.get()).ifPresent(Runnable::run);
  }

  @Override
  public void close() {}
}
