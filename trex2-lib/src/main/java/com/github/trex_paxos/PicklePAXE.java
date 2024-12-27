package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/// The PaXE protocol is designed to be compatible with QUIC or even raw UDP.
///
/// This class pickles and unpickles TrexMessages with a standard from/to/type header.
/// In the case of a DirectMessage, the to field is used to specify the destination node.
/// In the case of a BroadcastMessage, the to field is set to 0.
/// The purpose of the fixed size header is for rapid multiplexing and demultiplexing of messages.
public class PicklePAXE {

    private static final int HEADER_SIZE = 3; // fromNode(1) + toNode(1) + type(1)
    private static final int BALLOT_NUMBER_SIZE = Integer.BYTES + 1; // counter(4) + nodeId(1)

    public static byte[] pickle(TrexMessage msg) {
        // Calculate size needed for the message
        int size = HEADER_SIZE + calculateMessageSize(msg);
        ByteBuffer buffer = ByteBuffer.allocate(size);
        writeHeader(msg, buffer);
        writeMessageBody(msg, buffer);
        return buffer.array();
    }

    private static int calculateMessageSize(TrexMessage msg) {
        return switch (msg) {
            case Prepare p -> Long.BYTES + BALLOT_NUMBER_SIZE;
            case PrepareResponse p -> calculatePrepareResponseSize(p);
            case Accept a -> calculateAcceptSize(a);
            case AcceptResponse a -> calculateAcceptResponseSize(a);
            case Fixed f -> Long.BYTES + BALLOT_NUMBER_SIZE;
            case Catchup c -> Long.BYTES + BALLOT_NUMBER_SIZE;
            case CatchupResponse c -> calculateCatchupResponseSize(c);
        };
    }

    ///  This must match the lookup table in the unpickle method
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
        buffer.put(fromNode);
        buffer.put(toNode);
        buffer.put(type);
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

    public static TrexMessage unpickle(ByteBuffer buffer) {
        byte fromNode = buffer.get();
        byte toNode = buffer.get();
        byte type = buffer.get();
        return switch (type) {
            case 1 -> readPrepare(buffer, fromNode);
            case 2 -> readPrepareResponse(buffer, fromNode, toNode);
            case 3 -> readAccept(buffer, fromNode);
            case 4 -> readAcceptResponse(buffer, fromNode, toNode);
            case 5 -> readFixed(buffer, fromNode);
            case 6 -> readCatchup(buffer, fromNode, toNode);
            case 7 -> readCatchupResponse(buffer, fromNode, toNode);
            default -> throw new IllegalStateException("Unknown type: " + type);
        };
    }

    public static void write(PrepareResponse m, ByteBuffer buffer) {
        write(m.vote(), buffer);
        buffer.putLong(m.highestAcceptedIndex());
        buffer.put((byte) (m.journaledAccept().isPresent() ? 1 : 0));
        m.journaledAccept().ifPresent(accept -> writeInner(accept, buffer));
    }

    private static int calculatePrepareResponseSize(PrepareResponse m) {
        int size = Long.BYTES + 1; // highestAcceptedIndex + isPresent flag
        size += calculateVotePrepareSize(m.vote());
        if (m.journaledAccept().isPresent()) {
            size += calculateAcceptInnerSize(m.journaledAccept().get());
        }
        return size;
    }

    public static PrepareResponse readPrepareResponse(ByteBuffer buffer, byte from, byte to) {
        final var vote = readVotePrepare(buffer, from, to);
        long highestFixedIndex = buffer.getLong();
        Optional<Accept> highestUnfixed = buffer.get() == 1 ? 
            Optional.of(readAcceptInner(buffer)) : Optional.empty();
        return new PrepareResponse(from, to, vote, highestUnfixed, highestFixedIndex);
    }

