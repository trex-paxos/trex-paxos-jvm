package com.github.trex_paxos.demo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class UUIDv7GeneratorTest {

    static class DebugUUIDv7Generator extends UUIDv7Generator {
        private final long fixedTimestamp;
        private final byte[] fixedRandomBytes;
        
        public DebugUUIDv7Generator(byte nodeIdentifier, long fixedTimestamp) {
            super(nodeIdentifier);
            this.fixedTimestamp = fixedTimestamp;
            this.fixedRandomBytes = new byte[7];  // All zeros
        }
        
        @Override
        protected long getCurrentTimestamp() {
            return fixedTimestamp;
        }
        
        @Override
        protected byte[] getRandomBytes(int size) {
            return fixedRandomBytes;
        }
        
        public String debugBitPattern(UUID uuid) {
            long msb = uuid.getMostSignificantBits();
            long lsb = uuid.getLeastSignificantBits();
            
            return String.format(
                "MSB: %64s\n" +
                "LSB: %64s\n" +
                "Version bits: %4s (decimal: %d)",
                Long.toBinaryString(msb),
                Long.toBinaryString(lsb),
                Long.toBinaryString(uuid.version()),
                uuid.version()
            ).replace(' ', '0');
        }
    }
    

    @Test
    public void testVersionIs7WithDebug() {
        DebugUUIDv7Generator generator = new DebugUUIDv7Generator((byte)1, 0L);
        UUID uuid = generator.generateUUID();
        
        System.out.println("Generated UUID: " + uuid);
        System.out.println(generator.debugBitPattern(uuid));
        
        assertEquals(7, uuid.version(), "UUID version should be 7");
    }

    @Test
    public void testVersionIs7WithRealData() {
        UUIDv7Generator generator = new UUIDv7Generator((byte)1);
        
        // Generate multiple UUIDs to ensure version bits are consistently correct
        for (int i = 0; i < 1000; i++) {
            UUID uuid = generator.generateUUID();
            assertEquals(7, uuid.version(), 
                String.format("UUID version should be 7, but was %d for UUID: %s", 
                    uuid.version(), uuid.toString()));
        }
    }

    @Test
    public void testVariantIsDCE() {
        UUIDv7Generator generator = new UUIDv7Generator((byte) 1);
        UUID uuid = generator.generateUUID();
        assertEquals(2, uuid.variant(), "UUID variant should be 2 (DCE)");
    }

    @Test
    public void testNodeIdentifierIsPreserved() {
        byte nodeId = (byte) 42;
        UUIDv7Generator generator = new UUIDv7Generator(nodeId);
        UUID uuid = generator.generateUUID();
        assertEquals(nodeId, UUIDv7Generator.extractNodeIdentifier(uuid),
                "Node identifier should be preserved in the generated UUID");
    }

    @Test
    public void testTimestampIsMonotonic() {
        UUIDv7Generator generator = new UUIDv7Generator((byte) 1);
        UUID uuid1 = generator.generateUUID();
        UUID uuid2 = generator.generateUUID();
        
        long timestamp1 = UUIDv7Generator.extractTimestamp(uuid1);
        long timestamp2 = UUIDv7Generator.extractTimestamp(uuid2);
        
        assertTrue(timestamp2 >= timestamp1, 
                "Sequential UUIDs should have monotonically increasing timestamps");
    }

    @Test
    public void testTimestampAccuracy() {
        UUIDv7Generator generator = new UUIDv7Generator((byte) 1);
        long beforeGeneration = System.currentTimeMillis();
        UUID uuid = generator.generateUUID();
        long afterGeneration = System.currentTimeMillis();
        
        long extractedTimestamp = UUIDv7Generator.extractTimestamp(uuid);
        
        assertTrue(extractedTimestamp >= beforeGeneration && extractedTimestamp <= afterGeneration,
                "Extracted timestamp should be between the before and after timestamps");
    }

    @Test
    public void testUniqueness() {
        UUIDv7Generator generator = new UUIDv7Generator((byte) 1);
        Set<UUID> uuids = new HashSet<>();
        int count = 10000;
        
        for (int i = 0; i < count; i++) {
            UUID uuid = generator.generateUUID();
            assertTrue(uuids.add(uuid), 
                    "Generated UUIDs should be unique");
        }
        
        assertEquals(count, uuids.size(), 
                "Should have generated exactly " + count + " unique UUIDs");
    }

    @Test
    public void testDifferentNodeIdentifiers() {
        UUIDv7Generator generator1 = new UUIDv7Generator((byte) 1);
        UUIDv7Generator generator2 = new UUIDv7Generator((byte) 2);
        
        UUID uuid1 = generator1.generateUUID();
        UUID uuid2 = generator2.generateUUID();
        
        assertNotEquals(
            UUIDv7Generator.extractNodeIdentifier(uuid1),
            UUIDv7Generator.extractNodeIdentifier(uuid2),
            "UUIDs from different generators should have different node identifiers"
        );
    }
}