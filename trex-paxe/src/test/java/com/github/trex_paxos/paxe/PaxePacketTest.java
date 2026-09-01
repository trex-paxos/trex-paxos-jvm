// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe;

import com.github.trex_paxos.network.Channel;
import com.github.trex_paxos.NodeId;
import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PaxePacketTest {

  private static final int AES_KEY_SIZE = 256;
  private static final SecureRandom RANDOM = new SecureRandom();

  private byte[] randomClusterKey() throws GeneralSecurityException {
    KeyGenerator keyGen = KeyGenerator.getInstance("AES");
    keyGen.init(AES_KEY_SIZE);
    SecretKey key = keyGen.generateKey();
    assertEquals(ClusterKeyManager.CLUSTER_PSK_SIZE, key.getEncoded().length);
    return key.getEncoded();
  }

  @Test
  void prefixIsByteExactBigEndianLayout() throws GeneralSecurityException {
    NodeId from = new NodeId((short) 0x0102);
    NodeId to = new NodeId((short) 0x0304);
    Channel channel = new Channel(0x05060708);
    byte epoch = (byte) 0x09;
    byte[] key = randomClusterKey();

    PaxePacket packet = PaxePacket.seal(from, to, channel, epoch, new byte[]{0x42}, key);
    byte[] datagram = packet.toDatagram();

    assertEquals(PaxePacket.FRAME_OVERHEAD + 1, datagram.length);
    assertArrayEquals(new byte[]{
        0x01, 0x02,
        0x03, 0x04,
        0x05, 0x06, 0x07, 0x08,
        0x09
    }, Arrays.copyOfRange(datagram, 0, PaxePacket.PREFIX_SIZE));
  }

  @Test
  void channelRoundTripsAllU32BitPatterns() throws GeneralSecurityException {
    int[] channelBits = {0, 1, 65535, 0x7FFFFFFF, 0x80000000, 0xFFFFFFFF};
    byte[] key = randomClusterKey();
    byte[] plaintext = "channel-test".getBytes();

    for (int bits : channelBits) {
      Channel channel = new Channel(bits);
      PaxePacket sealed = PaxePacket.seal(new NodeId((short) 1), new NodeId((short) 2), channel, (byte) 0, plaintext, key);
      PaxePacket parsed = PaxePacket.fromDatagram(sealed.toDatagram());

      assertEquals(channel, parsed.channel(), "channel bits 0x" + Integer.toHexString(bits));
      assertArrayEquals(plaintext, parsed.decrypt(key));
    }
  }

  @Test
  void frameOverheadIsThirtySevenBytes() throws GeneralSecurityException {
    byte[] key = randomClusterKey();
    PaxePacket packet = PaxePacket.seal(
        new NodeId((short) 1),
        new NodeId((short) 2),
        new Channel(1),
        (byte) 0,
        new byte[0],
        key);

    assertEquals(37, PaxePacket.FRAME_OVERHEAD);
    assertEquals(37, packet.toDatagram().length);
  }

  @Test
  void plaintextLengthDerivedFromDatagramSize() throws GeneralSecurityException {
    byte[] key = randomClusterKey();
    byte[] plaintext = new byte[128];
    RANDOM.nextBytes(plaintext);

    PaxePacket packet = PaxePacket.seal(new NodeId((short) 1), new NodeId((short) 2), new Channel(3), (byte) 0, plaintext, key);
    byte[] datagram = packet.toDatagram();

    assertEquals(PaxePacket.FRAME_OVERHEAD + plaintext.length, datagram.length);
    assertArrayEquals(plaintext, PaxePacket.fromDatagram(datagram).decrypt(key));
  }

  @Test
  void encryptDecryptRoundTrip() throws GeneralSecurityException {
    byte[] key = randomClusterKey();
    byte[] plaintext = "Hello, World!".getBytes();

    PaxePacket packet = PaxePacket.seal(new NodeId((short) 1), new NodeId((short) 2), new Channel(1), (byte) 0, plaintext, key);
    assertArrayEquals(plaintext, packet.decrypt(key));
  }

  @Test
  void rejectsTruncatedDatagram() throws GeneralSecurityException {
    byte[] key = randomClusterKey();
    PaxePacket packet = PaxePacket.seal(new NodeId((short) 1), new NodeId((short) 2), new Channel(1), (byte) 0, new byte[8], key);
    byte[] truncated = Arrays.copyOf(packet.toDatagram(), PaxePacket.FRAME_OVERHEAD - 1);

    assertThrows(SecurityException.class, () -> PaxePacket.fromDatagram(truncated));
  }

  @Test
  void rejectsExtendedDatagram() throws GeneralSecurityException {
    byte[] key = randomClusterKey();
    PaxePacket packet = PaxePacket.seal(new NodeId((short) 1), new NodeId((short) 2), new Channel(1), (byte) 0, new byte[8], key);
    byte[] extended = Arrays.copyOf(packet.toDatagram(), packet.toDatagram().length + 1);
    extended[extended.length - 1] = 0x7F;

    PaxePacket parsed = PaxePacket.fromDatagram(extended);
    assertThrows(SecurityException.class, () -> parsed.decrypt(key));
  }

  @Test
  void tamperWithEveryPrefixAndPayloadByteFailsAuthentication() throws GeneralSecurityException {
    byte[] key = randomClusterKey();
    PaxePacket packet = PaxePacket.seal(new NodeId((short) 1), new NodeId((short) 2), new Channel(0xAABBCCDD), (byte) 0xEE, new byte[16], key);
    byte[] datagram = packet.toDatagram();

  for (int offset = 0; offset < datagram.length; offset++) {
      byte[] tampered = Arrays.copyOf(datagram, datagram.length);
      tampered[offset] ^= 0x01;
      PaxePacket parsed = PaxePacket.fromDatagram(tampered);
      assertThrows(SecurityException.class, () -> parsed.decrypt(key), "offset " + offset);
    }
  }

  @Test
  void authenticatedDataCoversFullNineBytePrefix() throws GeneralSecurityException {
    byte[] key = randomClusterKey();
    NodeId from = new NodeId((short) 9);
    NodeId to = new NodeId((short) 8);
    Channel channel = new Channel(0x12345678);
    byte epoch = (byte) 0xAB;

    PaxePacket packet = PaxePacket.seal(from, to, channel, epoch, new byte[]{1, 2, 3}, key);
    byte[] tamperedEpoch = packet.toDatagram();
    tamperedEpoch[8] ^= 0x01;

    PaxePacket parsed = PaxePacket.fromDatagram(tamperedEpoch);
    assertThrows(SecurityException.class, () -> parsed.decrypt(key));
  }

  @Test
  void rejectsWrongClusterKey() throws GeneralSecurityException {
    byte[] key = randomClusterKey();
    byte[] otherKey = randomClusterKey();
    PaxePacket packet = PaxePacket.seal(new NodeId((short) 1), new NodeId((short) 2), new Channel(1), (byte) 0, new byte[4], key);
    assertThrows(SecurityException.class, () -> packet.decrypt(otherKey));
  }

  @Test
  void rejectsInvalidNonceAndTagSizes() {
    assertThrows(IllegalArgumentException.class, () -> new PaxePacket(
        new NodeId((short) 1), new NodeId((short) 2), new Channel(1), (byte) 0,
        new byte[11], new byte[0], new byte[16]));
    assertThrows(IllegalArgumentException.class, () -> new PaxePacket(
        new NodeId((short) 1), new NodeId((short) 2), new Channel(1), (byte) 0,
        new byte[12], new byte[0], new byte[15]));
  }

  @Test
  void prefixBytesMatchDatagramPrefix() throws GeneralSecurityException {
    byte[] key = randomClusterKey();
    PaxePacket packet = PaxePacket.seal(
        new NodeId((short) 1), new NodeId((short) 2), new Channel(0x01020304), (byte) 5, new byte[]{9}, key);
    assertArrayEquals(packet.prefixBytes(), Arrays.copyOfRange(packet.toDatagram(), 0, PaxePacket.PREFIX_SIZE));
  }

  @Test
  void rejectsOversizedPlaintext() throws GeneralSecurityException {
    byte[] key = randomClusterKey();
    assertThrows(IllegalArgumentException.class, () -> PaxePacket.seal(
        new NodeId((short) 1),
        new NodeId((short) 2),
        new Channel(1),
        (byte) 0,
        new byte[PaxeProtocol.MAX_PLAINTEXT_SIZE + 1],
        key));
  }

  @Test
  void equalsAndHashCodeUseValueSemantics() throws GeneralSecurityException {
    byte[] key = randomClusterKey();
    PaxePacket one = PaxePacket.seal(new NodeId((short) 1), new NodeId((short) 2), new Channel(3), (byte) 0, new byte[]{1}, key);
    PaxePacket two = PaxePacket.fromDatagram(one.toDatagram());
    assertEquals(one, two);
    assertEquals(one.hashCode(), two.hashCode());
  }
}
