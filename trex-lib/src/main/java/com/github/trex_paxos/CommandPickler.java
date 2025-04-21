// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import com.github.trex_paxos.msg.Accept;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.UUID;

/// CommandPickler is a utility class for serializing and deserializing the record types that the [Journal] uses.
/// Java serialization is famously broken but the Java Platform team are working on it.
/// This class does things the boilerplate way.
public class CommandPickler {

  public static Pickler<Command> instance = new Pickler<>() {

    @Override
    public void serialize(Command cmd, ByteBuffer buffer) {
      try {
        byte[] bytes = write(cmd);
        buffer.put(bytes);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public Command deserialize(ByteBuffer buffer) {
      try {
        // Get remaining bytes from the buffer instead of using array()
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return (Command) CommandPickler.readCommand(bytes);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    
    @Override
    public int sizeOf(Command cmd) {
      if (cmd == null) return 0;
      
      // Calculate size based on:
      // - 4 bytes for operation bytes length
      // - operation bytes length
      // - 8 bytes for UUID most significant bits
      // - 8 bytes for UUID least significant bits
      // - 1 byte for flavour
      return 4 + (cmd.operationBytes() != null ? cmd.operationBytes().length : 0) + 8 + 8 + 1;
    }
  };

  public static byte[] writeProgress(Progress progress) throws IOException {
    try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
         DataOutputStream dos = new DataOutputStream(byteArrayOutputStream)) {
      write(progress, dos);
      return byteArrayOutputStream.toByteArray();
    }
  }

  public static void write(Progress progress, DataOutputStream dos) throws IOException {
    dos.writeShort(progress.nodeIdentifier());
    write(progress.highestPromised(), dos);
    dos.writeLong(progress.highestFixedIndex());
  }

  public static Progress readProgress(byte[] pickled) throws IOException {
    try (ByteArrayInputStream bis = new ByteArrayInputStream(pickled);
         DataInputStream dis = new DataInputStream(bis)) {
      return readProgress(dis);
    }
  }

  private static Progress readProgress(DataInputStream dis) throws IOException {
    return new Progress(dis.readShort(), readBallotNumber(dis), dis.readLong());
  }

  public static byte[] write(BallotNumber n) throws IOException {
    try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
         DataOutputStream dos = new DataOutputStream(byteArrayOutputStream)) {
      write(n, dos);
      return byteArrayOutputStream.toByteArray();
    }
  }

  public static void write(BallotNumber n, DataOutputStream dataOutputStream) throws IOException {
    dataOutputStream.writeShort(n.era());
    dataOutputStream.writeInt(n.counter());
    dataOutputStream.writeShort(n.nodeIdentifier());
  }

  public static BallotNumber readBallotNumber(byte[] pickled) throws IOException {
    try (ByteArrayInputStream bis = new ByteArrayInputStream(pickled);
         DataInputStream dis = new DataInputStream(bis)) {
      return readBallotNumber(dis);
    }
  }

  public static BallotNumber readBallotNumber(DataInputStream dataInputStream) throws IOException {
    return new BallotNumber(dataInputStream.readShort(), dataInputStream.readInt(), dataInputStream.readShort());
  }

  public static void write(Accept m, DataOutputStream dataStream) throws IOException {
    dataStream.writeShort(m.from());
    dataStream.writeLong(m.slot());
    write(m.number(), dataStream);
    write(m.command(), dataStream);
  }

  public static Accept readAccept(DataInputStream dataInputStream) throws IOException {
    final short from = dataInputStream.readShort();
    final long logIndex = dataInputStream.readLong();
    final BallotNumber number = readBallotNumber(dataInputStream);
    final var command = readCommand(dataInputStream);
    return new Accept(from, logIndex, number, command);
  }

  public static byte[] write(AbstractCommand c) throws IOException {
    try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
         DataOutputStream dos = new DataOutputStream(byteArrayOutputStream)) {
      write(c, dos);
      return byteArrayOutputStream.toByteArray();
    }
  }

  public static void write(AbstractCommand c, DataOutputStream dataStream) throws IOException {
    switch (c) {
      case NoOperation _ ->
        // Here we use zero bytes as a sentinel to represent the NOOP command.
          dataStream.writeInt(0);
      case Command(var uuid, var operationBytes, var flavour) -> {
        dataStream.writeInt(operationBytes.length);
        dataStream.write(operationBytes);
        dataStream.writeLong(uuid.getMostSignificantBits());
        dataStream.writeLong(uuid.getLeastSignificantBits());
        dataStream.writeByte(flavour);
      }
    }
  }

  public static AbstractCommand readCommand(byte[] pickled) throws IOException {
    try (ByteArrayInputStream bis = new ByteArrayInputStream(pickled);
         DataInputStream dis = new DataInputStream(bis)) {
      return readCommand(dis);
    }
  }

  public static AbstractCommand readCommand(DataInputStream dataInputStream) throws IOException {
    final var byteLength = dataInputStream.readInt();
    if (byteLength == 0) {
      return NoOperation.NOOP;
    }
    byte[] bytes = new byte[byteLength];
    dataInputStream.readFully(bytes);
    final var msb = dataInputStream.readLong();
    final var lsb = dataInputStream.readLong();
    byte flavour = dataInputStream.readByte();
    return new Command(new UUID(msb, lsb), bytes, flavour);
  }

  public static byte[] write(Accept a) throws IOException {
    try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
         DataOutputStream dos = new DataOutputStream(byteArrayOutputStream)) {
      write(a, dos);
      return byteArrayOutputStream.toByteArray();
    }
  }

  public static Accept readAccept(byte[] pickled) throws IOException {
    try (ByteArrayInputStream bis = new ByteArrayInputStream(pickled);
         DataInputStream dis = new DataInputStream(bis)) {
      return readAccept(dis);
    }
  }
}

