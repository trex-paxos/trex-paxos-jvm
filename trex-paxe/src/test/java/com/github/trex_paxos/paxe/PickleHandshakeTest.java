package com.github.trex_paxos.paxe;

import com.github.trex_paxos.network.NodeId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PickleHandshakeTest {
  static {
    System.setProperty(SRPUtils.class.getName() + ".useHash", "SHA-1");
  }

  @Test
  void shouldPickleAndUnpickleRequest() {
    NodeId from = new NodeId((short) 1);
    byte[] publicKey = new byte[]{1, 2, 3, 4};
    var request = new SessionKeyManager.KeyMessage.KeyHandshakeRequest(from, publicKey);

    byte[] pickled = PickleHandshake.pickle(request);
    SessionKeyManager.KeyMessage unpickled = PickleHandshake.unpickle(pickled);

    assertInstanceOf(SessionKeyManager.KeyMessage.KeyHandshakeRequest.class, unpickled);
    var unpackedRequest = (SessionKeyManager.KeyMessage.KeyHandshakeRequest) unpickled;
    assertEquals(from, unpackedRequest.from());
    assertArrayEquals(publicKey, unpackedRequest.publicKey());
  }

  @Test
  void shouldPickleAndUnpickleResponse() {
    NodeId from = new NodeId((short) 2);
    byte[] publicKey = new byte[]{5, 6, 7, 8};
    var response = new SessionKeyManager.KeyMessage.KeyHandshakeResponse(from, publicKey);

    byte[] pickled = PickleHandshake.pickle(response);
    SessionKeyManager.KeyMessage unpickled = PickleHandshake.unpickle(pickled);

    assertInstanceOf(SessionKeyManager.KeyMessage.KeyHandshakeResponse.class, unpickled);
    var unpackedResponse = (SessionKeyManager.KeyMessage.KeyHandshakeResponse) unpickled;
    assertEquals(from, unpackedResponse.from());
    assertArrayEquals(publicKey, unpackedResponse.publicKey());
  }

  @Test
  void shouldFailOnInvalidType() {
    byte[] invalid = new byte[]{99, 0, 1, 0, 0, 0, 1, 0}; // Invalid type 99
    assertThrows(IllegalArgumentException.class, () -> PickleHandshake.unpickle(invalid));
  }
}
