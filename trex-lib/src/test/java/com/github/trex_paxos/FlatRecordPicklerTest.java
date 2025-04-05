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

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FlatRecordPicklerTest {
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
  void testBasicSerialization() {
    var serde = FlatRecordPickler.createPickler(TestRecord.class);
    var record = new TestRecord(42, 123L, true, "hello");

    byte[] bytes = serde.serialize(record);
    var deserialized = serde.deserialize(bytes);

    assertEquals(record, deserialized);
  }

  @Test
  void testNullString() {
    var serde = FlatRecordPickler.createPickler(StringRecord.class);
    var record = new StringRecord(null);

    byte[] bytes = serde.serialize(record);
    var deserialized = serde.deserialize(bytes);

    assertEquals(record, deserialized);
  }

  @Test
  void testEmptyString() {
    var serde = FlatRecordPickler.createPickler(StringRecord.class);
    var record = new StringRecord("");

    byte[] bytes = serde.serialize(record);
    var deserialized = serde.deserialize(bytes);

    assertEquals(record, deserialized);
  }

  @Test
  void testNullInputs() {
    var serde = FlatRecordPickler.createPickler(TestRecord.class);

    assertNull(serde.deserialize(null));
    assertNull(serde.deserialize(new byte[0]));
    assertEquals(0, serde.serialize(null).length);
  }

  @Test
  void testEdgeCases() {
    var serde = FlatRecordPickler.createPickler(TestRecord.class);
    var record = new TestRecord(
        Integer.MAX_VALUE,
        Long.MAX_VALUE,
        false,
        "Special chars: !@#$%^&*()"
    );

    byte[] bytes = serde.serialize(record);
    var deserialized = serde.deserialize(bytes);

    assertEquals(record, deserialized);
  }

  @Test
  void testEmptyOptional() {
    var serde = FlatRecordPickler.createPickler(OptionalRecord.class);
    var record = new OptionalRecord(Optional.empty());

    byte[] bytes = serde.serialize(record);
    var deserialized = serde.deserialize(bytes);

    assertEquals(record, deserialized);
    assertTrue(deserialized.value().isEmpty());
  }

  @Test
  void testPresentOptional() {
    var serde = FlatRecordPickler.createPickler(OptionalRecord.class);
    var record = new OptionalRecord(Optional.of("test id"));

    byte[] bytes = serde.serialize(record);
    var deserialized = serde.deserialize(bytes);

    assertEquals(record, deserialized);
    assertTrue(deserialized.value().isPresent());
    assertEquals("test id", deserialized.value().get());
  }

  @Test
  void testMixedRecord() {
    var serde = FlatRecordPickler.createPickler(MixedRecord.class);
    var record = new MixedRecord(42, Optional.of("optional"), "required");

    byte[] bytes = serde.serialize(record);
    var deserialized = serde.deserialize(bytes);

    assertEquals(record, deserialized);
    assertEquals(42, deserialized.intValue());
    assertTrue(deserialized.optionalValue().isPresent());
    assertEquals("optional", deserialized.optionalValue().get());
    assertEquals("required", deserialized.stringValue());
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
