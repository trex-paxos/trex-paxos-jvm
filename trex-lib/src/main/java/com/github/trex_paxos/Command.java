// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import java.util.UUID;
import java.util.zip.CRC32;

/// A [Command] which is the value we are trying to fix. The
/// application is responsible for encoding and decoding real host commands and values from and to byte array.
///
/// @param uuid           The client message unique identifier used to respond to the client who issued the command.
///                                                                   This just be universally unique across all clients and all time.
/// @param operationBytes The application specific binary encoding of the application command to apply
///                                                                   to the application state machine.
/// @param flavour        A byte that can be used to distinguish between different types of commands. This allows us to multiplex
///                                                                   different types of commands within the same Paxos cluster. Negative numbers are
///                                             reserved for system administration commands.
public record Command(
    UUID uuid, byte[] operationBytes, byte flavour
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
    this(UUIDGenerator.generateUUID(), bytes, flavour);
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