    public static void write(AbstractCommand c, ByteBuffer buffer) {
        switch (c) {
            case NoOperation _ ->
                // Here we use zero bytes as a sentinel to represent the NOOP command.
                buffer.putInt(0);
            case Command command -> {
                buffer.putInt(command.operationBytes().length);
                buffer.put(command.operationBytes());
                byte[] clientMsgBytes = command.clientMsgUuid().getBytes(StandardCharsets.UTF_8);
                buffer.putInt(clientMsgBytes.length);
                buffer.put(clientMsgBytes);
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
        int stringLength = buffer.getInt();
        byte[] stringBytes = new byte[stringLength];
        buffer.get(stringBytes);
        String clientMsgUuid = new String(stringBytes, StandardCharsets.UTF_8);
        return new Command(clientMsgUuid, bytes);
    }

    public static void write(AcceptResponse m, ByteBuffer buffer) {
        write(m.vote(), buffer);
        buffer.putLong(m.highestFixedIndex());
    }

    private static int calculateAcceptResponseSize(AcceptResponse m) {
        return calculateVoteAcceptSize(m.vote()) + Long.BYTES;
    }

    public static AcceptResponse readAcceptResponse(ByteBuffer buffer, byte from, byte to) {
        final var vote = readVoteAccept(buffer);
        final long highestFixedIndex = buffer.getLong();
        return new AcceptResponse(from, to, vote, highestFixedIndex);
    }

    public static AcceptResponse.Vote readVoteAccept(ByteBuffer buffer) {
        byte from = buffer.get();
        byte to = buffer.get();
        long logIndex = buffer.getLong();
        boolean vote = buffer.get() != 0;
        return new AcceptResponse.Vote(from, to, logIndex, vote);
    }

    private static int calculateVoteAcceptSize(AcceptResponse.Vote vote) {
        return 2 + Long.BYTES + 1; // from + to + logIndex + vote
    }

    public static void write(AcceptResponse.Vote m, ByteBuffer buffer) {
        buffer.put(m.from());
        buffer.put(m.to());
        buffer.putLong(m.logIndex());
        buffer.put((byte) (m.vote() ? 1 : 0));
    }

    public static PrepareResponse.Vote readVotePrepare(ByteBuffer buffer, byte from, byte to) {
        long logIndex = buffer.getLong();
        boolean vote = buffer.get() != 0;
        BallotNumber number = readBallotNumber(buffer);
        return new PrepareResponse.Vote(from, to, logIndex, vote, number);
    }

    private static int calculateVotePrepareSize(PrepareResponse.Vote vote) {
        return Long.BYTES + 1 + BALLOT_NUMBER_SIZE; // logIndex + vote + ballotNumber
    }

    public static void write(PrepareResponse.Vote m, ByteBuffer buffer) {
        buffer.putLong(m.logIndex());
        buffer.put((byte) (m.vote() ? 1 : 0));
        write(m.number(), buffer);
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
                            Integer.BYTES + c.clientMsgUuid().getBytes(StandardCharsets.UTF_8).length;
        };
    }

    public static void writeInner(Accept m, ByteBuffer buffer) {
        buffer.put(m.from());
        buffer.putLong(m.slot());
        write(m.number(), buffer);
        write(m.command(), buffer);
    }

    private static int calculateAcceptInnerSize(Accept m) {
        return 1 + calculateAcceptSize(m); // from + accept size
    }

    public static Accept readAccept(ByteBuffer buffer, byte from) {
        final long logIndex = buffer.getLong();
        final BallotNumber number = readBallotNumber(buffer);
        final var command = readCommand(buffer);
        return new Accept(from, logIndex, number, command);
    }

    public static Accept readAcceptInner(ByteBuffer buffer) {
        final byte from = buffer.get();
        final long logIndex = buffer.getLong();
        final BallotNumber number = readBallotNumber(buffer);
        final var command = readCommand(buffer);
        return new Accept(from, logIndex, number, command);
    }

    public static void write(Catchup m, ByteBuffer buffer) {
        buffer.putLong(m.highestFixedIndex());
        write(m.highestPromised(), buffer);
    }

    public static Catchup readCatchup(ByteBuffer buffer, byte from, byte to) {
        final var highestFixedIndex = buffer.getLong();
        final var highestPromised = readBallotNumber(buffer);
        return new Catchup(from, to, highestFixedIndex, highestPromised);
    }

    public static CatchupResponse readCatchupResponse(ByteBuffer buffer, byte from, byte to) {
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
                .mapToInt(PicklePAXE::calculateAcceptInnerSize)
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

    public static Fixed readFixed(ByteBuffer buffer, byte from) {
        final var fixedLogIndex = buffer.getLong();
        final var number = readBallotNumber(buffer);
        return new Fixed(from, fixedLogIndex, number);
    }

    public static Prepare readPrepare(ByteBuffer buffer, byte from) {
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
        buffer.putInt(n.counter());
        buffer.put(n.nodeIdentifier());
    }

    private static BallotNumber readBallotNumber(ByteBuffer buffer) {
        return new BallotNumber(buffer.getInt(), buffer.get());
    }
}