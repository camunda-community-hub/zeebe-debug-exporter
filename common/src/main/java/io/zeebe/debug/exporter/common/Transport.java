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

import java.util.Arrays;
import java.util.Objects;
import javax.annotation.Nonnull;
import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

@API(status = Status.EXPERIMENTAL)
public enum Transport {
  INET,
  UNIX,
  IPC;

  @Nonnull
  public static Transport of(final @Nonnull String value) {
    try {
      return valueOf(Objects.requireNonNull(value).toUpperCase());
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(
          String.format(
              "Expected transport to be one of %s, but was '%s'", Arrays.toString(values()), value),
          e);
    }
  }
}
