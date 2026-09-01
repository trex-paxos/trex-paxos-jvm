// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe;

import com.github.trex_paxos.NodeId;
import com.github.trex_paxos.network.Channel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaxeMessageTest {

  @Test
  void serializeReturnsPayloadBytes() {
    byte[] payload = {1, 2, 3};
    var message = new PaxeMessage(new NodeId((short) 1), new NodeId((short) 2), new Channel(5), payload);
    assertArrayEquals(payload, message.serialize());
  }

  @Test
  void deserializeRoundTrip() {
    NodeId from = new NodeId((short) 1);
    NodeId to = new NodeId((short) 2);
    Channel channel = new Channel(99);
    byte[] payload = "payload".getBytes();

    var original = new PaxeMessage(from, to, channel, payload);
    var restored = PaxeMessage.deserialize(from, to, channel, payload);

    assertEquals(original, restored);
  }

  @Test
  void rejectsNullFields() {
    assertThrows(NullPointerException.class,
        () -> new PaxeMessage(null, new NodeId((short) 2), new Channel(1), new byte[0]));
    assertThrows(NullPointerException.class,
        () -> new PaxeMessage(new NodeId((short) 1), null, new Channel(1), new byte[0]));
    assertThrows(NullPointerException.class,
        () -> new PaxeMessage(new NodeId((short) 1), new NodeId((short) 2), null, new byte[0]));
    assertThrows(NullPointerException.class,
        () -> new PaxeMessage(new NodeId((short) 1), new NodeId((short) 2), new Channel(1), null));
  }
}
