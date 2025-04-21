// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FlatRecordPicklerTest {
  private static final int BUFFER_SIZE = 1024;

  record TestRecord(
      int intValue,
      long longValue,
      boolean boolValue,
      String stringValue
  ) {
  }

  record StringRecord(String value) {
  }

  record OptionalRecord(Optional<String> value) {
  }

  record MixedRecord(
      int intValue,
      Optional<String> optionalValue,
      String stringValue
  ) {
  }

  @Test
  void testBasicSerialization() throws NoSuchMethodException, IllegalAccessException {
    var pickler = FlatRecordPickler.createPickler(TestRecord.class);
    var record = new TestRecord(42, 123L, true, "hello");

    ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
    int startPosition = buffer.position();

    pickler.serialize(record, buffer);

    int bytesWritten = buffer.position() - startPosition;
    buffer.flip();

    var deserialized = pickler.deserialize(buffer);

    assertEquals(record, deserialized);
    assertEquals(bytesWritten, pickler.sizeOf(record));
  }

  @Test
  void testNullString() throws Exception {
    var pickler = FlatRecordPickler.createPickler(StringRecord.class);
    var record = new StringRecord(null);

    ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
    int startPosition = buffer.position();

    pickler.serialize(record, buffer);

    int bytesWritten = buffer.position() - startPosition;
    buffer.flip();

    var deserialized = pickler.deserialize(buffer);

    assertEquals(record, deserialized);
    assertEquals(bytesWritten, pickler.sizeOf(record));
  }

  @Test
  void testEmptyString() throws Exception {
    var pickler = FlatRecordPickler.createPickler(StringRecord.class);
    var record = new StringRecord("");

    ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
    int startPosition = buffer.position();

    pickler.serialize(record, buffer);

    int bytesWritten = buffer.position() - startPosition;
    buffer.flip();

    var deserialized = pickler.deserialize(buffer);

    assertEquals(record, deserialized);
    assertEquals(bytesWritten, pickler.sizeOf(record));
  }

  @Test
  void testNullInputs() throws Exception {
    var pickler = FlatRecordPickler.createPickler(TestRecord.class);

    assertNull(pickler.deserialize(null));

    ByteBuffer emptyBuffer = ByteBuffer.allocate(0);
    assertNull(pickler.deserialize(emptyBuffer));

    assertEquals(0, pickler.sizeOf(null));

    ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
    int startPosition = buffer.position();

    pickler.serialize(null, buffer);

    int bytesWritten = buffer.position() - startPosition;
    assertEquals(0, bytesWritten);
  }

  @Test
  void testEdgeCases() throws Exception {
    var pickler = FlatRecordPickler.createPickler(TestRecord.class);
    var record = new TestRecord(
        Integer.MAX_VALUE,
        Long.MAX_VALUE,
        false,
        "Special chars: !@#$%^&*()"
    );

    ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
    int startPosition = buffer.position();

    pickler.serialize(record, buffer);

    int bytesWritten = buffer.position() - startPosition;
    buffer.flip();

    var deserialized = pickler.deserialize(buffer);

    assertEquals(record, deserialized);
    assertEquals(bytesWritten, pickler.sizeOf(record));
  }

  @Test
  void testEmptyOptional() throws Exception {
    var pickler = FlatRecordPickler.createPickler(OptionalRecord.class);
    var record = new OptionalRecord(Optional.empty());

    ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
    int startPosition = buffer.position();

    pickler.serialize(record, buffer);

    int bytesWritten = buffer.position() - startPosition;
    buffer.flip();

    var deserialized = pickler.deserialize(buffer);

    assertEquals(record, deserialized);
    assertTrue(deserialized.value().isEmpty());
    assertEquals(bytesWritten, pickler.sizeOf(record));
  }

  @Test
  void testPresentOptional() throws Exception {
    var pickler = FlatRecordPickler.createPickler(OptionalRecord.class);
    var record = new OptionalRecord(Optional.of("test id"));

    ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
    int startPosition = buffer.position();

    pickler.serialize(record, buffer);

    int bytesWritten = buffer.position() - startPosition;
    buffer.flip();

    var deserialized = pickler.deserialize(buffer);

    assertEquals(record, deserialized);
    assertTrue(deserialized.value().isPresent());
    assertEquals("test id", deserialized.value().get());
    assertEquals(bytesWritten, pickler.sizeOf(record));
  }

  @Test
  void testMixedRecord() throws Exception {
    var pickler = FlatRecordPickler.createPickler(MixedRecord.class);
    var record = new MixedRecord(42, Optional.of("optional"), "required");

    ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
    int startPosition = buffer.position();

    pickler.serialize(record, buffer);

    int bytesWritten = buffer.position() - startPosition;
    buffer.flip();

    var deserialized = pickler.deserialize(buffer);

    assertEquals(record, deserialized);
    assertEquals(42, deserialized.intValue());
    assertTrue(deserialized.optionalValue().isPresent());
    assertEquals("optional", deserialized.optionalValue().get());
    assertEquals("required", deserialized.stringValue());
    assertEquals(bytesWritten, pickler.sizeOf(record));
  }

  @Test
  void testInvalidClass() {
    class NotARecord {
    }

    //noinspection unchecked,rawtypes
    assertThrows(IllegalArgumentException.class, () ->
        FlatRecordPickler.createPickler((Class) NotARecord.class)
    );
  }
}
