package com.github.trex_paxos;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class RecordSerdeTest {
    record TestRecord(
        int intValue,
        long longValue,
        boolean boolValue,
        String stringValue
    ) {}

    record EmptyRecord() {}

    record StringRecord(String value) {}
    
    record OptionalRecord(Optional<String> value) {}

    record MixedRecord(
        int intValue,
        Optional<String> optionalValue,
        String stringValue
    ) {}

    @Test
    void testBasicSerialization() {
        var serde = RecordSerde.createSerde(TestRecord.class);
        var record = new TestRecord(42, 123L, true, "hello");
        
        byte[] bytes = serde.serialize(record);
        var deserialized = serde.deserialize(bytes);
        
        assertEquals(record, deserialized);
    }

    @Test
    void testNullString() {
        var serde = RecordSerde.createSerde(StringRecord.class);
        var record = new StringRecord(null);
        
        byte[] bytes = serde.serialize(record);
        var deserialized = serde.deserialize(bytes);
        
        assertEquals(record, deserialized);
    }

    @Test
    void testEmptyString() {
        var serde = RecordSerde.createSerde(StringRecord.class);
        var record = new StringRecord("");
        
        byte[] bytes = serde.serialize(record);
        var deserialized = serde.deserialize(bytes);
        
        assertEquals(record, deserialized);
    }

    @Test
    void testNullInputs() {
        var serde = RecordSerde.createSerde(TestRecord.class);
        
        assertNull(serde.deserialize(null));
        assertNull(serde.deserialize(new byte[0]));
        assertEquals(0, serde.serialize(null).length);
    }

    @Test
    void testEdgeCases() {
        var serde = RecordSerde.createSerde(TestRecord.class);
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
        var serde = RecordSerde.createSerde(OptionalRecord.class);
        var record = new OptionalRecord(Optional.empty());
        
        byte[] bytes = serde.serialize(record);
        var deserialized = serde.deserialize(bytes);
        
        assertEquals(record, deserialized);
        assertTrue(deserialized.value().isEmpty());
    }

    @Test
    void testPresentOptional() {
        var serde = RecordSerde.createSerde(OptionalRecord.class);
        var record = new OptionalRecord(Optional.of("test value"));
        
        byte[] bytes = serde.serialize(record);
        var deserialized = serde.deserialize(bytes);
        
        assertEquals(record, deserialized);
        assertTrue(deserialized.value().isPresent());
        assertEquals("test value", deserialized.value().get());
    }

    @Test
    void testMixedRecord() {
        var serde = RecordSerde.createSerde(MixedRecord.class);
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
        class NotARecord {}
        
        assertThrows(IllegalArgumentException.class, () -> 
            RecordSerde.createSerde((Class)NotARecord.class)
        );
    }
}