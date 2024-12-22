package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.github.trex_paxos.Pickle.readBallotNumber;

/// The PaXE protocol is designed to be compatible with QUIC or even raw UDP.
///
/// This class pickles and unpickles TrexMessages with a standard from/to/type header.
/// In the case of a DirectMessage, the to field is used to specify the destination node.
/// In the case of a BroadcastMessage, the to field is set to 0.
/// The purpose of the fixed size header is for rapid multiplexing and demultiplexing of messages.
public class PicklePAXE {

  public static void pickle(TrexMessage msg, DataOutputStream out) {
    try {
      writeHeader(msg, out);
      writeMessageBody(msg, out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  ///  This must match the lookup table in the unpickle method [PicklePAXE#unpickle(DataInputStream)unpickle]
  public static byte toByte(TrexMessage msg) {
    return switch (msg) {
      case Prepare _ -> 1;
      case PrepareResponse _ -> 2;
      case Accept _ -> 3;
      case AcceptResponse _ -> 4;
      case Fixed _ -> 5;
      case Catchup _ -> 6;
      case CatchupResponse _ -> 7;
    };
  }

  private static void writeHeader(TrexMessage msg, DataOutputStream out) throws IOException {
    final var fromNode = msg.from();
    final var toNode = switch (msg) {
      case BroadcastMessage _ -> 0; // Broadcast
      case DirectMessage dm -> dm.to();
    };
    final var type = toByte(msg);
    out.writeByte(fromNode);
    out.writeByte(toNode);
    out.writeByte(type);
  }

  private static void writeMessageBody(TrexMessage msg, DataOutputStream out) throws IOException {
    switch (msg) {
      case Prepare p -> write(p, out);
      case PrepareResponse p -> write(p, out);
      case Accept a -> write(a, out);
      case AcceptResponse a -> write(a, out);
      case Fixed f -> write(f, out);
      case Catchup c -> write(c, out);
      case CatchupResponse c -> write(c, out);
      default -> throw new IllegalArgumentException("Unknown message type: " + msg.getClass());
    }
  }

  public static TrexMessage unpickle(DataInputStream in) {
    try {
      byte fromNode = in.readByte();
      byte toNode = in.readByte();
      byte type = in.readByte();
      return switch (type) {
        case 1 -> readPrepare(in, fromNode);
        case 2 -> readPrepareResponse(in, fromNode, toNode);
        case 3 -> readAccept(in, fromNode);
        case 4 -> readAcceptResponse(in, fromNode, toNode);
        case 5 -> readFixed(in, fromNode);
        case 6 -> readCatchup(in, fromNode, toNode);
        case 7 -> readCatchupResponse(in, fromNode, toNode);
        default -> throw new IllegalStateException("Unknown type: " + type);
      };
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static void write(PrepareResponse m, DataOutputStream dos) throws IOException {
    write(m.vote(), dos);
    dos.writeLong(m.highestAcceptedIndex());
    dos.writeBoolean(m.journaledAccept().isPresent());
    if (m.journaledAccept().isPresent()) {
      writeInner(m.journaledAccept().get(), dos);
    }
  }

  public static PrepareResponse readPrepareResponse(DataInputStream dis, byte from, byte to) throws IOException {
    final var vote = readVotePrepare(dis, from, to);
    long highestFixedIndex = dis.readLong();
    Optional<Accept> highestUnfixed = dis.readBoolean() ? Optional.of(readAcceptInner(dis)) : Optional.empty();
    return new PrepareResponse(from, to, vote, highestUnfixed, highestFixedIndex);
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
    dos.writeLong(m.highestFixedIndex());
  }

  public static AcceptResponse readAcceptResponse(DataInputStream dis, byte from, byte to) throws IOException {
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

  public static PrepareResponse.Vote readVotePrepare(DataInputStream dis, byte from, byte to) throws IOException {
    long logIndex = dis.readLong();
    boolean vote = dis.readBoolean();
    BallotNumber number = readBallotNumber(dis);
    return new PrepareResponse.Vote(from, to, logIndex, vote, number);
  }

  public static void write(PrepareResponse.Vote m, DataOutputStream dos) throws IOException {
    dos.writeLong(m.logIndex());
    dos.writeBoolean(m.vote());
    Pickle.write(m.number(), dos);
  }

  public static void write(Accept m, DataOutputStream dataStream) throws IOException {
    dataStream.writeLong(m.slot());
    Pickle.write(m.number(), dataStream);
    write(m.command(), dataStream);
  }

  public static void writeInner(Accept m, DataOutputStream dataStream) throws IOException {
    dataStream.writeByte(m.from());
    dataStream.writeLong(m.slot());
    Pickle.write(m.number(), dataStream);
    write(m.command(), dataStream);
  }

  public static Accept readAccept(DataInputStream dataInputStream, byte from) throws IOException {
    final long logIndex = dataInputStream.readLong();
    final BallotNumber number = readBallotNumber(dataInputStream);
    final var command = readCommand(dataInputStream);
    return new Accept(from, logIndex, number, command);
  }

  public static Accept readAcceptInner(DataInputStream dataInputStream) throws IOException {
    final byte from = dataInputStream.readByte();
    final long logIndex = dataInputStream.readLong();
    final BallotNumber number = readBallotNumber(dataInputStream);
    final var command = readCommand(dataInputStream);
    return new Accept(from, logIndex, number, command);
  }

  public static void write(Catchup m, DataOutputStream dos) throws IOException {
    dos.writeLong(m.highestFixedIndex());
    Pickle.write(m.highestPromised(), dos);
  }

  public static Catchup readCatchup(DataInputStream dis, byte from, byte to) throws IOException {
    final var highestFixedIndex = dis.readLong();
    final var highestPromised = readBallotNumber(dis);
    return new Catchup(from, to, highestFixedIndex, highestPromised);
  }

  public static CatchupResponse readCatchupResponse(DataInputStream dis, byte from, byte to) throws IOException {
    final int catchupSize = dis.readInt();
    List<Accept> catchup = new ArrayList<>();
    IntStream.range(0, catchupSize).forEach(_ -> {
      try {
        catchup.add(readAcceptInner(dis));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
    return new CatchupResponse(from, to, catchup);
  }

  public static void write(CatchupResponse m, DataOutputStream dos) throws IOException {
    dos.writeInt(m.accepts().size());
    for (Accept accept : m.accepts()) {
      writeInner(accept, dos);
    }
  }

  public static void write(Fixed m, DataOutputStream dos) throws IOException {
    dos.writeLong(m.slotTerm().logIndex());
    Pickle.write(m.slotTerm().number(), dos);
  }

  public static Fixed readFixed(DataInputStream dis, byte from)
      throws IOException {
    final var fixedLogIndex = dis.readLong();
    final var number = readBallotNumber(dis);
    return new Fixed(from, fixedLogIndex, number);
  }

  public static Prepare readPrepare(DataInputStream dataInputStream, byte from) throws IOException {
    final long logIndex = dataInputStream.readLong();
    final BallotNumber number = readBallotNumber(dataInputStream);
    return new Prepare(from, logIndex, number);
  }

  public static void write(Prepare p, DataOutputStream dataOutputStream) throws IOException {
    dataOutputStream.writeLong(p.slot());
    Pickle.write(p.number(), dataOutputStream);
  }
}
