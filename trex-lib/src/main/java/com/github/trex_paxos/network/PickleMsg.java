package com.github.trex_paxos.network;

import com.github.trex_paxos.*;
import com.github.trex_paxos.msg.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

public class PickleMsg implements Pickler<TrexMessage> {
  public static PickleMsg instance = new PickleMsg();

  protected PickleMsg() {
  }

  private static final int HEADER_SIZE = 5; // fromNode(2) + toNode(2) + type(1)
  private static final int BALLOT_NUMBER_SIZE = 8; // era(2) + counter(4) + nodeId(2)

  public static byte[] pickle(TrexMessage msg) {
    int size = HEADER_SIZE + calculateMessageSize(msg);
    ByteBuffer buffer = ByteBuffer.allocate(size);
    writeHeader(msg, buffer);
    writeMessageBody(msg, buffer);
    return buffer.array();
  }

  private static int calculateMessageSize(TrexMessage msg) {
    return switch (msg) {
      case Prepare _ -> Long.BYTES + BALLOT_NUMBER_SIZE;
      case PrepareResponse p -> calculatePrepareResponseSize(p);
      case Accept a -> calculateAcceptSize(a);
      case AcceptResponse _ -> calculateAcceptResponseSize();
      case Fixed _, Catchup _ -> Long.BYTES + BALLOT_NUMBER_SIZE;
      case CatchupResponse c -> calculateCatchupResponseSize(c);
    };
  }

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

  private static void writeHeader(TrexMessage msg, ByteBuffer buffer) {
    final var fromNode = msg.from();
    final var toNode = switch (msg) {
      case BroadcastMessage _ -> 0; // Broadcast
      case DirectMessage dm -> dm.to();
    };
    final var type = toByte(msg);
    buffer.putShort(fromNode);
    buffer.putShort(toNode);
    buffer.put(type);
  }

  public static TrexMessage unpickle(ByteBuffer buffer) {
    short fromNode = buffer.getShort();
    short toNode = buffer.getShort();
    byte type = buffer.get();
    return switch (type) {
      case 1 -> readPrepare(buffer, fromNode);
      case 2 -> readPrepareResponse(buffer, fromNode, toNode);
      case 3 -> readAccept(buffer, fromNode);
      case 4 -> readAcceptResponse(buffer, fromNode, toNode);
      case 5 -> readFixed(buffer, fromNode);
      case 6 -> readCatchup(buffer, fromNode, toNode);
      case 7 -> readCatchupResponse(buffer, fromNode, toNode);
      default -> throw new IllegalArgumentException("Unknown type: " + type);
    };
  }

  private static void writeMessageBody(TrexMessage msg, ByteBuffer buffer) {
    switch (msg) {
      case Prepare p -> write(p, buffer);
      case PrepareResponse p -> write(p, buffer);
      case Accept a -> write(a, buffer);
      case AcceptResponse a -> write(a, buffer);
      case Fixed f -> write(f, buffer);
      case Catchup c -> write(c, buffer);
      case CatchupResponse c -> write(c, buffer);
      default -> throw new IllegalArgumentException("Unknown message type: " + msg.getClass());
    }
  }

  private static int calculatePrepareResponseSize(PrepareResponse m) {
    int size = Long.BYTES + Short.BYTES + 1; // highestAcceptedIndex + era + isPresent flag
    size += calculateVotePrepareSize();
    if (m.journaledAccept().isPresent()) {
      size += calculateAcceptInnerSize(m.journaledAccept().get());
    }
    return size;
  }

  public static void write(PrepareResponse m, ByteBuffer buffer) {
    buffer.putShort(m.era()); // 3
    write(m.vote(), buffer);
    buffer.putLong(m.highestAcceptedIndex());//1234213424
    buffer.put((byte) (m.journaledAccept().isPresent() ? 1 : 0));// true
    m.journaledAccept().ifPresent(accept -> writeInner(accept, buffer));
  }

