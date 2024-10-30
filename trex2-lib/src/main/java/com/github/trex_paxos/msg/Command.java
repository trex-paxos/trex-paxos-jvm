package com.github.trex_paxos.msg;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * A client command to be executed by the state machine. As this library is neutral to the application, the command is
 * completely opaque to the library. The application is responsible for encoding and decoding the commands.
 *
 * @param clientMsgUuid  the client message UUID used to respond to the client who issued the command. The application is
 *                       responsible for ensuring that this UUID is unique. In the real world, this UUID would be generated.
 *                       In a test environment, the UUID could be a simple counter appended to a trivial client ID string.
 * @param operationBytes the application specific binary encoding of the application command to apply to the application state machine.
 */
public record Command(String clientMsgUuid, byte[] operationBytes) implements AbstractCommand {

    @Override
    public void writeTo(DataOutputStream dataStream) throws IOException {
      dataStream.writeUTF(clientMsgUuid);
      dataStream.writeInt(operationBytes.length);
      dataStream.write(operationBytes);
    }

    public static Command readFrom(DataInputStream dataInputStream) throws IOException {
        return new Command(dataInputStream.readUTF(), readByteArray(dataInputStream));
    }

    private static byte[] readByteArray(DataInputStream dataInputStream) throws IOException {
        byte[] bytes = new byte[dataInputStream.readInt()];
        dataInputStream.readFully(bytes);
        return bytes;
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

  @Override
  public String toString() {
    return "Command[" +
        "clientMsgUuid='" + clientMsgUuid + '\'' +
        ", operationBytes=" + Arrays.toString(operationBytes) +
        ']';
  }
}
