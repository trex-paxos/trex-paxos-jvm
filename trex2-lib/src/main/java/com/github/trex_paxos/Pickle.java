package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/// Pickle is a utility class for serializing and deserializing record types.
/// Java serialization is famously broken but the Java Platform team are working on it.
/// This class does things the boilerplate way.
public class Pickle {

  public static byte[] writeProgress(Progress progress) throws IOException {
    try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
         DataOutputStream dos = new DataOutputStream(byteArrayOutputStream)) {
      Progress.writeTo(progress, dos);
      return byteArrayOutputStream.toByteArray();
    }
  }

  public static Progress readProgress(byte[] pickled) throws IOException {
    try (ByteArrayInputStream bis = new ByteArrayInputStream(pickled);
         DataInputStream dis = new DataInputStream(bis)) {
      return Progress.readFrom(dis);
    }
  }

  public static TrexMessage readMessage(byte[] bytes) {
    ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
    DataInputStream dis = new DataInputStream(bis);
    try {
      MessageType messageType = MessageType.fromMessageId(dis.readByte());
      return switch (messageType) {
        case MessageType.Prepare -> readPrepare(dis);
        case MessageType.PrepareResponse -> PrepareResponse.readFrom(dis);
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

  public static byte[] writeMessage(TrexMessage message) throws IOException {
    try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
         DataOutputStream dos = new DataOutputStream(byteArrayOutputStream)) {

      dos.writeByte(MessageType.fromPaxosMessage(message).id());

      switch (message) {
        case Prepare p -> write(p, dos);
        case PrepareResponse p -> PrepareResponse.writeTo(p, dos);
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

  public static long uncheckedReadLong(DataInputStream dis) {
    try {
      return dis.readLong();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void uncheckedWriteLong(DataOutputStream dos, long i) {
    try {
      dos.writeLong(i);
    } catch (IOException e) {
      throw new RuntimeException(e);
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
    Progress.writeTo(m.progress(), dos);
  }

  public static AcceptResponse readAcceptResponse(DataInputStream dis) throws IOException {
    Vote vote = readVote(dis);
    Progress progress = Progress.readFrom(dis);
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

  public static Catchup readCatchup(DataInputStream dis) throws IOException {
    final var from = dis.readByte();
    final var to = dis.readByte();
    final var length = dis.readShort();
    final long[] slotGaps = IntStream.range(0, length)
        .mapToLong(_ -> uncheckedReadLong(dis))
        .toArray();
    final var highestCommitedIndex = dis.readLong();
    return new Catchup(from, to, slotGaps, highestCommitedIndex);
  }

  public static void write(Catchup m, DataOutputStream dos) throws IOException {
    dos.writeByte(m.from());
    dos.writeByte(m.to());
    final var length = Math.min(m.slotGaps().length, Short.MAX_VALUE);
    dos.writeShort(length);
    IntStream.range(0, length).forEach(i -> uncheckedWriteLong(dos, m.slotGaps()[i]));
    dos.writeLong(m.highestCommitedIndex());
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
