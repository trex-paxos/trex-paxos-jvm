package com.github.trex_paxos;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public sealed interface PaxosMessage permits
        Prepare,
        PrepareAck,
        PrepareNack,
        Accept,
        AcceptAck,
        AcceptNack,
        Commit,
        RetransmitRequest,
        RetransmitResponse,
        CheckTimeout,
        HeartBeat,
        AbstractCommand,
        NoOperation,
        Command {
}

enum CommandType {
    Prepare((byte)0),
    PrepareAck((byte)1),
    PrepareNack((byte)2),
    Accept((byte)3),
    AcceptAck((byte)4),
    AcceptNack((byte)5),
    Commit((byte)6),
    RetransmitRequest((byte)7),
    RetransmitResponse((byte)8),
    CheckTimeout((byte)9),
    HeartBeat((byte)10),
    CommandValue((byte)11),
    NoOperation((byte)12),
    ClientCommand((byte)13);

    private final byte id;

    CommandType(byte id) {
        this.id = id;
    }

    public Byte id() {
        return id;
    }

    public static final Map<Byte, CommandType> ORDINAL_TO_TYPE_MAP;

    static {
        ORDINAL_TO_TYPE_MAP = Arrays.stream(values())
                .collect(Collectors.toMap(CommandType::id, Function.identity()));
    }

}