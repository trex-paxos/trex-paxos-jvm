package com.github.trex_paxos.demo;

import java.security.SecureRandom;
import java.util.UUID;

import com.github.f4b6a3.uuid.UuidCreator;

public class UUIDv7Generator {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte nodeIdentifier;

    public UUIDv7Generator(byte nodeIdentifier) {
        this.nodeIdentifier = nodeIdentifier;
    }

    protected long getCurrentTimestamp() {
        return System.currentTimeMillis();
    }

    protected byte[] getRandomBytes(int size) {
        byte[] randomBytes = new byte[size];
        RANDOM.nextBytes(randomBytes);
        return randomBytes;
    }

    public UUID generateUUID() {
        long timestamp = getCurrentTimestamp();
        
        // Get the most significant bits
        long msb = constructMsb(timestamp);
        
        // Get the least significant bits
        //long lsb = constructLsb(randomBytes);
        
        byte[] data = getRandomBytes(16);
        data[6]  &= 0x0f;  /* clear version        */
        data[6]  |= 0x40;  /* set to version 4     */
        data[8]  &= 0x3f;  /* clear variant        */
        data[8]  |= (byte) 0x80;  /* set to IETF variant  */
        //long msb = 0;
        long lsb = 0;
        assert data.length == 16 : "data must be 16 bytes in length";
        for (int i=8; i<16; i++)
            lsb = (lsb << 8) | (data[i] & 0xff);
        return new UUID(msb, lsb);
    }

    public static void main(String[] args) {
        UUIDv7Generator generator = new UUIDv7Generator((byte)1);
        UUID uuid = generator.generateUUID2();
        System.out.println("Generated UUID: " + uuid);
    }

    public UUID generateUUID2() {
        UUID uuid = UuidCreator.getTimeOrderedEpoch();
        return uuid; 
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