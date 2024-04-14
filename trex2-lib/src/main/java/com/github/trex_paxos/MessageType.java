package com.github.trex_paxos;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum MessageType {
  Prepare(1),
  PrepareResponse(2),

  Accept(3),
  AcceptResponse(4),
  Commit(5),
  Catchup(6),
  CatchupResponse(7);

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

  public static final Map<Byte, Class<? extends TrexMessage>> ORDINAL_TO_CLASS_MAP = Map.of(
      (byte) 0, Prepare.class,
      (byte) 1, PrepareResponse.class,
      (byte) 2, Accept.class,
      (byte) 3, AcceptResponse.class,
      (byte) 4, Commit.class,
      (byte) 5, Catchup.class,
      (byte) 6, CatchupResponse.class
  );

  public static MessageType fromPaxosMessage(TrexMessage trexMessage) {
    return switch (trexMessage) {
      case Prepare _ -> Prepare;
      case PrepareResponse _ -> PrepareResponse;
      case Accept _ -> Accept;
      case AcceptResponse _ -> AcceptResponse;
      case Commit _ -> Commit;
      case Catchup _ -> Catchup;
      case CatchupResponse _ -> CatchupResponse;
    };
  }
}
