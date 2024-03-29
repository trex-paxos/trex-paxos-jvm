package com.github.trex_paxos;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public sealed interface PaxosMessage permits
        AbstractCommand,
        Accept,
        AcceptAck,
        AcceptNack,
        AcceptResponse,
        Command,
        Commit,
        NoOperation,
        Prepare,
        PrepareAck,
        PrepareNack,
        PrepareResponse {
    void writeTo(DataOutputStream dos) throws IOException;
}

enum MessageType {
    Prepare(0),
    PrepareAck(1),
    PrepareNack(2),
    Accept(3),
    AcceptAck(4),
    AcceptNack(5),
    Commit(6),
    CommandValue(7),
    NoOperation(8),
    ClientCommand(9);

    private final byte id;

    MessageType(int id) {
        this.id = (byte)id;
    }

    public Byte id() {
        return id;
    }

    public static final Map<Byte, MessageType> ORDINAL_TO_TYPE_MAP;

    static {
        ORDINAL_TO_TYPE_MAP = Arrays.stream(values())
                .collect(Collectors.toMap(MessageType::id, Function.identity()));
    }

    public static MessageType fromId(byte id) {
        return ORDINAL_TO_TYPE_MAP.get(id);
    }

    public static MessageType fromPaxosMessage(PaxosMessage paxosMessage){
        return switch(paxosMessage){
            case Prepare _ -> Prepare;
            case PrepareAck _ -> PrepareAck;
            case PrepareNack _ -> PrepareNack;
            case Accept _ -> Accept;
            case AcceptAck _ -> AcceptAck;
            case AcceptNack _ -> AcceptNack;
            case Commit _ -> Commit;
            case NoOperation _ -> NoOperation;
            case Command _ -> ClientCommand;
            case AbstractCommand _ -> CommandValue;
        };
    }
}
