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

import com.github.trex_paxos.msg.*;

import java.io.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
/// Pickle is a utility class for serializing and deserializing record types.
/// Java serialization is famously broken but the Java Platform team are working on it.
/// This class does things the boilerplate way.
// TODO: Pass in the stream so that can act on a list of messages.
public class Pickle {

  public static byte[] writeProgress(Progress progress) throws IOException {
    try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
         DataOutputStream dos = new DataOutputStream(byteArrayOutputStream)) {
      write(progress, dos);
      return byteArrayOutputStream.toByteArray();
    }
  }

  public static void write(Progress progress, DataOutputStream dos) throws IOException {
    dos.writeByte(progress.nodeIdentifier());
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
    return new Progress(dis.readByte(), readBallotNumber(dis), dis.readLong());
  }

  public static TrexMessage readMessage(byte[] bytes) {
    ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
    DataInputStream dis = new DataInputStream(bis);
    try {
      MessageType messageType = MessageType.fromMessageId(dis.readByte());
      return switch (messageType) {
        case MessageType.Prepare -> readPrepare(dis);
        case MessageType.PrepareResponse -> readPrepareResponse(dis);
        case MessageType.Accept -> readAccept(dis);
        case MessageType.AcceptResponse -> readAcceptResponse(dis);
        case MessageType.Fixed -> readFixed(dis);
        case MessageType.Catchup -> readCatchup(dis);
        case MessageType.CatchupResponse -> readCatchupResponse(dis);
      };
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }


  public static void write(PrepareResponse m, DataOutputStream dos) throws IOException {
    dos.writeByte(m.from());
    dos.writeByte(m.to());
    Pickle.write(m.vote(), dos);
    dos.writeLong(m.highestAcceptedIndex());
    dos.writeBoolean(m.journaledAccept().isPresent());
    if (m.journaledAccept().isPresent()) {
      Pickle.write(m.journaledAccept().get(), dos);
    }
  }

  public static PrepareResponse readPrepareResponse(DataInputStream dis) throws IOException {
    final var from = dis.readByte();
    final var to = dis.readByte();
    final var vote = Pickle.readVotePrepare(dis);
    long highestFixedIndex = dis.readLong();
    Optional<Accept> highestUnfixed = dis.readBoolean() ? Optional.of(Pickle.readAccept(dis)) : Optional.empty();
    return new PrepareResponse(from, to, vote, highestUnfixed, highestFixedIndex);
  }


  public static byte[] writeMessage(TrexMessage message) throws IOException {
    try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
         DataOutputStream dos = new DataOutputStream(byteArrayOutputStream)) {

      dos.writeByte(MessageType.fromPaxosMessage(message).id());

      switch (message) {
        case Prepare p -> write(p, dos);
        case PrepareResponse p -> write(p, dos);
        case Accept a -> write(a, dos);
        case AcceptResponse a -> write(a, dos);
        case Fixed c -> write(c, dos);
        case Catchup c -> write(c, dos);
        case CatchupResponse c -> write(c, dos);
      }

      dos.flush();
      return byteArrayOutputStream.toByteArray();
    }
  }

  public static void write(AbstractCommand c, DataOutputStream dataStream) throws IOException {
    switch (c) {
      case NoOperation _ ->
        // Here we use zero bytes as a sentinel to represent the NOOP command.
          dataStream.writeInt(0);
      case Command command -> {
        dataStream.writeInt(command.operationBytes().length);
        dataStream.write(command.operationBytes());
        dataStream.writeUTF(command.clientMsgUuid());
      }
    }
  }

  public static AbstractCommand readCommand(DataInputStream dataInputStream) throws IOException {
    final var byteLength = dataInputStream.readInt();
    if (byteLength == 0) {
      return NoOperation.NOOP;
    }
    byte[] bytes = new byte[byteLength];
    dataInputStream.readFully(bytes);
    return new Command(dataInputStream.readUTF(), bytes);
  }

  public static void write(AcceptResponse m, DataOutputStream dos) throws IOException {
    dos.writeByte(m.from());
    dos.writeByte(m.to());
    write(m.vote(), dos);
    dos.writeLong(m.highestFixedIndex());
  }

  public static AcceptResponse readAcceptResponse(DataInputStream dis) throws IOException {
    final var from = dis.readByte();
    final var to = dis.readByte();
    final var vote = readVoteAccept(dis);
    final long highestFixedIndex = dis.readLong();
    return new AcceptResponse(from, to, vote, highestFixedIndex);
  }

  public static AcceptResponse.Vote readVoteAccept(DataInputStream dis) throws IOException {
    byte from = dis.readByte();
    byte to = dis.readByte();
    long logIndex = dis.readLong();
    boolean vote = dis.readBoolean();
    return new AcceptResponse.Vote(from, to, logIndex, vote);
  }

  public static void write(AcceptResponse.Vote m, DataOutputStream dos) throws IOException {
    dos.writeByte(m.from());
    dos.writeByte(m.to());
    dos.writeLong(m.logIndex());
    dos.writeBoolean(m.vote());
  }

  public static PrepareResponse.Vote readVotePrepare(DataInputStream dis) throws IOException {
    byte from = dis.readByte();
    byte to = dis.readByte();
    long logIndex = dis.readLong();
    boolean vote = dis.readBoolean();
    BallotNumber number = readBallotNumber(dis);
    return new PrepareResponse.Vote(from, to, logIndex, vote, number);
  }

  public static void write(PrepareResponse.Vote m, DataOutputStream dos) throws IOException {
    dos.writeByte(m.from());
    dos.writeByte(m.to());
    dos.writeLong(m.logIndex());
    dos.writeBoolean(m.vote());
    write(m.number(), dos);
  }

  public static void write(Accept m, DataOutputStream dataStream) throws IOException {
    dataStream.writeByte(m.from());
    dataStream.writeLong(m.slot());
    write(m.number(), dataStream);
    write(m.command(), dataStream);
  }

  public static Accept readAccept(DataInputStream dataInputStream) throws IOException {
    final byte from = dataInputStream.readByte();
    final long logIndex = dataInputStream.readLong();
    final BallotNumber number = readBallotNumber(dataInputStream);
    final var command = readCommand(dataInputStream);
    return new Accept(from, logIndex, number, command);
  }

  public static void write(BallotNumber n, DataOutputStream dataOutputStream) throws IOException {
    dataOutputStream.writeInt(n.counter());
    dataOutputStream.writeByte(n.nodeIdentifier());
  }

  public static BallotNumber readBallotNumber(DataInputStream dataInputStream) throws IOException {
    return new BallotNumber(dataInputStream.readInt(), dataInputStream.readByte());
  }

  public static void write(Catchup m, DataOutputStream dos) throws IOException {
    dos.writeByte(m.from());
    dos.writeByte(m.to());
    dos.writeLong(m.highestFixedIndex());
    write(m.highestPromised(), dos);
  }

  public static Catchup readCatchup(DataInputStream dis) throws IOException {
    final var from = dis.readByte();
    final var to = dis.readByte();
    final var highestFixedIndex = dis.readLong();
    final var highestPromised = readBallotNumber(dis);
    return new Catchup(from, to, highestFixedIndex, highestPromised);
  }

  public static CatchupResponse readCatchupResponse(DataInputStream dis) throws IOException {
    final byte from = dis.readByte();
    final byte to = dis.readByte();
    final int catchupSize = dis.readInt();
    List<Accept> catchup = new ArrayList<>();
    IntStream.range(0, catchupSize).forEach(_ -> {
      try {
        catchup.add(readAccept(dis));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
    return new CatchupResponse(from, to, catchup);
  }

  public static void write(CatchupResponse m, DataOutputStream dos) throws IOException {
    dos.writeByte(m.from());
    dos.writeByte(m.to());
    dos.writeInt(m.accepts().size());
    for (Accept accept : m.accepts()) {
      write(accept, dos);
    }
  }

  public static void write(Fixed m, DataOutputStream dos) throws IOException {
    dos.writeByte(m.from());
    dos.writeLong(m.slotTerm().logIndex());
    write(m.slotTerm().number(), dos);
  }

  public static Fixed readFixed(DataInputStream dis)
      throws IOException {
    final var from = dis.readByte();
    final var fixedLogIndex = dis.readLong();
    final var number = readBallotNumber(dis);
    return new Fixed(from, fixedLogIndex, number);
  }

  public static Prepare readPrepare(DataInputStream dataInputStream) throws IOException {
    final byte from = dataInputStream.readByte();
    final long logIndex = dataInputStream.readLong();
    final BallotNumber number = readBallotNumber(dataInputStream);
    return new Prepare(from, logIndex, number);
  }

  public static void write(Prepare p, DataOutputStream dataOutputStream) throws IOException {
    dataOutputStream.writeByte(p.from());
    dataOutputStream.writeLong(p.slot());
    write(p.number(), dataOutputStream);
  }

  public enum MessageType {
    Prepare(1),
    PrepareResponse(2),
    Accept(3),
    AcceptResponse(4),
    Fixed(5),
    Catchup(6),
    CatchupResponse(7);

    private final byte id;

    MessageType(int id) {
      this.id = (byte) id;
    }

    public Byte id() {
      return id;
    }

    static final Map<Byte, MessageType> ORDINAL_TO_TYPE_MAP = Arrays.stream(values())
        .collect(Collectors.toMap(MessageType::id, Function.identity()));

    public static MessageType fromMessageId(byte id) {
      return ORDINAL_TO_TYPE_MAP.get(id);
    }

    static final Map<Byte, Class<? extends TrexMessage>> ORDINAL_TO_CLASS_MAP = Map.of(
        (byte) 0, Prepare.class,
        (byte) 1, PrepareResponse.class,
        (byte) 2, com.github.trex_paxos.msg.Accept.class,
        (byte) 3, com.github.trex_paxos.msg.AcceptResponse.class,
        (byte) 4, Fixed.class,
        (byte) 5, com.github.trex_paxos.msg.Catchup.class
    );

    /**
     * Host applications may want to use this map to convert ordinal values to message classes for custom deserialization.
     */
    @SuppressWarnings("unused")
    public static Class<? extends TrexMessage> classFromMessageId(byte id) {
      return ORDINAL_TO_CLASS_MAP.get(id);
    }

    static final Map<Class<? extends TrexMessage>, Byte> CLASS_TO_ORDINAL_MAP =
        ORDINAL_TO_CLASS_MAP.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

    /**
     * Host applications may want to use this map to convert message classes to ordinal values for custom serialization.
     */
    @SuppressWarnings("unused")
    public static byte idFromMessageTypeClass(Class<? extends TrexMessage> classType) {
      return CLASS_TO_ORDINAL_MAP.get(classType);
    }

    /**
     * Host applications may want to use this map to convert ordinal values to message types for custom serialization.
     */
    public static MessageType fromPaxosMessage(TrexMessage trexMessage) {
      return switch (trexMessage) {
        case Prepare _ -> Prepare;
        case PrepareResponse _ -> PrepareResponse;
        case Accept _ -> Accept;
        case AcceptResponse _ -> AcceptResponse;
        case Fixed _ -> Fixed;
        case Catchup _ -> Catchup;
        case CatchupResponse _ -> CatchupResponse;
      };
    }
  }

}
