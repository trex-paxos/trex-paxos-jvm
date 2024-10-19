package com.github.trex_paxos.msg;

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
  Catchup(6);

  private final byte id;

  MessageType(int id) {
    this.id = (byte) id;
  }

  public Byte id() {
    return id;
  }

  static final Map<Byte, MessageType> ORDINAL_TO_TYPE_MAP = Arrays.stream(values())
        .collect(Collectors.toMap(MessageType::id, Function.identity()));

  public static MessageType fromMessageId(byte id) {
    return ORDINAL_TO_TYPE_MAP.get(id);
  }

  static final Map<Byte, Class<? extends TrexMessage>> ORDINAL_TO_CLASS_MAP = Map.of(
      (byte) 0, Prepare.class,
      (byte) 1, PrepareResponse.class,
      (byte) 2, com.github.trex_paxos.msg.Accept.class,
      (byte) 3, com.github.trex_paxos.msg.AcceptResponse.class,
      (byte) 4, com.github.trex_paxos.msg.Commit.class,
      (byte) 5, com.github.trex_paxos.msg.Catchup.class
  );

  /**
   * Host applications may want to use this map to convert ordinal values to message classes for custom deserialization.
   */
  @SuppressWarnings("unused")
  public static Class<? extends TrexMessage> classFromMessageId(byte id) {
    return ORDINAL_TO_CLASS_MAP.get(id);
  }

  static final Map<Class<? extends TrexMessage>, Byte> CLASS_TO_ORDINAL_MAP =
      ORDINAL_TO_CLASS_MAP.entrySet().stream()
          .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

  /**
   * Host applications may want to use this map to convert message classes to ordinal values for custom serialization.
   */
  @SuppressWarnings("unused")
  public static byte idFromMessageTypeClass(Class<? extends TrexMessage> classType) {
    return CLASS_TO_ORDINAL_MAP.get(classType);
  }

  /**
   * Host applications may want to use this map to convert ordinal values to message types for custom serialization.
   */
  public static MessageType fromPaxosMessage(TrexMessage trexMessage) {
    return switch (trexMessage) {
      case Prepare _ -> Prepare;
      case PrepareResponse _ -> PrepareResponse;
      case Accept _ -> Accept;
      case AcceptResponse _ -> AcceptResponse;
      case Commit _ -> Commit;
      case Catchup _ -> Catchup;
    };
  }
}
