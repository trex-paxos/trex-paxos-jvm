package com.github.trex_paxos;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public sealed interface TrexMessage permits
  Accept,
  AcceptResponse,
  Prepare,
  PrepareResponse,
  Commit,
  RetransmitRequest,
  RetransmitResponse {
  void writeTo(DataOutputStream dos) throws IOException;
}

enum MessageType {
  Prepare(1),
  PrepareResponse(2),

  Accept(3),
  AcceptResponse(4),
  Commit(5),
  RetransmitRequest(6),
  RetransmitResponse(7);

  private final byte id;

  MessageType(int id) {
    this.id = (byte) id;
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

  public static MessageType fromPaxosMessage(TrexMessage trexMessage) {
    return switch (trexMessage) {
      case Prepare _ -> Prepare;
      case PrepareResponse _ -> PrepareResponse;
      case Accept _ -> Accept;
      case AcceptResponse _ -> AcceptResponse;
      case Commit _ -> Commit;
      case RetransmitRequest _ -> RetransmitRequest;
      case RetransmitResponse _ -> RetransmitResponse;
    };
  }
}