  public static PrepareResponse readPrepareResponse(ByteBuffer buffer, short from, short to) {
    final var era = buffer.getShort();
    final var vote = readVotePrepare(buffer, from, to);
    long highestFixedIndex = buffer.getLong();
    Optional<Accept> highestUnfixed = buffer.get() == 1 ?
        Optional.of(readAcceptInner(buffer)) : Optional.empty();
    return new PrepareResponse(from, to, era, vote, highestUnfixed, highestFixedIndex);
  }

  public static void write(AbstractCommand c, ByteBuffer buffer) {
    switch (c) {
      case NoOperation _ ->
        // Here we use zero bytes as a sentinel to represent the NOOP command.
          buffer.putInt(0);
      case Command(final UUID uuid, final var operationBytes, final var flavour) -> {
        buffer.putInt(operationBytes.length);
        buffer.put(operationBytes);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        buffer.put(flavour);
      }
    }
  }

  public static AbstractCommand readCommand(ByteBuffer buffer) {
    final var byteLength = buffer.getInt();
    if (byteLength == 0) {
      return NoOperation.NOOP;
    }
    byte[] bytes = new byte[byteLength];
    buffer.get(bytes);
    long mostSigBits = buffer.getLong();
    long leastSigBits = buffer.getLong();
    return new Command(new UUID(mostSigBits, leastSigBits), bytes, buffer.get());
  }

  private static int calculateAcceptResponseSize() {
    return calculateVoteAcceptSize() + Long.BYTES + Short.BYTES; // highestFixedIndex + era
  }

  public static void write(AcceptResponse m, ByteBuffer buffer) {
    buffer.putShort(m.era());
    write(m.vote(), buffer);
    buffer.putLong(m.highestFixedIndex());
  }

  public static AcceptResponse readAcceptResponse(ByteBuffer buffer, short from, short to) {
    final var era = buffer.getShort();
    final var vote = readVoteAccept(buffer);
    final long highestFixedIndex = buffer.getLong();
    return new AcceptResponse(from, to, era, vote, highestFixedIndex);
  }

  public static void write(SlotTerm slotTerm, ByteBuffer buffer){
    buffer.putLong(slotTerm.logIndex());
    write(slotTerm.number(), buffer);
  }

  public static SlotTerm readSlotTerm(ByteBuffer buffer){
    long logIndex = buffer.getLong();
    BallotNumber number = readBallotNumber(buffer);
    return new SlotTerm(logIndex, number);
  }

  public static void write(AcceptResponse.Vote m, ByteBuffer buffer) {
    buffer.putShort(m.from());
    buffer.putShort(m.to());
    write(m.slotTerm(), buffer);
    buffer.put((byte) (m.vote() ? 1 : 0));
  }

  public static AcceptResponse.Vote readVoteAccept(ByteBuffer buffer) {
    short from = buffer.getShort();
    short to = buffer.getShort();
    SlotTerm slotTerm = readSlotTerm(buffer);
    boolean vote = buffer.get() != 0;
    return new AcceptResponse.Vote(from, to, slotTerm, vote);
  }

  private static int calculateVoteAcceptSize() {
    return Short.BYTES + Short.BYTES + Long.BYTES + BALLOT_NUMBER_SIZE + 1; // from + to + slotTerm + vote
  }

  public static void write(PrepareResponse.Vote m, ByteBuffer buffer) {
    write(m.slotTerm(), buffer);
    buffer.put((byte) (m.vote() ? 1 : 0));
  }

  public static PrepareResponse.Vote readVotePrepare(ByteBuffer buffer, short from, short to) {
    SlotTerm slotTerm = readSlotTerm(buffer);
    boolean vote = buffer.get() != 0;
    return new PrepareResponse.Vote(from, to, slotTerm, vote);
  }

  private static int calculateVotePrepareSize() {
    return Long.BYTES + 1 + BALLOT_NUMBER_SIZE; // logIndex + vote + ballotNumber
  }

  public static void write(Accept m, ByteBuffer buffer) {
    buffer.putLong(m.slot());
    write(m.number(), buffer);
    write(m.command(), buffer);
  }

