package com.github.trex_paxos.paxe;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public record PaxePacket(
        NodeId from,
        NodeId to,
        Channel channel,
        byte flags,
        byte[] nonce,
        byte[] authTag,
        byte[] payload) {

    public static final int HEADER_SIZE = 6; // from(2), to(2), channel(1), flags(1)
    public static final int AUTHENCIATED_DATA_SIZE = 5; // from(2), to(2), channel(1)
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
        buffer.putShort(from.id());
        buffer.putShort(to.id());
        buffer.put(channel.value());
        buffer.put(flags);
        buffer.put(nonce);
        buffer.put(authTag);
        buffer.put(payload);
        return buffer.array();
    }

    public static PaxePacket fromBytes(byte[] bytes) {
        var buffer = ByteBuffer.wrap(bytes);
        var from = new NodeId(buffer.getShort());
        var to = new NodeId(buffer.getShort());
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
        var buffer = ByteBuffer.allocate(AUTHENCIATED_DATA_SIZE);
        buffer.putShort(from.id());
        buffer.putShort(to.id());
        buffer.put(channel.value());
        return buffer.array();
    }

    public byte[] ciphertext() {
        return payload; // Encrypted payload is the ciphertext
    }

    static PaxeMessage decrypt(PaxePacket packet, byte[] key) {
        try {
            // Real decryption using AES-GCM
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            var gcmSpec = new GCMParameterSpec(
                PaxePacket.AUTH_TAG_SIZE * 8, packet.nonce());
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), gcmSpec);
            cipher.updateAAD(packet.authenticatedData());
            
            // Combine ciphertext and tag for decryption
            var combined = new byte[packet.payload().length + packet.authTag().length];
            System.arraycopy(packet.payload(), 0, combined, 0, packet.payload().length);
            System.arraycopy(packet.authTag(), 0, combined, packet.payload().length, packet.authTag().length);
            
            var decrypted = cipher.doFinal(combined);
            return PaxeMessage.deserialize(packet.from(), packet.to(), packet.channel(), decrypted);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("Decryption failed", e);
        }
    }

    public static PaxePacket encrypt(PaxeMessage message, NodeId from, byte[] key) throws GeneralSecurityException {
        var nonce = new byte[NONCE_SIZE];
        ThreadLocalRandom.current().nextBytes(nonce);
    
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        var gcmSpec = new GCMParameterSpec(AUTH_TAG_SIZE * 8, nonce);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), gcmSpec);
    
        var tempPacket = new PaxePacket(
                from,
                message.to(),
                message.channel(),
                (byte) 0,
                nonce,
                new byte[AUTH_TAG_SIZE],
                message.serialize());
    
        cipher.updateAAD(tempPacket.authenticatedData());
        var ciphertext = cipher.doFinal(message.serialize());
    
        // Extract the authentication tag from the end of the ciphertext
        var authTag = new byte[AUTH_TAG_SIZE];
        System.arraycopy(ciphertext, ciphertext.length - AUTH_TAG_SIZE, authTag, 0, AUTH_TAG_SIZE);
    
        // Remove the authentication tag from the ciphertext
        var actualCiphertext = new byte[ciphertext.length - AUTH_TAG_SIZE];
        System.arraycopy(ciphertext, 0, actualCiphertext, 0, ciphertext.length - AUTH_TAG_SIZE);
    
        return new PaxePacket(
                from,
                message.to(),
                message.channel(),
                (byte) 0,
                nonce,
                authTag,
                actualCiphertext);
    }    

        @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaxePacket that)) return false;
        return flags == that.flags
                && from.equals(that.from)
                && to.equals(that.to)
                && channel.equals(that.channel)
                && Arrays.equals(nonce, that.nonce)
                && Arrays.equals(authTag, that.authTag)
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(from, to, channel, flags);
        result = 31 * result + Arrays.hashCode(nonce);
        result = 31 * result + Arrays.hashCode(authTag);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }
}