package com.github.trex_paxos;

import java.io.DataOutputStream;
import java.io.IOException;
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
        CheckTimeout,
        HeartBeat,
        AbstractCommand,
        NoOperation,
        Command {
    void writeTo(DataOutputStream dos) throws IOException;
}

enum CommandType {
    Prepare(0),
    PrepareAck(1),
    PrepareNack(2),
    Accept(3),
    AcceptAck(4),
    AcceptNack(5),
    Commit(6),
    CheckTimeout(7),
    HeartBeat(8),
    CommandValue(9),
    NoOperation(10),
    ClientCommand(11);

    private final byte id;

    CommandType(int id) {
        this.id = (byte)id;
    }

    public Byte id() {
        return id;
    }

    public static final Map<Byte, CommandType> ORDINAL_TO_TYPE_MAP;

    static {
        ORDINAL_TO_TYPE_MAP = Arrays.stream(values())
                .collect(Collectors.toMap(CommandType::id, Function.identity()));
    }

    public static CommandType fromId(byte id) {
        return ORDINAL_TO_TYPE_MAP.get(id);
    }

    public static CommandType fromPaxosMessage(PaxosMessage paxosMessage){
        return switch(paxosMessage){
            case Prepare _ -> Prepare;
            case PrepareAck _ -> PrepareAck;
            case PrepareNack _ -> PrepareNack;
            case Accept _ -> Accept;
            case AcceptAck _ -> AcceptAck;
            case AcceptNack _ -> AcceptNack;
            case Commit _ -> Commit;
            case CheckTimeout _ -> CheckTimeout;
            case HeartBeat _ -> HeartBeat;
            case NoOperation _ -> NoOperation;
            case Command _ -> ClientCommand;
            case AbstractCommand _ -> CommandValue;
        };
    }
}
