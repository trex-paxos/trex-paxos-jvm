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

import java.util.zip.CRC32;

/// A client command which is the value we are trying to fixe. As this library is neutral
/// to the application, the command is completely opaque to the library. The
/// application is responsible for encoding and decoding the commands from and to byte array.
///
/// @param clientMsgUuid  The client message unique identifier used to respond to the client who issued the command.
///                       This just be universally unique across all clients and all time.
///                       We are able to assign these within the cluster and may use a custom UUID that has a partial time within each node.
/// @param operationBytes The application specific binary encoding of the application command to apply
///                       to the application state machine.
public record Command(String clientMsgUuid, byte[] operationBytes) implements AbstractCommand {

  public Command {
    if (clientMsgUuid == null) {
      throw new IllegalArgumentException("clientMsgUuid cannot be null");
    }
    if (operationBytes == null) {
      throw new IllegalArgumentException("operationBytes cannot be null");
    }
    if (operationBytes.length == 0) {
      throw new IllegalArgumentException("operationBytes length can not be zero as that is reserved to signal a NoOperation recovery command.");
    }
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
      if (clientMsgUuid == null) {
        if (other.clientMsgUuid != null) {
          return false;
        }
      } else if (!clientMsgUuid.equals(other.clientMsgUuid)) {
        return false;
      }
      return java.util.Arrays.equals(operationBytes, other.operationBytes);
    }

  public String toString() {
    CRC32 crc32 = new CRC32();
    crc32.update(operationBytes);

    return String.format("Command[clientMsgUuid='%s', operationBytes=byte[%d]:CRC32=%d]",
        clientMsgUuid,
        operationBytes.length,
        crc32.getValue());
  }
}
