/*
 * Copyright 2024 - 2025 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.trex_paxos.paxe;

import com.github.trex_paxos.NodeId;
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
