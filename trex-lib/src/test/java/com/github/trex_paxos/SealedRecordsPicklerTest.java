/*
 * Copyright 2024 - 2025 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.trex_paxos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SealedRecordsPicklerTest {
  private Pickler<StackService.Value> pickler;

  @BeforeEach
  void setup() {
    pickler = SealedRecordsPickler.createPickler(StackService.Value.class);
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
        () -> SealedRecordsPickler.createPickler(Runnable.class));
  }
}
