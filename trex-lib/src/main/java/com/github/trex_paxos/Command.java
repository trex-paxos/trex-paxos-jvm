/*
 * Copyright 2024 Simon Massey
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
package com.github.trex_paxos;

import java.util.UUID;
import java.util.zip.CRC32;

/// A client command which is the id we are trying to fix. As this library is neutral
/// to the application, the command is completely opaque to the library. The
/// application is responsible for encoding and decoding the results from and to byte array.
///
/// @param flavour        A byte that can be used to distinguish between different types of commands. This allows us to multiplex
///                                             different types of commands within the same Paxos cluster. Negative numbers are
///                       reserved for system administration commands.
/// @param uuid           The client message unique identifier used to respond to the client who issued the command.
///                                             This just be universally unique across all clients and all time.
/// @param operationBytes The application specific binary encoding of the application command to apply
///                                             to the application state machine.
public record Command(
    byte flavour, UUID uuid,
    byte[] operationBytes
) implements AbstractCommand {

  public Command {
    if (uuid == null) {
      throw new IllegalArgumentException("clientMsgUuid cannot be null");
    }
    if (operationBytes == null) {
      throw new IllegalArgumentException("operationBytes cannot be null");
    }
    if (operationBytes.length == 0) {
      throw new IllegalArgumentException(
          "operationBytes length can not be zero as that is reserved to signal a NoOperation recovery command.");
    }
  }

  /// Creates a command with the specified operation bytes and flavor.
  /// The UUID will be automatically generated to be time ordered using [UUIDGenerator#generateUUID()].
  /// @param bytes The application-specific binary data for this command
  /// @param flavour A byte that distinguishes different types of commands. Negative numbers are reserved for system administration commands.
  public Command(byte[] bytes, byte flavour) {
    this(flavour, UUIDGenerator.generateUUID(), bytes);
  }

  /// Creates a command with the specified operation bytes.
  /// The UUID will be automatically generated and flavor will be set to 0
  public Command(byte[] bytes) {
    this(bytes, (byte) 0);
  }

  @Override
  public boolean equals(Object arg0) {
    if (this == arg0) {
      return true;
    }
    if (arg0 == null) {
      return false;
    }
    if (getClass() != arg0.getClass()) {
      return false;
    }
    Command other = (Command) arg0;
    if (uuid == null) {
      if (other.uuid != null) {
        return false;
      }
    } else if (!uuid.equals(other.uuid())) {
      return false;
    }
    return java.util.Arrays.equals(operationBytes, other.operationBytes);
  }

  public String toString() {
    CRC32 crc32 = new CRC32();
    crc32.update(operationBytes);

    return String.format("Command[flavour=%d, clientMsgUuid='%s', operationBytes=byte[%d]:CRC32=%d]",
        flavour,
        uuid.toString(),
        operationBytes.length,
        crc32.getValue());
  }
}
