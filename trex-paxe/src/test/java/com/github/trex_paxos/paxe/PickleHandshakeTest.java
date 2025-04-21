// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe;

import com.github.trex_paxos.NodeId;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class PickleHandshakeTest {
  static {
    System.setProperty(SRPUtils.class.getName() + ".useHash", "SHA-1");
  }

  private static final int BUFFER_SIZE = 1024;

  @Test
  void shouldPickleAndUnpickleRequest() {
    NodeId from = new NodeId((short) 1);
    byte[] publicKey = new byte[]{1, 2, 3, 4};
    var request = new SessionKeyManager.KeyMessage.KeyHandshakeRequest(from, publicKey);

    ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
    int startPosition = buffer.position();

    PickleHandshake.instance.serialize(request, buffer);

    int bytesWritten = buffer.position() - startPosition;
    buffer.flip();

    SessionKeyManager.KeyMessage unpickled = PickleHandshake.unpickle(buffer);

    assertInstanceOf(SessionKeyManager.KeyMessage.KeyHandshakeRequest.class, unpickled);
    var unpackedRequest = (SessionKeyManager.KeyMessage.KeyHandshakeRequest) unpickled;
    assertEquals(from, unpackedRequest.from());
    assertArrayEquals(publicKey, unpackedRequest.publicKey());
    assertEquals(bytesWritten, PickleHandshake.instance.sizeOf(request));
  }

  @Test
  void shouldPickleAndUnpickleResponse() {
    NodeId from = new NodeId((short) 2);
    byte[] publicKey = new byte[]{5, 6, 7, 8};
    var response = new SessionKeyManager.KeyMessage.KeyHandshakeResponse(from, publicKey);

    ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
    int startPosition = buffer.position();

    PickleHandshake.instance.serialize(response, buffer);

    int bytesWritten = buffer.position() - startPosition;
    buffer.flip();

    SessionKeyManager.KeyMessage unpickled = PickleHandshake.unpickle(buffer);

    assertInstanceOf(SessionKeyManager.KeyMessage.KeyHandshakeResponse.class, unpickled);
    var unpackedResponse = (SessionKeyManager.KeyMessage.KeyHandshakeResponse) unpickled;
    assertEquals(from, unpackedResponse.from());
    assertArrayEquals(publicKey, unpackedResponse.publicKey());
    assertEquals(bytesWritten, PickleHandshake.instance.sizeOf(response));
  }

  @Test
  void shouldFailOnInvalidType() {
    ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
    buffer.put((byte) 99); // Invalid type 99
    buffer.putShort((short) 1); // NodeId
    buffer.putInt(0); // Zero length public key
    buffer.flip();

    assertThrows(IllegalArgumentException.class, () -> PickleHandshake.unpickle(buffer));
  }
}
