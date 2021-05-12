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

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors.CheckedForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusException;
import java.util.Objects;
import java.util.Set;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.WillNotClose;
import net.jodah.failsafe.CircuitBreaker;
import net.jodah.failsafe.CircuitBreakerOpenException;

/**
 * A client call interceptor which records failures/successes to the given circuit breaker.
 * Otherwise acts as a forwarding interceptor.
 */
@ParametersAreNonnullByDefault
final class CircuitBreakerInterceptor implements ClientInterceptor {

  /** The set of status codes which should be considered as errors indicating unavailability. */
  private static final Set<Code> ERRORS =
      Set.of(
          Status.Code.DATA_LOSS,
          Status.Code.UNKNOWN,
          Status.Code.INTERNAL,
          Status.Code.UNIMPLEMENTED,
          Status.Code.UNAVAILABLE,
          Status.Code.DEADLINE_EXCEEDED);

  private final CircuitBreaker<Status> breaker;

  CircuitBreakerInterceptor(final @WillNotClose CircuitBreaker<Status> breaker) {
    this.breaker =
        Objects.requireNonNull(breaker).handleResultIf(status -> ERRORS.contains(status.getCode()));
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      final MethodDescriptor<ReqT, RespT> method,
      final CallOptions callOptions,
      final Channel next) {
    return new Call<>(next.newCall(method, callOptions));
  }

  private class Call<ReqT, RespT> extends CheckedForwardingClientCall<ReqT, RespT> {

    public Call(final ClientCall<ReqT, RespT> delegate) {
      super(delegate);
    }

    @Override
    protected void checkedStart(final Listener<RespT> responseListener, final Metadata headers)
        throws Exception {
      if (breaker.isOpen()) {
        final CircuitBreakerOpenException breakerException =
            new CircuitBreakerOpenException(breaker);
        throw new StatusException(
            Status.UNAVAILABLE
                .withCause(breakerException)
                .withDescription(breakerException.getMessage()));
      }

      delegate().start(new CircuitBreakerInterceptor.Listener<>(responseListener), headers);
    }

    @Override
    public void sendMessage(final ReqT message) {
      if (breaker.isOpen()) {
        final CircuitBreakerOpenException breakerException =
            new CircuitBreakerOpenException(breaker);
        final StatusException error =
            new StatusException(
                Status.UNAVAILABLE
                    .withCause(breakerException)
                    .withDescription(breakerException.getMessage()));
        cancel(breakerException.getMessage(), error);
        return;
      }

      breaker.preExecute();
      super.sendMessage(message);
    }
  }

  private class Listener<RespT> extends SimpleForwardingClientCallListener<RespT> {

    public Listener(final ClientCall.Listener<RespT> delegate) {
      super(delegate);
    }

    @Override
    public void onMessage(final RespT message) {
      breaker.recordSuccess();
      super.onMessage(message);
    }

    @Override
    public void onClose(final Status status, final Metadata trailers) {
      breaker.recordResult(status);
      super.onClose(status, trailers);
    }
  }
}
