package com.github.trex_paxos.paxe;

import java.nio.ByteBuffer;
import java.util.Objects;

public record PaxePacket(
    NodeId from,
    NodeId to, 
    Channel channel,
    byte flags,
    byte[] nonce,
    byte[] authTag,
    byte[] payload
) {
    public static final int HEADER_SIZE = 4; // from, to, channel, flags
    public static final int NONCE_SIZE = 12;
    public static final int AUTH_TAG_SIZE = 16;
    
    public PaxePacket {
        Objects.requireNonNull(from, "from cannot be null");
        Objects.requireNonNull(to, "to cannot be null");
        Objects.requireNonNull(channel, "channel cannot be null");
        Objects.requireNonNull(nonce, "nonce cannot be null");
        Objects.requireNonNull(authTag, "authTag cannot be null");
        Objects.requireNonNull(payload, "payload cannot be null");
        
        if (nonce.length != NONCE_SIZE) 
            throw new IllegalArgumentException("Invalid nonce size");
        if (authTag.length != AUTH_TAG_SIZE)
            throw new IllegalArgumentException("Invalid auth tag size");
    }
    
    public byte[] toBytes() {
        var size = HEADER_SIZE + NONCE_SIZE + AUTH_TAG_SIZE + payload.length;
        var buffer = ByteBuffer.allocate(size);
        buffer.put(from.value());
        buffer.put(to.value());
        buffer.put(channel.value());
        buffer.put(flags);
        buffer.put(nonce);
        buffer.put(authTag);
        buffer.put(payload);
        return buffer.array();
    }
    
    public static PaxePacket fromBytes(byte[] bytes) {
        var buffer = ByteBuffer.wrap(bytes);
        var from = new NodeId(buffer.get());
        var to = new NodeId(buffer.get());
        var channel = new Channel(buffer.get());
        var flags = buffer.get();
        
        var nonce = new byte[NONCE_SIZE];
        buffer.get(nonce);
        
        var authTag = new byte[AUTH_TAG_SIZE];
        buffer.get(authTag);
        
        var payload = new byte[buffer.remaining()];
        buffer.get(payload);
        
        return new PaxePacket(from, to, channel, flags, nonce, authTag, payload);
    }
    public byte[] authenticatedData() {
        var buffer = ByteBuffer.allocate(3);
        buffer.put(from.value());
        buffer.put(to.value());
        buffer.put(channel.value());
        return buffer.array();
    }

    public byte[] ciphertext() {
        return payload; // Encrypted payload is the ciphertext
    }
}