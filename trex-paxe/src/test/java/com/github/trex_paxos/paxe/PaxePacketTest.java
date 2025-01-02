package com.github.trex_paxos.paxe;

import org.junit.jupiter.api.Test;
import javax.crypto.SecretKey;
import javax.crypto.KeyGenerator;
import java.security.GeneralSecurityException;
import java.util.Arrays;
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
        Channel channel = new Channel((byte) 3);
        byte flags = 0x04;
        byte[] nonce = new byte[PaxePacket.NONCE_SIZE];
        byte[] authTag = new byte[PaxePacket.AUTH_TAG_SIZE];
        byte[] payload = "Test payload".getBytes();

        PaxePacket packet = new PaxePacket(from, to, channel, flags, nonce, authTag, payload);

        assertEquals(from, packet.from());
        assertEquals(to, packet.to());
        assertEquals(channel, packet.channel());
        assertEquals(flags, packet.flags());
        assertArrayEquals(nonce, packet.nonce());
        assertArrayEquals(authTag, packet.authTag());
        assertArrayEquals(payload, packet.payload());
    }

    @Test
    void testConstructorWithInvalidNonceSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new PaxePacket(new NodeId((short) 1), new NodeId((short) 2), new Channel((byte) 3),
                        (byte) 0, new byte[PaxePacket.NONCE_SIZE - 1], new byte[PaxePacket.AUTH_TAG_SIZE],
                        new byte[0]));
    }

    @Test
    void testConstructorWithInvalidAuthTagSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new PaxePacket(new NodeId((short) 1), new NodeId((short) 2), new Channel((byte) 3),
                        (byte) 0, new byte[PaxePacket.NONCE_SIZE], new byte[PaxePacket.AUTH_TAG_SIZE - 1],
                        new byte[0]));
    }

    @Test
    void testToBytes() {
        NodeId from = new NodeId((short) 1);
        NodeId to = new NodeId((short) 2);
        Channel channel = new Channel((byte) 3);
        byte flags = 0x04;
        byte[] nonce = new byte[PaxePacket.NONCE_SIZE];
        byte[] authTag = new byte[PaxePacket.AUTH_TAG_SIZE];
        byte[] payload = "Test payload".getBytes();

        PaxePacket packet = new PaxePacket(from, to, channel, flags, nonce, authTag, payload);
        byte[] bytes = packet.toBytes();

        assertEquals(PaxePacket.HEADER_SIZE + PaxePacket.NONCE_SIZE + PaxePacket.AUTH_TAG_SIZE + payload.length,
                bytes.length);
        assertEquals((short) ((bytes[0] << 8) | (bytes[1] & 0xFF)), from.id());
        assertEquals((short) ((bytes[2] << 8) | (bytes[3] & 0xFF)), to.id());
        assertEquals(channel.value(), bytes[4]);
        assertEquals(flags, bytes[5]);
        assertArrayEquals(nonce, Arrays.copyOfRange(bytes, 6, 6 + PaxePacket.NONCE_SIZE));
        assertArrayEquals(authTag, Arrays.copyOfRange(bytes, 6 + PaxePacket.NONCE_SIZE,
                6 + PaxePacket.NONCE_SIZE + PaxePacket.AUTH_TAG_SIZE));
        assertArrayEquals(payload,
                Arrays.copyOfRange(bytes, 6 + PaxePacket.NONCE_SIZE + PaxePacket.AUTH_TAG_SIZE, bytes.length));
    }

    @Test
    void testFromBytes() {
        NodeId from = new NodeId((short) 1);
        NodeId to = new NodeId((short) 2);
        Channel channel = new Channel((byte) 3);
        byte flags = 0x04;
        byte[] nonce = new byte[PaxePacket.NONCE_SIZE];
        byte[] authTag = new byte[PaxePacket.AUTH_TAG_SIZE];
        byte[] payload = "Test payload".getBytes();

        PaxePacket originalPacket = new PaxePacket(from, to, channel, flags, nonce, authTag, payload);
        byte[] bytes = originalPacket.toBytes();

        PaxePacket reconstructedPacket = PaxePacket.fromBytes(bytes);

        assertEquals(originalPacket, reconstructedPacket);
    }

    @Test
    void testAuthenticatedData() {
        NodeId from = new NodeId((short) 1);
        NodeId to = new NodeId((short) 2);
        Channel channel = new Channel((byte) 3);
        PaxePacket packet = new PaxePacket(from, to, channel, (byte) 0, new byte[PaxePacket.NONCE_SIZE],
                new byte[PaxePacket.AUTH_TAG_SIZE], new byte[0]);

        byte[] authenticatedData = packet.authenticatedData();

        assertEquals(PaxePacket.AUTHENCIATED_DATA_SIZE, authenticatedData.length);
        assertEquals((byte) (from.id() >> 8), authenticatedData[0]);
        assertEquals((byte) from.id(), authenticatedData[1]);
        assertEquals((byte) (to.id() >> 8), authenticatedData[2]);
        assertEquals((byte) to.id(), authenticatedData[3]);
        assertEquals(channel.value(), authenticatedData[4]);
    }

    @Test
    void testEncryptDecrypt() throws GeneralSecurityException {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(AES_KEY_SIZE);
        SecretKey key = keyGen.generateKey();

        // Create input data
        NodeId from = new NodeId((short) 1);
        PaxeMessage originalMessage = new PaxeMessage(
                from,
                new NodeId((short) 2),
                new Channel((byte) 1),
                "Hello, World!".getBytes());

        // Perform encryption and decryption
        PaxePacket encryptedPacket = PaxePacket.encrypt(originalMessage, from, key.getEncoded());
        PaxeMessage decryptedMessage = PaxePacket.decrypt(encryptedPacket, key.getEncoded());

        // Assert equality
        assertEquals(originalMessage, decryptedMessage);
    }

}
