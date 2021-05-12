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
