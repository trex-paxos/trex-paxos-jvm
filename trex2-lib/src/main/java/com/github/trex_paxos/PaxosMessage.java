package com.github.trex_paxos;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public sealed interface PaxosMessage permits
        Accept,
        AcceptAck,
        AcceptNack,
        AcceptResponse,
        Prepare,
        PrepareAck,
        PrepareNack,
        PrepareResponse {
    void writeTo(DataOutputStream dos) throws IOException;
}

enum MessageType {
    Prepare(1),
    PrepareAck(2),
    PrepareNack(3),

    Accept(4),
    AcceptAck(5),
    AcceptNack(6);

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
        };
    }
}
