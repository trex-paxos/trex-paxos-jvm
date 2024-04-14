package com.github.trex_paxos;

import java.io.*;

/**
 * Pickle is a utility class for serializing and deserializing `TrexMessage`s or the `Progress` as binary data.
 * For the algorithm to work correctly only the `Progress` and `Accept` messages need to be durable on disk.
 * This means that Trex itself only uses the Pickle class to serialize and deserialize the `JournalRecord` interface.
 * You may choose to use the Pickle class to serialize and deserialize other messages as well. Alternatively your
 * application can use a different serialization mechanism as your wire format such as JSON.
 */
public class Pickle {
  // TODO consider moving the DataInputStream and DataOutputStream usage into the Pickle class.
  public static TrexMessage readTrexMessage(byte[] bytes) {
    ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
    DataInputStream dis = new DataInputStream(bis);
    try {
      MessageType messageType = MessageType.fromMessageId(dis.readByte());
      return switch (messageType) {
        case MessageType.Prepare -> Prepare.readFrom(dis);
        case MessageType.PrepareResponse -> PrepareResponse.readFrom(dis);
        case MessageType.Accept -> Accept.readFrom(dis);
        case MessageType.AcceptResponse -> AcceptResponse.readFrom(dis);
        case MessageType.Commit -> Commit.readFrom(dis);
        case MessageType.Catchup -> Catchup.readFrom(dis);
        case MessageType.CatchupResponse -> CatchupResponse.readFrom(dis);
      };
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static byte[] write(TrexMessage message) throws IOException {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(byteArrayOutputStream);
    dos.writeByte(MessageType.fromPaxosMessage(message).id());
    message.writeTo(dos);
    dos.close();
    return byteArrayOutputStream.toByteArray();
  }

  public static byte[] write(Progress progress) throws IOException {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(byteArrayOutputStream);
    progress.writeTo(dos);
    dos.close();
    return byteArrayOutputStream.toByteArray();
  }

  public static Object readProgress(byte[] pickled) {
    ByteArrayInputStream bis = new ByteArrayInputStream(pickled);
    DataInputStream dis = new DataInputStream(bis);
    try {
      return Progress.readFrom(dis);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
