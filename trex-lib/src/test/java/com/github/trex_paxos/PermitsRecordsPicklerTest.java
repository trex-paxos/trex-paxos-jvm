package com.github.trex_paxos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PermitsRecordsPicklerTest {
  private Pickler<StackService.Value> pickler;

  @BeforeEach
  void setup() {
    pickler = PermitsRecordsPickler.createPickler(StackService.Value.class);
  }

  @Test
  void shouldSerializeAndDeserializePush() {
    StackService.Push original = new StackService.Push("test item");
    byte[] serialized = pickler.serialize(original);
    StackService.Value deserialized = pickler.deserialize(serialized);

    assertInstanceOf(StackService.Push.class, deserialized);
    StackService.Push push = (StackService.Push) deserialized;
    assertEquals(original.item(), push.item());
  }

  @Test
  void shouldSerializeAndDeserializePop() {
    StackService.Pop original = new StackService.Pop();
    byte[] serialized = pickler.serialize(original);
    StackService.Value deserialized = pickler.deserialize(serialized);

    assertInstanceOf(StackService.Pop.class, deserialized);
  }

  @Test
  void shouldSerializeAndDeserializePeek() {
    StackService.Peek original = new StackService.Peek();
    byte[] serialized = pickler.serialize(original);
    StackService.Value deserialized = pickler.deserialize(serialized);

    assertInstanceOf(StackService.Peek.class, deserialized);
  }

  @Test
  void shouldHandleNullValue() {
    byte[] serialized = pickler.serialize(null);
    StackService.Value deserialized = pickler.deserialize(serialized);
    assertNull(deserialized);
  }

  @Test
  void shouldHandleEmptyString() {
    StackService.Push original = new StackService.Push("");
    byte[] serialized = pickler.serialize(original);
    StackService.Value deserialized = pickler.deserialize(serialized);

    assertInstanceOf(StackService.Push.class, deserialized);
    StackService.Push push = (StackService.Push) deserialized;
    assertEquals("", push.item());
  }

  @Test
  void shouldMaintainTypeDiscrimination() {
    // Create instances of all types
    StackService.Value[] values = {
        new StackService.Push("test"),
        new StackService.Pop(),
        new StackService.Peek()
    };

    // Serialize all
    byte[][] serialized = new byte[values.length][];
    for (int i = 0; i < values.length; i++) {
      serialized[i] = pickler.serialize(values[i]);
    }

    // Deserialize in different order
    assertEquals(StackService.Push.class, pickler.deserialize(serialized[0]).getClass());
    assertEquals(StackService.Pop.class, pickler.deserialize(serialized[1]).getClass());
    assertEquals(StackService.Peek.class, pickler.deserialize(serialized[2]).getClass());
  }

  @Test
  void shouldRejectInvalidBytes() {
    byte[] invalid = {99}; // Invalid type ID
    assertThrows(IllegalArgumentException.class, () -> pickler.deserialize(invalid));
  }

  @Test
  void shouldFailToCreateSerdeForNonSealedInterface() {
    assertThrows(IllegalArgumentException.class,
        () -> PermitsRecordsPickler.createPickler(Runnable.class));
  }
}
