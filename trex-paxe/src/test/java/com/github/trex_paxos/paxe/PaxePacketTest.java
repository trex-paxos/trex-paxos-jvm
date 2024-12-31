package com.github.trex_paxos.paxe;
import org.junit.jupiter.api.Test;
import javax.crypto.SecretKey;
import javax.crypto.KeyGenerator;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class PaxePacketTest {

    private static final int AES_KEY_SIZE = 256;

    @Test
    void testConstructorAndGetters() {
        NodeId from = new NodeId((byte) 1);
        NodeId to = new NodeId((byte) 2);
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
        assertThrows(IllegalArgumentException.class, () -> 
            new PaxePacket(new NodeId((byte) 1), new NodeId((byte) 2), new Channel((byte) 3), 
                (byte) 0, new byte[PaxePacket.NONCE_SIZE - 1], new byte[PaxePacket.AUTH_TAG_SIZE], new byte[0]));
    }

    @Test
    void testConstructorWithInvalidAuthTagSize() {
        assertThrows(IllegalArgumentException.class, () -> 
            new PaxePacket(new NodeId((byte) 1), new NodeId((byte) 2), new Channel((byte) 3), 
                (byte) 0, new byte[PaxePacket.NONCE_SIZE], new byte[PaxePacket.AUTH_TAG_SIZE - 1], new byte[0]));
    }

    @Test
    void testToBytes() {
        NodeId from = new NodeId((byte) 1);
        NodeId to = new NodeId((byte) 2);
        Channel channel = new Channel((byte) 3);
        byte flags = 0x04;
        byte[] nonce = new byte[PaxePacket.NONCE_SIZE];
        byte[] authTag = new byte[PaxePacket.AUTH_TAG_SIZE];
        byte[] payload = "Test payload".getBytes();

        PaxePacket packet = new PaxePacket(from, to, channel, flags, nonce, authTag, payload);
        byte[] bytes = packet.toBytes();

        assertEquals(PaxePacket.HEADER_SIZE + PaxePacket.NONCE_SIZE + PaxePacket.AUTH_TAG_SIZE + payload.length, bytes.length);
        assertEquals(from.value(), bytes[0]);
        assertEquals(to.value(), bytes[1]);
        assertEquals(channel.value(), bytes[2]);
        assertEquals(flags, bytes[3]);
        assertArrayEquals(nonce, Arrays.copyOfRange(bytes, 4, 4 + PaxePacket.NONCE_SIZE));
        assertArrayEquals(authTag, Arrays.copyOfRange(bytes, 4 + PaxePacket.NONCE_SIZE, 4 + PaxePacket.NONCE_SIZE + PaxePacket.AUTH_TAG_SIZE));
        assertArrayEquals(payload, Arrays.copyOfRange(bytes, 4 + PaxePacket.NONCE_SIZE + PaxePacket.AUTH_TAG_SIZE, bytes.length));
    }

    @Test
    void testFromBytes() {
        NodeId from = new NodeId((byte) 1);
        NodeId to = new NodeId((byte) 2);
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
        NodeId from = new NodeId((byte) 1);
        NodeId to = new NodeId((byte) 2);
        Channel channel = new Channel((byte) 3);
        PaxePacket packet = new PaxePacket(from, to, channel, (byte) 0, new byte[PaxePacket.NONCE_SIZE], new byte[PaxePacket.AUTH_TAG_SIZE], new byte[0]);

        byte[] authenticatedData = packet.authenticatedData();

        assertEquals(3, authenticatedData.length);
        assertEquals(from.value(), authenticatedData[0]);
        assertEquals(to.value(), authenticatedData[1]);
        assertEquals(channel.value(), authenticatedData[2]);
    }

    @Test
    void testEncryptDecrypt() throws GeneralSecurityException {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(AES_KEY_SIZE);
        SecretKey key = keyGen.generateKey();
    
        // Create input data
        NodeId from = new NodeId((byte) 1);
        PaxeMessage originalMessage = new PaxeMessage(
            from,
            new NodeId((byte) 2),
            new Channel((byte) 1),
            "Hello, World!".getBytes()
        );
    
        // Perform encryption and decryption
        PaxePacket encryptedPacket = PaxePacket.encrypt(originalMessage, from, key.getEncoded());
        PaxeMessage decryptedMessage = PaxePacket.decrypt(encryptedPacket, key.getEncoded());
    
        // Assert equality
        assertEquals(originalMessage, decryptedMessage);
    }

}
