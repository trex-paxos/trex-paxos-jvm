package com.github.trex_paxos;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecordSerdeTest {
    // Test record with all supported types
    record TestRecord(
        int intValue,
        long longValue,
        boolean boolValue,
        String stringValue
    ) {}

    // Empty record for edge case testing
    record EmptyRecord() {}

    // Record with nullable String
    record StringRecord(String value) {}

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
    void testInvalidClass() {
        class NotARecord {}
        
        assertThrows(IllegalArgumentException.class, () -> 
            RecordSerde.createSerde((Class)NotARecord.class)
        );
    }
}