  private static int calculateAcceptSize(Accept m) {
    return Long.BYTES + BALLOT_NUMBER_SIZE + calculateCommandSize(m.command());
  }

  private static int calculateCommandSize(AbstractCommand command) {
    return switch (command) {
      case NoOperation _ -> Integer.BYTES;
      case Command c -> Integer.BYTES + c.operationBytes().length +
          Long.BYTES + Long.BYTES + 1; // operationBytes + UUID + flavour
    };
  }

  public static void writeInner(Accept m, ByteBuffer buffer) {
    buffer.putShort(m.from());
    buffer.putLong(m.slot());
    write(m.number(), buffer);
    write(m.command(), buffer);
  }

  private static int calculateAcceptInnerSize(Accept m) {
    return Short.BYTES + calculateAcceptSize(m); // from + accept
  }

  public static Accept readAccept(ByteBuffer buffer, short from) {
    final long logIndex = buffer.getLong();
    final BallotNumber number = readBallotNumber(buffer);
    final var command = readCommand(buffer);
    return new Accept(from, logIndex, number, command);
  }

  public static Accept readAcceptInner(ByteBuffer buffer) {
    final short from = buffer.getShort();
    final long logIndex = buffer.getLong();
    final BallotNumber number = readBallotNumber(buffer);
    final var command = readCommand(buffer);
    return new Accept(from, logIndex, number, command);
  }

  public static void write(Catchup m, ByteBuffer buffer) {
    buffer.putLong(m.highestFixedIndex());
    write(m.highestPromised(), buffer);
  }

  public static Catchup readCatchup(ByteBuffer buffer, short from, short to) {
    final var highestFixedIndex = buffer.getLong();
    final var highestPromised = readBallotNumber(buffer);
    return new Catchup(from, to, highestFixedIndex, highestPromised);
  }

  public static CatchupResponse readCatchupResponse(ByteBuffer buffer, short from, short to) {
    final int catchupSize = buffer.getInt();
    List<Accept> catchup = new ArrayList<>();
    IntStream.range(0, catchupSize).forEach(_ ->
        catchup.add(readAcceptInner(buffer))
    );
    return new CatchupResponse(from, to, catchup);
  }

  private static int calculateCatchupResponseSize(CatchupResponse m) {
    return Integer.BYTES + // size of list
        m.accepts().stream()
            .mapToInt(PickleMsg::calculateAcceptInnerSize)
            .sum();
  }

  public static void write(CatchupResponse m, ByteBuffer buffer) {
    buffer.putInt(m.accepts().size());
    for (Accept accept : m.accepts()) {
      writeInner(accept, buffer);
    }
  }

  public static void write(Fixed m, ByteBuffer buffer) {
    buffer.putLong(m.slotTerm().logIndex());
    write(m.slotTerm().number(), buffer);
  }

  public static Fixed readFixed(ByteBuffer buffer, short from) {
    final var fixedLogIndex = buffer.getLong();
    final var number = readBallotNumber(buffer);
    return new Fixed(from, fixedLogIndex, number);
  }

  public static Prepare readPrepare(ByteBuffer buffer, short from) {
    final long logIndex = buffer.getLong();
    final BallotNumber number = readBallotNumber(buffer);
    return new Prepare(from, logIndex, number);
  }

  public static void write(Prepare p, ByteBuffer buffer) {
    buffer.putLong(p.slot());
    write(p.number(), buffer);
  }

  // Utility methods for BallotNumber
  private static void write(BallotNumber n, ByteBuffer buffer) {
    buffer.putShort(n.era());
    buffer.putInt(n.counter());
    buffer.putShort(n.nodeIdentifier());
  }

  private static BallotNumber readBallotNumber(ByteBuffer buffer) {
    return new BallotNumber(buffer.getShort(), buffer.getInt(), buffer.getShort());
  }

  @Override
  public byte[] serialize(TrexMessage trexMessage) {
    return pickle(trexMessage);
  }

  @Override
  public TrexMessage deserialize(byte[] bytes) {
    return unpickle(ByteBuffer.wrap(bytes));
  }
}
