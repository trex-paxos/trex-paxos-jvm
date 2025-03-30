package com.github.trex_paxos.paxe;

import org.junit.jupiter.api.Test;

import com.github.trex_paxos.network.Channel;
import com.github.trex_paxos.NodeId;

import javax.crypto.SecretKey;
import javax.crypto.KeyGenerator;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PaxePacketTest {
    static {
        System.setProperty(SRPUtils.class.getName() + ".useHash", "SHA-1");
    }

    private static final int AES_KEY_SIZE = 256;

    @Test
    void testConstructorAndGetters() {
        NodeId from = new NodeId((short) 1);
        NodeId to = new NodeId((short) 2);
        Channel channel = new Channel((short) 3);
        byte[] nonce = new byte[PaxePacket.NONCE_SIZE];
        byte[] authTag = new byte[PaxePacket.AUTH_TAG_SIZE];
        byte[] payload = "Test payload".getBytes();

        PaxePacket packet = new PaxePacket(from, to, channel, Optional.of(nonce), Optional.of(authTag), payload);

        assertEquals(from, packet.from());
        assertEquals(to, packet.to());
        assertEquals(channel, packet.channel());
        assertArrayEquals(nonce, packet.nonce().orElseThrow());
        assertArrayEquals(authTag, packet.authTag().orElseThrow());
        assertArrayEquals(payload, packet.payload());
    }

    @Test
    void testConstructorWithInvalidNonceSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new PaxePacket(new NodeId((short) 1), new NodeId((short) 2), new Channel((short) 3),
                        Optional.of(new byte[PaxePacket.NONCE_SIZE - 1]), 
                        Optional.of(new byte[PaxePacket.AUTH_TAG_SIZE]),
                        new byte[0]));
    }

    @Test
    void testConstructorWithInvalidAuthTagSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new PaxePacket(new NodeId((short) 1), new NodeId((short) 2), new Channel((short) 3),
                        Optional.of(new byte[PaxePacket.NONCE_SIZE]), 
                        Optional.of(new byte[PaxePacket.AUTH_TAG_SIZE - 1]),
                        new byte[0]));
    }

    @Test
    void testToBytes() {
        NodeId from = new NodeId((short) 1);
        NodeId to = new NodeId((short) 2);
        Channel channel = new Channel((short) 3);
        byte[] nonce = new byte[PaxePacket.NONCE_SIZE];
        byte[] authTag = new byte[PaxePacket.AUTH_TAG_SIZE];
        byte[] payload = "Test payload".getBytes();

        PaxePacket packet = new PaxePacket(from, to, channel, Optional.of(nonce), Optional.of(authTag), payload);
        byte[] bytes = packet.toBytes();

        assertEquals(PaxePacket.HEADER_SIZE + PaxePacket.NONCE_SIZE + PaxePacket.AUTH_TAG_SIZE + payload.length,
                bytes.length);
        assertEquals(from.id(), (short) ((bytes[0] << 8) | (bytes[1] & 0xFF)));
        assertEquals(to.id(), (short) ((bytes[2] << 8) | (bytes[3] & 0xFF)));
        assertEquals(channel.id(), (short) ((bytes[4] << 8) | (bytes[5] & 0xFF)));
        assertEquals(payload.length, ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF));
        assertArrayEquals(nonce, Arrays.copyOfRange(bytes, 8, 8 + PaxePacket.NONCE_SIZE));
        assertArrayEquals(authTag, Arrays.copyOfRange(bytes, 8 + PaxePacket.NONCE_SIZE,
                8 + PaxePacket.NONCE_SIZE + PaxePacket.AUTH_TAG_SIZE));
        assertArrayEquals(payload,
                Arrays.copyOfRange(bytes, 8 + PaxePacket.NONCE_SIZE + PaxePacket.AUTH_TAG_SIZE, bytes.length));
    }

    @Test
    void testFromBytes() {
        NodeId from = new NodeId((short) 1);
        NodeId to = new NodeId((short) 2);
        Channel channel = new Channel((short) 3);
        byte[] nonce = new byte[PaxePacket.NONCE_SIZE];
        byte[] authTag = new byte[PaxePacket.AUTH_TAG_SIZE];
        byte[] payload = "Test payload".getBytes();

        PaxePacket originalPacket = new PaxePacket(from, to, channel, Optional.of(nonce), Optional.of(authTag), payload);
        byte[] bytes = originalPacket.toBytes();

        PaxePacket reconstructedPacket = PaxePacket.fromBytes(bytes);

        assertEquals(originalPacket, reconstructedPacket);
    }

    @Test
    void testAuthenticatedData() {
        NodeId from = new NodeId((short) 1);
        NodeId to = new NodeId((short) 2);
        Channel channel = new Channel((short) 3);
        PaxePacket packet = new PaxePacket(from, to, channel, Optional.empty(), Optional.empty(), new byte[0]);

        byte[] authenticatedData = packet.authenticatedData();

        assertEquals(PaxePacket.AUTHENTICATED_DATA_SIZE, authenticatedData.length);
        assertEquals((byte) (from.id() >> 8), authenticatedData[0]);
        assertEquals((byte) from.id(), authenticatedData[1]);
        assertEquals((byte) (to.id() >> 8), authenticatedData[2]);
        assertEquals((byte) to.id(), authenticatedData[3]);
        assertEquals((byte) (channel.id() >> 8), authenticatedData[4]);
        assertEquals((byte) channel.id(), authenticatedData[5]);
    }

    @Test
    void testEncryptDecrypt() throws GeneralSecurityException {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(AES_KEY_SIZE);
        SecretKey key = keyGen.generateKey();

        NodeId from = new NodeId((short) 1);
        PaxeMessage originalMessage = new PaxeMessage(
                from,
                new NodeId((short) 2),
                new Channel((short) 1),
                "Hello, World!".getBytes());

        PaxePacket encryptedPacket = PaxePacket.encrypt(originalMessage, from, key.getEncoded());
        PaxeMessage decryptedMessage = PaxePacket.decrypt(encryptedPacket, key.getEncoded());

        assertEquals(originalMessage, decryptedMessage);
    }
}
