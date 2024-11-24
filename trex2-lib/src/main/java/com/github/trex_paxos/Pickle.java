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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
/// Pickle is a utility class for serializing and deserializing record types.
/// Java serialization is famously broken but the Java Platform team are working on it.
/// This class does things the boilerplate way.
public class Pickle {

  public static byte[] writeProgress(Progress progress) throws IOException {
    try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
         DataOutputStream dos = new DataOutputStream(byteArrayOutputStream)) {
      dos.writeByte(progress.nodeIdentifier());
      write(progress.highestPromised(), dos);
      dos.writeLong(progress.highestCommittedIndex());
      return byteArrayOutputStream.toByteArray();
    }
  }

  public static Progress readProgress(byte[] pickled) throws IOException {
    try (ByteArrayInputStream bis = new ByteArrayInputStream(pickled);
         DataInputStream dis = new DataInputStream(bis)) {
      return new Progress(dis.readByte(), readBallotNumber(dis), dis.readLong());
    }
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
        case MessageType.Commit -> readCommit(dis);
        case MessageType.Catchup -> readCatchup(dis);
        case MessageType.CatchupResponse -> readCatchupResponse(dis);
      };
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }


  public static void write(PrepareResponse m, DataOutputStream dos) throws IOException {
    Pickle.write(m.vote(), dos);
    dos.writeLong(m.highestAcceptedIndex());
    dos.writeBoolean(m.highestUncommitted().isPresent());
    if (m.highestUncommitted().isPresent()) {
      Pickle.write(m.highestUncommitted().get(), dos);
    }
  }

  public static PrepareResponse readPrepareResponse(DataInputStream dis) throws IOException {
    Vote vote = Pickle.readVote(dis);
    long highestCommittedIndex = dis.readLong();
    Optional<Accept> highestUncommitted = dis.readBoolean() ? Optional.of(Pickle.readAccept(dis)) : Optional.empty();
    return new PrepareResponse(vote, highestCommittedIndex, highestUncommitted);
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
        case Commit c -> write(c, dos);
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
    write(m.vote(), dos);
    Progress m1 = m.progress();
    dos.writeByte(m1.nodeIdentifier());
    write(m1.highestPromised(), dos);
    dos.writeLong(m1.highestCommittedIndex());
  }

  public static AcceptResponse readAcceptResponse(DataInputStream dis) throws IOException {
    Vote vote = readVote(dis);
    Progress progress = new Progress(dis.readByte(), readBallotNumber(dis), dis.readLong());
    return new AcceptResponse(vote, progress);
  }

  public static Vote readVote(DataInputStream dis) throws IOException {
    byte from = dis.readByte();
    byte to = dis.readByte();
    long logIndex = dis.readLong();
    boolean vote = dis.readBoolean();
    BallotNumber number = readBallotNumber(dis);
    return new Vote(from, to, logIndex, vote, number);
  }

  public static void write(Vote m, DataOutputStream dos) throws IOException {
    dos.writeByte(m.from());
    dos.writeByte(m.to());
    dos.writeLong(m.logIndex());
    dos.writeBoolean(m.vote());
    write(m.number(), dos);
  }

  public static void write(Accept m, DataOutputStream dataStream) throws IOException {
    dataStream.writeByte(m.from());
    dataStream.writeLong(m.logIndex());
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

  public static void write(BallotNumber n, DataOutputStream daos) throws IOException {
    daos.writeInt(n.counter());
    daos.writeByte(n.nodeIdentifier());
  }

  public static BallotNumber readBallotNumber(DataInputStream dataInputStream) throws IOException {
    return new BallotNumber(dataInputStream.readInt(), dataInputStream.readByte());
  }

  public static void write(Catchup m, DataOutputStream dos) throws IOException {
    dos.writeByte(m.from());
    dos.writeByte(m.to());
    dos.writeLong(m.highestCommitedIndex());
    write(m.highestPromised(), dos);
  }

  public static Catchup readCatchup(DataInputStream dis) throws IOException {
    final var from = dis.readByte();
    final var to = dis.readByte();
    final var highestCommitedIndex = dis.readLong();
    final var highestPromised = readBallotNumber(dis);
    return new Catchup(from, to, highestCommitedIndex, highestPromised);
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
    final var commit = readCommit(dis);
    return new CatchupResponse(from, to, catchup, commit);
  }

  public static void write(CatchupResponse m, DataOutputStream dos) throws IOException {
    dos.writeByte(m.from());
    dos.writeByte(m.to());
    dos.writeInt(m.catchup().size());
    for (Accept accept : m.catchup()) {
      write(accept, dos);
    }
    write(m.commit(), dos);
  }

  public static void write(Commit m, DataOutputStream dos) throws IOException {
    dos.writeByte(m.from());
    dos.writeLong(m.committedLogIndex());
    write(m.number(), dos);
  }

  public static Commit readCommit(DataInputStream dis)
      throws IOException {
    final var from = dis.readByte();
    final var committedLogIndex = dis.readLong();
    final var number = readBallotNumber(dis);
    return new Commit(from, number, committedLogIndex);
  }

  public static Prepare readPrepare(DataInputStream dataInputStream) throws IOException {
    final byte from = dataInputStream.readByte();
    final long logIndex = dataInputStream.readLong();
    final BallotNumber number = readBallotNumber(dataInputStream);
    return new Prepare(from, logIndex, number);
  }

  public static void write(Prepare p, DataOutputStream dataOutputStream) throws IOException {
    dataOutputStream.writeByte(p.from());
    dataOutputStream.writeLong(p.logIndex());
    write(p.number(), dataOutputStream);
  }
}
