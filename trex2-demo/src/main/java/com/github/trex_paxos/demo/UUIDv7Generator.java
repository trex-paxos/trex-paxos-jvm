package com.github.trex_paxos.demo;

import java.security.SecureRandom;
import java.util.UUID;

public class UUIDv7Generator {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte nodeIdentifier;

    public UUIDv7Generator(byte nodeIdentifier) {
        this.nodeIdentifier = nodeIdentifier;
    }

    protected long getCurrentTimestamp() {
        return System.currentTimeMillis();
    }

    protected byte[] getRandomBytes() {
        byte[] randomBytes = new byte[7];
        RANDOM.nextBytes(randomBytes);
        return randomBytes;
    }

    public UUID generateUUID() {
        long timestamp = getCurrentTimestamp();
        byte[] randomBytes = getRandomBytes();
        
        // Get the most significant bits
        long msb = constructMsb(timestamp);
        
        // Get the least significant bits
        long lsb = constructLsb(randomBytes);
        
        return new UUID(msb, lsb);
    }

    protected long constructMsb(long timestamp) {
        long msb = 0L;
        
        // Set time bits
        msb |= (timestamp << 16);
        
        // Set version 7
        msb &= ~(0xfL << 12);  // Clear version bits
        msb |= (0x7L << 12);   // Set version to 7
        
        return msb;
    }

    protected long constructLsb(byte[] randomBytes) {
        // Construct LSB with node identifier in most significant byte
        long lsb = ((long)(nodeIdentifier & 0xFF)) << 56;
        for (int i = 0; i < 7; i++) {
            lsb |= ((long)(randomBytes[i] & 0xFF) << (48 - (i * 8)));
        }
        
        // Set variant bits (2 MSB to 10)
        lsb = (lsb & ~(0xC000000000000000L)) | 0x8000000000000000L;
        
        return lsb;
    }

    public static long extractTimestamp(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long timestamp = (msb & 0x0000FFFFFFFFFFFFL) >>> 16;
        timestamp |= (msb & 0xFFFF000000000000L) >>> 32;
        return timestamp;
    }

    public static byte extractNodeIdentifier(UUID uuid) {
        return (byte)((uuid.getLeastSignificantBits() >>> 56) & 0xFF);
    }
}