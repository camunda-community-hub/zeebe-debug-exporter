package io.zeebe.debug.exporter;

import io.camunda.zeebe.util.CloseableSilently;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

/**
 * An instance of this type monitors a single server for availability, and allows consumers to
 * define how to probe for the server status and a single callback to react on availability.
 *
 * <p>Currently this interface exists mostly to provide a thin abstraction on top of {@link
 * net.jodah.failsafe.CircuitBreaker}, but allow for easier unit testing, notably for {@link
 * RecordStreamer}.
 *
 * <p>NOTE: there is currently no real way to react to unavailability as it wasn't needed yet - if
 * there is a need for it from the consumer point of view, feel free to add it.
 */
@SuppressWarnings("UnusedReturnValue")
@ParametersAreNonnullByDefault
interface ServerMonitor extends CloseableSilently {

  /**
   * Should return true if the server can definitely receive handle requests, false otherwise.
   * Should return false during the probe phase (i.e. half-open state)
   *
   * @return true if the server is available, false otherwise
   */
  boolean isServerAvailable();

  /**
   * Provides a callback which will be called exactly once every time the server goes from
   * unavailable to available. If a previous callback was already set, the new one will overwrite
   * it, such that there is only one callback at any time. You can pass null as a way to unset the
   * previous callback.
   *
   * @param callback a callback which will be called on availability
   * @return this monitor for chaining
   */
  @Nonnull
  ServerMonitor onAvailable(final @Nullable Runnable callback);

  /**
   * Provides a callback which will be called when the server is unavailable to probe for
   * availability. If a previous callback was already set, the new one will overwrite it, such that
   * there is only one callback at any time. Cannot be null as there must always be a probe
   * callback, otherwise the monitor would not be able to determine if the server is now available.
   *
   * <p>NOTE: currently the interface assumes the callback will somehow be detected by the
   * implementation, which is a very generous assumption - as mentioned, this interface is mostly
   * built to simplify testing, and as such its usage isn't all that decoupled from the
   * implementation. Happy to accept suggestions here.
   *
   * @param callback a callback which will be called to probe for availability
   * @return this monitor for chaining
   */
  @Nonnull
  ServerMonitor withProbe(final Runnable callback);
}
