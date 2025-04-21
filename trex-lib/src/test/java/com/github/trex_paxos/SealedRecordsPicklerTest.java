// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.github.trex_paxos.TrexLogger.LOGGER;
import static org.junit.jupiter.api.Assertions.*;

class SealedRecordsPicklerTest {
  private Pickler<StackService.Value> pickler;
  private static final int BUFFER_SIZE = 1024;

  @BeforeAll
  static void setupLogging() {
    final var logLevel = System.getProperty("java.util.logging.ConsoleHandler.level", "FINE");
    final Level level = Level.parse(logLevel);

    LOGGER.setLevel(level);
    ConsoleHandler consoleHandler = new ConsoleHandler();
    consoleHandler.setLevel(level);
    LOGGER.addHandler(consoleHandler);

    // Configure SessionKeyManager logger
    Logger logger = Logger.getLogger("com.github.trex_paxos");
    logger.setLevel(level);
    ConsoleHandler skmHandler = new ConsoleHandler();
    skmHandler.setLevel(level);
    logger.addHandler(skmHandler);

    // Optionally disable parent handlers if needed
    LOGGER.setUseParentHandlers(false);
    logger.setUseParentHandlers(false);

    LOGGER.info("Logging initialized at level: " + level);
  }

  @BeforeEach
  void setup() {
    pickler = SealedRecordsPickler.createPickler(StackService.Value.class);
    LOGGER.fine("Created pickler for StackService.Value");
  }

  @Test
  void shouldSerializeAndDeserializePush() {
    StackService.Push original = new StackService.Push("test item");
    LOGGER.fine("Testing serialization/deserialization of Push with item: " + original.item());

    // Allocate fixed size buffer
    ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
    LOGGER.fine("Allocated buffer of size: " + BUFFER_SIZE);

    // Track position before serialization
    int startPosition = buffer.position();

    // Serialize
    LOGGER.fine("Serializing Push object");
    pickler.serialize(original, buffer);

    // Calculate actual bytes written
    int bytesWritten = buffer.position() - startPosition;
    LOGGER.fine("Serialized Push object, bytes written: " + bytesWritten);

    buffer.flip();
    LOGGER.fine("Buffer flipped, position: " + buffer.position() + ", limit: " + buffer.limit());

    // Deserialize
    LOGGER.fine("Deserializing from buffer");
    StackService.Value deserialized = pickler.deserialize(buffer);

    // Verify results
    LOGGER.fine("Verifying deserialized object type and content");
    assertInstanceOf(StackService.Push.class, deserialized);
    StackService.Push push = (StackService.Push) deserialized;
    assertEquals(original.item(), push.item());

    // Verify calculated size matches actual bytes written
    LOGGER.fine("Verifying size calculation, expected: " + bytesWritten + ", actual: " + pickler.sizeOf(original));
    assertEquals(bytesWritten, pickler.sizeOf(original));
  }

  @Test
  void shouldSerializeAndDeserializePop() {
    StackService.Pop original = new StackService.Pop();
    LOGGER.fine("Testing serialization/deserialization of Pop");

    // Allocate fixed size buffer
    ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
    LOGGER.fine("Allocated buffer of size: " + BUFFER_SIZE);

    // Track position before serialization
    int startPosition = buffer.position();

    // Serialize
    LOGGER.fine("Serializing Pop object");
    pickler.serialize(original, buffer);

    // Calculate actual bytes written
    int bytesWritten = buffer.position() - startPosition;
    LOGGER.fine("Serialized Pop object, bytes written: " + bytesWritten);

    buffer.flip();
    LOGGER.fine("Buffer flipped, position: " + buffer.position() + ", limit: " + buffer.limit());

    // Deserialize
    LOGGER.fine("Deserializing from buffer");
    StackService.Value deserialized = pickler.deserialize(buffer);

    // Verify results
    LOGGER.fine("Verifying deserialized object type");
    assertInstanceOf(StackService.Pop.class, deserialized);

    // Verify calculated size matches actual bytes written
    LOGGER.fine("Verifying size calculation, expected: " + bytesWritten + ", actual: " + pickler.sizeOf(original));
    assertEquals(bytesWritten, pickler.sizeOf(original));
  }

  @Test
  void shouldSerializeAndDeserializePeek() {
    StackService.Peek original = new StackService.Peek();
    LOGGER.fine("Testing serialization/deserialization of Peek");

    // Allocate fixed size buffer
    ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
    LOGGER.fine("Allocated buffer of size: " + BUFFER_SIZE);

    // Track position before serialization
    int startPosition = buffer.position();

    // Serialize
    LOGGER.fine("Serializing Peek object");
    pickler.serialize(original, buffer);

    // Calculate actual bytes written
    int bytesWritten = buffer.position() - startPosition;
    LOGGER.fine("Serialized Peek object, bytes written: " + bytesWritten);

    buffer.flip();
    LOGGER.fine("Buffer flipped, position: " + buffer.position() + ", limit: " + buffer.limit());

    // Deserialize
    LOGGER.fine("Deserializing from buffer");
    StackService.Value deserialized = pickler.deserialize(buffer);

    // Verify results
    LOGGER.fine("Verifying deserialized object type");
    assertInstanceOf(StackService.Peek.class, deserialized);

    // Verify calculated size matches actual bytes written
    LOGGER.fine("Verifying size calculation, expected: " + bytesWritten + ", actual: " + pickler.sizeOf(original));
    assertEquals(bytesWritten, pickler.sizeOf(original));
  }

