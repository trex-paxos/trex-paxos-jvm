/*
 * Copyright 2024 - 2025 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.trex_paxos;

import com.github.trex_paxos.msg.Accept;

import java.io.*;
import java.util.UUID;

/// Pickle is a utility class for serializing and deserializing the record types that the [Journal] uses.
/// Java serialization is famously broken but the Java Platform team are working on it.
/// This class does things the boilerplate way.
public class Pickle {

  public static Pickler<Command> instance = new Pickler<>() {

    @Override
    public byte[] serialize(Command cmd) {
      try {
        return Pickle.write(cmd);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public Command deserialize(byte[] bytes) {
      try {
        return (Command) Pickle.readCommand(bytes);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
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
