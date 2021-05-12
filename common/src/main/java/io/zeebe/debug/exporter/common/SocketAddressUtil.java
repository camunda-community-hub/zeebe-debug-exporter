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
package io.zeebe.debug.exporter.common;

import io.grpc.inprocess.InProcessSocketAddress;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.util.NetUtil;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

@API(status = Status.EXPERIMENTAL)
@ParametersAreNonnullByDefault
public final class SocketAddressUtil {
  private SocketAddressUtil() {}

  @Nonnull
  public static SocketAddress convert(final String target, final Transport transport) {
    Objects.requireNonNull(target);
    Objects.requireNonNull(transport);

    switch (transport) {
      case UNIX:
        return new DomainSocketAddress(target);
      case IPC:
        return new InProcessSocketAddress(target);
      case INET:
      default:
        final URI uri = URI.create(target);
        return new InetSocketAddress(uri.getHost(), uri.getPort());
    }
  }

  @Nonnull
  public static String toString(final SocketAddress address) {
    Objects.requireNonNull(address);

    if (address instanceof InetSocketAddress) {
      return NetUtil.toSocketAddressString((InetSocketAddress) address);
    }

    return address.toString();
  }
}