  @Test
  void shouldHandleNullValue() {
    LOGGER.fine("Testing handling of null value");

    // Allocate fixed size buffer
    ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
    LOGGER.fine("Allocated buffer of size: " + BUFFER_SIZE);

    // Track position before serialization
    int startPosition = buffer.position();

    // Serialize
    LOGGER.fine("Serializing null value");
    pickler.serialize(null, buffer);

    // Calculate actual bytes written
    int bytesWritten = buffer.position() - startPosition;
    LOGGER.fine("Serialized null value, bytes written: " + bytesWritten);

    buffer.flip();
    LOGGER.fine("Buffer flipped, position: " + buffer.position() + ", limit: " + buffer.limit());

    // Deserialize
    LOGGER.fine("Deserializing from buffer");
    StackService.Value deserialized = pickler.deserialize(buffer);

    // Verify results
    LOGGER.fine("Verifying deserialized object is null");
    assertNull(deserialized);

    // Verify calculated size matches actual bytes written
    LOGGER.fine("Verifying size calculation, expected: " + bytesWritten + ", actual: " + pickler.sizeOf(null));
    assertEquals(bytesWritten, pickler.sizeOf(null));
  }

  @Test
  void shouldHandleEmptyString() {
    StackService.Push original = new StackService.Push("");
    LOGGER.fine("Testing serialization/deserialization of Push with empty string");

    // Allocate fixed size buffer
    ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
    LOGGER.fine("Allocated buffer of size: " + BUFFER_SIZE);

    // Track position before serialization
    int startPosition = buffer.position();

    // Serialize
    LOGGER.fine("Serializing Push object with empty string");
    pickler.serialize(original, buffer);

    // Calculate actual bytes written
    int bytesWritten = buffer.position() - startPosition;
    LOGGER.fine("Serialized Push object, bytes written: " + bytesWritten);

    buffer.flip();
    LOGGER.fine("Buffer flipped, position: " + buffer.position() + ", limit: " + buffer.limit());

    // Deserialize
    LOGGER.fine("Deserializing from buffer");
    StackService.Value deserialized = pickler.deserialize(buffer);

    // Verify results
    LOGGER.fine("Verifying deserialized object type and content");
    assertInstanceOf(StackService.Push.class, deserialized);
    StackService.Push push = (StackService.Push) deserialized;
    assertEquals("", push.item());

    // Verify calculated size matches actual bytes written
    LOGGER.fine("Verifying size calculation, expected: " + bytesWritten + ", actual: " + pickler.sizeOf(original));
    assertEquals(bytesWritten, pickler.sizeOf(original));
  }

  @Test
  void shouldMaintainTypeDiscrimination() {
    LOGGER.fine("Testing type discrimination for all StackService.Value types");

    // Create instances of all types
    StackService.Value[] values = {
        new StackService.Push("test"),
        new StackService.Pop(),
        new StackService.Peek()
    };

    // Serialize all
    ByteBuffer[] buffers = new ByteBuffer[values.length];
    for (int i = 0; i < values.length; i++) {
      buffers[i] = ByteBuffer.allocate(BUFFER_SIZE);
      LOGGER.fine("Allocated buffer of size: " + BUFFER_SIZE + " for value index: " + i);

      // Track position before serialization
      int startPosition = buffers[i].position();

      // Serialize
      LOGGER.fine("Serializing value at index: " + i);
      pickler.serialize(values[i], buffers[i]);

      // Calculate actual bytes written
      int bytesWritten = buffers[i].position() - startPosition;
      LOGGER.fine("Serialized value at index: " + i + ", bytes written: " + bytesWritten);

      buffers[i].flip();
      LOGGER.fine("Buffer flipped for value index: " + i + ", position: " + buffers[i].position() + ", limit: " + buffers[i].limit());

      // Verify calculated size matches actual bytes written
      LOGGER.fine("Verifying size calculation for value index: " + i + ", expected: " + bytesWritten + ", actual: " + pickler.sizeOf(values[i]));
      assertEquals(bytesWritten, pickler.sizeOf(values[i]));
    }

    // Deserialize in same order
    LOGGER.fine("Deserializing and verifying types in order");
    assertEquals(StackService.Push.class, pickler.deserialize(buffers[0]).getClass());
    assertEquals(StackService.Pop.class, pickler.deserialize(buffers[1]).getClass());
    assertEquals(StackService.Peek.class, pickler.deserialize(buffers[2]).getClass());
  }

  @Test
  void shouldRejectInvalidBytes() {
    LOGGER.fine("Testing rejection of invalid bytes");

    ByteBuffer invalid = ByteBuffer.allocate(1);
    invalid.put((byte) 99); // Invalid type ID
    invalid.flip();
    LOGGER.fine("Buffer with invalid bytes prepared, position: " + invalid.position() + ", limit: " + invalid.limit());

    assertThrows(IllegalArgumentException.class, () -> pickler.deserialize(invalid));
    LOGGER.fine("Successfully threw exception for invalid bytes");
  }

  @Test
  void shouldFailToCreateSerdeForNonSealedInterface() {
    LOGGER.fine("Testing failure to create serde for non-sealed interface");

    assertThrows(IllegalArgumentException.class,
        () -> SealedRecordsPickler.createPickler(Runnable.class));
    LOGGER.fine("Successfully threw exception for non-sealed interface");
  }
}
